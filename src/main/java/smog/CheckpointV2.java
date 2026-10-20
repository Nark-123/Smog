package smog;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Streaming v2: bounded compressed globals, <=8 cells/record, trailing Cube16 index. */
final class CheckpointV2 {

    static final long MAX_FILE = 16L * 1024 * 1024 * 1024;
    private static final int MAGIC = 0x534D4738, MAX_CELLS = 1000000;

    private static final class Index {

        CubeKey key;
        long offset;
        int length, cells;
    }

    private static final class FileOutput extends OutputStream {

        final RandomAccessFile file;

        FileOutput(RandomAccessFile file) {
            this.file = file;
        }

        @Override
        public void write(int b) throws IOException {
            file.write(b);
        }

        @Override
        public void write(byte[] b, int off, int length) throws IOException {
            file.write(b, off, length);
        }
    }

    private static final class BlocksOutput extends OutputStream {

        final DataOutput out;
        final byte[] block = new byte[65536];
        int used;
        long logical;

        BlocksOutput(DataOutput out) {
            this.out = out;
        }

        @Override
        public void write(int b) throws IOException {
            if (used == block.length) flushBlock();
            block[used++] = (byte) b;
        }

        @Override
        public void write(byte[] bytes, int off, int len) throws IOException {
            while (len > 0) {
                int n = Math.min(len, block.length - used);
                System.arraycopy(bytes, off, block, used, n);
                off += n;
                len -= n;
                used += n;
                if (used == block.length) flushBlock();
            }
        }

        void flushBlock() throws IOException {
            if (used == 0) return;
            logical += used;
            if (logical > 256L * 1048576) throw new IOException("Checkpoint globals exceed streaming limit");
            RecordCodec.write(out, Arrays.copyOf(block, used));
            used = 0;
        }

        @Override
        public void close() throws IOException {
            flushBlock();
            out.writeInt(-1);
        }
    }

    private static final class BlocksInput extends InputStream {

        final RandomAccessFile in;
        final long end;
        byte[] block = new byte[0];
        int cursor;
        boolean done;
        long logical;

        BlocksInput(RandomAccessFile in, long end) {
            this.in = in;
            this.end = end;
        }

        boolean next() throws IOException {
            if (done) return false;
            if (in.getFilePointer() + 4 > end) throw new IOException("Missing global block terminator");
            long at = in.getFilePointer();
            int length = in.readInt();
            if (length == -1) {
                done = true;
                return false;
            }
            if (length < 0 || length > 65536) throw new IOException("Checkpoint global block bounds");
            in.seek(at);
            block = RecordCodec.read(in);
            cursor = 0;
            logical += block.length;
            if (in.getFilePointer() > end || logical > 256L * 1048576)
                throw new IOException("Checkpoint globals limit");
            return true;
        }

        @Override
        public int read() throws IOException {
            while (cursor == block.length) if (!next()) return -1;
            return block[cursor++] & 255;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            while (cursor == block.length) if (!next()) return -1;
            int n = Math.min(len, block.length - cursor);
            System.arraycopy(block, cursor, b, off, n);
            cursor += n;
            return n;
        }
    }

    static void write(Path target, Checkpoint.Data data) throws IOException {
        if (data.cells.size() > MAX_CELLS || data.sources.size() > MAX_CELLS || data.frames.size() > MAX_CELLS)
            throw new IOException("Too many checkpoint records");
        LinkedHashMap<CubeKey, List<Checkpoint.CellData>> grouped = new LinkedHashMap<>();
        Set<CellKey> seen = new HashSet<>();
        for (Checkpoint.CellData c : data.cells) {
            if (!seen.add(c.key) || c.key.dimension != data.dimension)
                throw new IOException("Duplicate checkpoint cell");
            List<Checkpoint.CellData> cells = grouped.computeIfAbsent(c.key.cube(), k -> new ArrayList<>());
            cells.add(c);
            if (cells.size() > 8) throw new IOException("Cube16 cell overflow");
        }
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            try (RandomAccessFile out = new RandomAccessFile(tmp.toFile(), "rw")) {
                out.writeInt(MAGIC);
                out.writeInt(2);
                out.writeLong(0);
                try (DataOutputStream globals = new DataOutputStream(new BlocksOutput(out))) {
                    Checkpoint.writeGlobals(data, globals);
                }
                out.writeInt(data.cells.size());
                List<Index> indexes = new ArrayList<>(grouped.size());
                for (Map.Entry<CubeKey, List<Checkpoint.CellData>> e : grouped.entrySet()) {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    try (DataOutputStream record = new DataOutputStream(bytes)) {
                        record.writeByte(
                            e.getValue()
                                .size());
                        for (Checkpoint.CellData c : e.getValue()) Checkpoint.writeCell(record, c);
                    }
                    Index index = new Index();
                    index.key = e.getKey();
                    index.cells = e.getValue()
                        .size();
                    index.offset = out.getFilePointer();
                    RecordCodec.write(out, bytes.toByteArray());
                    index.length = (int) (out.getFilePointer() - index.offset);
                    indexes.add(index);
                }
                long indexAt = out.getFilePointer();
                out.writeInt(indexes.size());
                for (Index e : indexes) {
                    out.writeInt(e.key.x);
                    out.writeInt(e.key.y);
                    out.writeInt(e.key.z);
                    out.writeLong(e.offset);
                    out.writeInt(e.length);
                    out.writeByte(e.cells);
                }
                long indexEnd = out.getFilePointer();
                out.write(hashRange(out, indexAt, indexEnd));
                if (out.length() > MAX_FILE) throw new IOException("Checkpoint file limit");
                out.seek(8);
                out.writeLong(indexAt);
                out.getFD()
                    .sync();
            }
            RecordCodec.replace(tmp, target);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static byte[] hashRange(RandomAccessFile file, long start, long end) throws IOException {
        MessageDigest hash = RecordCodec.digest();
        byte[] bytes = new byte[65536];
        file.seek(start);
        while (start < end) {
            int n = (int) Math.min(bytes.length, end - start);
            file.readFully(bytes, 0, n);
            hash.update(bytes, 0, n);
            start += n;
        }
        return hash.digest();
    }

    private static List<Index> index(RandomAccessFile in, int dimension, long start, long recordsStart, int cells)
        throws IOException {
        if (start < recordsStart || start + 36 > in.length()) throw new IOException("Checkpoint index bounds");
        in.seek(start);
        int count = in.readInt();
        if (count < 0 || count > MAX_CELLS || start + 4L + 25L * count + 32 != in.length())
            throw new IOException("Checkpoint index extent");
        List<Index> result = new ArrayList<>(count);
        Set<CubeKey> seen = new HashSet<>();
        long end = recordsStart, total = 0;
        for (int i = 0; i < count; i++) {
            Index e = new Index();
            e.key = new CubeKey(dimension, in.readInt(), in.readInt(), in.readInt());
            e.offset = in.readLong();
            e.length = in.readInt();
            e.cells = in.readUnsignedByte();
            if (!seen.add(e.key) || e.cells < 1
                || e.cells > 8
                || e.offset != end
                || e.length < 12
                || e.length > RecordCodec.MAX + 1036L
                || e.offset + e.length > start) throw new IOException("Checkpoint cube index");
            end += e.length;
            total += e.cells;
            result.add(e);
        }
        long hashAt = in.getFilePointer();
        byte[] expected = new byte[32];
        in.readFully(expected);
        if (end != start || total != cells || !MessageDigest.isEqual(hashRange(in, start, hashAt), expected))
            throw new IOException("Checkpoint index checksum/count");
        return result;
    }

    private static List<Checkpoint.CellData> readCube(RandomAccessFile in, Index index, int dimension)
        throws IOException {
        in.seek(index.offset);
        byte[] bytes = RecordCodec.read(in);
        if (in.getFilePointer() != index.offset + index.length) throw new IOException("Checkpoint cube extent");
        List<Checkpoint.CellData> result = new ArrayList<>(8);
        int mask = 0;
        try (DataInputStream record = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int n = record.readUnsignedByte();
            if (n != index.cells) throw new IOException("Checkpoint cube count");
            for (int i = 0; i < n; i++) {
                Checkpoint.CellData cell = Checkpoint.readCell(record, dimension);
                int bit = 1 << cell.key.slot();
                if (!cell.key.cube()
                    .equals(index.key) || (mask & bit) != 0) throw new IOException("Checkpoint cell index mismatch");
                mask |= bit;
                result.add(cell);
            }
            if (record.read() != -1) throw new IOException("Checkpoint cube trailing data");
        }
        return result;
    }

    static Checkpoint.Data read(Path path) throws IOException {
        return read(path, true);
    }

    static void verify(Path path) throws IOException {
        read(path, false);
    }

    private static Checkpoint.Data read(Path path, boolean retain) throws IOException {
        try (RandomAccessFile in = new RandomAccessFile(path.toFile(), "r")) {
            if (in.length() < 52 || in.length() > MAX_FILE || in.readInt() != MAGIC || in.readInt() != 2)
                throw new IOException("Unsupported checkpoint v2");
            long at = in.readLong();
            if (at < 20 || at >= in.length()) throw new IOException("Checkpoint index pointer");
            BlocksInput stream = new BlocksInput(in, at);
            DataInputStream globals = new DataInputStream(stream);
            Checkpoint.Data data = Checkpoint.readGlobals(globals);
            if (globals.read() != -1) throw new IOException("Checkpoint globals trailing bytes");
            int cells = in.readInt();
            if (cells < 0 || cells > MAX_CELLS) throw new IOException("Checkpoint cell count");
            long records = in.getFilePointer();
            double accounted = data.escaped + data.removed;
            for (Index entry : index(in, data.dimension, at, records, cells)) {
                List<Checkpoint.CellData> group = readCube(in, entry, data.dimension);
                for (Checkpoint.CellData c : group) {
                    if (c.mass != null) accounted += c.mass.total();
                    for (double mass : c.trapped.values()) accounted += mass;
                }
                if (retain) data.cells.addAll(group);
            }
            Checkpoint.validateAccounted(data, accounted);
            return data;
        }
    }
}

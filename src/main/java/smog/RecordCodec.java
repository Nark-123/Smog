package smog;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;

/** Bounded independently compressed/checksummed records. No native codec dependency. */
final class RecordCodec {

    static final int MAX = 1024 * 1024;

    static void write(DataOutput out, byte[] raw) throws IOException {
        if (raw.length > MAX) throw new IOException("Record too large");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        try (DeflaterOutputStream compressed = new DeflaterOutputStream(bytes, deflater)) {
            compressed.write(raw);
        } finally {
            deflater.end();
        }
        byte[] coded = bytes.toByteArray();
        CRC32 crc = new CRC32();
        crc.update(raw);
        out.writeInt(raw.length);
        out.writeInt(coded.length);
        out.writeInt((int) crc.getValue());
        out.write(coded);
    }

    static void write(RandomAccessFile out, byte[] raw) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(bytes);
        write((DataOutput) data, raw);
        data.flush();
        out.write(bytes.toByteArray());
    }

    static byte[] read(DataInput in) throws IOException {
        int length = in.readInt(), coded = in.readInt(), expected = in.readInt();
        return readBody(in, length, coded, expected);
    }

    static byte[] read(RandomAccessFile in) throws IOException {
        byte[] header = new byte[12];
        in.readFully(header);
        DataInputStream data = new DataInputStream(new ByteArrayInputStream(header));
        return readBody(in, data.readInt(), data.readInt(), data.readInt());
    }

    private static byte[] readBody(DataInput in, int length, int coded, int expected) throws IOException {
        if (length < 0 || length > MAX || coded < 1 || coded > MAX + 1024)
            throw new IOException("Invalid record bounds");
        byte[] bytes = new byte[coded];
        in.readFully(bytes);
        byte[] raw = new byte[length];
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(bytes);
            int offset = 0;
            while (offset < raw.length) {
                int n = inflater.inflate(raw, offset, raw.length - offset);
                if (n == 0) throw new IOException("Incomplete compressed record");
                offset += n;
            }
            byte[] extra = new byte[1];
            if (inflater.inflate(extra) != 0 || !inflater.finished() || inflater.getRemaining() != 0)
                throw new IOException("Trailing compressed record");
        } catch (DataFormatException e) {
            throw new IOException("Invalid compression", e);
        } finally {
            inflater.end();
        }
        CRC32 crc = new CRC32();
        crc.update(raw);
        if ((int) crc.getValue() != expected) throw new IOException("Record checksum mismatch");
        return raw;
    }

    static byte[] sha(Path path) throws IOException {
        MessageDigest digest = digest();
        byte[] buffer = new byte[65536];
        try (InputStream in = Files.newInputStream(path)) {
            for (int n; (n = in.read(buffer)) >= 0;) digest.update(buffer, 0, n);
        }
        return digest.digest();
    }

    static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    static void replace(Path temporary, Path target) throws IOException {
        try (
            java.nio.channels.FileChannel f = java.nio.channels.FileChannel.open(temporary, StandardOpenOption.WRITE)) {
            f.force(true);
        }
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}

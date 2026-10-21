package smog;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Arrays;

/** Immutable exact membership. Bitset means solid/-1 or one air component/0. */
final class VoxelLabels {

    static final int UNIFORM = 0, BITSET = 1, BYTE = 2, SHORT = 3;
    private static final boolean COMPACT = Boolean.parseBoolean(System.getProperty("smog.compactGeometry", "true"));
    private static final ThreadLocal<byte[]> SHORT_SCRATCH = ThreadLocal.withInitial(() -> new byte[1024]);
    private static final VoxelLabels SOLID = new VoxelLabels(-1, null, null, null, null);
    private static final VoxelLabels AIR = new VoxelLabels(0, null, null, null, null);
    private final int uniform;
    private final long[] bits;
    private final byte[] narrow;
    private final short[] wide;
    private final int[] dense;

    private VoxelLabels(int uniform, long[] bits, byte[] narrow, short[] wide, int[] dense) {
        this.uniform = uniform;
        this.bits = bits;
        this.narrow = narrow;
        this.wide = wide;
        this.dense = dense;
    }

    static VoxelLabels uniform(int value) {
        return value == -1 ? SOLID : value == 0 ? AIR : new VoxelLabels(value, null, null, null, null);
    }

    static VoxelLabels of(int[] values) {
        if (values.length != 512) throw new IllegalArgumentException("Cell8 labels size");
        int first = values[0], max = first;
        boolean same = true;
        for (int value : values) {
            if (value < -1 || value > 511) throw new IllegalArgumentException("Cell8 label range");
            same &= value == first;
            max = Math.max(max, value);
        }
        if (!COMPACT) return new VoxelLabels(0, null, null, null, values.clone());
        if (same) return uniform(first);
        if (max == 0) {
            long[] bits = new long[8];
            for (int p = 0; p < 512; p++) if (values[p] == 0) bits[p >>> 6] |= 1L << (p & 63);
            return new VoxelLabels(0, bits, null, null, null);
        }
        if (max <= 254) {
            byte[] bytes = new byte[512];
            for (int p = 0; p < 512; p++) bytes[p] = (byte) (values[p] + 1);
            return new VoxelLabels(0, null, bytes, null, null);
        }
        short[] shorts = new short[512];
        for (int p = 0; p < 512; p++) shorts[p] = (short) values[p];
        return new VoxelLabels(0, null, null, shorts, null);
    }

    private static int valid(int value, int nodes, int[] counts) throws IOException {
        if (value < -1 || value >= nodes) throw new IOException("Cache membership");
        if (value >= 0) counts[value]++;
        return value;
    }

    /** Legacy wire: stream directly into final RAM width, including one-component bitsets. */
    static VoxelLabels read(DataInput in, int nodes, int[] counts) throws IOException {
        // v1 and v2/SHORT retain their wire layout. One bulk read avoids 1024
        // synchronized ByteArrayInputStream reads for every Cell8; scratch is per IO thread.
        byte[] raw = SHORT_SCRATCH.get();
        in.readFully(raw);
        if (COMPACT && nodes <= 1) {
            long[] bits = null;
            int first = -1;
            for (int p = 0; p < 512; p++) {
                int value = valid(signed(raw, p), nodes, counts);
                if (p == 0) first = value;
                if (bits == null && value != first) {
                    bits = new long[8];
                    if (first == 0) for (int q = 0; q < p; q++) bits[q >>> 6] |= 1L << (q & 63);
                }
                if (bits != null && value == 0) bits[p >>> 6] |= 1L << (p & 63);
            }
            return bits == null ? uniform(first) : new VoxelLabels(0, bits, null, null, null);
        }
        byte[] bytes = null;
        short[] shorts = null;
        int[] ints = COMPACT ? null : new int[512];
        int first = 0;
        boolean same = true;
        for (int p = 0; p < 512; p++) {
            int value = valid(signed(raw, p), nodes, counts);
            if (p == 0) first = value;
            else same &= value == first;
            if (COMPACT && !same && bytes == null && shorts == null) {
                if (nodes <= 255) {
                    bytes = new byte[512];
                    Arrays.fill(bytes, 0, p, (byte) (first + 1));
                } else {
                    shorts = new short[512];
                    Arrays.fill(shorts, 0, p, (short) first);
                }
            }
            if (bytes != null) bytes[p] = (byte) (value + 1);
            else if (shorts != null) shorts[p] = (short) value;
            else if (ints != null) ints[p] = value;
        }
        return COMPACT && same ? uniform(first) : new VoxelLabels(0, null, bytes, shorts, ints);
    }

    private static int signed(byte[] raw, int p) {
        return (short) (((raw[p * 2] & 255) << 8) | (raw[p * 2 + 1] & 255));
    }

    int encoding(int nodes) {
        int first = get(0);
        boolean same = true;
        for (int p = 1; p < 512 && same; p++) same = get(p) == first;
        return same ? UNIFORM : nodes <= 1 ? BITSET : nodes <= 255 ? BYTE : SHORT;
    }

    void writeAdaptive(DataOutput out, int nodes) throws IOException {
        int tag = encoding(nodes);
        out.writeByte(tag);
        if (tag == UNIFORM) out.writeShort(get(0));
        else if (tag == BITSET) {
            for (int word = 0; word < 8; word++) {
                long mask = 0;
                for (int bit = 0; bit < 64; bit++) if (get(word * 64 + bit) == 0) mask |= 1L << bit;
                out.writeLong(mask);
            }
        } else for (int p = 0; p < 512; p++) {
            if (tag == BYTE) out.writeByte(get(p) + 1);
            else out.writeShort(get(p));
        }
    }

    static VoxelLabels readAdaptive(DataInput in, int nodes, int[] counts) throws IOException {
        int tag = in.readUnsignedByte();
        if (tag == UNIFORM) {
            int value = in.readShort();
            if (value < -1 || value >= nodes) throw new IOException("Cache uniform membership");
            if (value >= 0) counts[value] = 512;
            if (COMPACT) return uniform(value);
            int[] values = new int[512];
            Arrays.fill(values, value);
            return of(values);
        }
        if (tag == BITSET) {
            if (nodes != 1) throw new IOException("Bitset needs one air component");
            long[] bits = new long[8];
            int count = 0;
            for (int i = 0; i < 8; i++) {
                bits[i] = in.readLong();
                count += Long.bitCount(bits[i]);
            }
            counts[0] = count;
            if (COMPACT) return count == 0 ? SOLID : count == 512 ? AIR : new VoxelLabels(0, bits, null, null, null);
            int[] values = new int[512];
            for (int p = 0; p < 512; p++) values[p] = (bits[p >>> 6] & (1L << (p & 63))) == 0 ? -1 : 0;
            return of(values);
        }
        if (tag == SHORT) return read(in, nodes, counts);
        if (tag != BYTE || nodes > 255) throw new IOException("Invalid cache label encoding");
        byte[] bytes = new byte[512];
        in.readFully(bytes);
        int first = 0;
        boolean same = true;
        int[] ints = COMPACT ? null : new int[512];
        for (int p = 0; p < 512; p++) {
            int value = valid((bytes[p] & 255) - 1, nodes, counts);
            if (p == 0) first = value;
            else same &= value == first;
            if (ints != null) ints[p] = value;
        }
        return COMPACT && same ? uniform(first) : new VoxelLabels(0, null, COMPACT ? bytes : null, null, ints);
    }

    boolean isUniform() {
        return bits == null && narrow == null && wide == null && dense == null;
    }

    int get(int p) {
        if (bits != null) return (bits[p >>> 6] & (1L << (p & 63))) == 0 ? -1 : 0;
        if (narrow != null) return (narrow[p] & 255) - 1;
        if (wide != null) return wide[p];
        return dense == null ? uniform : dense[p];
    }

    int[] toArray() {
        int[] a = new int[512];
        for (int p = 0; p < 512; p++) a[p] = get(p);
        return a;
    }

    long payloadBytes() {
        return bits != null ? 64 : narrow != null ? 512 : wide != null ? 1024 : dense != null ? 2048 : 0;
    }

    long bytes() {
        return 40 + (payloadBytes() == 0 ? 0 : 16 + payloadBytes());
    }
}

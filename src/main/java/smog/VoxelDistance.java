package smog;

/** Immutable unsigned voxel distances. Uniform only after examining all 512 values. */
final class VoxelDistance {
    private static final boolean COMPACT = Boolean.parseBoolean(System.getProperty("smog.compactGeometry", "true"));
    private static final VoxelDistance ZERO = new VoxelDistance(0, null);
    private final int uniform;
    private final byte[] values;
    private VoxelDistance(int uniform, byte[] values) { this.uniform = uniform; this.values = values; }
    static VoxelDistance of(byte[] values) {
        if (values.length != 512) throw new IllegalArgumentException("Cell8 distance size");
        boolean same = COMPACT;
        for (int p = 1; p < 512 && same; p++) same = values[p] == values[0];
        int first = values[0] & 255;
        return same ? first == 0 ? ZERO : new VoxelDistance(first, null) : new VoxelDistance(0, values.clone());
    }
    void writeAdaptive(java.io.DataOutput out) throws java.io.IOException {
        int first=get(0); boolean same=true;
        for(int p=1;p<512 && same;p++) same=get(p)==first;
        out.writeByte(same ? 0 : 1);
        if(same) out.writeByte(first); else for(int p=0;p<512;p++) out.writeByte(get(p));
    }
    static VoxelDistance readAdaptive(java.io.DataInput in,byte[] scratch) throws java.io.IOException {
        int tag=in.readUnsignedByte();
        if(tag==0) {
            int value=in.readUnsignedByte();
            if(COMPACT) return value==0 ? ZERO : new VoxelDistance(value,null);
            java.util.Arrays.fill(scratch,(byte)value);
        } else if(tag==1) in.readFully(scratch);
        else throw new java.io.IOException("Invalid cache distance encoding");
        return of(scratch);
    }
    int get(int p) { return values == null ? uniform : values[p] & 255; }
    byte[] toArray() {
        byte[] result = new byte[512];
        for (int p = 0; p < 512; p++) result[p] = (byte) get(p);
        return result;
    }
    long payloadBytes() { return values == null ? 0 : 512; }
    long bytes() { return 24 + (values == null ? 0 : 528); }
}

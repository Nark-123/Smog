package smog;

/**
 * Immutable spatial identity, independent of runtime graph IDs.
 */
public final class BlockPos implements Comparable<BlockPos> {

    public final int dimension, x, y, z;

    public BlockPos(int dimension, int x, int y, int z) {
        this.dimension = dimension;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public BlockPos offset(int dx, int dy, int dz) {
        return new BlockPos(dimension, Math.addExact(x, dx), Math.addExact(y, dy), Math.addExact(z, dz));
    }

    public CubeKey cube() {
        return new CubeKey(dimension, Math.floorDiv(x, 16), Math.floorDiv(y, 16), Math.floorDiv(z, 16));
    }

    public CellKey cell() {
        return new CellKey(dimension, Math.floorDiv(x, 8), Math.floorDiv(y, 8), Math.floorDiv(z, 8));
    }

    int local8() {
        return Math.floorMod(x, 8) + 8 * Math.floorMod(y, 8) + 64 * Math.floorMod(z, 8);
    }

    @Override
    public int hashCode() {
        int h = dimension;
        h = 31 * h + x;
        h = 31 * h + y;
        return 31 * h + z;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof BlockPos)) {
            return false;
        }
        BlockPos k = (BlockPos) o;
        return dimension == k.dimension && x == k.x && y == k.y && z == k.z;
    }

    @Override
    public int compareTo(BlockPos k) {
        int c = Integer.compare(dimension, k.dimension);
        if (c == 0) {
            c = Integer.compare(z, k.z);
        }
        if (c == 0) {
            c = Integer.compare(y, k.y);
        }
        if (c == 0) {
            c = Integer.compare(x, k.x);
        }
        return c;
    }

    @Override
    public String toString() {
        return dimension + ":" + x + "," + y + "," + z;
    }
}

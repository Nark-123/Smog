package smog;

/**
 * Immutable spatial identity, independent of runtime graph IDs.
 */
public final class CellKey implements Comparable<CellKey> {

    public final int dimension, x, y, z;

    public CellKey(int dimension, int x, int y, int z) {
        this.dimension = dimension;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public CellKey offset(int dx, int dy, int dz) {
        return new CellKey(dimension, Math.addExact(x, dx), Math.addExact(y, dy), Math.addExact(z, dz));
    }

    public CubeKey cube() {
        return new CubeKey(dimension, Math.floorDiv(x, 2), Math.floorDiv(y, 2), Math.floorDiv(z, 2));
    }

    int slot() {
        return Math.floorMod(x, 2) + 2 * Math.floorMod(y, 2) + 4 * Math.floorMod(z, 2);
    }

    public BlockPos block(int p) {
        return new BlockPos(
            dimension,
            Math.addExact(Math.multiplyExact(x, 8), p % 8),
            Math.addExact(Math.multiplyExact(y, 8), p / 8 % 8),
            Math.addExact(Math.multiplyExact(z, 8), p / 64));
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
        if (!(o instanceof CellKey)) {
            return false;
        }
        CellKey k = (CellKey) o;
        return dimension == k.dimension && x == k.x && y == k.y && z == k.z;
    }

    @Override
    public int compareTo(CellKey k) {
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

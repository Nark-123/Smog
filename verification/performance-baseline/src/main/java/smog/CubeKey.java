package smog;

/**
 * Immutable spatial identity, independent of runtime graph IDs.
 */
public final class CubeKey implements Comparable<CubeKey> {

    public final int dimension, x, y, z;

    public CubeKey(int dimension, int x, int y, int z) {
        this.dimension = dimension;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public CubeKey offset(int dx, int dy, int dz) {
        return new CubeKey(dimension, Math.addExact(x, dx), Math.addExact(y, dy), Math.addExact(z, dz));
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
        if (!(o instanceof CubeKey)) {
            return false;
        }
        CubeKey k = (CubeKey) o;
        return dimension == k.dimension && x == k.x && y == k.y && z == k.z;
    }

    @Override
    public int compareTo(CubeKey k) {
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

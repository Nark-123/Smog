package smog;

/**
 * Same scene/PRNG implementation as tools/native_benchmark.cpp, independent of NumPy.
 */
final class BenchmarkScene {

    private long random = 20261013;

    byte[] world(String name, int nx, int ny, int nz) {
        if (!name.equals("open") && !name.equals("factory") && !name.equals("shafts80")) {
            throw new IllegalArgumentException("Scene: open, factory or shafts80");
        }
        byte[] air = new byte[Math.multiplyExact(Math.multiplyExact(nx, ny), nz)];
        for (int z = 0, p = 0; z < nz; z++) {
            for (int y = 0; y < ny; y++) {
                for (int x = 0; x < nx; x++, p++) {
                    boolean value = true;
                    if (name.equals("shafts80")) {
                        value = (x % 3 == 1 && z % 3 == 1) || y % 12 == 11;
                    } else if (name.equals("factory")) {
                        int xx = x % 16, yy = y % 16, zz = z % 16;
                        value = xx != 0 && yy != 0 && zz != 0;
                        if (xx == 0 && zz >= 7 && zz < 10 && yy >= 1 && yy < 5) {
                            value = true;
                        }
                        if (zz == 0 && xx >= 7 && xx < 10 && yy >= 1 && yy < 5) {
                            value = true;
                        }
                        if (((xx >= 2 && xx < 7) || (xx >= 10 && xx < 15))
                            && ((zz >= 2 && zz < 7) || (zz >= 10 && zz < 15))
                            && yy >= 1
                            && yy < 13) {
                            value = false;
                        }
                        if (yy == 0 && xx >= 7 && xx < 9 && zz >= 7 && zz < 9) {
                            value = true;
                        }
                    }
                    air[p] = value ? (byte) 1 : 0;
                }
            }
        }
        return air;
    }

    int next(int bound) {
        random ^= random << 13;
        random ^= random >>> 7;
        random ^= random << 17;
        return (int) Long.remainderUnsigned(random, bound);
    }

    int[] positions(byte[] air, int count) {
        int[] result = new int[count];
        for (int i = 0; i < count; i++) {
            int p;
            do {
                p = next(air.length);
            } while (air[p] == 0);
            result[i] = p;
        }
        return result;
    }
}

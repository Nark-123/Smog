package smog;

/**
 * 16^3 blocks, x fastest; explicit physical atmosphere faces, never lazy borders.
 */
public final class ImmutableCubeSnapshot {

    public final CubeKey key;

    public final long epoch, revision;

    private final byte[] air, atmosphere;

    private byte[] fingerprint;

    /** Semantic geometry input, independent of process-local epoch/revision. */
    byte[] fingerprint() {
        if (fingerprint == null) {
            java.security.MessageDigest hash = RecordCodec.digest();
            hash.update((byte) 1); // air-rule schema; bump when Forge air semantics change
            byte[] canonical = air;
            for (byte value : air) if (value != 0 && value != 1) {
                canonical = air.clone();
                for (int i = 0; i < canonical.length; i++) canonical[i] = (byte) (canonical[i] == 0 ? 0 : 1);
                break;
            }
            hash.update(canonical);
            hash.update(atmosphere);
            fingerprint = hash.digest();
        }
        return fingerprint;
    }

    public ImmutableCubeSnapshot(CubeKey key, long epoch, long revision, byte[] air) {
        this(key, epoch, revision, air, new byte[4096]);
    }

    public ImmutableCubeSnapshot(CubeKey key, long epoch, long revision, byte[] air, byte[] atmosphere) {
        if (key == null || air.length != 4096 || atmosphere.length != 4096 || epoch < 0 || revision < 0) {
            throw new IllegalArgumentException("Invalid cube snapshot");
        }
        this.key = key;
        this.epoch = epoch;
        this.revision = revision;
        this.air = air.clone();
        this.atmosphere = atmosphere.clone();
        for (byte b : atmosphere) {
            if ((b & ~63) != 0) {
                throw new IllegalArgumentException("Invalid atmosphere mask");
            }
        }
    }

    private int index(int x, int y, int z) {
        return Math.floorMod(x, 16) + 16 * Math.floorMod(y, 16) + 256 * Math.floorMod(z, 16);
    }

    public boolean airAt(int x, int y, int z) {
        return air[index(x, y, z)] != 0;
    }

    public int atmosphereAt(int x, int y, int z) {
        return atmosphere[index(x, y, z)] & 63;
    }
}

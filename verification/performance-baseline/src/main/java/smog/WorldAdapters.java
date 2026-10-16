package smog;

/**
 * Lifecycle forwarding only; block ownership/readiness stays in the provider.
 */
public final class WorldAdapters {

    private WorldAdapters() {}

    /**
     * Visit only existing SMOG records in a legacy column, not every possible Y.
     */
    public static void columnReady(DimensionState state, int x, int z) {
        state.columnEvent(x, z, false);
    }

    public static void columnUnloading(DimensionState state, int x, int z) {
        state.columnEvent(x, z, true);
    }

    public static void cubeReady(DimensionState state, CubeKey key, long epoch, long revision) {
        state.onWorldCubeReady(key, epoch, revision);
    }

    public static void cubeUnloading(DimensionState state, CubeKey key, long epoch) {
        state.onWorldCubeUnloading(key, epoch);
    }
}

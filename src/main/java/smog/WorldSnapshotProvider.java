package smog;

/**
 * Called only on the simulation/world owner thread. Never force-load a cube.
 * Snapshots must remain immutable; epoch changes on every unload/reload and
 * revision on every edit. Send notifications after updating provider state.
 */
public interface WorldSnapshotProvider {

    boolean isReady(CubeKey key);

    ImmutableCubeSnapshot snapshotIfReady(CubeKey key);

    /**
     * True when a captured snapshot still describes the same ready world cube.
     * Missing cubes are represented by null and must remain missing. Implementations
     * may check their epoch/revision directly without allocating a new snapshot.
     * The default preserves the original correctness checks for test/providers.
     */
    default boolean matchesSnapshot(CubeKey key, ImmutableCubeSnapshot captured) {
        ImmutableCubeSnapshot current = isReady(key) ? snapshotIfReady(key) : null;
        if (current == null || captured == null) {
            return current == captured;
        }
        return current.key.equals(key) && captured.key.equals(key)
            && current.epoch == captured.epoch
            && current.revision == captured.revision;
    }
}

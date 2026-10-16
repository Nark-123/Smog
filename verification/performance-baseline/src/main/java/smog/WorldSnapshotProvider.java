package smog;

/**
 * Called only on the simulation/world owner thread. Never force-load a cube.
 * Snapshots must remain immutable; epoch changes on every unload/reload and
 * revision on every edit. Send notifications after updating provider state.
 */
public interface WorldSnapshotProvider {

    boolean isReady(CubeKey key);

    ImmutableCubeSnapshot snapshotIfReady(CubeKey key);
}

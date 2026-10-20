package smog;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

/** Fixture compatible with the 2026-10-19 src.zip checkpoint reader and the adapted v2 reader. */
public final class ColdMassCheckpointInterop {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static Checkpoint.Data fixture() {
        Checkpoint.Data data = new Checkpoint.Data();
        data.dimension = 0;
        data.config = SmogConfig.throat8();
        data.parts = 20;
        Checkpoint.CellData cell = new Checkpoint.CellData();
        cell.key = new CellKey(0, -2, 3, 5);
        int[] labels = new int[512];
        Arrays.fill(labels, 256, 512, 1);
        cell.mass = new DimensionState.MassRecord(labels, new int[] { 256, 256 },
                new double[] { 17.5, 22.5 });
        cell.trapped.put(7, 2.25);
        data.cells.add(cell);
        data.injected = 42.25;
        return data;
    }

    private static Checkpoint.Data inspect(Path directory) throws Exception {
        Checkpoint.Loaded loaded = Checkpoint.read(directory);
        check(loaded.generation == 1, "unexpected checkpoint generation");
        Checkpoint.Data data = loaded.data;
        check(data.dimension == 0 && data.cells.size() == 1, "lost dimension/cell");
        Checkpoint.CellData cell = data.cells.get(0);
        check(cell.key.equals(new CellKey(0, -2, 3, 5)), "lost cell key");
        check(cell.mass != null && cell.mass.volumes.length == 2, "lost local nodes");
        check(cell.mass.volumes[0] == 256 && cell.mass.volumes[1] == 256, "lost volumes");
        check(cell.mass.masses[0] == 17.5 && cell.mass.masses[1] == 22.5, "lost masses");
        check(cell.trapped.size() == 1 && cell.trapped.get(7) == 2.25, "lost trapped mass");
        check(data.injected == 42.25 && data.frames.isEmpty() && data.sources.isEmpty(),
                "lost authoritative globals");
        return data;
    }

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        Path path = Paths.get(args[1]);
        if ("write".equals(mode)) {
            check(Checkpoint.write(path, fixture(), 0) == 1, "failed first generation");
        } else if ("read".equals(mode)) {
            inspect(path);
        } else if ("read-and-restore".equals(mode)) {
            Checkpoint.Data data = inspect(path);
            WorldSnapshotProvider world = new WorldSnapshotProvider() {
                @Override public boolean isReady(CubeKey key) { return false; }
                @Override public ImmutableCubeSnapshot snapshotIfReady(CubeKey key) { return null; }
            };
            try (DimensionState state = Checkpoint.restore(data, world)) {
                check(Math.abs(state.balanceError()) < 1e-8, "restore ledger mismatch");
                check(Math.abs(state.parkedMass() + state.trappedMass() - 42.25) < 1e-8,
                        "restore lost parked/trapped mass");
            }
        } else {
            throw new IllegalArgumentException("mode: " + mode);
        }
        System.out.println("SMOG_CHECKPOINT_INTEROP_PASS " + mode);
    }
}

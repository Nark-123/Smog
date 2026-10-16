package smog;

import org.smogmod.compat.gt5u.PendingPulse;
import org.smogmod.persistence.WorldStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public final class CoreIntegrationSmoke {

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void main(String[] args) throws Exception {
        for (String name :
                new String[] {
                    "analyticTwoCells",
                    "budgetEquivalence",
                    "trapsAndSources",
                    "configurationAndValidation",
                    "customCycle"
                }) {
            java.lang.reflect.Method method = SmogTests.class.getDeclaredMethod(name);
            method.setAccessible(true);
            method.invoke(null);
        }
        SparseTests.orders();
        SparseTests.lazy();
        SparseTests.physics();
        SparseTests.lifecycle();
        SparseTests.saves();
        SparseTests.edits();
        SparseEventTests.main(new String[0]);
        SparseTests.World world = new SparseTests.World();
        CubeKey cube = new CubeKey(7, -1, -2, -1);
        BlockPos point = new BlockPos(7, -1, -17, -1);
        Path path = Files.createTempDirectory(Paths.get("build"), "integration-store-");
        List<PendingPulse> queue = new ArrayList<>();
        queue.add(new PendingPulse(point, 8750, false, false));
        WorldStore store = new WorldStore(path);
        DimensionState state = store.load(7, world, new ArrayList<PendingPulse>());
        check(state.generatedMass() == 0, "Fresh world must not import GT legacy mass");
        store.markDirty();
        store.saveAndAwaitForShutdown(state, queue, 10000);
        state.close();
        List<PendingPulse> restored = new ArrayList<>();
        store = new WorldStore(path);
        state = store.load(7, world, restored);
        check(
                restored.size() == 1
                        && restored.get(0).position.equals(point)
                        && restored.get(0).amount == 8750,
                "Deferred exact XYZ pulse lost");
        world.put(cube, SparseTests.air(), 1, 0);
        check(
                state.queueAddMass(point, restored.get(0).amount),
                "Pulse must be accepted after independent negative-Y cube becomes ready");
        restored.clear();
        store.markDirty();
        store.saveAndAwaitForShutdown(state, restored, 10000);
        state.close();
        restored = new ArrayList<>();
        store = new WorldStore(path);
        state = store.load(7, world, restored);
        check(restored.isEmpty(), "Transferred event duplicated in external queue");
        state.resumeReadyCubes();
        SparseTests.drain(state);
        state.finishPendingPart(20);
        SparseTests.near(state.liveMass(), 8750, 1e-10, "One deferred event must deliver once");
        SpatialQueries.removeChunk(state, -1, -1, 250);
        state.finishPendingPart(20);
        SparseTests.near(
                SpatialQueries.chunkMass(state, -1, -1), 8500, 1e-10, "Negative legacy cleaner");
        SparseTests.near(state.balanceError(), 0, 1e-10, "Cleanup mass balance");
        world.unload(state, cube);
        SparseTests.near(state.parkedMass(), 8500, 1e-10, "Independent cube unload parks mass");
        SpatialQueries.removeChunk(state, -1, -1, 500);
        SparseTests.near(state.parkedMass(), 8000, 1e-10, "Cleanup must include inactive mass");
        store.markDirty();
        store.saveAndAwaitForShutdown(state, restored, 10000);
        state.close();
        check(Files.exists(path.resolve("PREVIOUS")), "Previous checkpoint pointer missing");
        String current =
                new String(
                        Files.readAllBytes(path.resolve("CURRENT")),
                        java.nio.charset.StandardCharsets.US_ASCII);
        Files.write(path.resolve(current).resolve("pending.bin"), new byte[] {0});
        state = new WorldStore(path).load(7, world, new ArrayList<PendingPulse>());
        state.close();
        System.out.println("SMOG_CORE_INTEGRATION_PASS");
    }
}

package smog;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.smogmod.compat.gt5u.PendingPulse;
import org.smogmod.persistence.WorldStore;

/** A small real-filesystem phase measurement, separate from the blocked-writer A/B. */
public final class SavePhaseProbe {
    public static void main(String[] args) throws Exception {
        WorldSnapshotProvider unavailable = new WorldSnapshotProvider() {
            public boolean isReady(CubeKey key) {
                return false;
            }

            public ImmutableCubeSnapshot snapshotIfReady(CubeKey key) {
                throw new AssertionError("I/O measurement requested world geometry");
            }
        };
        List<PendingPulse> pulses = new ArrayList<>();
        DimensionState state = new WorldStore(Paths.get(args[0])).load(0, unavailable, pulses);
        WorldStore store = new WorldStore(Files.createTempDirectory("smog-phase-probe-"));
        store.saveAndAwaitForShutdown(state, pulses, 3000);
        System.out.println("SAVE_PHASES " + store.status());
        state.close();
    }
}

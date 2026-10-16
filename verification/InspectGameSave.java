import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.io.DataInputStream;
import smog.CubeKey;
import smog.DimensionState;
import smog.ImmutableCubeSnapshot;
import smog.WorldSnapshotProvider;

/** Read the actual stopped-server save without loading Minecraft or world chunks. */
public final class InspectGameSave {
    public static void main(String[] args) throws Exception {
        Path store = Paths.get(args[0]);
        String current = new String(Files.readAllBytes(store.resolve("CURRENT")), StandardCharsets.US_ASCII);
        Path bundle = store.resolve(current);
        WorldSnapshotProvider unavailable = new WorldSnapshotProvider() {
            public boolean isReady(CubeKey key) { return false; }
            public ImmutableCubeSnapshot snapshotIfReady(CubeKey key) { return null; }
        };
        try (DimensionState state = DimensionState.load(bundle.resolve("core"), unavailable);
             DataInputStream queue = new DataInputStream(Files.newInputStream(bundle.resolve("pending.bin")))) {
            if (queue.readInt() != 0x534D5051 || queue.readInt() != 1 || queue.readInt() != 0) {
                throw new AssertionError("Expected empty committed external queue after game delivery");
            }
            if (state.injectedMass() <= 0 || Math.abs(state.balanceError()) > 1e-6) {
                throw new AssertionError("Actual game mass was not committed or its balance failed");
            }
            System.out.println("SMOG_GAME_SAVE_REOPEN_PASS injected=" + state.injectedMass()
                + " parked=" + state.parkedMass() + " trapped=" + state.trappedMass()
                + " pending=" + state.pendingMass() + " balance=" + state.balanceError());
        }
    }
}

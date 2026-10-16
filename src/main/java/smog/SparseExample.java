package smog;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;

/**
 * Minimal standalone integration. Optional argument: checkpoint directory.
 */
public final class SparseExample {

    private SparseExample() {}

    public static void main(String[] args) throws Exception {
        CubeKey key = new CubeKey(0, 0, 0, 0);
        byte[] air = new byte[4096];
        Arrays.fill(air, (byte) 1);
        final ImmutableCubeSnapshot blocks = new ImmutableCubeSnapshot(key, 1, 1, air);
        WorldSnapshotProvider world = new WorldSnapshotProvider() {

            public boolean isReady(CubeKey k) {
                return blocks.key.equals(k);
            }

            public ImmutableCubeSnapshot snapshotIfReady(CubeKey k) {
                return isReady(k) ? blocks : null;
            }
        };
        try (DimensionState smog = new DimensionState(0, SmogConfig.throat8(), world)) {
            // zero geometry for clean READY
            smog.onWorldCubeReady(key, 1, 1);
            smog.setSourceRate(new BlockPos(0, 2, 2, 2), 1);
            for (int t = 0; t < 400; t++) {
                smog.tick(500000);
            }
            while (smog.pendingIntervals() > 0) {
                smog.finishPendingPart(20);
            }
            System.out.println(
                "cells=" + smog.cellCount()
                    + " nodes="
                    + smog.nodeCount()
                    + " edges="
                    + smog.edgeCount()
                    + " mass="
                    + smog.liveMass()
                    + " balance="
                    + smog.balanceError());
            if (args.length > 0) {
                Path path = Paths.get(args[0]);
                CompletableFuture<Path> saved = smog.requestSave(path);
                // In a server, subsequent ticks do this work; never block the world thread on the future.
                while (!saved.isDone()) {
                    smog.geometry(500000);
                    Thread.yield();
                }
                saved.get();
                try (DimensionState restored = DimensionState.load(path, world)) {
                    restored.resumeReadyCubes();
                    while (restored.geometryPending()) {
                        restored.geometry(500000);
                    }
                    System.out.println("restored mass=" + restored.liveMass() + " balance=" + restored.balanceError());
                }
            }
        }
    }
}

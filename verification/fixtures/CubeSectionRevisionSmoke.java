package org.smogmod.world;

import net.minecraft.block.Block;
import net.minecraft.block.BlockDoor;
import net.minecraft.init.Blocks;
import net.minecraft.world.chunk.Chunk;

import smog.CubeKey;
import smog.ImmutableCubeSnapshot;
import smog.WorldSnapshotProvider;

/** Correctness probes against the actual provider with Minecraft stubs. */
public final class CubeSectionRevisionSmoke {

    private static final class CountingChunk extends Chunk {
        int reads;
        boolean bottomDoorOpen = true;
        final BlockDoor door = new BlockDoor();

        CountingChunk(int x, int z) {
            super(x, z);
        }

        @Override
        public Block getBlock(int x, int y, int z) {
            reads++;
            return x == 7 && y == 16 && z == 7 ? door : Blocks.air;
        }

        @Override
        public int getBlockMetadata(int x, int y, int z) {
            if (x == 7 && z == 7 && y == 16) {
                return 8; // top half of the door reads the metadata at y=15
            }
            return x == 7 && z == 7 && y == 15 && bottomDoorOpen ? 4 : 0;
        }
    }

    private static void check(boolean ok, String message) {
        if (!ok) {
            throw new AssertionError(message);
        }
    }

    private static CubeKey key(int cx, int cy, int cz) {
        return new CubeKey(0, cx, cy, cz);
    }

    public static void main(String[] ignored) {
        ForgeWorldSnapshotProvider p = new ForgeWorldSnapshotProvider(0);
        CountingChunk c = new CountingChunk(0, 0);
        CubeKey k0 = key(0, 0, 0), k1 = key(0, 1, 0), k2 = key(0, 2, 0);
        check(p.matchesSnapshot(k0, null), "a missing cube must match captured null");
        check(p.snapshotIfReady(k0) == null && c.reads == 0, "no forced chunk loading");
        p.load(c);
        check(!p.matchesSnapshot(k0, null), "loaded cube must invalidate captured absence");

        ImmutableCubeSnapshot s0 = p.snapshotIfReady(k0);
        ImmutableCubeSnapshot s1 = p.snapshotIfReady(k1);
        ImmutableCubeSnapshot s2 = p.snapshotIfReady(k2);
        check(p.cachedSnapshotCount() == 3, "three distinct section snapshots");
        check(s1.airAt(7, 16, 7), "top door should be open");
        check(p.matchesSnapshot(k0, s0) && p.matchesSnapshot(k1, s1), "versions immediately match");
        int reads = c.reads;
        for (int n = 0; n < 100; n++) {
            check(p.matchesSnapshot(k1, s1), "stable version");
        }
        check(c.reads == reads, "fast version check read Minecraft blocks");

        // A metadata change to the lower half of a door at y=15 affects the upper
        // half at y=16, but must NOT invalidate an unrelated y=32 section.
        c.bottomDoorOpen = false;
        p.reviseBlock(7, 15, 7);
        check(!p.matchesSnapshot(k0, s0), "lower section not revised");
        check(!p.matchesSnapshot(k1, s1), "door upper-section dependency missed");
        check(p.matchesSnapshot(k2, s2), "unrelated height invalidated");
        check(p.cachedSnapshotCount() == 1, "nonlocal cache eviction");
        ImmutableCubeSnapshot closed = p.snapshotIfReady(k1);
        check(!closed.airAt(7, 16, 7), "door meta not refreshed in upper section");
        check(p.matchesSnapshot(k1, closed), "rebuilt upper snapshot has wrong stamp");

        // An ordinary edit to y=16 must not touch section 0 or 2.
        ImmutableCubeSnapshot new0 = p.snapshotIfReady(k0);
        p.reviseBlock(4, 16, 4);
        check(p.matchesSnapshot(k0, new0), "y16 changed y0 section");
        check(!p.matchesSnapshot(k1, closed), "y16 failed to invalidate own section");
        check(p.matchesSnapshot(k2, s2), "y16 changed y32 section");

        // Inclusive bounded region, with and without a boundary-crossing door.
        ImmutableCubeSnapshot new1 = p.snapshotIfReady(k1);
        p.reviseRange(0, 0, 1, 14);
        check(p.matchesSnapshot(k1, new1), "range without y15 touched upper section");
        p.reviseRange(0, 0, 15, 15);
        check(!p.matchesSnapshot(k1, new1), "region at y15 missed upper section");
        check(p.matchesSnapshot(k2, s2), "region invalidated unrelated section");
        p.reviseRange(0, 0, -500, 16);
        check(p.matchesSnapshot(k2, s2), "range clamp invalidated above y16");
        p.reviseRange(0, 0, 256, 500);
        check(p.matchesSnapshot(k2, s2), "out-of-world region invalidated geometry");

        // Cache belongs to the loaded Chunk object; unload/reload changes epoch.
        ImmutableCubeSnapshot keep = p.snapshotIfReady(k2);
        p.load(c);
        check(p.matchesSnapshot(k2, keep), "identical Chunk load unexpectedly invalidated cache");
        long epoch = p.unload(0, 0);
        check(epoch == keep.epoch, "unload epoch incorrect");
        check(p.cachedSnapshotCount() == 0, "unload left cached snapshots");
        check(!p.matchesSnapshot(k2, keep), "unload did not invalidate captured snapshot");
        check(p.matchesSnapshot(k2, null), "unloaded cube must match null");
        p.load(new CountingChunk(0, 0));
        check(!p.matchesSnapshot(k2, keep), "new chunk must invalidate old epoch");
        check(!p.matchesSnapshot(k2, null), "reloaded cube must differ from absence");
        ImmutableCubeSnapshot newEpoch = p.snapshotIfReady(k2);
        check(newEpoch.epoch != keep.epoch, "new chunk did not obtain new epoch");

        // Whole-column compatibility revision is still conservative and explicit.
        p.revise(0, 0);
        check(!p.matchesSnapshot(k2, newEpoch), "legacy column revision failed");
        check(p.cachedSnapshotCount() == 0, "legacy revision did not clear cache");

        // Negative chunk coordinates must be keyed without collisions.
        p.load(new CountingChunk(-1, -1));
        ImmutableCubeSnapshot negative = p.snapshotIfReady(key(-1, 0, -1));
        check(negative != null && p.matchesSnapshot(key(-1, 0, -1), negative), "negative chunk missing");
        p.reviseBlock(-1, 4, -1);
        check(!p.matchesSnapshot(key(-1, 0, -1), negative), "negative coordinates revised wrong column");
        check(p.snapshotIfReady(new CubeKey(1, 0, 0, 0)) == null,
                "wrong-dimension snapshot accessible");

        // Fallback for third-party WorldSnapshotProvider implementations.
        WorldSnapshotProvider basic = new WorldSnapshotProvider() {
            ImmutableCubeSnapshot snapshot = p.snapshotIfReady(k0);
            @Override public boolean isReady(CubeKey k) { return k.equals(k0); }
            @Override public ImmutableCubeSnapshot snapshotIfReady(CubeKey k) {
                return isReady(k) ? snapshot : null;
            }
        };
        check(basic.matchesSnapshot(k0, basic.snapshotIfReady(k0)), "default provider fallback broken");
        check(basic.matchesSnapshot(k1, null), "default provider null fallback broken");
        check(!basic.matchesSnapshot(k0, null), "default provider fallback missed a loaded cube");

        check(p.cachedSnapshotCount() >= 1, "cache counter unexpected");
        System.out.println("SMOG_CUBE_VERSION_FASTPATH_PASS");
    }
}

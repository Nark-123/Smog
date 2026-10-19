package smog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/** Exact edge/boundary equivalence after one or many neighbour-publication changes. */
public final class ContactRefreshReuseTests {

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }

    private static CellBuild complete(DimensionState state, DimensionState.Cell cell) {
        CellBuild b = new CellBuild(state, cell);
        for (int n = 0; !b.ready && n < 100000; n++) {
            b.advance();
        }
        check(b.ready, "CellBuild did not complete");
        return b;
    }

    private static List<String> edges(EdgeAccumulator data, SmogConfig config) {
        final List<String> result = new ArrayList<>();
        data.appendTo(
            config,
            (a, b, g) -> result.add(a + ":" + b + ":" + Long.toHexString(Double.doubleToLongBits(g))));
        return result;
    }

    private static void compare(CellBuild actual, CellBuild fresh, SmogConfig config, String message) {
        check(Arrays.equals(actual.boundary, fresh.boundary), message + " boundary changed");
        check(
            edges(actual.edges, config).equals(edges(fresh.edges, config)),
            message + " edges differ from a complete rebuild");
    }

    private static DimensionState.Geometry neighborGeometry(int id, int salt) {
        DimensionState.Geometry g = new DimensionState.Geometry();
        g.labels = new int[512];
        g.distance = new byte[512];
        Arrays.fill(g.labels, 0);
        for (int p = 0; p < 512; p++) {
            g.distance[p] = (byte) ((p + salt) % 6);
        }
        g.ids = new int[] { id };
        g.volumes = new int[] { 512 };
        return g;
    }

    private static void testCopyPreservesHashTableWithResizing(SmogConfig config) {
        EdgeAccumulator internal = new EdgeAccumulator(), combined = new EdgeAccumulator();
        for (int i = 0; i < 125; i++) {
            int a = i % 11, b = 11 + i % 37;
            internal.add(a, b, 1 + (i % 9));
            internal.add(a, b, 2 + (i % 11));
        }
        combined.copyFrom(internal);
        check(edges(internal, config).equals(edges(combined, config)), "equal-capacity copy mismatch");
        for (int i = 0; i < 600; i++) {
            combined.add(i % 200, 512 + i, 1 + (i % 17));
        }
        // Now the destination has a much larger hash table than the source.
        combined.copyFrom(internal);
        check(edges(internal, config).equals(edges(combined, config)), "rehash copy mismatch");
        combined.add(0, 9999, 3);
        combined.copyFrom(internal);
        check(edges(internal, config).equals(edges(combined, config)), "second copy mismatch");
        combined.copyFrom(combined);
        check(edges(internal, config).equals(edges(combined, config)), "self copy mismatch");
    }

    private static void testRefreshingNeighbors(SmogConfig config) {
        int nx = 32, ny = 16, nz = 16;
        byte[] air = new byte[nx * ny * nz];
        Random random = new Random(0x534d4f47L);
        for (int i = 0; i < air.length; i++) {
            // A fairly complex mix of rooms and passages stresses internal edges.
            air[i] = (byte) (random.nextInt(8) != 0 ? 1 : 0);
        }
        SparseTests.World world = SparseTests.world(air, nx, ny, nz, 63);
        DimensionState state = new DimensionState(0, config, world);
        try {
            DimensionState.Cell cell = state.cell(new CellKey(0, 0, 0, 0), true);
            DimensionState.Cell neighbor = state.cell(new CellKey(0, 1, 0, 0), true);
            CellBuild candidate = complete(state, cell);
            check(candidate.validWorld(), "initial snapshots not valid");
            check(candidate.validContacts(), "initial neighbor contacts not valid");
            double[] frozenBoundary = candidate.boundary.clone();
            List<String> originalEdges = edges(candidate.edges, config);
            // Simulate publication of the first neighboring Cell8 after preparation.
            neighbor.geometry = neighborGeometry(100, 0);
            check(!candidate.validContacts(), "new neighbor must invalidate contacts");
            candidate.refreshContacts();
            check(
                candidate.diagnosticStage() == 4 && !candidate.ready,
                "refresh must restart only external contacts (stage 4)");
            check(Arrays.equals(frozenBoundary, candidate.boundary), "refresh cleared boundaries");
            for (int n = 0; !candidate.ready && n < 100; n++) {
                check(candidate.diagnosticStage() == 4, "refresh recomputed internal contacts");
                candidate.advance();
            }
            check(candidate.ready && candidate.validContacts(), "refreshed neighbor not ready");
            compare(candidate, complete(state, cell), config, "new neighbor");
            check(
                edges(candidate.edges, config).size() > originalEdges.size(),
                "new neighbor did not generate external edges");

            // Neighbor ID/geometry changes twice; old external contacts must disappear.
            for (int i = 0; i < 2; i++) {
                neighbor.geometry = neighborGeometry(200 + i, i + 1);
                check(!candidate.validContacts(), "replaced neighbor was missed");
                candidate.refreshContacts();
                check(candidate.diagnosticStage() == 4 && !candidate.ready, "replacement restarted internal phase");
                while (!candidate.ready) {
                    candidate.advance();
                }
                compare(candidate, complete(state, cell), config, "replacement " + i);
                check(Arrays.equals(frozenBoundary, candidate.boundary), "boundary drift");
            }
            neighbor.geometry = null;
            check(!candidate.validContacts(), "neighbor unload was missed");
            candidate.refreshContacts();
            while (!candidate.ready) {
                candidate.advance();
            }
            compare(candidate, complete(state, cell), config, "neighbor removal");
            check(
                edges(candidate.edges, config).equals(originalEdges),
                "stale external edges survived neighbor removal");
            check(candidate.validWorld(), "world changed during contact refresh");
        } finally {
            state.close();
        }
    }

    public static void main(String[] args) {
        SmogConfig a = SmogConfig.throat8();
        SmogConfig b = SmogConfig.builder()
            .mode(SmogConfig.COMPONENT)
            .build();
        testCopyPreservesHashTableWithResizing(a);
        testCopyPreservesHashTableWithResizing(b);
        testRefreshingNeighbors(a);
        testRefreshingNeighbors(b);
        System.out.println("SMOG_CONTACT_REFRESH_REUSE_PASS");
    }
}

package smog;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * The single atomic append implementation for cached and freshly built geometry.
 * All world/contact checks and incremental capacity preparation precede this call.
 * Local endpoints index runtimeIds; negative endpoints encode -1-runtimeId.
 */
final class EpochAppendBatch {

    static class Item {

        final DimensionState.Cell cell;
        final NeighborView neighbors;
        DimensionState.Geometry geometry;
        GeometryPack pack;
        DimensionState.PreparedMass mass;
        int base;
        int[] localRecords = EdgeAccumulator.EMPTY_RECORDS, externalRecords = EdgeAccumulator.EMPTY_RECORDS;

        Item(DimensionState state, DimensionState.Cell cell) {
            this(state, cell, new NeighborView(state, cell.key));
        }

        Item(DimensionState state, DimensionState.Cell cell, NeighborView neighbors) {
            this.cell = cell;
            this.neighbors = neighbors;
        }
    }

    static long append(DimensionState state, List<? extends Item> items, int from, int to, int[] runtimeIds, int nodes,
        int edges) {
        LinkedHashSet<DimensionState.Cell> fronts = new LinkedHashSet<>();
        Map<DimensionState.Cell, NeighborView> views = new LinkedHashMap<>();
        long start = System.nanoTime();
        for (int i = from; i < to; i++) {
            Item e = items.get(i);
            long massStart = System.nanoTime();
            e.mass = state.prepareEpochMass(e.cell, e.geometry);
            if (e instanceof EpochBuildAppend) state.recordFallbackPreparation(System.nanoTime() - massStart);
            fronts.add(e.cell);
            e.neighbors.addFronts(fronts);
            views.put(e.cell, e.neighbors);
        }
        state.restartMetrics.add("epoch_prepare_ms", (System.nanoTime() - start) / 1e6);
        long appendStart = System.nanoTime(), reserveStart = appendStart;
        boolean duringStep = state.graph.isStepInProgress();
        if (duringStep) state.reserveEpoch(nodes, edges);
        else state.reserveFast(nodes, edges);
        long reserveNanos = System.nanoTime() - reserveStart;
        state.profile.record(ProfilerCounters.RESERVE, reserveNanos, (long) nodes + edges);
        double reserveMs = reserveNanos / 1e6;
        state.restartMetrics.add("epoch_append_reserve_ms", reserveMs);
        state.restartMetrics.max("epoch_append_reserve_max_ms", reserveMs);
        try {
            long nodeStart = System.nanoTime();
            for (int i = from; i < to; i++) {
                Item e = items.get(i);
                state.publishEpochGeometry(e.cell, e.geometry, e.pack, e.mass);
                System.arraycopy(e.geometry.ids, 0, runtimeIds, e.base, e.geometry.ids.length);
            }
            state.restartMetrics.add("fast_restore_nodes_ms", (System.nanoTime() - nodeStart) / 1e6);
            long edgeStart = System.nanoTime();
            for (int i = from; i < to; i++) {
                Item e = items.get(i);
                appendEdges(state, e.localRecords, runtimeIds);
                appendEdges(state, e.externalRecords, runtimeIds);
            }
            state.restartMetrics.add("fast_restore_edges_ms", (System.nanoTime() - edgeStart) / 1e6);
            long frontStart = System.nanoTime();
            state.refreshFastFronts(fronts, views);
            state.restartMetrics.add("fast_restore_front_ms", (System.nanoTime() - frontStart) / 1e6);
        } catch (RuntimeException | Error failure) {
            state.failAppend(failure);
            throw failure;
        } finally {
            state.graph.endAppendBatch();
        }
        try {
            state.applyEvents();
            for (int i = from; i < to; i++) {
                Item e = items.get(i);
                long finishStart = System.nanoTime();
                state.finishPublicationMass(e.cell, e.mass.policy);
                if (e instanceof EpochBuildAppend)
                    state.restartMetrics.add("fallback_mass_finalization_ms", (System.nanoTime() - finishStart) / 1e6);
                state.restartMetrics.add("neighbor_lookups_per_append", e.neighbors.lookups);
            }
        } catch (RuntimeException | Error failure) {
            state.failAppend(failure);
            throw failure;
        }
        long duration = System.nanoTime() - appendStart;
        state.profile.record(ProfilerCounters.APPEND, duration, to - from);
        state.profile.detail(ProfilerCounters.APPEND, nodes, edges);
        state.profile.batch(ProfilerCounters.APPEND, to - from);
        if (duringStep) {
            state.restartMetrics.add("epoch_append_cells", to - from);
            state.restartMetrics.add("epoch_append_nodes", nodes);
            state.restartMetrics.add("epoch_append_edges", edges);
            state.restartMetrics.add("epoch_append_batches", 1);
        }
        state.restartMetrics.add("epoch_append_ms", duration / 1e6);
        state.restartMetrics.max("epoch_append_max_ms", duration / 1e6);
        state.restartMetrics.histogram("batchCells", to - from);
        state.restartMetrics.histogram("batchNodes", nodes);
        state.restartMetrics.histogram("batchEdges", edges);
        return duration;
    }

    private static void appendEdges(DimensionState state, int[] records, int[] ids) {
        for (int i = 0; i < records.length; i += 4) {
            double length = state.config.mode == SmogConfig.COMPONENT ? state.config.containerSize
                : Math.max(1, records[i + 3]);
            int a = records[i], b = records[i + 1];
            state.graph.addEdge(a < 0 ? -1 - a : ids[a], b < 0 ? -1 - b : ids[b], records[i + 2] / length);
        }
    }
}

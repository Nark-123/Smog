package smog;

import java.util.Collections;

/** One bounded CellBuild COLD_NEW/EXPANSION_NEW packet, sharing the cache commit path. */
final class EpochBuildAppend extends EpochAppendBatch.Item {
    private final CellBuild build;
    private final int[] runtimeIds;
    private boolean capacityReady;

    EpochBuildAppend(CellBuild build) {
        super(build.state, build.cell, build.neighborView);
        this.build = build; geometry = build.geometry;
        geometry.ids = new int[geometry.volumes.length];
        runtimeIds = new int[geometry.ids.length];
        pack = build.pack();
        records();
        build.state.restartMetrics.add("fallback_epoch_eligible", 1);
    }

    private void records() {
        long profileStart = build.state.profile.begin(ProfilerCounters.CONVERSION);
        boolean profileFailed = false;
        try {
            EdgeAccumulator converted = new EdgeAccumulator();
            int[] records = build.edges.records();
            for (int i = 0; i < records.length; i += 4) {
                int a = records[i], b = records[i + 1];
                converted.addAggregate(a < 512 ? a : -1 - (a - 512), b < 512 ? b : -1 - (b - 512),
                    records[i + 2], records[i + 3]);
            }
            localRecords = converted.records();

        } catch (RuntimeException | Error failure) {
            profileFailed = true; build.state.profile.failure(ProfilerCounters.CONVERSION); throw failure;
        } finally { build.state.profile.end(ProfilerCounters.CONVERSION, profileStart, profileFailed ? 0 : localRecords.length / 4); }
    }

    /** -1 invalid private build, 0 committed, 1 bounded preparation. No retries of a stale world. */
    int advance() {
        DimensionState state = build.state;
        int edges = localRecords.length / 4;
        if (!capacityReady) {
            if (!state.prepareEpochCapacity(runtimeIds.length, edges)) {
                state.restartMetrics.add("capacity_retries", 1); return 1;
            }
            capacityReady = true;
        }
        state.restartMetrics.add("preflight_rechecks", 1);
        if (cell.geometry != null || cell.ticket != build.ticket || !state.provider.isReady(cell.cube.key)
            || !build.validWorld()) return -1;
        if (!build.validContacts()) {
            build.refreshContacts(); build.diagnosticRefreshingContacts = true;
            capacityReady = false;
            return 1;
        }
        boolean duringStep = state.graph.isStepInProgress();
        long duration = EpochAppendBatch.append(state, Collections.singletonList(this), 0, 1,
            runtimeIds, runtimeIds.length, edges);
        // Only successfully published, world-validated geometry reaches the optional cache.
        if (state.geometryCache != null && !build.fromCache) state.geometryCache.store(pack);
        state.recordFastPublication(duration, 1);
        if (duringStep) state.restartMetrics.add("fallback_epoch_appended_cells", 1);
        state.restartMetrics.add("fallback_commit_ms", duration / 1e6);
        state.restartMetrics.max("fallback_commit_max_ms", duration / 1e6);
        if (!duringStep) state.restartMetrics.add("fallback_safe_cut_cells", 1);
        return 0;
    }

    void refreshed() { records(); capacityReady = false; }
}

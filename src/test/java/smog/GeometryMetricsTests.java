package smog;

/** Owner-thread diagnostics must be observational and independently resettable. */
public final class GeometryMetricsTests {

    private static void check(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }

    public static void main(String[] args) {
        byte[] all = new byte[4096];
        java.util.Arrays.fill(all, (byte) 1);
        SparseTests.World world = new SparseTests.World();
        CubeKey cube = new CubeKey(0, 0, 0, 0);
        world.put(cube, all, 1, 1);
        DimensionState state = new DimensionState(0, SmogConfig.throat8(), world);
        BlockPos point = new BlockPos(0, 0, 0, 0);
        check(!state.hasSampleGeometry(point), "Unbuilt point claims a concentration");
        state.requestBuild(new CellKey(0, 0, 0, 0));
        check(state.sampleQueueAgeTicks(point) >= 0, "Queued point did not get a diagnostic age");
        state.tick(0L);
        check(state.sampleQueueAgeTicks(point) >= 1, "Queue age does not advance in simulation ticks");
        check(
            state.preparedLimit() == Math.max(8, Math.min(1024, Integer.getInteger("smog.preparedLimit", 64))),
            "Unexpected prepared queue limit");
        for (int i = 0; i < 100000 && state.geometryPending(); i++) {
            state.geometry(500000L);
        }
        check(!state.geometryPending(), "Build did not settle");
        GeometryMetrics metrics = state.geometryMetrics;
        check(metrics.geometryCalls > 0, "No geometry calls recorded");
        check(metrics.jobsStarted == 1, "Unexpected jobs count");
        check(metrics.cellsPrepared == 1 && metrics.partitionsCompleted == 1, "Missing prepared partition");
        check(metrics.publishCalls == 1, "Published geometry not counted");
        check(
            metrics.safeWindowsOpened > 0 && metrics.safeWindowPublishTotal == 1 && metrics.safeWindowMaxPublished == 1,
            "Safe publication window not counted");
        check(
            metrics.queueWaitSamples == 1 && metrics.preparedWaitSamples == 1 && metrics.queueWaitMaxTicks >= 1,
            "Queue-to-publish age not counted");
        check(
            state.hasSampleGeometry(point) && state.sampleQueueAgeTicks(point) == -1,
            "Published point still marked as waiting");
        for (int phase = 0; phase < GeometryMetrics.PHASE_COUNT; phase++) {
            check(metrics.phaseCalls[phase] > 0, "Missing phase " + phase);
            check(metrics.phaseMaxNanos[phase] <= metrics.phaseNanos[phase], "Phase maximum larger than its total");
        }
        DimensionState.GeometryFootprint footprint = state.geometryFootprint();
        check(footprint.registeredCubes == 1 && footprint.readyCubes == 1, "Incorrect registered/ready cube count");
        check(footprint.activeCubes == 1 && footprint.activeCells == 1, "Incorrect active geometry count");
        check(footprint.allocatedCells >= footprint.activeCells, "Allocated cells cannot be fewer than active ones");
        int[] queues = state.geometryQueues();
        check(
            queues.length == 4 && queues[0] == 0 && queues[1] == 0 && queues[2] == 0 && queues[3] == 0,
            "Queues did not empty");
        state.geometry(100000L);
        check(metrics.idleEndCalls > 0, "Idle exit reason not recorded");
        double originalMass = state.liveMass() + state.parkedMass() + state.trappedMass();
        long originalGeneration = state.iteration();
        int originalNodes = state.nodeCount();
        metrics.reset();
        check(
            metrics.phaseCalls[GeometryMetrics.PARTITION] == 0 && metrics.publishCalls == 0
                && metrics.geometryCalls == 0
                && metrics.idleEndCalls == 0
                && metrics.budgetEndCalls == 0
                && metrics.partCalls == 0
                && metrics.partCommits == 0
                && metrics.safeWindowsOpened == 0
                && metrics.safeWindowPublishTotal == 0
                && metrics.queueWaitSamples == 0
                && metrics.preparedWaitSamples == 0,
            "Reset did not clear counters");
        check(
            state.nodeCount() == originalNodes && state.iteration() == originalGeneration,
            "Metric reset changed simulation structure or progress");
        check(state.liveMass() + state.parkedMass() + state.trappedMass() == originalMass, "Metric reset changed mass");
        GeometryMetrics windows = new GeometryMetrics();
        windows.safeWindow(true);
        windows.publish(10);
        windows.publish(20);
        windows.safeWindow(false);
        windows.safeWindow(true);
        windows.safeWindow(false);
        windows.publishedWait(18, 2);
        windows.publishedWait(700, 300);
        check(
            windows.safeWindowsOpened == 2 && windows.safeWindowsClosed == 2
                && windows.safeWindowsWithPublish == 1
                && windows.safeWindowPublishTotal == 2
                && windows.safeWindowLastPublished == 0
                && windows.safeWindowMaxPublished == 2,
            "Safe-window accounting is inconsistent");
        check(
            windows.queueWaitBuckets[0] == 1 && windows.queueWaitBuckets[3] == 1
                && windows.queueWaitSamples == 2
                && windows.preparedWaitSamples == 2
                && windows.queueWaitTotalTicks == 718
                && windows.preparedWaitTotalTicks == 302,
            "Publish-age buckets/totals are inconsistent");
        windows.reset();
        check(
            !windows.safeWindowOpen && windows.safeWindowsOpened == 0
                && windows.safeWindowPublishTotal == 0
                && windows.queueWaitSamples == 0,
            "Safe-window reset retained counters");
        state.close();
        System.out.println("SMOG_GEOMETRY_METRICS_PASS");
    }
}

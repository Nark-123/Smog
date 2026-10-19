package smog;

import java.util.Arrays;

/**
 * World-thread-only, transient geometry counters. Values are accumulated since the last
 * diagnostic reset and are never part of the checkpoint or the simulation state.
 * Timing is performed once per existing scheduler unit, never per voxel/BFS vertex.
 */
public final class GeometryMetrics {

    public static final int SNAPSHOTS = 0;
    public static final int HALO = 1;
    public static final int PARTITION = 2;
    public static final int INTERNAL_CONTACTS = 3;
    public static final int EXTERNAL_CONTACTS = 4;
    public static final int PHASE_COUNT = 5;

    public final long[] phaseCalls = new long[PHASE_COUNT];
    public final long[] phaseNanos = new long[PHASE_COUNT];
    public final long[] phaseMaxNanos = new long[PHASE_COUNT];

    // Flat, period-delta-friendly counters for profiling publication-eligible geometry calls.
    // All values are nanos or counts and only owner-thread code can mutate them.
    public static final int SAFE_CALLS = 0;
    public static final int SAFE_ELAPSED_NANOS = 1;
    public static final int SAFE_BUDGET_NANOS = 2;
    public static final int SAFE_BUDGET_ENDS = 3;
    public static final int SAFE_IDLE_ENDS = 4;
    public static final int SAFE_PREPARED_START_SUM = 5;
    public static final int SAFE_PREPARED_END_SUM = 6;
    public static final int SAFE_WORLD_CHECK_NANOS = 7;
    public static final int SAFE_CONTACT_CHECK_NANOS = 8;
    public static final int SAFE_REFRESH_REQUEST_NANOS = 9;
    public static final int SAFE_REFRESH_REBUILD_NANOS = 10;
    public static final int SAFE_BUILD_NANOS = 11;
    public static final int SAFE_PUBLISH_NANOS = 12;
    public static final int SAFE_APPLY_EVENTS_NANOS = 13;
    public static final int SAFE_FRONT_NANOS = 14;
    public static final int SAFE_PUBLISHED = 15;
    public static final int SAFE_COUNTER_COUNT = 16;
    private final long[] safeTotals = new long[SAFE_COUNTER_COUNT];
    public long safeCallMaxNanos;
    private boolean recordingSafeCall;

    public long[] safeSnapshot() {
        return safeTotals.clone();
    }

    void beginSafeCall(boolean eligible) {
        recordingSafeCall = eligible;
    }

    void endSafeCall(long elapsed, long budget, boolean budgetEnded,
            int preparedStart, int preparedEnd) {
        if (recordingSafeCall) {
            safeTotals[SAFE_CALLS]++;
            safeTotals[SAFE_ELAPSED_NANOS] += elapsed;
            safeTotals[SAFE_BUDGET_NANOS] += budget;
            safeTotals[budgetEnded ? SAFE_BUDGET_ENDS : SAFE_IDLE_ENDS]++;
            safeTotals[SAFE_PREPARED_START_SUM] += preparedStart;
            safeTotals[SAFE_PREPARED_END_SUM] += preparedEnd;
            safeCallMaxNanos = Math.max(safeCallMaxNanos, elapsed);
        }
        recordingSafeCall = false;
    }

    void publicationEvents(long nanos) {
        if (recordingSafeCall) {
            safeTotals[SAFE_APPLY_EVENTS_NANOS] += nanos;
        }
    }

    public long geometryCalls, geometryNanos, geometryMaxNanos, geometryBusyCalls;
    public long frontScans, frontNanos, frontMaxNanos;
    public long worldValidations, worldValidationNanos, worldValidationMaxNanos, worldInvalid;
    public long contactValidations, contactValidationNanos, contactValidationMaxNanos, contactRefreshes;
    public long jobsStarted, newJobs, rebuildJobs, staleJobs, budgetOverruns;
    public long cellsPrepared, partitionsCompleted, partitionNodes, maxPartitionNodes;
    public long captureCalls, captureNanos, captureMaxNanos;
    public long publishCalls, publishNanos, publishMaxNanos;

    // Per-geometry-call exit reasons. Each counter is independent; blocked reasons may overlap.
    public long budgetEndCalls, idleEndCalls, idleWithBacklogCalls;
    public long waitSolverCalls, waitCaptureCalls, preparedFullCalls;
    // Per-world-tick simulation progress, not the number of pending frames.
    public long partCalls, partCommits, partsSkippedForCapture;

    // A safe window spans consecutive geometry calls between solver steps/snapshot captures.
    // The counters measure actual publications, not theoretical spare time in a tick.
    public long safeWindowsOpened, safeWindowsClosed, safeWindowsWithPublish;
    public long safeWindowPublishTotal, safeWindowMaxPublished, safeWindowLastPublished;
    public long eligibleGeometryCalls, preparedAccepted;
    public boolean safeWindowOpen;
    public long safeWindowCurrentPublished;

    // Refresh invocation is cheap; the subsequent contact-stage work is measured separately.
    public long refreshRequestNanos, refreshRequestMaxNanos, refreshAdvanceNanos;
    public long queueWaitSamples, queueWaitTotalTicks, queueWaitMaxTicks;
    public long preparedWaitSamples, preparedWaitTotalTicks, preparedWaitMaxTicks;
    public final long[] queueWaitBuckets = new long[4];

    void phase(int phase, long nanos) {
        phaseCalls[phase]++;
        phaseNanos[phase] += nanos;
        if (recordingSafeCall) {
            safeTotals[SAFE_BUILD_NANOS] += nanos;
        }
        phaseMaxNanos[phase] = Math.max(phaseMaxNanos[phase], nanos);
    }

    void geometry(long nanos, boolean pending) {
        geometryCalls++;
        geometryNanos += nanos;
        geometryMaxNanos = Math.max(geometryMaxNanos, nanos);
        if (pending) {
            geometryBusyCalls++;
        }
    }

    void front(long nanos) {
        frontScans++;
        frontNanos += nanos;
        if (recordingSafeCall) {
            safeTotals[SAFE_FRONT_NANOS] += nanos;
        }
        frontMaxNanos = Math.max(frontMaxNanos, nanos);
    }

    void worldValidation(long nanos, boolean valid) {
        worldValidations++;
        worldValidationNanos += nanos;
        if (recordingSafeCall) {
            safeTotals[SAFE_WORLD_CHECK_NANOS] += nanos;
        }
        worldValidationMaxNanos = Math.max(worldValidationMaxNanos, nanos);
        if (!valid) {
            worldInvalid++;
        }
    }

    void contactValidation(long nanos, boolean valid) {
        contactValidations++;
        contactValidationNanos += nanos;
        if (recordingSafeCall) {
            safeTotals[SAFE_CONTACT_CHECK_NANOS] += nanos;
        }
        contactValidationMaxNanos = Math.max(contactValidationMaxNanos, nanos);
        if (!valid) {
            contactRefreshes++;
        }
    }

    void capture(long nanos) {
        captureCalls++;
        captureNanos += nanos;
        captureMaxNanos = Math.max(captureMaxNanos, nanos);
    }

    void safeWindow(boolean allowed) {
        if (allowed && !safeWindowOpen) {
            safeWindowOpen = true;
            safeWindowCurrentPublished = 0;
            safeWindowsOpened++;
        } else if (!allowed && safeWindowOpen) {
            safeWindowOpen = false;
            safeWindowsClosed++;
            safeWindowLastPublished = safeWindowCurrentPublished;
            if (safeWindowCurrentPublished > 0) {
                safeWindowsWithPublish++;
            }
            safeWindowCurrentPublished = 0;
        }
    }

    void refreshed(long nanos) {
        refreshRequestNanos += nanos;
        if (recordingSafeCall) {
            safeTotals[SAFE_REFRESH_REQUEST_NANOS] += nanos;
        }
        refreshRequestMaxNanos = Math.max(refreshRequestMaxNanos, nanos);
    }

    void refreshedAdvance(long nanos) {
        refreshAdvanceNanos += nanos;
        if (recordingSafeCall) {
            safeTotals[SAFE_REFRESH_REBUILD_NANOS] += nanos;
        }
    }

    void publishedWait(long queueTicks, long preparedTicks) {
        if (queueTicks >= 0) {
            queueWaitSamples++;
            queueWaitTotalTicks += queueTicks;
            queueWaitMaxTicks = Math.max(queueWaitMaxTicks, queueTicks);
            int bucket = queueTicks <= 20 ? 0 : queueTicks <= 100 ? 1
                    : queueTicks <= 600 ? 2 : 3;
            queueWaitBuckets[bucket]++;
        }
        if (preparedTicks >= 0) {
            preparedWaitSamples++;
            preparedWaitTotalTicks += preparedTicks;
            preparedWaitMaxTicks = Math.max(preparedWaitMaxTicks, preparedTicks);
        }
    }

    void publish(long nanos) {
        safeWindowPublishTotal++;
        if (safeWindowOpen) {
            safeWindowCurrentPublished++;
            safeWindowMaxPublished = Math.max(safeWindowMaxPublished,
                    safeWindowCurrentPublished);
        }
        publishCalls++;
        publishNanos += nanos;
        if (recordingSafeCall) {
            safeTotals[SAFE_PUBLISH_NANOS] += nanos;
            safeTotals[SAFE_PUBLISHED]++;
        }
        publishMaxNanos = Math.max(publishMaxNanos, nanos);
    }

    public void reset() {
        Arrays.fill(phaseCalls, 0);
        Arrays.fill(phaseNanos, 0);
        Arrays.fill(phaseMaxNanos, 0);
        geometryCalls = geometryNanos = geometryMaxNanos = geometryBusyCalls = 0;
        frontScans = frontNanos = frontMaxNanos = 0;
        worldValidations = worldValidationNanos = worldValidationMaxNanos = worldInvalid = 0;
        contactValidations = contactValidationNanos = contactValidationMaxNanos = contactRefreshes = 0;
        jobsStarted = newJobs = rebuildJobs = staleJobs = budgetOverruns = 0;
        cellsPrepared = partitionsCompleted = partitionNodes = maxPartitionNodes = 0;
        captureCalls = captureNanos = captureMaxNanos = 0;
        publishCalls = publishNanos = publishMaxNanos = 0;
        budgetEndCalls = idleEndCalls = idleWithBacklogCalls = 0;
        waitSolverCalls = waitCaptureCalls = preparedFullCalls = 0;
        partCalls = partCommits = partsSkippedForCapture = 0;
        safeWindowsOpened = safeWindowsClosed = safeWindowsWithPublish = 0;
        safeWindowPublishTotal = safeWindowMaxPublished = safeWindowLastPublished = 0;
        safeWindowOpen = false;
        safeWindowCurrentPublished = 0;
        eligibleGeometryCalls = preparedAccepted = 0;
        refreshRequestNanos = refreshRequestMaxNanos = refreshAdvanceNanos = 0;
        queueWaitSamples = queueWaitTotalTicks = queueWaitMaxTicks = 0;
        preparedWaitSamples = preparedWaitTotalTicks = preparedWaitMaxTicks = 0;
        Arrays.fill(queueWaitBuckets, 0);
        Arrays.fill(safeTotals, 0);
        safeCallMaxNanos = 0;
        recordingSafeCall = false;
    }
}

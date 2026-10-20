package org.smogmod.world;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkProviderServer;

import org.smogmod.compat.gt5u.PendingPulse;
import org.smogmod.persistence.WorldStore;

import cpw.mods.fml.common.FMLLog;
import smog.BlockPos;
import smog.CubeKey;
import smog.DimensionState;
import smog.GeometryMetrics;
import smog.ImmutableCubeSnapshot;
import smog.SpatialQueries;
import smog.WorldAdapters;

public final class WorldRuntime implements AutoCloseable {

    private static final int PERF_LOG_TICKS = Math
        .max(20, Math.min(72000, Integer.getInteger("smog.perfLogTicks", 1200)));

    // Stable, numeric CSV schema for comparison across identical server runs.
    private static final String PERF_CSV_HEADER = "timestamp_utc,schema_version,dimension,world_tick,period_ticks,prepared_limit"
        + ",runtime_avg_ms,geometry_avg_ms,snapshot_phase_ms,halo_phase_ms"
        + ",partition_phase_ms,inner_contacts_phase_ms,outer_contacts_phase_ms"
        + ",front_total_ms,contacts_refreshes,prepared_accepted,law_capture_skips"
        + ",safe_calls,safe_used_ms,safe_budget_ms"
        + ",safe_max_call_ms,safe_budget_ends,safe_idle_ends,safe_prepared_start_avg"
        + ",safe_prepared_end_avg,safe_world_validation_ms,safe_contact_validation_ms"
        + ",safe_refresh_request_ms,safe_refresh_rebuild_ms,safe_fresh_build_ms"
        + ",safe_publish_ms,safe_publish_us_per_cell,safe_apply_events_ms,safe_front_ms"
        + ",safe_other_ms,publications,safe_windows_opened,safe_windows_with_publication"
        + ",window_max_publications,build_queue,prepared_queue,front_queue,stale_jobs"
        + ",model_lag_sec";

    public final WorldServer world;

    public final ForgeWorldSnapshotProvider provider;

    public final DimensionState state;

    public final List<PendingPulse> pending = new ArrayList<>();

    private final Set<BlockPos> changes = new HashSet<>();

    private final WorldStore store;

    private final PerformanceFileLog performanceFileLog;

    private boolean seeded;

    private boolean closed;

    private long lastTick = Long.MIN_VALUE;

    public long acceptedPulses;

    public long networkBatches, networkUniqueChunks, networkFullScans, networkCubeVisits, networkPackets;

    private long perfTicks, perfTickNanos, perfMaxTickNanos;
    private long perfDeliveryNanos, perfMaxDeliveryNanos, perfDeliveryAttempts;
    private long perfDelivered, perfDeferred, perfInvalidatedBlocks, perfInvalidationNanos;
    private long perfStartedAt = System.nanoTime();
    private long perfSlowRuntimeTicks, perfSlowGeometryTicks;
    private static final long SLOW_SEGMENT_NANOS = 10000000L;

    private boolean perfLogging = Boolean.getBoolean("smog.perfLog");
    private long nextPerfLogTick = Long.MIN_VALUE;
    private long lastLogTick, lastLogRuntimeNanos, lastLogGeometryNanos;
    private long lastLogSnapshotNanos, lastLogSnapshotMisses;
    private long lastLogPublished;
    private long lastLogWindowsOpened, lastLogWindowsClosed, lastLogWindowsWithPublish;
    private long lastLogWindowPublications, lastLogEligibleCalls, lastLogPreparedAccepted;
    private long lastLogWorldChecks, lastLogWorldCheckNanos;
    private long lastLogContactsChecks, lastLogContactsCheckNanos;
    private long lastLogRefreshRequestNanos, lastLogRefreshAdvanceNanos;
    private long lastLogQueueWaitSamples, lastLogQueueWaitTicks;
    private long lastLogPreparedWaitSamples, lastLogPreparedWaitTicks;
    private final long[] lastLogPhaseNanos = new long[GeometryMetrics.PHASE_COUNT];
    private long lastLogFrontNanos, lastLogInvalidateNanos, lastLogEvicted;
    private long lastLogStaleJobs, lastLogContactRefreshes;
    private long lastLogBudgetEnd, lastLogIdleWithBacklog, lastLogWaitSolver;
    private long lastLogWaitCapture, lastLogPreparedFull;
    private long lastLogPartCalls, lastLogPartCommits, lastLogCaptureSkips;
    private long lastLogUnloadCancellations;
    private long lastLogFrontScans, lastLogSlowRuntimeTicks, lastLogSlowGeometryTicks;
    private final long[] lastSafeTotals = new long[GeometryMetrics.SAFE_COUNTER_COUNT];
    private int lastLogBuildQueue;
    private long perfLogAttempts, lastPerfLogAttemptTick = Long.MIN_VALUE;

    public WorldRuntime(WorldServer world) throws Exception {
        long dimensionOpened = System.nanoTime();
        this.world = world;
        provider = new ForgeWorldSnapshotProvider(world.provider.dimensionId);
        performanceFileLog = new PerformanceFileLog(
            world.getSaveHandler()
                .getWorldDirectory()
                .toPath()
                .resolve("data/smog/diagnostics/perf-dimension_" + world.provider.dimensionId + ".log"));
        store = new WorldStore(
            world.getSaveHandler()
                .getWorldDirectory()
                .toPath()
                .resolve("data/smog/dimension_" + world.provider.dimensionId));
        state = store.load(world.provider.dimensionId, provider, pending);
        state.dimensionOpenedAt(dimensionOpened);
        state.geometryCache(
            world.getSaveHandler()
                .getWorldDirectory()
                .toPath()
                .resolve("data/smog/geometry-dimension_" + world.provider.dimensionId));
        if (perfLogging) {
            long now = world.getTotalWorldTime();
            lastLogTick = now;
            nextPerfLogTick = now + PERF_LOG_TICKS;
        }
    }

    public void accept(PendingPulse pulse) {
        provider.checkThread();
        if (pending.size() >= 1000000) {
            throw new IllegalStateException("SMOG pending queue full; refusing silent loss");
        }
        if (pulse.amount != 0) {
            pending.add(pulse);
            acceptedPulses++;
            store.markDirty();
        }
    }

    public void load(Chunk chunk) {
        store.markDirty();
        provider.load(chunk);
        WorldAdapters.columnReady(state, chunk.xPosition, chunk.zPosition);
    }

    public void unload(Chunk chunk) {
        store.markDirty();
        long epoch = provider.unload(chunk.xPosition, chunk.zPosition);
        for (int y = 0; y < 16; y++) {
            WorldAdapters
                .cubeUnloading(state, new CubeKey(state.dimension, chunk.xPosition, y, chunk.zPosition), epoch);
        }
    }

    public void changed(int x, int y, int z) {
        store.markDirty();
        provider.reviseBlock(x, y, z);
        changes.add(new BlockPos(state.dimension, x, y, z));
    }

    public void invalidate(BlockPos min, BlockPos max) {
        store.markDirty();
        for (Object value : ((ChunkProviderServer) world.getChunkProvider()).loadedChunks) {
            Chunk chunk = (Chunk) value;
            if ((long) chunk.xPosition * 16 <= max.x && (long) chunk.xPosition * 16 + 15 >= min.x
                && (long) chunk.zPosition * 16 <= max.z
                && (long) chunk.zPosition * 16 + 15 >= min.z) {
                provider.reviseRange(chunk.xPosition, chunk.zPosition, min.y, max.y);
            }
        }
        state.invalidateRegion(min, max);
    }

    public void tick() {
        provider.checkThread();
        long now = world.getTotalWorldTime();
        if (lastTick == now) {
            return;
        }
        long tickStart = System.nanoTime();
        lastTick = now;
        store.pollSaveCompletion();
        store.markDirty();
        if (!seeded) {
            for (Object chunk : ((ChunkProviderServer) world.getChunkProvider()).loadedChunks) {
                provider.load((Chunk) chunk);
            }
            state.resumeReadyCubes();
            seeded = true;
        }
        state.restartMetrics.set("startup_registered_columns", provider.loadedColumnCount());
        if (!changes.isEmpty()) {
            perfInvalidatedBlocks += changes.size();
            long invalidateStart = System.nanoTime();
            state.invalidateBlocks(changes);
            perfInvalidationNanos += System.nanoTime() - invalidateStart;
            changes.clear();
        }
        long deliveryStart = System.nanoTime();
        for (Iterator<PendingPulse> iterator = pending.iterator(); iterator.hasNext();) {
            perfDeliveryAttempts++;
            if (deliver(iterator.next())) {
                perfDelivered++;
                iterator.remove();
            } else {
                perfDeferred++;
            }
        }
        long deliveryDuration = System.nanoTime() - deliveryStart;
        perfDeliveryNanos += deliveryDuration;
        perfMaxDeliveryNanos = Math.max(perfMaxDeliveryNanos, deliveryDuration);
        long preGeometrySmog = System.nanoTime() - tickStart;
        Worlds.smogUsed(this, preGeometrySmog, 0);
        long geometryAllowance = Worlds.geometryAllowance(this);
        state.restartMetrics.set("server_geometry_budget_ms", geometryAllowance / 1e6);
        state.restartMetrics.set("server_previous_mspt", Worlds.previousMspt());
        state.tick(2000000L, 20, false, geometryAllowance);
        state.restartMetrics.set("world_snapshot_build_ms", provider.diagnostics().buildNanos / 1e6);
        state.restartMetrics.set("memory_snapshot_bytes", provider.cachedSnapshotCount() * 8256L);
        store.pollSaveCompletion();
        if (now % 6000 == 0) {
            save();
        }
        long tickDuration = System.nanoTime() - tickStart;
        Worlds.smogUsed(this, tickDuration - preGeometrySmog, state.lastGeometryNanos);
        perfTicks++;
        perfTickNanos += tickDuration;
        perfMaxTickNanos = Math.max(perfMaxTickNanos, tickDuration);
        if (tickDuration >= SLOW_SEGMENT_NANOS) {
            perfSlowRuntimeTicks++;
        }
        if (state.lastGeometryNanos >= SLOW_SEGMENT_NANOS) {
            perfSlowGeometryTicks++;
        }
        if (perfLogging && now >= nextPerfLogTick) {
            logPerformance(now);
        }
    }

    private static String ms(long nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1e6);
    }

    private static String avgMs(long nanos, long count) {
        return ms(count == 0 ? 0 : nanos / count);
    }

    /** On-demand diagnostics; the cube scan is intentionally NOT done in tick(). */
    public String[] performanceLines() {
        provider.checkThread();
        GeometryMetrics m = state.geometryMetrics;
        state.updateRestartMemoryMetrics();
        Worlds.updateMsptMetrics(state.restartMetrics);
        ForgeWorldSnapshotProvider.Diagnostics p = provider.diagnostics();
        DimensionState.GeometryFootprint f = state.geometryFootprint();
        int[] queues = state.geometryQueues();
        long[] queueHeads = state.geometryQueueHeadAgeTicks();
        String[] names = { "snap", "halo", "part", "inner", "outer" };
        StringBuilder totals = new StringBuilder("buildPhasesMs(snap/halo/part/inner/outer)=");
        StringBuilder max = new StringBuilder("buildPhaseMaxMs(snap/halo/part/inner/outer)=");
        StringBuilder calls = new StringBuilder("buildPhaseCalls(snap/halo/part/inner/outer)=");
        for (int i = 0; i < names.length; i++) {
            if (i != 0) {
                totals.append('/');
                max.append('/');
                calls.append('/');
            }
            totals.append(ms(m.phaseNanos[i]));
            max.append(ms(m.phaseMaxNanos[i]));
            calls.append(m.phaseCalls[i]);
        }
        return new String[] {
            "SMOG PERF dim=" + state.dimension
                + " ticks="
                + perfTicks
                + " elapsedSec="
                + String.format(Locale.ROOT, "%.1f", (System.nanoTime() - perfStartedAt) / 1e9)
                + " log="
                + (perfLogging ? "on" : "off")
                + " periodTicks="
                + PERF_LOG_TICKS,
            "tickAvg/MaxMs=" + avgMs(perfTickNanos, perfTicks)
                + "/"
                + ms(perfMaxTickNanos)
                + " geometryAvg/MaxMs="
                + avgMs(m.geometryNanos, m.geometryCalls)
                + "/"
                + ms(m.geometryMaxNanos)
                + " geomCalls/busy="
                + m.geometryCalls
                + "/"
                + m.geometryBusyCalls
                + " slow10ms(runtime/geometry)="
                + perfSlowRuntimeTicks
                + "/"
                + perfSlowGeometryTicks,
            totals.toString(), max.toString() + " " + calls.toString(),
            "build jobs/new/rebuild/stale=" + m.jobsStarted
                + "/"
                + m.newJobs
                + "/"
                + m.rebuildJobs
                + "/"
                + m.staleJobs
                + " overruns="
                + m.budgetOverruns
                + " prepared/partitions/nodes/maxNodes="
                + m.cellsPrepared
                + "/"
                + m.partitionsCompleted
                + "/"
                + m.partitionNodes
                + "/"
                + m.maxPartitionNodes,
            "front count/total/max=" + m.frontScans
                + "/"
                + ms(m.frontNanos)
                + "/"
                + ms(m.frontMaxNanos)
                + " worldChecks/bad="
                + m.worldValidations
                + "/"
                + m.worldInvalid
                + " worldCheckMs="
                + ms(m.worldValidationNanos),
            "contacts checks/refreshes=" + m.contactValidations
                + "/"
                + m.contactRefreshes
                + " checkMs/maxMs="
                + ms(m.contactValidationNanos)
                + "/"
                + ms(m.contactValidationMaxNanos)
                + " refreshRequestMs/max="
                + ms(m.refreshRequestNanos)
                + "/"
                + ms(m.refreshRequestMaxNanos)
                + " refreshBuildMs="
                + ms(m.refreshAdvanceNanos),
            coldMassDiagnostics(m), state.restartMetrics.logLine(),
            "publish count/total/maxMs=" + m.publishCalls
                + "/"
                + ms(m.publishNanos)
                + "/"
                + ms(m.publishMaxNanos)
                + " worldCheckMs/maxMs="
                + ms(m.worldValidationNanos)
                + "/"
                + ms(m.worldValidationMaxNanos),
            "safeWindows open/closed/withPublish=" + m.safeWindowsOpened
                + "/"
                + m.safeWindowsClosed
                + "/"
                + m.safeWindowsWithPublish
                + " publications/maxPerWindow/last/current="
                + m.safeWindowPublishTotal
                + "/"
                + m.safeWindowMaxPublished
                + "/"
                + m.safeWindowLastPublished
                + "/"
                + m.safeWindowCurrentPublished
                + " eligibleGeometryCalls="
                + m.eligibleGeometryCalls,
            "publishedAge queued(avg/maxTicks)="
                + (m.queueWaitSamples == 0 ? "n/a" : String.valueOf(m.queueWaitTotalTicks / m.queueWaitSamples))
                + "/"
                + m.queueWaitMaxTicks
                + " prepared(avg/maxTicks)="
                + (m.preparedWaitSamples == 0 ? "n/a"
                    : String.valueOf(m.preparedWaitTotalTicks / m.preparedWaitSamples))
                + "/"
                + m.preparedWaitMaxTicks
                + " buckets(<=20/<=100/<=600/>600)="
                + m.queueWaitBuckets[0]
                + "/"
                + m.queueWaitBuckets[1]
                + "/"
                + m.queueWaitBuckets[2]
                + "/"
                + m.queueWaitBuckets[3],
            "snapCache hits/miss/unready=" + p.hits
                + "/"
                + p.misses
                + "/"
                + p.unavailable
                + " cached="
                + provider.cachedSnapshotCount()
                + " loadedColumns="
                + provider.loadedColumnCount()
                + " buildTotal/MaxMs="
                + ms(p.buildNanos)
                + "/"
                + ms(p.maxBuildNanos)
                + " errors="
                + p.buildFailures,
            "cacheInvalidate calls/evicted=" + p.invalidations
                + "/"
                + p.evictedSnapshots
                + " total/maxMs="
                + ms(p.invalidateNanos)
                + "/"
                + ms(p.maxInvalidateNanos)
                + " revisions="
                + p.revisions
                + " loads/unloads="
                + p.loads
                + "/"
                + p.unloads,
            "queues builds/job/prepared/fronts=" + queues[0]
                + "/"
                + queues[1]
                + "/"
                + queues[2]
                + "/"
                + queues[3]
                + " preparedLimit="
                + state.preparedLimit()
                + " queueHeadAgeTicks(build/prepared)="
                + queueHeads[0]
                + "/"
                + queueHeads[1]
                + " modelLagSeconds="
                + String.format(Locale.ROOT, "%.3f", state.gameTime() - state.modelTime())
                + " frames="
                + state.pendingIntervals(),
            "cubeRecords total/ready/active/parkedOnly=" + f.registeredCubes
                + "/"
                + f.readyCubes
                + "/"
                + f.activeCubes
                + "/"
                + f.parkedOnlyCubes
                + " cells allocated/active/parked="
                + f.allocatedCells
                + "/"
                + f.activeCells
                + "/"
                + f.parkedCells,
            "pulses attempts/delivered/deferred=" + perfDeliveryAttempts
                + "/"
                + perfDelivered
                + "/"
                + perfDeferred
                + " queue="
                + pending.size()
                + " deliveryTotal/MaxMs="
                + ms(perfDeliveryNanos)
                + "/"
                + ms(perfMaxDeliveryNanos)
                + " invalidatedBlocks="
                + perfInvalidatedBlocks
                + " invalidationMs="
                + ms(perfInvalidationNanos),
            "geometryExit budget/idle/idleBacklog/solver/capture/preparedFull=" + m.budgetEndCalls
                + "/"
                + m.idleEndCalls
                + "/"
                + m.idleWithBacklogCalls
                + "/"
                + m.waitSolverCalls
                + "/"
                + m.waitCaptureCalls
                + "/"
                + m.preparedFullCalls
                + " lawParts tried/committed/skippedCapture="
                + m.partCalls
                + "/"
                + m.partCommits
                + "/"
                + m.partsSkippedForCapture
                + " unloadCancellations="
                + state.unloadCancellations,
            "capture calls/total/MaxMs=" + m.captureCalls
                + "/"
                + ms(m.captureNanos)
                + "/"
                + ms(m.captureMaxNanos)
                + " existingMaxSnapshotUnitMs="
                + ms(state.maxSnapshotUnitNanos)
                + " staleJobs="
                + state.staleJobs
                + " overruns="
                + state.budgetOverruns
                + " (last three lifetime)",
            performanceLogStatus() };
    }

    // Cumulative since /smog perf reset; keep the existing safe_v1 CSV schema intact.
    private String coldMassDiagnostics(GeometryMetrics m) {
        return "coldMassTotals exactEnabled=" + state.coldExactMassEnabled()
            + " coldExactCount="
            + m.coldExactCount
            + " coldFallbackCount="
            + m.coldFallbackCount
            + " coldExactMassMs="
            + String.format(Locale.ROOT, "%.6f", m.coldExactMassMs)
            + " coldFallbackMassMs="
            + String.format(Locale.ROOT, "%.6f", m.coldFallbackMassMs)
            + " epsilonDroppedMass="
            + Double.toString(m.epsilonDroppedMass)
            + " coldRestoredMass="
            + Double.toString(m.coldRestoredMass)
            + " coldPublishCount="
            + m.coldPublishCount
            + " replacementPublishCount="
            + m.replacementPublishCount;
    }

    /** Reset only transient statistics, not work queues, snapshots, history or mass. */
    public void resetPerformance() {
        provider.checkThread();
        state.geometryMetrics.reset();
        provider.resetDiagnostics();
        perfTicks = perfTickNanos = perfMaxTickNanos = 0;
        perfSlowRuntimeTicks = perfSlowGeometryTicks = 0;
        perfDeliveryNanos = perfMaxDeliveryNanos = perfDeliveryAttempts = 0;
        perfDelivered = perfDeferred = perfInvalidatedBlocks = perfInvalidationNanos = 0;
        perfStartedAt = System.nanoTime();
        lastLogTick = lastTick == Long.MIN_VALUE ? world.getTotalWorldTime() : lastTick;
        lastLogRuntimeNanos = lastLogGeometryNanos = 0;
        lastLogSnapshotNanos = lastLogSnapshotMisses = lastLogPublished = 0;
        lastLogWindowsOpened = lastLogWindowsClosed = lastLogWindowsWithPublish = 0;
        lastLogWindowPublications = lastLogEligibleCalls = lastLogPreparedAccepted = 0;
        lastLogWorldChecks = lastLogWorldCheckNanos = 0;
        lastLogContactsChecks = lastLogContactsCheckNanos = 0;
        lastLogRefreshRequestNanos = lastLogRefreshAdvanceNanos = 0;
        lastLogQueueWaitSamples = lastLogQueueWaitTicks = 0;
        lastLogPreparedWaitSamples = lastLogPreparedWaitTicks = 0;
        Arrays.fill(lastSafeTotals, 0);
        Arrays.fill(lastLogPhaseNanos, 0);
        lastLogFrontNanos = lastLogInvalidateNanos = lastLogEvicted = 0;
        lastLogStaleJobs = lastLogContactRefreshes = 0;
        lastLogBudgetEnd = lastLogIdleWithBacklog = lastLogWaitSolver = 0;
        lastLogWaitCapture = lastLogPreparedFull = 0;
        lastLogPartCalls = lastLogPartCommits = lastLogCaptureSkips = 0;
        lastLogUnloadCancellations = state.unloadCancellations;
        lastLogFrontScans = lastLogSlowRuntimeTicks = lastLogSlowGeometryTicks = 0;
        lastLogBuildQueue = state.geometryQueues()[0];
        nextPerfLogTick = lastLogTick + PERF_LOG_TICKS;
    }

    public void setPerformanceLogging(boolean enabled) {
        provider.checkThread();
        perfLogging = enabled;
        if (enabled) {
            lastLogTick = lastTick == Long.MIN_VALUE ? world.getTotalWorldTime() : lastTick;
            lastLogRuntimeNanos = perfTickNanos;
            lastLogGeometryNanos = state.geometryMetrics.geometryNanos;
            lastLogSnapshotNanos = provider.diagnostics().buildNanos;
            lastLogSnapshotMisses = provider.diagnostics().misses;
            lastLogPublished = state.geometryMetrics.publishCalls;
            captureLogGeometryBaseline(state.geometryMetrics);
            System.arraycopy(
                state.geometryMetrics.safeSnapshot(),
                0,
                lastSafeTotals,
                0,
                GeometryMetrics.SAFE_COUNTER_COUNT);
            System.arraycopy(state.geometryMetrics.phaseNanos, 0, lastLogPhaseNanos, 0, GeometryMetrics.PHASE_COUNT);
            lastLogFrontNanos = state.geometryMetrics.frontNanos;
            lastLogInvalidateNanos = provider.diagnostics().invalidateNanos;
            lastLogEvicted = provider.diagnostics().evictedSnapshots;
            lastLogStaleJobs = state.geometryMetrics.staleJobs;
            lastLogContactRefreshes = state.geometryMetrics.contactRefreshes;
            lastLogBudgetEnd = state.geometryMetrics.budgetEndCalls;
            lastLogIdleWithBacklog = state.geometryMetrics.idleWithBacklogCalls;
            lastLogWaitSolver = state.geometryMetrics.waitSolverCalls;
            lastLogWaitCapture = state.geometryMetrics.waitCaptureCalls;
            lastLogPreparedFull = state.geometryMetrics.preparedFullCalls;
            lastLogPartCalls = state.geometryMetrics.partCalls;
            lastLogPartCommits = state.geometryMetrics.partCommits;
            lastLogCaptureSkips = state.geometryMetrics.partsSkippedForCapture;
            lastLogUnloadCancellations = state.unloadCancellations;
            lastLogFrontScans = state.geometryMetrics.frontScans;
            lastLogSlowRuntimeTicks = perfSlowRuntimeTicks;
            lastLogSlowGeometryTicks = perfSlowGeometryTicks;
            lastLogBuildQueue = state.geometryQueues()[0];
            nextPerfLogTick = lastLogTick + PERF_LOG_TICKS;
            writePerformanceLog(
                "SMOG-PERF EVENT enabled dim=" + state.dimension + " periodTicks=" + PERF_LOG_TICKS,
                false);
        } else {
            writePerformanceLog("SMOG-PERF EVENT disabled dim=" + state.dimension, false);
        }
    }

    /** Independent-file diagnostics, also submitted through the usual Forge FML logger. */
    private void writePerformanceLog(String message, boolean test) {
        perfLogAttempts++;
        lastPerfLogAttemptTick = world.getTotalWorldTime();
        if (test) {
            FMLLog.warning("%s", message);
        } else {
            FMLLog.info("%s", message);
        }
        performanceFileLog.offer(message);
    }

    /** Emit a recognizable one-off record without waiting for the periodic interval. */
    public void testPerformanceLogging() {
        provider.checkThread();
        writePerformanceLog(
            "SMOG-PERF TEST dim=" + state.dimension
                + " worldTick="
                + world.getTotalWorldTime()
                + " expectedFile="
                + performanceFileLog.path(),
            true);
    }

    public String performanceLogStatus() {
        provider.checkThread();
        return "perfLog attempts/queued/written/rejected/ioErrors=" + perfLogAttempts
            + "/"
            + performanceFileLog.queued()
            + "/"
            + performanceFileLog.written()
            + "/"
            + performanceFileLog.rejected()
            + "/"
            + performanceFileLog.errors()
            + " lastAttemptTick="
            + lastPerfLogAttemptTick
            + " file="
            + performanceFileLog.path()
            + " csvFile="
            + performanceFileLog.csvPath()
            + (performanceFileLog.lastError()
                .isEmpty() ? "" : " lastError=" + performanceFileLog.lastError());
    }

    private void captureLogGeometryBaseline(GeometryMetrics m) {
        lastLogWindowsOpened = m.safeWindowsOpened;
        lastLogWindowsClosed = m.safeWindowsClosed;
        lastLogWindowsWithPublish = m.safeWindowsWithPublish;
        lastLogWindowPublications = m.safeWindowPublishTotal;
        lastLogEligibleCalls = m.eligibleGeometryCalls;
        lastLogPreparedAccepted = m.preparedAccepted;
        lastLogWorldChecks = m.worldValidations;
        lastLogWorldCheckNanos = m.worldValidationNanos;
        lastLogContactsChecks = m.contactValidations;
        lastLogContactsCheckNanos = m.contactValidationNanos;
        lastLogRefreshRequestNanos = m.refreshRequestNanos;
        lastLogRefreshAdvanceNanos = m.refreshAdvanceNanos;
        lastLogQueueWaitSamples = m.queueWaitSamples;
        lastLogQueueWaitTicks = m.queueWaitTotalTicks;
        lastLogPreparedWaitSamples = m.preparedWaitSamples;
        lastLogPreparedWaitTicks = m.preparedWaitTotalTicks;
    }

    private void logPerformance(long now) {
        GeometryMetrics m = state.geometryMetrics;
        ForgeWorldSnapshotProvider.Diagnostics p = provider.diagnostics();
        long period = Math.max(1, now - lastLogTick);
        int[] queues = state.geometryQueues();
        long[] heads = state.geometryQueueHeadAgeTicks();
        long newQueueWaits = m.queueWaitSamples - lastLogQueueWaitSamples;
        long newPreparedWaits = m.preparedWaitSamples - lastLogPreparedWaitSamples;
        long[] safeNow = m.safeSnapshot();
        long[] safe = new long[GeometryMetrics.SAFE_COUNTER_COUNT];
        for (int i = 0; i < safe.length; i++) {
            safe[i] = safeNow[i] - lastSafeTotals[i];
        }
        long freshBuildNanos = Math
            .max(0, safe[GeometryMetrics.SAFE_BUILD_NANOS] - safe[GeometryMetrics.SAFE_REFRESH_REBUILD_NANOS]);
        long otherNanos = Math.max(
            0,
            safe[GeometryMetrics.SAFE_ELAPSED_NANOS] - safe[GeometryMetrics.SAFE_WORLD_CHECK_NANOS]
                - safe[GeometryMetrics.SAFE_CONTACT_CHECK_NANOS]
                - safe[GeometryMetrics.SAFE_REFRESH_REQUEST_NANOS]
                - safe[GeometryMetrics.SAFE_BUILD_NANOS]
                - safe[GeometryMetrics.SAFE_PUBLISH_NANOS]
                - safe[GeometryMetrics.SAFE_APPLY_EVENTS_NANOS]
                - safe[GeometryMetrics.SAFE_FRONT_NANOS]);
        long safeCalls = safe[GeometryMetrics.SAFE_CALLS];
        long safePublished = safe[GeometryMetrics.SAFE_PUBLISHED];
        StringBuilder phases = new StringBuilder();
        for (int phase = 0; phase < GeometryMetrics.PHASE_COUNT; phase++) {
            if (phase != 0) {
                phases.append('/');
            }
            phases.append(ms(m.phaseNanos[phase] - lastLogPhaseNanos[phase]));
        }
        String message = "SMOG-PERF SAMPLE dim=" + state.dimension
            + " periodTicks="
            + period
            + " runtimeAvgMs="
            + ms((perfTickNanos - lastLogRuntimeNanos) / period)
            + " geometryAvgMs="
            + ms((m.geometryNanos - lastLogGeometryNanos) / period)
            + " phaseMs(snap/halo/part/inner/outer)="
            + phases
            + " snapshotBuildMs="
            + ms(p.buildNanos - lastLogSnapshotNanos)
            + " snapshotMisses="
            + (p.misses - lastLogSnapshotMisses)
            + " cacheEvicted="
            + (p.evictedSnapshots - lastLogEvicted)
            + " cacheInvalidMs="
            + ms(p.invalidateNanos - lastLogInvalidateNanos)
            + " frontMs="
            + ms(m.frontNanos - lastLogFrontNanos)
            + " frontScans="
            + (m.frontScans - lastLogFrontScans)
            + " staleJobs="
            + (m.staleJobs - lastLogStaleJobs)
            + " contactsRefreshes="
            + (m.contactRefreshes - lastLogContactRefreshes)
            + " published="
            + (m.publishCalls - lastLogPublished)
            + " preparedAccepted="
            + (m.preparedAccepted - lastLogPreparedAccepted)
            + " preparedLimit="
            + state.preparedLimit()
            + " safeWindows(opened/closed/withPublish)="
            + (m.safeWindowsOpened - lastLogWindowsOpened)
            + "/"
            + (m.safeWindowsClosed - lastLogWindowsClosed)
            + "/"
            + (m.safeWindowsWithPublish - lastLogWindowsWithPublish)
            + " windowPublications="
            + (m.safeWindowPublishTotal - lastLogWindowPublications)
            + " windowMaxPublished="
            + m.safeWindowMaxPublished
            + " windowCurrentPublished="
            + m.safeWindowCurrentPublished
            + " eligibleCalls="
            + (m.eligibleGeometryCalls - lastLogEligibleCalls)
            + " validation(worldCount/contactCount)="
            + (m.worldValidations - lastLogWorldChecks)
            + "/"
            + (m.contactValidations - lastLogContactsChecks)
            + " validationMs(world/contact)="
            + ms(m.worldValidationNanos - lastLogWorldCheckNanos)
            + "/"
            + ms(m.contactValidationNanos - lastLogContactsCheckNanos)
            + " contactsRefreshMs(request/rebuild)="
            + ms(m.refreshRequestNanos - lastLogRefreshRequestNanos)
            + "/"
            + ms(m.refreshAdvanceNanos - lastLogRefreshAdvanceNanos)
            + " agePublishedTicks(avgQueued/maxQueued/avgPrepared/maxPrepared)="
            + (newQueueWaits == 0 ? -1 : (m.queueWaitTotalTicks - lastLogQueueWaitTicks) / newQueueWaits)
            + "/"
            + m.queueWaitMaxTicks
            + "/"
            + (newPreparedWaits == 0 ? -1 : (m.preparedWaitTotalTicks - lastLogPreparedWaitTicks) / newPreparedWaits)
            + "/"
            + m.preparedWaitMaxTicks
            + " queueHeadAgeTicks(build/prepared)="
            + heads[0]
            + "/"
            + heads[1]
            + " queues(build/job/prepared/front)="
            + queues[0]
            + "/"
            + queues[1]
            + "/"
            + queues[2]
            + "/"
            + queues[3]
            + " buildQueueDelta="
            + (queues[0] - lastLogBuildQueue)
            + " geomExit(budget/idleBacklog/solver/capture/preparedFull)="
            + (m.budgetEndCalls - lastLogBudgetEnd)
            + "/"
            + (m.idleWithBacklogCalls - lastLogIdleWithBacklog)
            + "/"
            + (m.waitSolverCalls - lastLogWaitSolver)
            + "/"
            + (m.waitCaptureCalls - lastLogWaitCapture)
            + "/"
            + (m.preparedFullCalls - lastLogPreparedFull)
            + " lawParts(tried/committed/skippedCapture)="
            + (m.partCalls - lastLogPartCalls)
            + "/"
            + (m.partCommits - lastLogPartCommits)
            + "/"
            + (m.partsSkippedForCapture - lastLogCaptureSkips)
            + " unloadCancellations="
            + (state.unloadCancellations - lastLogUnloadCancellations)
            + " modelLagSec="
            + String.format(Locale.ROOT, "%.3f", state.gameTime() - state.modelTime())
            + " pendingPulses="
            + pending.size()
            + " frames="
            + state.pendingIntervals()
            + " slow10ms(runtime/geometry)="
            + (perfSlowRuntimeTicks - lastLogSlowRuntimeTicks)
            + "/"
            + (perfSlowGeometryTicks - lastLogSlowGeometryTicks)
            + " captureMaxMs="
            + ms(m.captureMaxNanos)
            + " safeProfile(calls/usedMs/budgetMs/publishMs/eventsMs/freshBuildMs/otherMs)="
            + safeCalls
            + "/"
            + ms(safe[GeometryMetrics.SAFE_ELAPSED_NANOS])
            + "/"
            + ms(safe[GeometryMetrics.SAFE_BUDGET_NANOS])
            + "/"
            + ms(safe[GeometryMetrics.SAFE_PUBLISH_NANOS])
            + "/"
            + ms(safe[GeometryMetrics.SAFE_APPLY_EVENTS_NANOS])
            + "/"
            + ms(freshBuildNanos)
            + "/"
            + ms(otherNanos)
            + " "
            + coldMassDiagnostics(m);
        state.updateRestartMemoryMetrics();
        Worlds.updateMsptMetrics(state.restartMetrics);
        writePerformanceLog(message + " " + state.restartMetrics.logLine(), false);
        StringBuilder csv = new StringBuilder(400);
        csv.append(
            java.time.Instant.now()
                .toString())
            .append(",2,")
            .append(state.dimension)
            .append(',')
            .append(now)
            .append(',')
            .append(period)
            .append(',')
            .append(state.preparedLimit())
            .append(',')
            .append(ms((perfTickNanos - lastLogRuntimeNanos) / period))
            .append(',')
            .append(ms((m.geometryNanos - lastLogGeometryNanos) / period))
            .append(',')
            .append(ms(m.phaseNanos[GeometryMetrics.SNAPSHOTS] - lastLogPhaseNanos[GeometryMetrics.SNAPSHOTS]))
            .append(',')
            .append(ms(m.phaseNanos[GeometryMetrics.HALO] - lastLogPhaseNanos[GeometryMetrics.HALO]))
            .append(',')
            .append(ms(m.phaseNanos[GeometryMetrics.PARTITION] - lastLogPhaseNanos[GeometryMetrics.PARTITION]))
            .append(',')
            .append(
                ms(
                    m.phaseNanos[GeometryMetrics.INTERNAL_CONTACTS]
                        - lastLogPhaseNanos[GeometryMetrics.INTERNAL_CONTACTS]))
            .append(',')
            .append(
                ms(
                    m.phaseNanos[GeometryMetrics.EXTERNAL_CONTACTS]
                        - lastLogPhaseNanos[GeometryMetrics.EXTERNAL_CONTACTS]))
            .append(',')
            .append(ms(m.frontNanos - lastLogFrontNanos))
            .append(',')
            .append(m.contactRefreshes - lastLogContactRefreshes)
            .append(',')
            .append(m.preparedAccepted - lastLogPreparedAccepted)
            .append(',')
            .append(m.partsSkippedForCapture - lastLogCaptureSkips)
            .append(',')
            .append(safeCalls)
            .append(',')
            .append(ms(safe[GeometryMetrics.SAFE_ELAPSED_NANOS]))
            .append(',')
            .append(ms(safe[GeometryMetrics.SAFE_BUDGET_NANOS]))
            .append(',')
            .append(ms(m.safeCallMaxNanos))
            .append(',')
            .append(safe[GeometryMetrics.SAFE_BUDGET_ENDS])
            .append(',')
            .append(safe[GeometryMetrics.SAFE_IDLE_ENDS])
            .append(',')
            .append(safeCalls == 0 ? -1 : safe[GeometryMetrics.SAFE_PREPARED_START_SUM] / safeCalls)
            .append(',')
            .append(safeCalls == 0 ? -1 : safe[GeometryMetrics.SAFE_PREPARED_END_SUM] / safeCalls)
            .append(',')
            .append(ms(safe[GeometryMetrics.SAFE_WORLD_CHECK_NANOS]))
            .append(',')
            .append(ms(safe[GeometryMetrics.SAFE_CONTACT_CHECK_NANOS]))
            .append(',')
            .append(ms(safe[GeometryMetrics.SAFE_REFRESH_REQUEST_NANOS]))
            .append(',')
            .append(ms(safe[GeometryMetrics.SAFE_REFRESH_REBUILD_NANOS]))
            .append(',')
            .append(ms(freshBuildNanos))
            .append(',')
            .append(ms(safe[GeometryMetrics.SAFE_PUBLISH_NANOS]))
            .append(',')
            .append(
                String.format(
                    Locale.ROOT,
                    "%.3f",
                    safePublished == 0 ? 0.0 : safe[GeometryMetrics.SAFE_PUBLISH_NANOS] / (1000.0 * safePublished)))
            .append(',')
            .append(ms(safe[GeometryMetrics.SAFE_APPLY_EVENTS_NANOS]))
            .append(',')
            .append(ms(safe[GeometryMetrics.SAFE_FRONT_NANOS]))
            .append(',')
            .append(ms(otherNanos))
            .append(',')
            .append(safePublished)
            .append(',')
            .append(m.safeWindowsOpened - lastLogWindowsOpened)
            .append(',')
            .append(m.safeWindowsWithPublish - lastLogWindowsWithPublish)
            .append(',')
            .append(m.safeWindowMaxPublished)
            .append(',')
            .append(queues[0])
            .append(',')
            .append(queues[2])
            .append(',')
            .append(queues[3])
            .append(',')
            .append(m.staleJobs - lastLogStaleJobs)
            .append(',')
            .append(String.format(Locale.ROOT, "%.3f", state.gameTime() - state.modelTime()));
        performanceFileLog.offerCsv(
            PERF_CSV_HEADER + "," + state.restartMetrics.csvHeader(),
            csv.append(',')
                .append(state.restartMetrics.csvValues())
                .toString());
        lastLogTick = now;
        lastLogRuntimeNanos = perfTickNanos;
        lastLogGeometryNanos = m.geometryNanos;
        lastLogSnapshotNanos = p.buildNanos;
        lastLogSnapshotMisses = p.misses;
        lastLogPublished = m.publishCalls;
        captureLogGeometryBaseline(m);
        System.arraycopy(safeNow, 0, lastSafeTotals, 0, GeometryMetrics.SAFE_COUNTER_COUNT);
        System.arraycopy(m.phaseNanos, 0, lastLogPhaseNanos, 0, GeometryMetrics.PHASE_COUNT);
        lastLogFrontNanos = m.frontNanos;
        lastLogInvalidateNanos = p.invalidateNanos;
        lastLogEvicted = p.evictedSnapshots;
        lastLogStaleJobs = m.staleJobs;
        lastLogContactRefreshes = m.contactRefreshes;
        lastLogBudgetEnd = m.budgetEndCalls;
        lastLogIdleWithBacklog = m.idleWithBacklogCalls;
        lastLogWaitSolver = m.waitSolverCalls;
        lastLogWaitCapture = m.waitCaptureCalls;
        lastLogPreparedFull = m.preparedFullCalls;
        lastLogPartCalls = m.partCalls;
        lastLogPartCommits = m.partCommits;
        lastLogCaptureSkips = m.partsSkippedForCapture;
        lastLogUnloadCancellations = state.unloadCancellations;
        lastLogFrontScans = m.frontScans;
        lastLogSlowRuntimeTicks = perfSlowRuntimeTicks;
        lastLogSlowGeometryTicks = perfSlowGeometryTicks;
        lastLogBuildQueue = queues[0];
        nextPerfLogTick = now + PERF_LOG_TICKS;
    }

    private boolean deliver(PendingPulse pulse) {
        BlockPos position = pulse.position;
        if (pulse.amount < 0) {
            if (pulse.chunkOnly || pulse.adjacent) {
                SpatialQueries.removeChunk(state, position.x >> 4, position.z >> 4, -(double) pulse.amount);
            } else {
                state.queueRemoveMass(position, -(double) pulse.amount);
            }
            return true;
        }
        if (pulse.chunkOnly) {
            return deliverColumn(pulse);
        }
        if (state.queueAddMass(position, pulse.amount)) {
            return true;
        }
        if (pulse.adjacent) {
            for (int[] side : SIDES) {
                if (state.queueAddMass(position.offset(side[0], side[1], side[2]), pulse.amount)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static final int[][] SIDES = { { 0, 1, 0 }, { 1, 0, 0 }, { -1, 0, 0 }, { 0, 0, 1 }, { 0, 0, -1 },
        { 0, -1, 0 } };

    /**
     * Unknown Y: choose the first gas voxel in every loaded section, weighted by section gas
     * volume.
     */
    private boolean deliverColumn(PendingPulse pulse) {
        List<BlockPos> points = new ArrayList<>();
        List<Integer> volumes = new ArrayList<>();
        int total = 0;
        for (int section = 0; section < 16; section++) {
            ImmutableCubeSnapshot snapshot = provider
                .snapshotIfReady(new CubeKey(state.dimension, pulse.position.x >> 4, section, pulse.position.z >> 4));
            if (snapshot == null) {
                continue;
            }
            BlockPos first = null;
            int volume = 0;
            for (int y = section * 16; y < section * 16 + 16; y++) {
                for (int z = pulse.position.z; z < pulse.position.z + 16; z++) {
                    for (int x = pulse.position.x; x < pulse.position.x + 16; x++) {
                        if (snapshot.airAt(x, y, z)) {
                            if (first == null) {
                                first = new BlockPos(state.dimension, x, y, z);
                            }
                            volume++;
                        }
                    }
                }
            }
            if (first != null) {
                points.add(first);
                volumes.add(volume);
                total += volume;
            }
        }
        if (total == 0) {
            return false;
        }
        double remaining = pulse.amount;
        for (int i = 0; i < points.size(); i++) {
            double part = i == points.size() - 1 ? remaining : pulse.amount * (double) volumes.get(i) / total;
            if (!state.queueAddMass(points.get(i), part)) {
                throw new IllegalStateException("SMOG snapshot changed during delivery");
            }
            remaining -= part;
        }
        return true;
    }

    public void save() {
        provider.checkThread();
        store.save(state, pending);
    }

    public void markDirty() {
        provider.checkThread();
        store.markDirty();
    }

    public String saveStatus() {
        return store.status();
    }

    @Override
    public void close() {
        provider.checkThread();
        if (closed) {
            return;
        }
        try {
            store.saveAndAwaitForShutdown(state, pending, 30000);
            state.close();
            closed = true;
        } catch (Exception exception) {
            throw new IllegalStateException("SMOG final save failed; dimension not closed", exception);
        }
    }
}

package smog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Private region preparation, bounded micro-publications, one simulation owning thread.
 * Local edge endpoints are group offsets; external endpoints are encoded as -1-runtimeId.
 * Each cross-cell edge belongs to the later entry, so future micro-batches defer it naturally. */
final class EpochRestorePlan extends RestorePlan {
    private static final class Load {
        final CubeKey key;
        final CompletableFuture<GeometryCache.Lookup> future;
        GeometryCache.Lookup lookup;
        Load(DimensionState s, CubeKey key) { this.key = key; future = s.geometryCache.request(key); }
    }

    private static final class Entry extends EpochAppendBatch.Item {
        final long ticket;
        final Load load;
        final Entry[] localNeighbors = new Entry[6];
        final EdgeAccumulator local = new EdgeAccumulator(), external = new EdgeAccumulator();
        final int[][] faceRecords = new int[6][];
        ImmutableCubeSnapshot[] snapshots;
        int order;
        Entry(DimensionState state, DimensionState.Cell cell, Load load) {
            super(state, cell); this.load = load; ticket = cell.ticket;
        }
        void mergeExternal() {
            external.clear();
            for (int[] records : faceRecords) if (records != null)
                for (int i = 0; i < records.length; i += 4)
                    external.addAggregate(records[i], records[i + 1], records[i + 2], records[i + 3]);
            externalRecords = external.records();
            external.clear();
        }
    }

    private final DimensionState state;
    private final ArrayList<Load> loads = new ArrayList<>();
    private final ArrayList<Entry> entries = new ArrayList<>();
    private final Map<CellKey, Entry> index = new HashMap<>();
    private final boolean optimistic;
    private int stage, cursor, dependency, face, voxel, nodes, published, refreshing, preparedEdges;
    private int[] runtimeIds;
    private int batchStop = -1, batchNodes, batchEdges, refreshMask;
    private boolean capacityReady;
    private long waitingSince, prepareNanos, contactsNanos;

    EpochRestorePlan(DimensionState state, DimensionState.Cell first) {
        this.state = state;
        boolean cold = true;
        for (DimensionState.Cell c : first.cube.cells) if (c != null && c.reloadCacheEligible) cold = false;
        optimistic = cold && state.restartOptions.optimistic;
        addCube(first.cube.key);
        if (cold && state.restartOptions.epochRegions) {
            for (CubeKey key : state.startup.claimRegion(first.cube.key, state.restartOptions.regionCells / 8 - 1))
                addCube(key);
        }
        state.restartMetrics.histogram("planCells", entries.size());
        state.restartMetrics.histogram("planCubes", loads.size());
        if (!cold) state.restartMetrics.add("plan_stop_reactivation", 1);
        else if (!state.restartOptions.epochRegions) state.restartMetrics.add("plan_stop_grouping_disabled", 1);
    }

    private void addCube(CubeKey key) {
        DimensionState.CubeRecord cube = state.cubes.get(key);
        if (cube == null || !state.provider.isReady(key)) return;
        Load load = null;
        for (DimensionState.Cell c : cube.cells) {
            if (c == null || c.geometry != null || c.building || c.cacheAttempted
                || !(c.checkpointRestorePending || c.reloadCacheEligible)) continue;
            if (load == null) { load = new Load(state, key); loads.add(load); }
            c.queued = false;
            c.building = c.cacheAttempted = true;
            entries.add(new Entry(state, c, load));
        }
    }

    boolean runnable() {
        if (!state.epochRestoreEnabled()) return true;
        return stage == 0 ? cursor == loads.size() || loads.get(cursor).future.isDone()
            : stage != 3 || state.canEpochCommit();
    }

    int advance() {
        int phase = stage;
        long start = System.nanoTime();
        int result = advanceUnit();
        long now = System.nanoTime();
        state.profile.record(phase == 2 || phase == 4 ? ProfilerCounters.EPOCH_CONTACTS
            : ProfilerCounters.EPOCH_PLAN, now - start, 1);
        if (result < 0) state.profile.reason(phase == 0 ? ProfilerCounters.CACHE_PENDING
            : ProfilerCounters.WAIT_SAFE_CUT, now - start);
        if (result < 0) {
            if (waitingSince == 0) waitingSince = start;
        } else {
            if (waitingSince != 0) {
                state.restartMetrics.add("epoch_wait_ms", (now - waitingSince) / 1e6);
                waitingSince = 0;
            }
            if (phase == 2 || phase == 4) contactsNanos += now - start;
            else if (phase != 3) prepareNanos += now - start;
        }
        if (result <= 0 || phase == 3) {
            if (contactsNanos != 0) state.restartMetrics.add("epoch_contacts_ms", contactsNanos / 1e6);
            if (prepareNanos != 0) state.restartMetrics.add("epoch_prepare_ms", prepareNanos / 1e6);
            contactsNanos = prepareNanos = 0;
        }
        return result;
    }

    private int advanceUnit() {
        if (!state.epochRestoreEnabled()) return abandon();
        if (stage == 0) {
            if (cursor == loads.size()) { cursor = 0; stage = 1; return 1; }
            Load load = loads.get(cursor);
            if (!load.future.isDone()) { state.restartMetrics.add("plan_stop_cache_not_ready", 1); return -1; }
            try { load.lookup = load.future.join(); }
            catch (RuntimeException failure) { load.lookup = new GeometryCache.Lookup(GeometryCache.Status.CORRUPT); }
            state.geometryCache.accept(load.key, load.lookup, state.restartMetrics);
            cursor++;
            return 1;
        }
        if (stage == 1) {
            if (cursor == entries.size()) {
                for (int i = 0; i < entries.size(); i++) {
                    Entry e = entries.get(i); e.order = i; index.put(e.cell.key, e);
                }
                for (Entry e : entries)
                    for (int d = 0; d < 6; d++) {
                        e.localNeighbors[d] = index.get(e.neighbors.keys[d]);
                        if (e.localNeighbors[d] != null) e.neighbors.bind(d, e.localNeighbors[d].cell);
                    }
                runtimeIds = new int[nodes];
                cursor = face = voxel = 0; stage = 2;
                return 1;
            }
            Entry e = entries.get(cursor);
            if (e.pack == null) {
                e.pack = e.load.lookup.cells.get(e.cell.key);
                if (e.load.lookup.status != GeometryCache.Status.OK || e.pack == null) {
                    String metric = e.load.lookup.status == GeometryCache.Status.CONFIG ? "cache_invalid_config"
                        : e.load.lookup.status == GeometryCache.Status.CORRUPT ? "cache_corrupt" : "cache_misses";
                    state.restartMetrics.add(metric, 1);
                    if (e.load.lookup.status == GeometryCache.Status.CORRUPT || e.load.lookup.status == GeometryCache.Status.CONFIG)
                        e.cell.cacheState = DimensionState.GeometryState.INVALID;
                    release(e, false); entries.remove(cursor); return 1;
                }
                if (nodes + e.pack.volumes.length > state.restartOptions.regionNodes
                    || e.pack.volumes.length > state.restartOptions.batchNodes) {
                    state.restartMetrics.add("plan_stop_node_limit", 1);
                    release(e, true); entries.remove(cursor); return 1;
                }
                if (!optimistic) e.snapshots = new ImmutableCubeSnapshot[e.pack.dependencies.length];
                dependency = 0;
            }
            if (!optimistic && dependency < e.snapshots.length) {
                ImmutableCubeSnapshot snapshot = state.snapshot(e.pack.dependencies[dependency]);
                long start = System.nanoTime();
                byte[] fingerprint = snapshot == null ? null : snapshot.fingerprint();
                state.restartMetrics.add("cache_fingerprint_ms", (System.nanoTime() - start) / 1e6);
                if (!Arrays.equals(fingerprint, e.pack.fingerprints[dependency])) {
                    state.restartMetrics.add("cache_invalid_world", 1);
                    release(e, false); entries.remove(cursor); return 1;
                }
                e.snapshots[dependency++] = snapshot;
                return 1;
            }
            if (!valid(e)) { release(e, false); entries.remove(cursor); return 1; }
            DimensionState.Geometry g = e.geometry = new DimensionState.Geometry();
            g.verification = optimistic ? DimensionState.GeometryState.UNVERIFIED : DimensionState.GeometryState.VALIDATED;
            g.labels = e.pack.labels; g.distance = e.pack.distance; g.volumes = e.pack.volumes;
            g.ids = new int[g.volumes.length]; g.dependencies = e.pack.dependencies;
            if (!optimistic) {
                g.epochs = new long[g.dependencies.length]; g.revisions = new long[g.dependencies.length];
                for (int i = 0; i < g.dependencies.length; i++) {
                    ImmutableCubeSnapshot s = e.snapshots[i];
                    g.epochs[i] = s == null ? -1 : s.epoch; g.revisions[i] = s == null ? -1 : s.revision;
                }
            }
            e.base = nodes; nodes += g.volumes.length;
            for (int i = 0; i < e.pack.contacts.length; i += 4)
                e.local.addAggregate(e.base + e.pack.contacts[i], e.base + e.pack.contacts[i + 1],
                    e.pack.contacts[i + 2], e.pack.contacts[i + 3]);
            state.restartMetrics.add("cache_hits", 1);
            cursor++;
            return 1;
        }
        if (stage == 2 || stage == 4) {
            if (stage == 2 && cursor == entries.size()) { stage = 3; return 1; }
            Entry e = entries.get(stage == 4 ? refreshing : cursor);
            if (stage == 4 && (refreshMask & (1 << face)) == 0) {
                if (++face < 6) return 1;
                face = 0; e.mergeExternal(); stage = 3; batchStop = -1; return 1;
            }
            if (voxel == 0) e.neighbors.capture(face);
            Entry other = e.localNeighbors[face];
            DimensionState.Geometry g = other == null ? e.neighbors.geometry[face] : other.geometry;
            // Empty/deferred faces need no per-voxel loop. A full one-node Cell8 and
            // uniform distances prove all 64 contacts have exactly the same endpoints
            // and length; aggregate their exact area, without merging physical nodes.
            if (g == null || other != null && (stage == 4 || other.order >= e.order)) voxel = 64;
            else if (state.restartOptions.epochCompactContacts && voxel == 0
                && e.geometry.volumes.length == 1 && e.geometry.volumes[0] == 512
                && g.volumes.length == 1 && g.volumes[0] == 512
                && e.geometry.distance.payloadBytes() == 0 && g.distance.payloadBytes() == 0) {
                    EdgeAccumulator edges = other == null ? e.external : e.local;
                    edges.addAggregate(e.base, other == null ? -1 - g.ids[0] : other.base, 64,
                        e.geometry.distance.get(0) + g.distance.get(0) + 1);
                    voxel = 64;
            }
            int unit = state.restartOptions.epochCompactContacts ? 64 : 16;
            for (int stop = Math.min(64, voxel + unit); voxel < stop; voxel++) {
                int p = CellBuild.face(face, voxel), q = CellBuild.face(face ^ 1, voxel);
                int a = e.geometry.labels.get(p), b = g.labels.get(q);
                if (a >= 0 && b >= 0) {
                    EdgeAccumulator edges = other == null ? e.external : e.local;
                    edges.add(e.base + a, other == null ? -1 - g.ids[b] : other.base + b,
                        e.geometry.distance.get(p) + g.distance.get(q) + 1);
                }
            }
            if (voxel == 64) {
                voxel = 0;
                e.faceRecords[face] = e.external.records(); e.external.clear();
                if (++face == 6) {
                    face = 0; e.mergeExternal();
                    if (stage == 2) {
                        preparedEdges += e.local.size() + e.externalRecords.length / 4;
                        if (preparedEdges > state.restartOptions.regionEdges) {
                            state.restartMetrics.add("plan_stop_edge_limit", 1); return abandon(e);
                        }
                        e.localRecords = e.local.records(); cursor++;
                    } else { stage = 3; batchStop = -1; }
                }
            }
            return 1;
        }
        if (published == entries.size()) return 0;
        if (!state.canEpochCommit()) return -1;
        if (batchStop < 0) {
            batchStop = published; batchNodes = batchEdges = 0; capacityReady = false;
            String reason = "complete";
            while (batchStop < entries.size()) {
                if (batchStop - published == state.restartOptions.microCells) { reason = "cell_limit"; break; }
                Entry e = entries.get(batchStop);
                int n = e.geometry.ids.length, edges = (e.localRecords.length + e.externalRecords.length) / 4;
                if (batchNodes + n > state.restartOptions.batchNodes) { reason = "node_limit"; break; }
                if (batchEdges + edges > state.restartOptions.batchEdges) { reason = "edge_limit"; break; }
                batchNodes += n; batchEdges += edges; batchStop++;
            }
            state.restartMetrics.add("batch_stop_" + reason, 1);
        }
        if (batchStop == published) return abandon(entries.get(published));
        // RESERVING never redoes world validation or contact preparation.
        if (!capacityReady) {
            if (!state.prepareEpochCapacity(batchNodes, batchEdges)) {
                state.restartMetrics.add("capacity_retries", 1); return 1;
            }
            capacityReady = true;
        }
        // One final check on the owner thread immediately before the atomic append.
        state.restartMetrics.add("preflight_rechecks", 1);
        for (int i = published; i < batchStop; i++) {
            Entry e = entries.get(i);
            if (!valid(e)) { state.restartMetrics.add("batch_stop_stale", 1); return abandon(e); }
            int changed = 0;
            for (int d = 0; d < 6; d++) {
                Entry other = e.localNeighbors[d];
                if (other != null) {
                    if (other.order < published && other.cell.geometry != other.geometry) return abandon(other);
                } else if (e.neighbors.changed(d)) changed |= 1 << d;
            }
            if (changed != 0) {
                refreshing = i; refreshMask = changed; face = voxel = 0;
                e.external.clear(); stage = 4; return 1;
            }
        }
        int stop = batchStop;
        for (int i = published; i < stop; i++)
            if (entries.get(i).cell.reloadCacheEligible) state.restartMetrics.add("cache_reactivation_cells", 1);
        long duration = EpochAppendBatch.append(state, entries, published, stop, runtimeIds, batchNodes, batchEdges);
        try {
            for (int i = published; i < stop; i++) {
                Entry e = entries.get(i);
                if (optimistic) state.validator.add(e.cell, e.geometry, e.pack.fingerprints);
                e.cell.building = false;
                e.cell.diagnosticQueuedTick = e.cell.diagnosticPreparedTick = -1;
                state.startup.published(e.cell, true);
            }
        } catch (RuntimeException | Error failure) { state.failAppend(failure); throw failure; }
        int count = stop - published;
        state.recordFastPublication(duration, count);
        state.restartMetrics.add("fast_restore_batches", 1);
        state.restartMetrics.add("fast_restore_nodes", batchNodes);
        state.restartMetrics.add("fast_restore_edges", batchEdges);
        state.restartMetrics.add("fast_restore_commit_ms", duration / 1e6);
        state.restartMetrics.max("fast_restore_max_commit_ms", duration / 1e6);
        published = stop; batchStop = -1;
        return published == entries.size() ? 0 : 1;
    }

    private boolean valid(Entry e) {
        if (e.cell.ticket != e.ticket || e.cell.geometry != null
            || !(e.cell.checkpointRestorePending || e.cell.reloadCacheEligible)
            || !state.provider.isReady(e.cell.cube.key)) return false;
        if (!optimistic && e.snapshots != null)
            for (int i = 0; i < e.snapshots.length; i++)
                if (!state.provider.matchesSnapshot(e.pack.dependencies[i], e.snapshots[i])) return false;
        return true;
    }

    private void release(Entry e, boolean retry) {
        e.cell.building = false; e.cell.cacheAttempted = !retry;
        state.restartMetrics.add("epoch_fallback_cells", 1);
        state.enqueue(e.cell);
    }
    private int abandon() { return abandon(null); }
    private int abandon(Entry invalid) {
        // Discard all private offsets/records together. Valid entries re-enter cache restore
        // with fresh local indices; only the invalid cell is forced through CellBuild.
        for (int i = published; i < entries.size(); i++) {
            Entry e = entries.get(i); release(e, e != invalid && valid(e));
        }
        return 0;
    }
}

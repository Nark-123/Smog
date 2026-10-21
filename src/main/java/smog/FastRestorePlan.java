package smog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.concurrent.CompletableFuture;

/** Private Cube16 transaction. Geometry/contact preparation never changes the active graph. */
final class FastRestorePlan extends RestorePlan {

    static final int EXTERNAL = 4096;

    static final class Entry {

        final DimensionState.Cell cell;
        final long ticket;
        GeometryPack pack;
        DimensionState.Geometry geometry;
        ImmutableCubeSnapshot[] snapshots;
        final DimensionState.Geometry[] neighbors = new DimensionState.Geometry[6];
        int base, policy;

        Entry(DimensionState.Cell c) {
            cell = c;
            ticket = c.ticket;
        }

        ImmutableCubeSnapshot self() {
            if (snapshots == null) return null;
            for (int i = 0; i < pack.dependencies.length; i++)
                if (pack.dependencies[i].equals(cell.cube.key)) return snapshots[i];
            return null;
        }
    }

    private final DimensionState state;
    private final ArrayList<Entry> entries = new ArrayList<>();
    private final CompletableFuture<GeometryCache.Lookup> future;
    private GeometryCache.Lookup lookup;
    final EdgeAccumulator edges = new EdgeAccumulator();
    private int stage, cursor, dependency, nodes, face, voxel;
    private final boolean optimistic;
    private int[] runtimeIds, edgeRecords;
    private boolean contactsCaptured;

    FastRestorePlan(DimensionState state, DimensionState.Cell first) {
        this.state = state;
        // Runtime reactivation validates before publishing: a newer session build may
        // still have a delayed disk write. It never reuses the cold-mass exemption.
        boolean coldOnly = true;
        for (DimensionState.Cell c : first.cube.cells)
            if (c != null && c.reloadCacheEligible) coldOnly = false;
        optimistic = coldOnly && state.restartOptions.optimistic && state.restartOptions.fast;
        for (DimensionState.Cell c : first.cube.cells) if (c != null && (c.checkpointRestorePending || c.reloadCacheEligible)
            && c.geometry == null
            && !c.building
            && !c.cacheAttempted
            && state.provider.isReady(c.cube.key)) {
                c.queued = false;
                c.building = true;
                c.cacheAttempted = true;
                entries.add(new Entry(c));
            }
        future = state.geometryCache.request(first.cube.key);
    }

    private void release(Entry e, boolean retry) {
        e.cell.building = false;
        e.cell.cacheAttempted = !retry;
        state.enqueue(e.cell);
    }

    private void invalidate(Entry e) {
        state.restartMetrics.add("cache_invalid_world", 1);
        release(e, false);
    }

    boolean runnable() {
        return stage == 0 ? future.isDone() : stage == 1 || state.canFastCommit();
    }

    // -1 = wait for I/O or an idle boundary; 0 = complete; 1 = one bounded unit.
    int advance() {
        if (stage == 0) {
            if (!future.isDone()) return -1;
            try {
                lookup = future.join();
            } catch (RuntimeException failure) {
                lookup = new GeometryCache.Lookup(GeometryCache.Status.CORRUPT);
            }
            state.geometryCache.accept(entries.get(0).cell.cube.key, lookup, state.restartMetrics);
            stage = 1;
            return 1;
        }
        if (stage == 1) {
            if (cursor == entries.size()) {
                cursor = face = voxel = 0;
                stage = 2;
                return 1;
            }
            Entry e = entries.get(cursor);
            if (e.pack == null) {
                e.pack = lookup.cells.get(e.cell.key);
                if (lookup.status != GeometryCache.Status.OK || e.pack == null) {
                    if (lookup.status == GeometryCache.Status.CORRUPT || lookup.status == GeometryCache.Status.CONFIG)
                        e.cell.cacheState = DimensionState.GeometryState.INVALID;
                    String metric = lookup.status == GeometryCache.Status.CONFIG ? "cache_invalid_config"
                        : lookup.status == GeometryCache.Status.CORRUPT ? "cache_corrupt" : "cache_misses";
                    state.restartMetrics.add(metric, 1);
                    release(e, false);
                    entries.remove(cursor);
                    return 1;
                }
                if (nodes + e.pack.volumes.length > state.restartOptions.batchNodes
                    || 1728 * (cursor + 1) > state.restartOptions.batchEdges) {
                    release(e, true);
                    entries.remove(cursor);
                    return 1;
                }
                if (!optimistic) e.snapshots = new ImmutableCubeSnapshot[e.pack.dependencies.length];
                dependency = 0;
            }
            if (!optimistic && dependency < e.snapshots.length) {
                CubeKey key = e.pack.dependencies[dependency];
                ImmutableCubeSnapshot s = state.snapshot(key);
                long start = System.nanoTime();
                byte[] fingerprint = s == null ? null : s.fingerprint();
                state.restartMetrics.add("cache_fingerprint_ms", (System.nanoTime() - start) / 1e6);
                if (!Arrays.equals(fingerprint, e.pack.fingerprints[dependency])) {
                    invalidate(e);
                    entries.remove(cursor);
                    dependency = 0;
                    return 1;
                }
                e.snapshots[dependency++] = s;
                return 1;
            }
            if ((!optimistic && e.self() == null) || e.cell.ticket != e.ticket
                || !state.provider.isReady(e.cell.cube.key)) {
                invalidate(e);
                entries.remove(cursor);
                return 1;
            }
            state.restartMetrics.add("cache_hits", 1);
            if (!state.restartOptions.fast) {
                state.stageCachedBuild(e);
                entries.remove(cursor);
                return 1;
            }
            DimensionState.Geometry g = e.geometry = new DimensionState.Geometry();
            g.verification = optimistic ? DimensionState.GeometryState.UNVERIFIED : DimensionState.GeometryState.VALIDATED;
            // These geometry inputs are immutable after pack decode. Runtime IDs/version
            // arrays remain private to this publication; parked mass always copies membership.
            g.labels = e.pack.labels;
            g.distance = e.pack.distance;
            g.volumes = e.pack.volumes;
            g.ids = new int[g.volumes.length];
            g.dependencies = e.pack.dependencies;
            if (!optimistic) {
                g.epochs = new long[g.dependencies.length];
                g.revisions = new long[g.dependencies.length];
                for (int i = 0; i < g.dependencies.length; i++) {
                    ImmutableCubeSnapshot s = e.snapshots[i];
                    g.epochs[i] = s == null ? -1 : s.epoch;
                    g.revisions[i] = s == null ? -1 : s.revision;
                }
            }
            e.base = nodes;
            nodes += g.volumes.length;
            int[] contact = e.pack.contacts;
            for (int i = 0; i < contact.length; i += 4)
                edges.addAggregate(e.base + contact[i], e.base + contact[i + 1], contact[i + 2], contact[i + 3]);
            cursor++;
            return 1;
        }
        if (stage == 2) {
            // Local topology/IO can be staged across solver parts. Capture external
            // neighbors when this transaction can publish, so queued adjacent plans
            // do not each allocate and discard an obsolete external contact table.
            if (!state.canFastCommit()) return -1;
            if (!contactsCaptured) {
                for (Entry e : entries) for (int d = 0; d < 6; d++) {
                    DimensionState.Cell c = state.cell(CellBuild.neighbor(e.cell.key, d), false);
                    e.neighbors[d] = c == null ? null : c.geometry;
                }
                contactsCaptured = true;
            }
            if (cursor == entries.size()) {
                // Allocate/sort the stable local contact table before the atomic boundary.
                runtimeIds = new int[nodes];
                edgeRecords = edges.records();
                stage = 3;
                return 1;
            }
            Entry e = entries.get(cursor);
            CellKey neighbor = CellBuild.neighbor(e.cell.key, face);
            Entry other = find(neighbor);
            DimensionState.Geometry g = other == null ? e.neighbors[face] : other.geometry;
            for (int stop = Math.min(64, voxel + 16); voxel < stop; voxel++) {
                if (g == null || (other != null && other.base < e.base)) continue;
                int p = CellBuild.face(face, voxel), q = CellBuild.face(face ^ 1, voxel), a = e.geometry.labels.get(p),
                    b = g.labels.get(q);
                if (a >= 0 && b >= 0) edges.add(
                    e.base + a,
                    other == null ? EXTERNAL + g.ids[b] : other.base + b,
                    e.geometry.distance.get(p) + g.distance.get(q) + 1);
            }
            if (voxel == 64) {
                voxel = 0;
                if (++face == 6) {
                    face = 0;
                    cursor++;
                }
            }
            return 1;
        }
        if (entries.isEmpty()) return 0;
        if (!state.canFastCommit()) return -1;
        for (Entry e : entries) {
            if (e.cell.ticket != e.ticket || e.cell.geometry != null
                || !(e.cell.checkpointRestorePending || e.cell.reloadCacheEligible)
                || !state.provider.isReady(e.cell.cube.key))
                return abandon();
            for (int i = 0; !optimistic && i < e.snapshots.length; i++)
                if (!state.provider.matchesSnapshot(e.pack.dependencies[i], e.snapshots[i])) return abandon();
            for (int d = 0; d < 6; d++) {
                DimensionState.Cell c = state.cell(CellBuild.neighbor(e.cell.key, d), false);
                if ((c == null ? null : c.geometry) != e.neighbors[d]) return refreshContacts();
            }
        }
        if (nodes > state.restartOptions.batchNodes || edges.size() > state.restartOptions.batchEdges) return abandon();
        LinkedHashSet<DimensionState.Cell> fronts = new LinkedHashSet<>();
        for (Entry e : entries) {
            if (e.cell.reloadCacheEligible) state.restartMetrics.add("cache_reactivation_cells", 1);
            fronts.add(e.cell);
            for (int d = 0; d < 6; d++) {
                DimensionState.Cell c = state.cell(CellBuild.neighbor(e.cell.key, d), false);
                if (c != null && c.geometry != null) fronts.add(c);
            }
        }
        long reserveStart = System.nanoTime();
        state.reserveFast(nodes, edges.size());
        double reserveMs = (System.nanoTime() - reserveStart) / 1e6;
        state.restartMetrics.add("fast_restore_reserve_ms", reserveMs);
        state.restartMetrics.max("fast_restore_max_reserve_ms", reserveMs);
        // All recoverable validation, allocation and budget decisions precede topology mutation.
        long start = System.nanoTime();
        for (Entry e : entries)
            e.policy = state.publishGeometry(e.cell, e.geometry, e.pack.seeds, e.pack.boundary, e.self(), null, false);
        state.restartMetrics.add("fast_restore_nodes_ms", (System.nanoTime() - start) / 1e6);
        long edgeStart = System.nanoTime();
        for (Entry e : entries) System.arraycopy(e.geometry.ids, 0, runtimeIds, e.base, e.geometry.ids.length);
        for (int i = 0; i < edgeRecords.length; i += 4) {
            double length = state.config.mode == SmogConfig.COMPONENT ? state.config.containerSize : edgeRecords[i + 3];
            state.graph.addEdge(id(edgeRecords[i]), id(edgeRecords[i + 1]), edgeRecords[i + 2] / length);
        }
        state.restartMetrics.add("fast_restore_edges_ms", (System.nanoTime() - edgeStart) / 1e6);
        long frontStart = System.nanoTime();
        state.refreshFastFronts(fronts);
        state.restartMetrics.add("fast_restore_front_ms", (System.nanoTime() - frontStart) / 1e6);
        long publication = System.nanoTime() - start;
        long eventsStart = System.nanoTime();
        state.applyEvents();
        long eventNanos = System.nanoTime() - eventsStart;
        state.geometryMetrics.publicationEvents(eventNanos);
        state.restartMetrics.add("fast_restore_events_ms", eventNanos / 1e6);
        long epsilonStart = System.nanoTime();
        for (Entry e : entries) {
            state.finishPublicationMass(e.cell, e.policy);
            if (optimistic) state.validator.add(e.cell, e.geometry, e.pack.fingerprints);
            e.cell.building = false;
            e.cell.diagnosticQueuedTick = e.cell.diagnosticPreparedTick = -1;
            state.startup.published(e.cell, true);
        }
        publication += System.nanoTime() - epsilonStart;
        state.restartMetrics.add("fast_restore_finish_ms", (System.nanoTime() - epsilonStart) / 1e6);
        state.recordFastPublication(publication, entries.size());
        double commit = (System.nanoTime() - start) / 1e6;
        state.restartMetrics.add("fast_restore_batches", 1);
        state.restartMetrics.add("fast_restore_nodes", nodes);
        state.restartMetrics.add("fast_restore_edges", edges.size());
        state.restartMetrics.add("fast_restore_commit_ms", commit);
        state.restartMetrics.max("fast_restore_max_commit_ms", commit);
        return 0;
    }

    private Entry find(CellKey key) {
        for (Entry e : entries) if (e.cell.key.equals(key)) return e;
        return null;
    }

    private int refreshContacts() {
        // Another private transaction committed first. Reuse decoded local topology,
        // discard only its external contacts, exactly like the ordinary CellBuild path.
        edges.clear();
        for (Entry e : entries) {
            for (int d = 0; d < 6; d++) {
                DimensionState.Cell c = state.cell(CellBuild.neighbor(e.cell.key, d), false);
                e.neighbors[d] = c == null ? null : c.geometry;
            }
            for (int i = 0; i < e.pack.contacts.length; i += 4)
                edges.addAggregate(e.base + e.pack.contacts[i], e.base + e.pack.contacts[i + 1],
                    e.pack.contacts[i + 2], e.pack.contacts[i + 3]);
        }
        cursor = face = voxel = 0;
        contactsCaptured = true;
        stage = 2;
        return 1;
    }

    private int id(int local) {
        if (local >= EXTERNAL) return local - EXTERNAL;
        return runtimeIds[local];
    }

    private int abandon() {
        for (Entry e : entries) invalidate(e);
        return 0;
    }
}

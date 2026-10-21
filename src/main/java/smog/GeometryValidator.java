package smog;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Map;

/** Owner-thread, bounded deferred world checks. Missing sections sleep until a readiness event. */
final class GeometryValidator {
    static final class Task {
        final DimensionState.Cell cell;
        final DimensionState.Geometry geometry;
        final byte[][] fingerprints;
        ImmutableCubeSnapshot[] checked;
        final long tick;
        int cursor;
        boolean missing, queued, waiting;
        Task(DimensionState.Cell cell, DimensionState.Geometry geometry, byte[][] fingerprints, long tick) {
            this.cell = cell;
            this.geometry = geometry;
            this.fingerprints = fingerprints;
            this.tick = tick;
        }
    }

    private final DimensionState state;
    private final ArrayDeque<Task> queue = new ArrayDeque<>();
    private final LinkedHashSet<Task> pending = new LinkedHashSet<>();
    private int waiting;
    private static final class Checked {
        final ImmutableCubeSnapshot snapshot;
        final byte[] expected;
        Checked(ImmutableCubeSnapshot snapshot, byte[] expected) { this.snapshot = snapshot; this.expected = expected; }
    }
    // Per dimension/config instance; positive checks only, bounded to 128 immutable snapshots.
    private final Map<CubeKey, Checked> dependencies = new LinkedHashMap<CubeKey, Checked>(128, .75f, true) {
        protected boolean removeEldestEntry(Map.Entry<CubeKey, Checked> e) { return size() > 128; }
    };
    void invalidate(CubeKey key) { dependencies.remove(key); }

    private ImmutableCubeSnapshot dependency(CubeKey key, byte[] expected) {
        Checked previous = dependencies.get(key);
        if (previous != null) {
            if (state.provider.matchesSnapshot(key, previous.snapshot)) {
                if (Arrays.equals(previous.expected, expected)) {
                    state.restartMetrics.add("reused_dependency_checks", 1); return previous.snapshot;
                }
            } else {
                dependencies.remove(key); state.restartMetrics.add("validation_stale_rechecks", 1);
            }
        }
        ImmutableCubeSnapshot snapshot = state.snapshot(key);
        if (snapshot != null) {
            state.restartMetrics.add("unique_dependency_checks", 1);
            if (Arrays.equals(snapshot.fingerprint(), expected)) dependencies.put(key, new Checked(snapshot, expected));
        }
        return snapshot;
    }

    GeometryValidator(DimensionState state) { this.state = state; }

    void add(DimensionState.Cell cell, DimensionState.Geometry geometry, byte[][] fingerprints) {
        cancel(cell);
        Task task = new Task(cell, geometry, fingerprints, state.ticks);
        cell.validation = task;
        pending.add(task);
        offer(task);
        metrics();
    }

    private void offer(Task task) {
        if (!task.queued) {
            task.queued = true;
            queue.addLast(task);
        }
    }

    void cancel(DimensionState.Cell cell) {
        Task task = cell.validation;
        if (task == null) return;
        cell.validation = null;
        task.checked = null;
        pending.remove(task);
        if (task.waiting) { task.waiting = false; waiting--; }
        metrics();
    }

    void wake(DimensionState.Cell cell) {
        Task task = cell.validation;
        if (task == null) return;
        if (task.waiting) { task.waiting = false; waiting--; }
        task.cursor = 0;
        task.missing = false;
        task.checked = null;
        offer(task);
        metrics();
    }

    boolean runnable() { return !queue.isEmpty(); }

    void advance(long budget) {
        long start = System.nanoTime();
        long profileChecks = 0;
        while (!queue.isEmpty() && System.nanoTime() - start < budget) {
            Task task = queue.removeFirst();
            task.queued = false;
            if (task.cell.validation != task || task.cell.geometry != task.geometry) continue;
            DimensionState.Geometry geometry = task.geometry;
            if (task.checked == null) task.checked = new ImmutableCubeSnapshot[geometry.dependencies.length];
            if (task.cursor < geometry.dependencies.length) {
                int i = task.cursor++;
                profileChecks++;
                CubeKey key = geometry.dependencies[i];
                // No snapshots or periodic retries for missing dependencies.
                ImmutableCubeSnapshot snapshot = dependency(key, task.fingerprints[i]);
                if (snapshot == null) task.missing = true;
                else {
                    state.restartMetrics.add("validation_checks", 1);
                    task.checked[i] = snapshot;
                    if (!Arrays.equals(snapshot.fingerprint(), task.fingerprints[i])) {
                        state.restartMetrics.add("cache_invalid_world", 1);
                        state.restartMetrics.add("validation_dirty", 1);
                        state.validationDirty(task.cell);
                        continue;
                    }
                }
                // Finish one small dependency set before retaining snapshots for another cell.
                task.queued = true;
                queue.addFirst(task);
                continue;
            }
            boolean stale = false;
            for (int i = 0; i < task.checked.length; i++) {
                if (task.checked[i] != null && !state.provider.matchesSnapshot(geometry.dependencies[i], task.checked[i])) {
                    stale = true;
                    break;
                }
            }
            if (stale) {
                state.restartMetrics.add("validation_stale", 1);
                wake(task.cell);
            } else if (task.missing) {
                // Retain hashes and versions, not 16^3 world snapshots, while sleeping.
                task.checked = null;
                task.waiting = true;
                waiting++;
            } else {
                // Versions become meaningful only after all world fingerprints match.
                geometry.epochs = new long[task.checked.length];
                geometry.revisions = new long[task.checked.length];
                for (int i = 0; i < task.checked.length; i++) {
                    geometry.epochs[i] = task.checked[i].epoch;
                    geometry.revisions[i] = task.checked[i].revision;
                }
                geometry.verification = DimensionState.GeometryState.VALIDATED;
                state.restartMetrics.add("validation_validated", 1);
                cancel(task.cell);
            }
        }
        long profileNanos = System.nanoTime() - start;
        state.profile.record(ProfilerCounters.VALIDATOR, profileNanos, profileChecks);
        state.restartMetrics.add("validation_ms", profileNanos / 1e6);
        metrics();
    }

    private void metrics() {
        state.restartMetrics.set("validation_backlog", pending.size());
        state.restartMetrics.set("validation_waiting", waiting);
        state.restartMetrics.set("validation_oldest_ticks", pending.isEmpty() ? 0 :
            state.ticks - pending.iterator().next().tick);
    }
}

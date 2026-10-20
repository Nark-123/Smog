package smog;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Saved-section/availability index. The runnable queue contains only ready saved sections. */
final class StartupPipeline {

    private final DimensionState state;
    private final Map<Long, TreeMap<Integer, DimensionState.CubeRecord>> columns = new HashMap<>();
    private final LinkedHashMap<CubeKey, ArrayDeque<CubeKey>> groups = new LinkedHashMap<>();
    private final Set<CubeKey> queued = new HashSet<>();
    private final ArrayDeque<DimensionState.CubeRecord> discovery = new ArrayDeque<>();
    private long opened = System.nanoTime();
    private boolean resumed;
    long remaining, available, unchecked;
    private double initialMass, restoredMass;

    StartupPipeline(DimensionState state) {
        this.state = state;
    }

    void openedAt(long time) {
        opened = time;
    }

    private static long column(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    private static CubeKey region(CubeKey key) {
        return new CubeKey(key.dimension, Math.floorDiv(key.x, 4), Math.floorDiv(key.y, 4), Math.floorDiv(key.z, 4));
    }

    void register(DimensionState.CubeRecord cube) {
        columns.computeIfAbsent(column(cube.key.x, cube.key.z), k -> new TreeMap<>())
            .put(cube.key.y, cube);
        state.restartMetrics.set("startup_relevant_columns", columns.size());
    }

    void saved(DimensionState.Cell cell) {
        if (cell.parked != null) initialMass += cell.parked.total();
        if (cell.cube.savedMask == 0) discovery.addLast(cell.cube);
        cell.cube.savedMask |= 1 << cell.key.slot();
        remaining++;
        unchecked++;
        state.restartMetrics.add("restart_cells_total", 1);
        counts();
    }

    void resume() {
        resumed = true;
    }

    private void offer(CubeKey key) {
        if (queued.add(key)) groups.computeIfAbsent(region(key), k -> new ArrayDeque<>())
            .addLast(key);
    }

    private void remove(CubeKey key) {
        if (!queued.remove(key)) return;
        CubeKey region = region(key);
        ArrayDeque<CubeKey> group = groups.get(region);
        if (group != null) {
            group.remove(key);
            if (group.isEmpty()) groups.remove(region);
        }
    }

    boolean pending() {
        return resumed && !groups.isEmpty();
    }

    boolean discovering() {
        return resumed && !discovery.isEmpty();
    }

    boolean restores(DimensionState.Cell cell) {
        return resumed && cell != null
            && cell.checkpointRestorePending
            && (cell.cube.savedMask & (1 << cell.key.slot())) != 0;
    }

    void discover(long deadline) {
        for (int n = 0; discovering() && n < 512 && System.nanoTime() < deadline; n++) {
            DimensionState.CubeRecord cube = discovery.removeFirst();
            if (!cube.restartChecked) ready(cube, state.provider.isReady(cube.key));
        }
    }

    private void counts() {
        state.restartMetrics.set("restore_saved_ready", available);
        state.restartMetrics.set("restart_cells_waiting_chunk", remaining - available - unchecked);
        state.restartMetrics.set("restart_cells_unchecked", unchecked);
    }

    /** Claim additional ready Cube16 in the same region; no scans of the world or player distance. */
    java.util.List<CubeKey> claimRegion(CubeKey first, int limit) {
        java.util.List<CubeKey> result = new java.util.ArrayList<>();
        ArrayDeque<CubeKey> group = groups.get(region(first));
        if (group == null) return result;
        while (!group.isEmpty() && result.size() < limit) {
            CubeKey key = group.removeFirst();
            queued.remove(key);
            DimensionState.CubeRecord cube = state.cubes.get(key);
            if (cube != null && cube.readySavedMask != 0 && state.provider.isReady(key)) {
                state.onWorldCubeAvailable(key);
                result.add(key);
            }
        }
        if (group.isEmpty()) groups.remove(region(first));
        return result;
    }

    void prefetch(GeometryCache cache) {
        if (!pending()) return;
        int count = 0, scanned = 0;
        for (ArrayDeque<CubeKey> group : groups.values()) for (CubeKey key : group) {
            if (++scanned > 16) return;
            DimensionState.CubeRecord cube = state.cubes.get(key);
            if (cube != null && cube.savedMask != 0 && state.provider.isReady(key)) {
                if (cache.prefetch(key) && ++count == 3) return;
            }
        }
    }

    boolean advance() {
        if (!pending()) return false;
        long start = System.nanoTime();
        Iterator<ArrayDeque<CubeKey>> it = groups.values()
            .iterator();
        ArrayDeque<CubeKey> group = it.next();
        CubeKey key = group.removeFirst();
        if (group.isEmpty()) it.remove();
        queued.remove(key);
        if (state.restartOptions.optimistic && state.restartOptions.fast && state.geometryCache != null)
            state.onWorldCubeAvailable(key);
        else {
            ImmutableCubeSnapshot snapshot = state.snapshot(key);
            if (snapshot != null) state.onWorldCubeReady(key, snapshot.epoch, snapshot.revision);
        }
        state.restartMetrics.add("resume_ready_ms", (System.nanoTime() - start) / 1e6);
        if (state.geometryCache != null) prefetch(state.geometryCache);
        return true;
    }

    void ready(DimensionState.CubeRecord cube, boolean ready) {
        if (!cube.restartChecked) {
            unchecked -= Integer.bitCount(cube.savedMask);
            cube.restartChecked = true;
        }
        int next = ready ? cube.savedMask : 0;
        boolean newlyAvailable = next != 0 && cube.readySavedMask == 0;
        available += Integer.bitCount(next) - Integer.bitCount(cube.readySavedMask);
        cube.readySavedMask = next;
        if (newlyAvailable) offer(cube.key);
        else if (!ready) remove(cube.key);
        if (ready && cube.savedMask != 0 && state.restartMetrics.get("dimension_to_first_ready_ms") < 0)
            state.restartMetrics.set("dimension_to_first_ready_ms", (System.nanoTime() - opened) / 1e6);
        state.restartMetrics.set("restart_cells_ready", available);
        counts();
    }

    void published(DimensionState.Cell cell, boolean fast) {
        int mask = 1 << cell.key.slot();
        if ((cell.cube.savedMask & mask) == 0) return;
        cell.cube.savedMask &= ~mask;
        remaining--;
        if (!cell.cube.restartChecked) unchecked--;
        if ((cell.cube.readySavedMask & mask) != 0) {
            cell.cube.readySavedMask &= ~mask;
            available--;
        }
        state.restartMetrics.add("restart_cells_published", 1);
        state.restartMetrics.add(fast ? "fast_restore_cells" : "fallback_restore_cells", 1);
        state.restartMetrics.set("restart_cells_ready", available);
        counts();
        double elapsed = (System.nanoTime() - opened) / 1e6;
        int[] seconds = { 1, 5, 10, 30, 60 };
        for (int second : seconds) if (elapsed <= second * 1000.0) state.restartMetrics
            .set("restore_cells_" + second + "s", state.restartMetrics.get("restart_cells_published"));
    }

    void massRestored(double mass) {
        restoredMass += mass;
        double ratio = initialMass == 0 ? 1 : Math.min(1, restoredMass / initialMass);
        state.restartMetrics.set("restore_mass_ratio", ratio);
        double elapsed = (System.nanoTime() - opened) / 1e6;
        if (ratio >= .5 && state.restartMetrics.get("restore_mass_50_ms") < 0)
            state.restartMetrics.set("restore_mass_50_ms", elapsed);
        if (ratio >= .9 && state.restartMetrics.get("restore_mass_90_ms") < 0)
            state.restartMetrics.set("restore_mass_90_ms", elapsed);
        if (ratio >= .99 && state.restartMetrics.get("restore_mass_99_ms") < 0)
            state.restartMetrics.set("restore_mass_99_ms", elapsed);
    }

    void columnEvent(int x, int z, boolean unloading) {
        long start = System.nanoTime();
        TreeSet<Integer> levels = new TreeSet<>();
        for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
            TreeMap<Integer, DimensionState.CubeRecord> map = columns.get(column(x + dx, z + dz));
            if (map != null) for (int y : map.keySet()) {
                levels.add(y - 1);
                levels.add(y);
                levels.add(y + 1);
            }
        }
        resumed = true;
        for (int y : levels) {
            CubeKey key = new CubeKey(state.dimension, x, y, z);
            DimensionState.CubeRecord cube = state.cubes.get(key);
            if (unloading) state.onWorldCubeUnloading(key, cube == null ? -1 : cube.epoch);
            else state.onWorldCubeAvailable(key);
        }
        state.restartMetrics.add("column_event_ms", (System.nanoTime() - start) / 1e6);
    }
}

package smog;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Integration queries; the solver, law and checkpoint format remain unchanged.
 */
public final class SpatialQueries {

    private SpatialQueries() {}

    public static long chunkKey(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    public static final class ChunkMassBatch {
        public final Map<Long, Double> masses;

        public final long cubeVisits;

        private ChunkMassBatch(Map<Long, Double> masses, long cubeVisits) {
            this.masses = java.util.Collections.unmodifiableMap(masses);
            this.cubeVisits = cubeVisits;
        }
    }

    /** One traversal of existing records; never asks the world provider for geometry. */
    public static ChunkMassBatch chunkMasses(DimensionState state, Set<Long> requested) {
        Map<Long, Double> masses = new HashMap<>();
        for (Long key : requested) {
            masses.put(key, 0.0);
        }
        long visits = 0;
        if (!requested.isEmpty()) {
            for (DimensionState.CubeRecord cube : state.cubes.values()) {
                visits++;
                long key = chunkKey(cube.key.x, cube.key.z);
                Double previous = masses.get(key);
                if (previous == null) {
                    continue;
                }
                double total = previous;
                // Preserve each addition and its order, including the parked subtotal.
                for (DimensionState.Cell cell : cube.cells) {
                    if (cell == null) {
                        continue;
                    }
                    if (cell.geometry != null) {
                        for (int id : cell.geometry.ids) {
                            total += state.graph.nodeMass(id);
                        }
                    }
                    if (cell.parked != null) {
                        total += cell.parked.total();
                    }
                    for (double mass : cell.trapped.values()) {
                        total += mass;
                    }
                }
                masses.put(key, total);
            }
        }
        return new ChunkMassBatch(masses, visits);
    }

    public static double chunkMass(DimensionState state, int x, int z) {
        double total = 0;
        for (DimensionState.CubeRecord cube : state.cubes.values()) {
            if (cube.key.x != x || cube.key.z != z) {
                continue;
            }
            for (DimensionState.Cell cell : cube.cells) {
                if (cell == null) {
                    continue;
                }
                if (cell.geometry != null) {
                    for (int id : cell.geometry.ids) {
                        total += state.graph.nodeMass(id);
                    }
                }
                if (cell.parked != null) {
                    total += cell.parked.total();
                }
                for (double mass : cell.trapped.values()) {
                    total += mass;
                }
            }
        }
        return total;
    }

    /**
     * Remove existing mass proportionally; queued live removals use the original event API.
     */
    public static void removeChunk(DimensionState state, int x, int z, double amount) {
        double total = chunkMass(state, x, z);
        if (total <= 0 || amount <= 0) {
            return;
        }
        double fraction = Math.min(1, amount / total);
        for (DimensionState.CubeRecord cube : state.cubes.values()) {
            if (cube.key.x != x || cube.key.z != z) {
                continue;
            }
            for (DimensionState.Cell cell : cube.cells) {
                if (cell == null) {
                    continue;
                }
                state.preserveForCapture(cell);
                if (cell.geometry != null) {
                    boolean[] seen = new boolean[cell.geometry.ids.length];
                    for (int voxel = 0; voxel < 512; voxel++) {
                        int label = cell.geometry.labels[voxel];
                        if (label < 0 || seen[label]) {
                            continue;
                        }
                        seen[label] = true;
                        state.queueRemoveMass(
                            cell.key.block(voxel),
                            state.graph.nodeMass(cell.geometry.ids[label]) * fraction);
                    }
                }
                if (cell.parked != null) {
                    DimensionState.MassRecord old = cell.parked;
                    double[] masses = old.masses.clone();
                    for (int i = 0; i < masses.length; i++) {
                        double removed = masses[i] * fraction;
                        masses[i] -= removed;
                        state.removed += removed;
                    }
                    cell.parked = new DimensionState.MassRecord(old.labels, old.volumes, masses);
                }
                for (java.util.Map.Entry<Integer, Double> entry : cell.trapped.entrySet()) {
                    double removed = entry.getValue() * fraction;
                    entry.setValue(entry.getValue() - removed);
                    state.removed += removed;
                }
            }
        }
    }
}

package smog;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * Private candidate. Captures at most eight immutable world cubes, fills a 2
 * voxel halo incrementally, runs the shared partitioner and stages contacts.
 * No mutable world access and no published graph mutation before commit.
 */
final class CellBuild {

    static final int[] DX = { -1, 1, 0, 0, 0, 0 }, DY = { 0, 0, -1, 1, 0, 0 }, DZ = { 0, 0, 0, 0, -1, 1 };

    final DimensionState state;

    final DimensionState.Cell cell;

    final long ticket;

    final Local local;

    final DimensionState.Geometry geometry = new DimensionState.Geometry();

    final EdgeAccumulator edges = new EdgeAccumulator();

    // Frozen after stage 3. Neighbour publications can invalidate only stage-4 edges.
    private final EdgeAccumulator internalEdges = new EdgeAccumulator();

    final double[] boundary = new double[512];

    private final CubeKey[] keys;

    private final ImmutableCubeSnapshot[] snapshots;

    private final DimensionState.Geometry[] neighbors = new DimensionState.Geometry[6];

    // Captured by validContacts on the owner thread; reused by refreshContacts.
    private final DimensionState.Geometry[] checkedNeighbors = new DimensionState.Geometry[6];

    private final Partitioner partitioner;

    private final BlockPos origin;

    private int stage, cursor, face;

    boolean ready;

    // Diagnostic only: subsequent contact-stage work belongs to a refresh.
    boolean diagnosticRefreshingContacts;

    CellBuild(DimensionState state, DimensionState.Cell cell) {
        this.state = state;
        this.cell = cell;
        ticket = cell.ticket;
        origin = cell.key.block(0);
        local = new Local(state.config, state.haloWorkspace);
        partitioner = state.partitionWorkspace(local);
        CubeKey min = origin.offset(-2, -2, -2)
            .cube(),
            max = origin.offset(9, 9, 9)
                .cube();
        ArrayList<CubeKey> list = new ArrayList<>();
        for (int z = min.z; z <= max.z; z++) {
            for (int y = min.y; y <= max.y; y++) {
                for (int x = min.x; x <= max.x; x++) {
                    list.add(new CubeKey(state.dimension, x, y, z));
                }
            }
        }
        keys = list.toArray(new CubeKey[0]);
        snapshots = new ImmutableCubeSnapshot[keys.length];
    }

    boolean inContacts() {
        return stage >= 3;
    }

    // Diagnostics only: inspect the next scheduler stage without advancing the build.
    int diagnosticStage() {
        return stage;
    }

    void advance() {
        switch (stage) {
            case 0:
                if (cursor < keys.length) {
                    snapshots[cursor] = state.snapshot(keys[cursor]);
                    cursor++;
                } else {
                    cursor = 0;
                    stage = 1;
                }
                break;
            case 1:
                for (int stop = Math.min(1728, cursor + 32); cursor < stop; cursor++) {
                    int x = origin.x + cursor % 12 - 2, y = origin.y + cursor / 12 % 12 - 2,
                        z = origin.z + cursor / 144 - 2;
                    ImmutableCubeSnapshot s = at(x, y, z);
                    local.halo[cursor] = (byte) (s != null && s.airAt(x, y, z) ? 1 : 0);
                }
                if (cursor == 1728) {
                    partitioner.begin(0, 0, 0);
                    stage = 2;
                }
                break;
            case 2:
                if (partitioner.advance(32)) {
                    geometry.labels = local.labels;
                    geometry.distance = local.distance;
                    geometry.volumes = Arrays.copyOf(local.volumes, local.count);
                    geometry.dependencies = keys;
                    geometry.epochs = new long[keys.length];
                    geometry.revisions = new long[keys.length];
                    for (int i = 0; i < keys.length; i++) {
                        geometry.epochs[i] = snapshots[i] == null ? -1 : snapshots[i].epoch;
                        geometry.revisions[i] = snapshots[i] == null ? -1 : snapshots[i].revision;
                    }
                    for (int d = 0; d < 6; d++) {
                        DimensionState.Cell c = state.cell(neighbor(cell.key, d), false);
                        neighbors[d] = c == null ? null : c.geometry;
                    }
                    cursor = 0;
                    stage = 3;
                }
                break;
            case 3:
                for (int stop = Math.min(512, cursor + 32); cursor < stop; cursor++) {
                    int p = cursor, a = local.labels[p];
                    if (a < 0) {
                        continue;
                    }
                    if (p % 8 < 7) {
                        contact(p, p + 1);
                    }
                    if (p / 8 % 8 < 7) {
                        contact(p, p + 8);
                    }
                    if (p / 64 < 7) {
                        contact(p, p + 64);
                    }
                    BlockPos w = cell.key.block(p);
                    ImmutableCubeSnapshot s = at(w.x, w.y, w.z);
                    int faces = s == null ? 0 : Integer.bitCount(s.atmosphereAt(w.x, w.y, w.z) & state.config.openMask);
                    boundary[a] += faces
                        / (state.config.mode == SmogConfig.COMPONENT ? 4.0 : (local.distance[p] & 255) + .5);
                }
                if (cursor == 512) {
                    // Preserve internal conductances once; they depend only on the
                    // checked world snapshots, never on neighbour publication order.
                    internalEdges.copyFrom(edges);
                    cursor = face = 0;
                    stage = 4;
                }
                break;
            case 4:
                for (int stop = Math.min(64, cursor + 16); cursor < stop; cursor++) {
                    DimensionState.Geometry g = neighbors[face];
                    if (g == null) {
                        continue;
                    }
                    int p = face(face, cursor), q = face(face ^ 1, cursor), a = local.labels[p], b = g.labels[q];
                    if (a >= 0 && b >= 0) {
                        edges.add(a, 512 + g.ids[b], (local.distance[p] & 255) + (g.distance[q] & 255) + 1);
                    }
                }
                if (cursor == 64) {
                    cursor = 0;
                    if (++face == 6) {
                        ready = true;
                        stage = 5;
                    }
                }
                break;
            default:
                break;
        }
    }

    private void contact(int p, int q) {
        int a = local.labels[p], b = local.labels[q];
        if (a >= 0 && b >= 0 && a != b) {
            edges.add(a, b, (local.distance[p] & 255) + (local.distance[q] & 255) + 1);
        }
    }

    ImmutableCubeSnapshot self() {
        for (int i = 0; i < keys.length; i++) {
            if (keys[i].equals(cell.cube.key)) {
                return snapshots[i];
            }
        }
        return null;
    }

    boolean validWorld() {
        if (cell.ticket != ticket || self() == null) {
            return false;
        }
        for (int i = 0; i < keys.length; i++) {
            // The Forge provider compares section revisions without reloading or
            // even retrieving the 16^3 snapshots. Others use the safe fallback.
            if (!state.provider.matchesSnapshot(keys[i], snapshots[i])) {
                return false;
            }
        }
        return true;
    }

    boolean validContacts() {
        boolean valid = true;
        for (int d = 0; d < 6; d++) {
            DimensionState.Cell c = state.cell(neighbor(cell.key, d), false);
            checkedNeighbors[d] = c == null ? null : c.geometry;
            if (checkedNeighbors[d] != neighbors[d]) {
                valid = false;
            }
        }
        return valid;
    }

    void refreshContacts() {
        // Discard stale external edges but retain the exact internal conductances
        // and atmospheric boundary computed from immutable world snapshots.
        edges.copyFrom(internalEdges);
        cursor = face = 0;
        stage = 4;
        ready = false;
        // validContacts just read all six neighbours on the same owner thread.
        // A second lookup here would duplicate the work, without improving safety.
        System.arraycopy(checkedNeighbors, 0, neighbors, 0, neighbors.length);
    }

    private ImmutableCubeSnapshot at(int x, int y, int z) {
        int cx = Math.floorDiv(x, 16), cy = Math.floorDiv(y, 16), cz = Math.floorDiv(z, 16);
        for (int i = 0; i < keys.length; i++) {
            if (keys[i].x == cx && keys[i].y == cy && keys[i].z == cz) {
                return snapshots[i];
            }
        }
        return null;
    }

    static CellKey neighbor(CellKey k, int d) {
        return k.offset(DX[d], DY[d], DZ[d]);
    }

    static int face(int d, int f) {
        int u = f % 8, v = f / 8;
        switch (d) {
            case 0:
                return 8 * u + 64 * v;
            case 1:
                return 7 + 8 * u + 64 * v;
            case 2:
                return u + 64 * v;
            case 3:
                return u + 56 + 64 * v;
            case 4:
                return u + 8 * v;
            default:
                return u + 8 * v + 448;
        }
    }

    static final class Local implements PartitionTarget {

        final SmogConfig config;

        final byte[] halo, distance = new byte[512];

        final int[] labels = new int[512], volumes = new int[512], seeds = new int[512];

        int count;

        Local(SmogConfig config, byte[] halo) {
            this.halo = halo;
            this.config = config;
            Arrays.fill(labels, -1);
        }

        public SmogConfig settings() {
            return config;
        }

        public boolean airAt(int x, int y, int z) {
            return x >= -2 && x < 10
                && y >= -2
                && y < 10
                && z >= -2
                && z < 10
                && halo[(x + 2) + 12 * (y + 2) + 144 * (z + 2)] != 0;
        }

        public int position(int x, int y, int z) {
            return x + 8 * y + 64 * z;
        }

        public int newNode(int seed) {
            seeds[count] = seed;
            return count++;
        }

        public void setVolume(int id, int volume) {
            volumes[id] = volume;
        }

        public void label(int p, int id, int d) {
            labels[p] = id;
            distance[p] = (byte) d;
        }
    }
}

package smog;

import java.util.Arrays;

/**
 * The original partition algorithm as a resumable, container-local state machine.
 * One unit visits one voxel/BFS vertex. Center reductions and array resets are
 * bounded by one container (512 voxels in the sparse API), never the world.
 */
final class Partitioner {

    private static final int FAR = 1_000_000;

    private PartitionTarget graph;

    private final int h, n;

    private final int[] world, component, coreComponent, owner, distance;

    private final int[] queue, members, seeds, ids, neighbors, volumes;

    private final int[] lx, ly, lz;

    private final boolean[] core, innerCore, strictCore;

    private int bx, by, bz, mode, strictCount, number, first, count, seedCount;

    private int head, tail, scan, stage, defaultSeed, candidates, innerSeeds, radius, far;

    private boolean inner, path;

    Partitioner(PartitionTarget graph) {
        this.graph = graph;
        h = graph.settings().containerSize;
        n = h * h * h;
        world = new int[n];
        component = new int[n];
        coreComponent = new int[n];
        owner = new int[n];
        distance = new int[n];
        queue = new int[n];
        members = new int[n];
        seeds = new int[n];
        ids = new int[n];
        volumes = new int[n];
        neighbors = new int[n * 6];
        lx = new int[n];
        ly = new int[n];
        lz = new int[n];
        core = new boolean[n];
        innerCore = new boolean[n];
        strictCore = new boolean[n];
        Arrays.fill(neighbors, -1);
        for (int p = 0; p < n; p++) {
            int x = p % h, y = p / h % h, z = p / (h * h), o = p * 6;
            lx[p] = x;
            ly[p] = y;
            lz[p] = z;
            if (x > 0) {
                neighbors[o] = p - 1;
            }
            if (x + 1 < h) {
                neighbors[o + 1] = p + 1;
            }
            if (y > 0) {
                neighbors[o + 2] = p - h;
            }
            if (y + 1 < h) {
                neighbors[o + 3] = p + h;
            }
            if (z > 0) {
                neighbors[o + 4] = p - h * h;
            }
            if (z + 1 < h) {
                neighbors[o + 5] = p + h * h;
            }
        }
    }

    void retarget(PartitionTarget target) {
        if (target.settings().containerSize != h) {
            throw new IllegalArgumentException("Workspace container size");
        }
        graph = target;
    }

    // Preserve the original bulk fast path for the legacy dense API. The sparse
    // scheduler uses begin/advance below; both paths share the same local arrays,
    // neighborhood/center/core rules and are checked against the C++ fixtures.
    void partition(int bx, int by, int bz) {
        Arrays.fill(world, -1);
        Arrays.fill(component, -1);
        Arrays.fill(coreComponent, -1);
        Arrays.fill(core, false);
        Arrays.fill(innerCore, false);
        Arrays.fill(strictCore, false);
        int mode = graph.settings().mode;
        int strictCount = 0;
        for (int p = 0; p < n; p++) {
            int x = bx + lx[p], y = by + ly[p], z = bz + lz[p];
            if (!graph.airAt(x, y, z)) {
                continue;
            }
            world[p] = graph.position(x, y, z);
            if (mode == SmogConfig.CORE || mode == SmogConfig.THROAT) {
                strictCore[p] = coreAt(x, y, z);
                if (strictCore[p]) {
                    strictCount++;
                }
                core[p] = strictCore[p];
                if (mode == SmogConfig.THROAT && !core[p]) {
                    core[p] = (lx[p] == 0 && coreAt(x - 1, y, z)) || (lx[p] == h - 1 && coreAt(x + 1, y, z))
                        || (ly[p] == 0 && coreAt(x, y - 1, z))
                        || (ly[p] == h - 1 && coreAt(x, y + 1, z))
                        || (lz[p] == 0 && coreAt(x, y, z - 1))
                        || (lz[p] == h - 1 && coreAt(x, y, z + 1));
                }
            }
        }
        int number = 0;
        for (int first = 0; first < n; first++) {
            if (world[first] < 0 || component[first] >= 0) {
                continue;
            }
            int count = floodComponent(first, number);
            System.arraycopy(queue, 0, members, 0, count);
            if (mode == SmogConfig.COMPONENT) {
                int id = graph.newNode(world[center(members, count)]);
                for (int i = 0; i < count; i++) {
                    int p = world[members[i]];
                    graph.label(p, id, 0);
                }
                graph.setVolume(id, count);
                number++;
                continue;
            }
            int seedCount = 0;
            if (mode == SmogConfig.CORE || mode == SmogConfig.THROAT) {
                // First preserve all independent core components from the baseline.
                for (int i = 0; i < count; i++) {
                    int s = members[i];
                    if (core[s] && coreComponent[s] < 0) {
                        seeds[seedCount++] = center(queue, floodCore(s, false));
                    }
                }
                // Refine only when the baseline would merge the whole core through
                // an outer-only bottleneck. Do not fragment already-separated rooms.
                if (mode == SmogConfig.THROAT && seedCount == 1 && !(count == n && strictCount == n)) {
                    // Only evaluate outer cores if the ordinary partitioning would
                    // merge the complete core into one seed. No cost in all-air tiles.
                    int defaultSeed = seeds[0], candidates = 0;
                    for (int i = 0; i < count; i++) {
                        int p = members[i];
                        // promoted border cores are outer
                        if (!strictCore[p]) {
                            continue;
                        }
                        int px = bx + lx[p], py = by + ly[p], pz = bz + lz[p];
                        boolean touchesWeak = false;
                        for (int d = 0; d < 6; d++) {
                            int v = neighbors[p * 6 + d];
                            boolean otherCore;
                            if (v >= 0) {
                                otherCore = strictCore[v];
                            } else {
                                int xx = px + (d == 0 ? -1 : d == 1 ? 1 : 0);
                                int yy = py + (d == 2 ? -1 : d == 3 ? 1 : 0);
                                int zz = pz + (d == 4 ? -1 : d == 5 ? 1 : 0);
                                otherCore = coreAt(xx, yy, zz);
                            }
                            if (!otherCore) {
                                touchesWeak = true;
                                break;
                            }
                        }
                        if (!touchesWeak) {
                            innerCore[p] = true;
                            candidates++;
                        }
                    }
                    if (candidates >= 2) {
                        Arrays.fill(coreComponent, -1);
                        int innerSeedCount = 0;
                        for (int i = 0; i < count; i++) {
                            int s = members[i];
                            if (innerCore[s] && coreComponent[s] < 0) {
                                seeds[innerSeedCount++] = center(queue, floodCore(s, true));
                            }
                        }
                        if (innerSeedCount > 1) {
                            seedCount = innerSeedCount;
                        } else {
                            seedCount = 1;
                            seeds[0] = defaultSeed;
                        }
                    }
                }
            }
            int localRadius = graph.settings().radius;
            if (mode == SmogConfig.THROAT && graph.settings().narrowPathRefinement
                && seedCount == 0
                && isOneCellPath(count)) {
                localRadius = Math.min(localRadius, 3);
            }
            if (seedCount == 0) {
                seeds[seedCount++] = center(members, count);
            }
            while (true) {
                assign(count, seedCount, number);
                int far = members[0];
                for (int i = 0; i < count; i++) {
                    int p = members[i];
                    if (distance[p] > distance[far]) {
                        far = p;
                    }
                }
                if (distance[far] <= localRadius) {
                    break;
                }
                seeds[seedCount++] = far;
            }
            for (int i = 0; i < seedCount; i++) {
                ids[i] = graph.newNode(world[seeds[i]]);
                volumes[i] = 0;
            }
            for (int i = 0; i < count; i++) {
                int p = members[i], id = ids[owner[p]];
                graph.label(world[p], id, distance[p]);
                volumes[owner[p]]++;
            }
            for (int i = 0; i < seedCount; i++) {
                graph.setVolume(ids[i], volumes[i]);
            }
            number++;
        }
    }

    void begin(int x, int y, int z) {
        bx = x;
        by = y;
        bz = z;
        mode = graph.settings().mode;
        Arrays.fill(world, -1);
        Arrays.fill(component, -1);
        Arrays.fill(coreComponent, -1);
        Arrays.fill(core, false);
        Arrays.fill(innerCore, false);
        Arrays.fill(strictCore, false);
        strictCount = number = first = scan = stage = 0;
    }

    boolean advance(int units) {
        while (units-- > 0 && stage != 99) {
            unit();
        }
        return stage == 99;
    }

    private void unit() {
        switch (stage) {
            case 0: {
                int p = scan++, x = bx + lx[p], y = by + ly[p], z = bz + lz[p];
                if (graph.airAt(x, y, z)) {
                    world[p] = graph.position(x, y, z);
                    if (mode == SmogConfig.CORE || mode == SmogConfig.THROAT) {
                        core[p] = strictCore[p] = coreAt(x, y, z);
                        if (strictCore[p]) {
                            strictCount++;
                        }
                        if (mode == SmogConfig.THROAT && !core[p]) {
                            core[p] = (lx[p] == 0 && coreAt(x - 1, y, z)) || (lx[p] == h - 1 && coreAt(x + 1, y, z))
                                || (ly[p] == 0 && coreAt(x, y - 1, z))
                                || (ly[p] == h - 1 && coreAt(x, y + 1, z))
                                || (lz[p] == 0 && coreAt(x, y, z - 1))
                                || (lz[p] == h - 1 && coreAt(x, y, z + 1));
                        }
                    }
                }
                if (scan == n) {
                    stage = 1;
                    first = 0;
                }
                break;
            }
            case 1:
                if (first == n) {
                    stage = 99;
                    break;
                }
                if (world[first] < 0 || component[first] >= 0) {
                    first++;
                    break;
                }
                queue[0] = first;
                component[first] = number;
                head = 0;
                tail = 1;
                stage = 2;
                break;
            case 2:
                if (head < tail) {
                    int o = queue[head++] * 6;
                    for (int k = 0; k < 6; k++) {
                        int v = neighbors[o + k];
                        if (v >= 0 && world[v] >= 0 && component[v] < 0) {
                            component[v] = number;
                            queue[tail++] = v;
                        }
                    }
                } else {
                    count = tail;
                    System.arraycopy(queue, 0, members, 0, count);
                    seedCount = 0;
                    scan = 0;
                    if (mode == SmogConfig.COMPONENT) {
                        ids[0] = graph.newNode(world[center(members, count)]);
                        graph.setVolume(ids[0], count);
                        stage = 3;
                    } else if (mode == SmogConfig.CORE || mode == SmogConfig.THROAT) {
                        stage = 4;
                    } else {
                        stage = 10;
                    }
                }
                break;
            case 3:
                graph.label(world[members[scan++]], ids[0], 0);
                if (scan == count) {
                    nextComponent();
                }
                break;
            case 4:
                if (scan < count) {
                    int p = members[scan++];
                    if (core[p] && coreComponent[p] < 0) {
                        startCore(p, false);
                        stage = 5;
                    }
                } else if (mode == SmogConfig.THROAT && seedCount == 1 && !(count == n && strictCount == n)) {
                    defaultSeed = seeds[0];
                    candidates = 0;
                    scan = 0;
                    stage = 6;
                } else {
                    stage = 10;
                }
                break;
            case 5:
                if (head < tail) {
                    coreUnit();
                } else {
                    seeds[seedCount++] = center(queue, tail);
                    stage = 4;
                }
                break;
            case 6:
                if (scan < count) {
                    int p = members[scan++];
                    if (!strictCore[p]) {
                        break;
                    }
                    int x = bx + lx[p], y = by + ly[p], z = bz + lz[p];
                    boolean weak = false;
                    for (int d = 0; d < 6; d++) {
                        int v = neighbors[p * 6 + d];
                        boolean other = v >= 0 ? strictCore[v]
                            : coreAt(
                                x + (d == 0 ? -1 : d == 1 ? 1 : 0),
                                y + (d == 2 ? -1 : d == 3 ? 1 : 0),
                                z + (d == 4 ? -1 : d == 5 ? 1 : 0));
                        if (!other) {
                            weak = true;
                            break;
                        }
                    }
                    if (!weak) {
                        innerCore[p] = true;
                        candidates++;
                    }
                } else if (candidates >= 2) {
                    Arrays.fill(coreComponent, -1);
                    innerSeeds = scan = 0;
                    stage = 7;
                } else {
                    stage = 10;
                }
                break;
            case 7:
                if (scan < count) {
                    int p = members[scan++];
                    if (innerCore[p] && coreComponent[p] < 0) {
                        startCore(p, true);
                        stage = 8;
                    }
                } else {
                    if (innerSeeds > 1) {
                        seedCount = innerSeeds;
                    } else {
                        seedCount = 1;
                        seeds[0] = defaultSeed;
                    }
                    stage = 10;
                }
                break;
            case 8:
                if (head < tail) {
                    coreUnit();
                } else {
                    seeds[innerSeeds++] = center(queue, tail);
                    stage = 7;
                }
                break;
            case 10:
                radius = graph.settings().radius;
                path = mode == SmogConfig.THROAT && graph.settings().narrowPathRefinement
                    && seedCount == 0
                    && count > 7;
                scan = 0;
                stage = path ? 11 : 12;
                break;
            case 11:
                if (scan < count && path) {
                    int p = members[scan++], o = p * 6;
                    for (int a = 1; a < 6; a += 2) {
                        for (int b = a + 2; b < 6; b += 2) {
                            int u = neighbors[o + a], v = neighbors[o + b];
                            if (u < 0 || v < 0 || world[u] < 0 || world[v] < 0) {
                                continue;
                            }
                            int diag = neighbors[u * 6 + b];
                            if (diag >= 0 && world[diag] >= 0) {
                                path = false;
                            }
                        }
                    }
                } else {
                    if (path) {
                        radius = Math.min(radius, 3);
                    }
                    stage = 12;
                }
                break;
            case 12:
                if (seedCount == 0) {
                    seeds[seedCount++] = center(members, count);
                }
                scan = 0;
                stage = 13;
                break;
            case 13:
                if (scan < count) {
                    int p = members[scan++];
                    owner[p] = -1;
                    distance[p] = FAR;
                } else {
                    head = tail = 0;
                    scan = 0;
                    stage = 14;
                }
                break;
            case 14:
                if (scan < seedCount) {
                    int p = seeds[scan];
                    owner[p] = scan++;
                    distance[p] = 0;
                    queue[tail++] = p;
                } else {
                    stage = 15;
                }
                break;
            case 15:
                if (head < tail) {
                    int p = queue[head++];
                    for (int k = 0; k < 6; k++) {
                        int v = neighbors[p * 6 + k];
                        if (v >= 0 && component[v] == number && owner[v] < 0) {
                            owner[v] = owner[p];
                            distance[v] = distance[p] + 1;
                            queue[tail++] = v;
                        }
                    }
                } else {
                    far = members[0];
                    scan = 0;
                    stage = 16;
                }
                break;
            case 16:
                if (scan < count) {
                    int p = members[scan++];
                    if (distance[p] > distance[far]) {
                        far = p;
                    }
                } else if (distance[far] > radius) {
                    seeds[seedCount++] = far;
                    scan = 0;
                    stage = 13;
                } else {
                    scan = 0;
                    stage = 17;
                }
                break;
            case 17:
                if (scan < seedCount) {
                    ids[scan] = graph.newNode(world[seeds[scan]]);
                    volumes[scan++] = 0;
                } else {
                    scan = 0;
                    stage = 18;
                }
                break;
            case 18:
                if (scan < count) {
                    int p = members[scan++], i = owner[p];
                    graph.label(world[p], ids[i], distance[p]);
                    volumes[i]++;
                } else {
                    scan = 0;
                    stage = 19;
                }
                break;
            case 19:
                if (scan < seedCount) {
                    graph.setVolume(ids[scan], volumes[scan]);
                    scan++;
                } else {
                    nextComponent();
                }
                break;
            default:
                throw new AssertionError(stage);
        }
    }

    private void nextComponent() {
        number++;
        first++;
        stage = 1;
    }

    private void startCore(int p, boolean useInner) {
        inner = useInner;
        queue[0] = p;
        coreComponent[p] = p;
        head = 0;
        tail = 1;
    }

    private void coreUnit() {
        int p = queue[head++], o = p * 6;
        for (int k = 0; k < 6; k++) {
            int v = neighbors[o + k];
            if (v >= 0 && (inner ? innerCore[v] : core[v]) && coreComponent[v] < 0) {
                coreComponent[v] = coreComponent[p];
                queue[tail++] = v;
            }
        }
    }

    // Restrict the refinement to components with no 2x2 air plaquette.
    // A one-cell path may bend/branch; a broad coreless sheet or 2x2 tube is unchanged.
    private boolean isOneCellPath(int count) {
        if (count <= 7) {
            return false;
        }
        for (int i = 0; i < count; i++) {
            int p = members[i], off = p * 6;
            for (int a = 1; a < 6; a += 2) {
                for (int b = a + 2; b < 6; b += 2) {
                    int u = neighbors[off + a], v = neighbors[off + b];
                    if (u < 0 || v < 0 || world[u] < 0 || world[v] < 0) {
                        continue;
                    }
                    int diagonal = neighbors[u * 6 + b];
                    if (diagonal >= 0 && world[diagonal] >= 0) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private int floodComponent(int first, int number) {
        int tail = 1;
        queue[0] = first;
        component[first] = number;
        for (int head = 0; head < tail; head++) {
            int offset = queue[head] * 6;
            for (int k = 0; k < 6; k++) {
                int v = neighbors[offset + k];
                if (v >= 0 && world[v] >= 0 && component[v] < 0) {
                    component[v] = number;
                    queue[tail++] = v;
                }
            }
        }
        return tail;
    }

    private int floodCore(int first, boolean useInner) {
        int tail = 1;
        queue[0] = first;
        coreComponent[first] = first;
        for (int head = 0; head < tail; head++) {
            int offset = queue[head] * 6;
            for (int k = 0; k < 6; k++) {
                int v = neighbors[offset + k];
                if (v >= 0 && (useInner ? innerCore[v] : core[v]) && coreComponent[v] < 0) {
                    coreComponent[v] = first;
                    queue[tail++] = v;
                }
            }
        }
        return tail;
    }

    private void assign(int count, int seedCount, int number) {
        for (int i = 0; i < count; i++) {
            owner[members[i]] = -1;
            distance[members[i]] = FAR;
        }
        int tail = 0;
        for (int i = 0; i < seedCount; i++) {
            int p = seeds[i];
            owner[p] = i;
            distance[p] = 0;
            queue[tail++] = p;
        }
        for (int head = 0; head < tail; head++) {
            int p = queue[head], offset = p * 6;
            for (int k = 0; k < 6; k++) {
                int v = neighbors[offset + k];
                if (v >= 0 && component[v] == number && owner[v] < 0) {
                    owner[v] = owner[p];
                    distance[v] = distance[p] + 1;
                    queue[tail++] = v;
                }
            }
        }
    }

    private int center(int[] points, int size) {
        double x = 0, y = 0, z = 0;
        for (int i = 0; i < size; i++) {
            int p = points[i];
            x += lx[p];
            y += ly[p];
            z += lz[p];
        }
        x /= size;
        y /= size;
        z /= size;
        double best = Double.POSITIVE_INFINITY;
        int result = points[0];
        for (int i = 0; i < size; i++) {
            int p = points[i];
            double dx = lx[p] - x, dy = ly[p] - y, dz = lz[p] - z, d = dx * dx + dy * dy + dz * dz;
            if (d < best) {
                best = d;
                result = p;
            }
        }
        return result;
    }

    private boolean coreAt(int x, int y, int z) {
        return graph.airAt(x, y, z) && graph.airAt(x - 1, y, z)
            && graph.airAt(x + 1, y, z)
            && graph.airAt(x, y - 1, z)
            && graph.airAt(x, y + 1, z)
            && graph.airAt(x, y, z - 1)
            && graph.airAt(x, y, z + 1);
    }

    long bytes() {
        return 79L * n;
    }
}

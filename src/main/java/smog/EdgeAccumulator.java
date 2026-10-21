package smog;

import java.util.Arrays;

/**
 * Primitive unordered pair -> (contact area, minimum path length). Build/patch only.
 */
final class EdgeAccumulator {

    static final int[] EMPTY_RECORDS = new int[0];
    private static final long[] EMPTY_KEYS = new long[0];

    // zero is unused; a < b, so a pair cannot be zero
    private long[] keys = EMPTY_KEYS;

    private int[] areas = EMPTY_RECORDS, distances = EMPTY_RECORDS;

    private int size;

    private long[] ordered = EMPTY_KEYS;

    void clear() {
        Arrays.fill(keys, 0);
        size = 0;
    }

    /** Restore a previously frozen set of internal edges without reallocating on refresh. */
    void copyFrom(EdgeAccumulator source) {
        if (source == this) {
            return;
        }
        if (source.size == 0) { clear(); return; }
        if (keys.length < source.keys.length) {
            keys = new long[source.keys.length];
            areas = new int[source.keys.length];
            distances = new int[source.keys.length];
        }
        if (keys.length == source.keys.length) {
            System.arraycopy(source.keys, 0, keys, 0, keys.length);
            System.arraycopy(source.areas, 0, areas, 0, areas.length);
            System.arraycopy(source.distances, 0, distances, 0, distances.length);
            size = source.size;
            return;
        }
        // Keep the larger table: its hash mask differs from the source's,
        // so each retained key must be rehashed into the destination table.
        Arrays.fill(keys, 0);
        size = 0;
        for (int i = 0; i < source.keys.length; i++) {
            long key = source.keys[i];
            if (key != 0) {
                int slot = slot(key);
                keys[slot] = key;
                areas[slot] = source.areas[i];
                distances[slot] = source.distances[i];
                size++;
            }
        }
    }

    void add(int a, int b, int distance) {
        addAggregate(a, b, 1, distance);
    }

    void addAggregate(int a, int b, int area, int distance) {
        if (a > b) {
            int t = a;
            a = b;
            b = t;
        }
        long key = ((long) a << 32) | (b & 0xffffffffL);
        if (keys.length == 0) {
            keys = new long[32]; areas = new int[32]; distances = new int[32];
        }
        if ((size + 1) * 2 >= keys.length) {
            grow();
        }
        int slot = slot(key);
        if (keys[slot] == 0) {
            keys[slot] = key;
            areas[slot] = area;
            distances[slot] = distance;
            size++;
        } else {
            areas[slot] += area;
            distances[slot] = Math.min(distances[slot], distance);
        }
    }

    int size() {
        return size;
    }

    /** Stable local IDs, area and minimum distance; never serialized conductance/runtime IDs. */
    int[] records() {
        if (size == 0) return EMPTY_RECORDS;
        int[] result = new int[size * 4];
        long[] sorted = new long[size];
        int n = 0;
        for (long key : keys) if (key != 0) sorted[n++] = key;
        Arrays.sort(sorted);
        for (int i = 0; i < size; i++) {
            long key = sorted[i];
            int s = slot(key);
            result[4 * i] = (int) (key >>> 32);
            result[4 * i + 1] = (int) key;
            result[4 * i + 2] = areas[s];
            result[4 * i + 3] = distances[s];
        }
        return result;
    }

    void appendTo(final SmogGraph graph) {
        appendTo(graph.config, new Consumer() {

            public void edge(int a, int b, double g) {
                graph.addEdge(a, b, g);
            }
        });
    }

    interface Consumer {

        void edge(int a, int b, double conductance);
    }

    void appendTo(SmogConfig config, Consumer consumer) {
        if (ordered.length < size) {
            ordered = new long[size];
        }
        int count = 0;
        for (long key : keys) {
            if (key != 0) {
                ordered[count++] = key;
            }
        }
        Arrays.sort(ordered, 0, count);
        for (int i = 0; i < count; i++) {
            long key = ordered[i];
            int slot = slot(key);
            double length = config.mode == SmogConfig.COMPONENT ? config.containerSize : Math.max(1, distances[slot]);
            consumer.edge((int) (key >>> 32), (int) key, areas[slot] / length);
        }
    }

    private int slot(long key) {
        int slot = hash(key) & (keys.length - 1);
        while (keys[slot] != 0 && keys[slot] != key) {
            slot = (slot + 1) & (keys.length - 1);
        }
        return slot;
    }

    static int hash(long key) {
        key ^= key >>> 33;
        key *= 0xff51afd7ed558ccdL;
        key ^= key >>> 33;
        key *= 0xc4ceb9fe1a85ec53L;
        return (int) (key ^ (key >>> 33));
    }

    private void grow() {
        if (keys.length >= 1 << 29) {
            throw new IllegalStateException("Too many graph edges");
        }
        long[] old = keys;
        int[] oldAreas = areas, oldDistances = distances;
        keys = new long[old.length * 2];
        areas = new int[keys.length];
        distances = new int[keys.length];
        for (int i = 0; i < old.length; i++) {
            if (old[i] != 0) {
                int slot = slot(old[i]);
                keys[slot] = old[i];
                areas[slot] = oldAreas[i];
                distances[slot] = oldDistances[i];
            }
        }
    }

    long bytes() {
        return 16L * keys.length + 8L * ordered.length;
    }
}

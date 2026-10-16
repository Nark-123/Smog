package smog;

import java.util.Arrays;

/**
 * Primitive unordered pair -> (contact area, minimum path length). Build/patch only.
 */
final class EdgeAccumulator {

    // zero is unused; a < b, so a pair cannot be zero
    private long[] keys = new long[32];

    private int[] areas = new int[32], distances = new int[32];

    private int size;

    private long[] ordered = new long[16];

    void clear() {
        Arrays.fill(keys, 0);
        size = 0;
    }

    void add(int a, int b, int distance) {
        if (a > b) {
            int t = a;
            a = b;
            b = t;
        }
        long key = ((long) a << 32) | (b & 0xffffffffL);
        if ((size + 1) * 2 >= keys.length) {
            grow();
        }
        int slot = slot(key);
        if (keys[slot] == 0) {
            keys[slot] = key;
            areas[slot] = 1;
            distances[slot] = distance;
            size++;
        } else {
            areas[slot]++;
            distances[slot] = Math.min(distances[slot], distance);
        }
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

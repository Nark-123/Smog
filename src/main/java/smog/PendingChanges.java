package smog;

import java.util.Arrays;

/**
 * Primitive FIFO, last write wins. Updating a key preserves its first enqueue age.
 */
final class PendingChanges {

    private int[] keys = new int[32], next = new int[32], previous = new int[32];

    private byte[] values = new byte[32];

    private long[] ticks = new long[32];

    private int occupied, head = -1, tail = -1;

    int size;

    void clear() {
        Arrays.fill(keys, 0);
        size = occupied = 0;
        head = tail = -1;
    }

    void put(int position, byte value) {
        put(position, value, 0);
    }

    void put(int position, byte value, long tick) {
        int key = position + 1, slot = find(key);
        if (keys[slot] == key) {
            values[slot] = value == 0 ? (byte) 0 : (byte) 1;
            return;
        }
        if ((occupied + 1) * 2 >= keys.length) {
            grow();
            slot = find(key);
        }
        if (keys[slot] == 0) {
            occupied++;
        }
        keys[slot] = key;
        values[slot] = value == 0 ? (byte) 0 : (byte) 1;
        ticks[slot] = tick;
        previous[slot] = tail;
        next[slot] = -1;
        if (tail >= 0) {
            next[tail] = slot;
        } else {
            head = slot;
        }
        tail = slot;
        size++;
    }

    void remove(int position) {
        int s = find(position + 1);
        if (keys[s] == position + 1) {
            removeSlot(s);
        }
    }

    private void removeSlot(int slot) {
        int p = previous[slot], n = next[slot];
        if (p < 0) {
            head = n;
        } else {
            next[p] = n;
        }
        if (n < 0) {
            tail = p;
        } else {
            previous[n] = p;
        }
        keys[slot] = -1;
        size--;
        if (size == 0) {
            clear();
        }
    }

    int take(int limit, int[] positions, byte[] output) {
        int count = 0;
        while (head >= 0 && count < limit) {
            int s = head;
            positions[count] = keys[s] - 1;
            output[count++] = values[s];
            removeSlot(s);
        }
        return count;
    }

    boolean contains(int p) {
        return keys[find(p + 1)] == p + 1;
    }

    byte value(int p, byte fallback) {
        int s = find(p + 1);
        return keys[s] == p + 1 ? values[s] : fallback;
    }

    void copy(int[] positions, byte[] output) {
        int count = 0;
        for (int s = head; s >= 0; s = next[s]) {
            positions[count] = keys[s] - 1;
            output[count++] = values[s];
        }
    }

    long oldestAge(long now) {
        return head < 0 ? 0 : now - ticks[head];
    }

    int overdue(long now, long age) {
        int result = 0;
        for (int s = head; s >= 0; s = next[s]) {
            if (now - ticks[s] > age) {
                result++;
            }
        }
        return result;
    }

    private int find(int key) {
        int mask = keys.length - 1, s = EdgeAccumulator.hash(key) & mask, hole = -1;
        while (keys[s] != 0 && keys[s] != key) {
            if (keys[s] < 0 && hole < 0) {
                hole = s;
            }
            s = (s + 1) & mask;
        }
        return keys[s] == key || hole < 0 ? s : hole;
    }

    private void grow() {
        int[] old = keys, oldNext = next;
        byte[] oldValues = values;
        long[] oldTicks = ticks;
        int first = head, capacity = size * 4 < keys.length ? keys.length : Math.multiplyExact(keys.length, 2);
        keys = new int[capacity];
        values = new byte[capacity];
        next = new int[capacity];
        previous = new int[capacity];
        ticks = new long[capacity];
        size = occupied = 0;
        head = tail = -1;
        for (int s = first; s >= 0; s = oldNext[s]) {
            put(old[s] - 1, oldValues[s], oldTicks[s]);
        }
    }

    long bytes() {
        return 21L * keys.length;
    }
}

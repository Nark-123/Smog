package smog;

import java.util.Arrays;

/**
 * Sparse primitive voxel -> mass ledger. Backshift deletion avoids tombstone growth.
 */
final class TrappedMass {

    // voxel index + 1, zero means unused
    private int[] keys = new int[16];

    private double[] values = new double[16];

    private int size;

    void clear() {
        Arrays.fill(keys, 0);
        Arrays.fill(values, 0);
        size = 0;
    }

    void add(int voxel, double mass) {
        if ((size + 1) * 2 >= keys.length) {
            grow();
        }
        int key = voxel + 1, slot = slot(key);
        if (keys[slot] == 0) {
            keys[slot] = key;
            values[slot] = mass;
            size++;
        } else {
            values[slot] += mass;
        }
    }

    double take(int voxel) {
        if (size == 0) {
            return 0;
        }
        int slot = slot(voxel + 1);
        if (keys[slot] == 0) {
            return 0;
        }
        double mass = values[slot];
        int hole = slot, mask = keys.length - 1;
        for (int scan = (hole + 1) & mask; keys[scan] != 0; scan = (scan + 1) & mask) {
            int home = EdgeAccumulator.hash(keys[scan]) & mask;
            if (((scan - home) & mask) >= ((scan - hole) & mask)) {
                keys[hole] = keys[scan];
                values[hole] = values[scan];
                hole = scan;
            }
        }
        keys[hole] = 0;
        values[hole] = 0;
        size--;
        return mass;
    }

    double total() {
        double sum = 0;
        for (int i = 0; i < keys.length; i++) {
            if (keys[i] != 0) {
                sum += values[i];
            }
        }
        return sum;
    }

    private int slot(int key) {
        int slot = EdgeAccumulator.hash(key) & (keys.length - 1);
        while (keys[slot] != 0 && keys[slot] != key) {
            slot = (slot + 1) & (keys.length - 1);
        }
        return slot;
    }

    private void grow() {
        int[] old = keys;
        double[] oldValues = values;
        keys = new int[old.length * 2];
        values = new double[keys.length];
        for (int i = 0; i < old.length; i++) {
            if (old[i] != 0) {
                int slot = slot(old[i]);
                keys[slot] = old[i];
                values[slot] = oldValues[i];
            }
        }
    }

    long bytes() {
        return 12L * keys.length;
    }
}

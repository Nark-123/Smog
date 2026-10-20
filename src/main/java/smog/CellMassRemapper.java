package smog;

import java.util.Arrays;

/** Owner-thread workspace for conservative local mass remapping, never an active graph. */
final class CellMassRemapper {

    // There can be at most 512 distinct (old local node, new local node) overlaps.
    private final int[] slots = new int[1024];
    private final int[] oldNodes = new int[512], targets = new int[512];
    private final int[] counts = new int[512], next = new int[512], heads = new int[512];
    private final int[] survivors = new int[512], representative = new int[512];
    private final double[] totals = new double[512], correction = new double[512];

    double[] remap(DimensionState.MassRecord old, int[] labels, int nodeCount) {
        return remap(old, VoxelLabels.of(labels), nodeCount);
    }

    double[] remap(DimensionState.MassRecord old, VoxelLabels labels, int nodeCount) {
        Arrays.fill(slots, -1);
        Arrays.fill(heads, 0, old.masses.length, -1);
        Arrays.fill(survivors, 0, old.masses.length, 0);
        Arrays.fill(representative, 0, old.masses.length, 0);
        Arrays.fill(totals, 0, nodeCount, 0);
        Arrays.fill(correction, 0, nodeCount, 0);
        int pairs = 0;
        for (int p = 0; p < 512; p++) {
            int a = old.labelAt(p), b = labels.get(p);
            if (a < 0) {
                continue;
            }
            representative[a] = p;
            if (b < 0) {
                continue;
            }
            survivors[a]++;
            long key = ((long) a << 32) | (b & 0xffffffffL);
            int slot = EdgeAccumulator.hash(key) & (slots.length - 1);
            while (slots[slot] >= 0 && (oldNodes[slots[slot]] != a || targets[slots[slot]] != b)) {
                slot = (slot + 1) & (slots.length - 1);
            }
            int entry = slots[slot];
            if (entry < 0) {
                entry = pairs++;
                slots[slot] = entry;
                oldNodes[entry] = a;
                targets[entry] = b;
                counts[entry] = 0;
                next[entry] = heads[a];
                heads[a] = entry;
            }
            counts[entry]++;
        }
        for (int a = 0; a < old.masses.length; a++) {
            double remaining = old.masses[a];
            for (int entry = heads[a]; entry >= 0; entry = next[entry]) {
                // The last destination receives the representable residual. No epsilon
                // test is allowed here: several small contributions may form a large node.
                double amount = next[entry] < 0 ? remaining
                    : Math.min(remaining, old.masses[a] * ((double) counts[entry] / survivors[a]));
                remaining -= amount;
                int target = targets[entry];
                double value = amount - correction[target];
                double sum = totals[target] + value;
                correction[target] = (sum - totals[target]) - value;
                totals[target] = sum;
            }
        }
        return totals;
    }

    boolean trapped(int oldNode) {
        return survivors[oldNode] == 0;
    }

    int representative(int oldNode) {
        return representative[oldNode];
    }
}

package smog;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Arrays;
import java.util.Random;

/** Independent high-precision per-voxel oracle for the sparse overlap implementation. */
public final class CellMassRemapperTests {

    public static void main(String[] args) {
        CellMassRemapper remapper = new CellMassRemapper();
        Random random = new Random(1411);
        MathContext precision = new MathContext(50);
        int checked = 0;
        for (int trial = 0; trial < 220; trial++) {
            int oldCount = trial == 0 ? 512 : 1 + random.nextInt(64);
            int newCount = trial == 0 ? 512 : 1 + random.nextInt(64);
            int[] oldLabels = new int[512], labels = new int[512], volumes = new int[oldCount];
            int[] surviving = new int[oldCount];
            double[] mass = new double[oldCount];
            for (int a = 0; a < oldCount; a++) mass[a] = (1 + random.nextInt(10000)) / 100.0;
            for (int p = 0; p < 512; p++) {
                int a = p % oldCount;
                oldLabels[p] = a;
                volumes[a]++;
                labels[p] = trial == 0 ? (511 - p) : random.nextInt(9) == 0 ? -1 : random.nextInt(newCount);
                if (labels[p] >= 0) surviving[a]++;
            }
            double[] got = remapper.remap(new DimensionState.MassRecord(oldLabels, volumes, mass), labels, newCount);
            BigDecimal[] expected = new BigDecimal[newCount];
            Arrays.fill(expected, BigDecimal.ZERO);
            for (int p = 0; p < 512; p++) if (labels[p] >= 0) {
                int a = oldLabels[p];
                expected[labels[p]] = expected[labels[p]].add(
                    BigDecimal.valueOf(mass[a])
                        .divide(BigDecimal.valueOf(surviving[a]), precision),
                    precision);
            }
            for (int a = 0; a < oldCount; a++) {
                if (remapper.trapped(a) != (surviving[a] == 0)) throw new AssertionError("trapped classification");
            }
            for (int b = 0; b < newCount; b++) {
                double reference = expected[b].doubleValue();
                if (got[b] < 0 || Math.abs(got[b] - reference) > 5e-13 * Math.max(1, reference))
                    throw new AssertionError("oracle mismatch: " + got[b] + " != " + reference);
                checked++;
            }
        }
        System.out.println("SMOG_MASS_REMAP_ORACLE_PASS checked=" + checked + " trials=220");
    }
}

package smog;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/** 3000 columns x 16 Cube16 x 8 Cell8, with >390 MiB v1-equivalent memberships. */
public final class LargeCheckpointTests {

    public static void main(String[] args) throws Exception {
        int count = args.length == 0 ? 24000 : Integer.parseInt(args[0]);
        if (count < 1 || count > 384000 || count % 8 != 0) throw new IllegalArgumentException();
        long start = System.nanoTime();
        Checkpoint.Data d = new Checkpoint.Data();
        d.config = SmogConfig.throat8();
        d.parts = 20;
        int[] labels = new int[512];
        DimensionState.MassRecord mass = new DimensionState.MassRecord(labels, new int[] { 512 }, new double[] { 10 });
        for (int i = 0; i < count; i++) {
            int cube = i / 8, slot = i % 8;
            Checkpoint.CellData c = new Checkpoint.CellData();
            c.key = new CellKey(0, (cube / 16) * 2 + (slot & 1), (cube % 16) * 2 + ((slot >> 1) & 1), (slot >> 2) & 1);
            c.mass = mass;
            d.cells.add(c);
            d.injected += 10;
        }
        Path dir = Files.createTempDirectory(Paths.get("build"), "large-checkpoint-");
        long writeStart = System.nanoTime();
        long gen = Checkpoint.write(dir, d, 0);
        long write = System.nanoTime() - writeStart;
        d = null;
        System.gc();
        long readStart = System.nanoTime();
        Checkpoint.Loaded loaded = Checkpoint.read(dir);
        long read = System.nanoTime() - readStart;
        if (gen != 1 || loaded.data.cells.size() != count || loaded.data.formatVersion != 2)
            throw new AssertionError("v2 count/version");
        Checkpoint.validateLedger(loaded.data);
        long membership = 0;
        for (Checkpoint.CellData c : loaded.data.cells) membership += c.mass.bytes();
        long logical = 256L + count * (25L + 1028 + 10);
        if (count == 384000 && logical <= 256L * 1048576) throw new AssertionError("not over v1 limit");
        // Generation 2, then damage its payload; generation 1 must remain usable.
        long secondStart = System.nanoTime();
        Checkpoint.write(dir, loaded.data, 1);
        long second = System.nanoTime() - secondStart;
        try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(
            dir.resolve("generation-2.smog")
                .toFile(),
            "rw")) {
            file.seek(file.length() - 1);
            int value = file.read();
            file.seek(file.length() - 1);
            file.write(value ^ 1);
        }
        loaded = null;
        System.gc();
        Checkpoint.Loaded recovered = Checkpoint.read(dir);
        if (recovered.data.cells.size() != count || recovered.generation != 2)
            throw new AssertionError("previous-generation recovery");
        System.out.printf(
            java.util.Locale.ROOT,
            "{\"cells\":%d,\"logical_v1_equivalent_bytes\":%d,\"file_bytes\":%d,\"compact_mass_estimate_bytes\":%d,\"write_ms\":%.3f,\"read_ms\":%.3f,\"second_write_ms\":%.3f,\"total_ms\":%.3f,\"format\":2,\"previous_recovery\":true}%n",
            count,
            logical,
            Files.size(dir.resolve("generation-1.smog")),
            membership,
            write / 1e6,
            read / 1e6,
            second / 1e6,
            (System.nanoTime() - start) / 1e6);
    }
}

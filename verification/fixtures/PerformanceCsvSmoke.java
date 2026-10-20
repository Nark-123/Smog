package org.smogmod.world;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/** Self-contained CSV writer smoke test; does not require Forge/GTNH dependencies. */
public final class PerformanceCsvSmoke {
    public static void main(String[] args) throws Exception {
        Files.createDirectories(Paths.get("build"));
        Path dir = Files.createTempDirectory(Paths.get("build"), "performance-v2-");
        PerformanceFileLog log = new PerformanceFileLog(dir.resolve("perf-dimension_0.log"));
        if (!log.csvPath().endsWith("_safe_v2.csv")) throw new AssertionError("Incorrect CSV schema filename");
        String header = "timestamp_utc,schema_version,restart_cells_total";
        String row = "2026-10-20T00:00:00Z,2,32";
        if (!log.offerCsv(header, row)) throw new AssertionError("Log request rejected");
        Path csv = Paths.get(log.csvPath());
        long deadline = System.nanoTime() + 3_000_000_000L;
        while (log.written() < 1 && log.errors() == 0 && System.nanoTime() < deadline) Thread.sleep(10);
        if (log.errors() != 0 || log.written() < 1 || !Files.exists(csv)) {
            throw new AssertionError("Asynchronous CSV writer: " + log.lastError());
        }
        List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
        if (lines.size() != 2 || !header.equals(lines.get(0)) || !row.equals(lines.get(1))) {
            throw new AssertionError("Incorrect CSV header/data");
        }
        System.out.println("SMOG_PERFORMANCE_CSV_V2_PASS");
    }
}

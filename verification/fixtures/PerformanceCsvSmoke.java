package org.smogmod.world;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Uses the production asynchronous file writer; verifies CSV header and append semantics. */
public final class PerformanceCsvSmoke {
    private static void check(boolean ok, String why) {
        if (!ok) {
            throw new AssertionError(why);
        }
    }

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("smog-perf-csv-");
        PerformanceFileLog log = new PerformanceFileLog(dir.resolve("perf-dimension_0.log"));
        check(log.csvPath().endsWith("perf-dimension_0_safe_v1.csv"), "CSV filename");
        check(log.offerCsv("time,dim,safe_publish_ms", "t1,0,1.234"), "Queue first CSV");
        check(log.offerCsv("time,dim,safe_publish_ms", "t2,0,2.345"), "Queue second CSV");
        check(log.offer("text log"), "Queue legacy log");
        Path csv = dir.resolve("perf-dimension_0_safe_v1.csv");
        long deadline = System.nanoTime() + 5000000000L;
        while ((log.written() < 3 || !Files.exists(csv)) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        check(log.written() == 3 && log.errors() == 0, "Writer did not finish");
        List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
        check(lines.size() == 3, "Expected header and two rows, got " + lines.size());
        check(lines.get(0).equals("time,dim,safe_publish_ms"), "Incorrect header");
        check(lines.get(1).equals("t1,0,1.234")
                && lines.get(2).equals("t2,0,2.345"), "Incorrect CSV data");
        check(Files.readAllLines(dir.resolve("perf-dimension_0.log"),
                StandardCharsets.UTF_8).size() == 1, "Legacy text log disappeared");
        System.out.println("SMOG_PERFORMANCE_CSV_PASS");
    }
}

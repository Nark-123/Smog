package org.smogmod.world;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Diagnostic-only, best-effort logfile independent of the Forge / Log4j appenders.
 *
 * Formatting and enqueueing happen on the world thread. Directory creation and disk I/O are
 * deliberately confined to one shared, daemon background writer with a bounded queue.
 * The writer never accesses live Minecraft or SMOG simulation state.
 */
final class PerformanceFileLog {

    private static final ThreadPoolExecutor WRITER = new ThreadPoolExecutor(
        0,
        1,
        30L,
        TimeUnit.SECONDS,
        new ArrayBlockingQueue<Runnable>(128),
        new ThreadFactory() {

            @Override
            public Thread newThread(Runnable action) {
                Thread thread = new Thread(action, "SMOG-perf-file-writer");
                thread.setDaemon(true);
                return thread;
            }
        },
        new ThreadPoolExecutor.AbortPolicy());

    private final Path path;
    private final Path csvPath;
    private final AtomicLong queued = new AtomicLong();
    private final AtomicLong written = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private final AtomicReference<String> lastError = new AtomicReference<>("");

    PerformanceFileLog(Path path) {
        this.path = path;
        String filename = path.getFileName()
            .toString();
        String stem = filename.endsWith(".log") ? filename.substring(0, filename.length() - 4) : filename;
        csvPath = path.resolveSibling(stem + "_epoch_v4.csv");
    }

    String path() {
        return path.toString();
    }

    String csvPath() {
        return csvPath.toString();
    }

    long queued() {
        return queued.get();
    }

    long written() {
        return written.get();
    }

    long rejected() {
        return rejected.get();
    }

    long errors() {
        return errors.get();
    }

    String lastError() {
        return lastError.get();
    }

    /** A file is appended only when its exact header matches, including future schema changes. */
    boolean offerCsv(final String header, final String row) {
        try {
            WRITER.execute(new Runnable() {

                @Override
                public void run() {
                    try {
                        Files.createDirectories(csvPath.getParent());
                        boolean writeHeader = !Files.exists(csvPath) || Files.size(csvPath) == 0;
                        if (!writeHeader) {
                            try (java.io.BufferedReader reader = Files.newBufferedReader(csvPath, StandardCharsets.UTF_8)) {
                                if (!header.equals(reader.readLine()))
                                    throw new IOException("CSV header mismatch: refusing mixed performance schemas");
                            }
                        }
                        String lines = (writeHeader ? header + "\n" : "") + row + "\n";
                        Files.write(
                            csvPath,
                            lines.getBytes(StandardCharsets.UTF_8),
                            StandardOpenOption.CREATE,
                            StandardOpenOption.APPEND);
                        written.incrementAndGet();
                    } catch (IOException | SecurityException exception) {
                        errors.incrementAndGet();
                        lastError.set(
                            exception.getClass()
                                .getSimpleName() + ": "
                                + exception.getMessage());
                    }
                }
            });
            queued.incrementAndGet();
            return true;
        } catch (RejectedExecutionException exception) {
            rejected.incrementAndGet();
            lastError.set("Asynchronous performance log queue full");
            return false;
        }
    }

    /** Returns false if the bounded asynchronous queue is full. Never waits for filesystem I/O. */
    boolean offer(String message) {
        final String line = Instant.now()
            .toString() + " "
            + message
            + System.lineSeparator();
        try {
            WRITER.execute(new Runnable() {

                @Override
                public void run() {
                    try {
                        Files.createDirectories(path.getParent());
                        Files.write(
                            path,
                            line.getBytes(StandardCharsets.UTF_8),
                            StandardOpenOption.CREATE,
                            StandardOpenOption.APPEND);
                        written.incrementAndGet();
                    } catch (IOException | SecurityException exception) {
                        errors.incrementAndGet();
                        lastError.set(
                            exception.getClass()
                                .getSimpleName() + ": "
                                + exception.getMessage());
                    }
                }
            });
            queued.incrementAndGet();
            return true;
        } catch (RejectedExecutionException exception) {
            rejected.incrementAndGet();
            lastError.set("Asynchronous performance log queue full");
            return false;
        }
    }
}

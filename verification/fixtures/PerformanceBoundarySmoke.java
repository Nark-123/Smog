package org.smogmod.diagnostics;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;

import org.smogmod.commands.SmogCommand;
import org.smogmod.world.WorldRuntime;

import smog.CellKey;
import smog.CubeKey;
import smog.GeometryMetrics;

/** Compiles and executes the real command, provider and runtime with Minecraft boundary doubles. */
public final class PerformanceBoundarySmoke {

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    static final class Sender implements ICommandSender {
        final WorldServer world;
        final List<String> messages = new ArrayList<>();

        Sender(WorldServer world) {
            this.world = world;
        }

        public WorldServer getEntityWorld() {
            return world;
        }

        public void addChatMessage(ChatComponentText text) {
            messages.add(text.text);
        }
    }

    private static String waitForContent(File file, String needle, long maxWaitMs)
            throws Exception {
        long until = System.nanoTime() + maxWaitMs * 1000000L;
        String contents = "";
        while (System.nanoTime() < until) {
            if (file.isFile()) {
                contents = new String(Files.readAllBytes(file.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8);
                if (contents.contains(needle)) {
                    return contents;
                }
            }
            Thread.sleep(10);
        }
        throw new AssertionError("No '" + needle + "' in " + file + ": " + contents);
    }

    private static int count(String text, String needle) {
        int n = 0;
        int from = 0;
        while ((from = text.indexOf(needle, from)) >= 0) {
            n++;
            from += needle.length();
        }
        return n;
    }

    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("smog-perf-boundary-").toFile();
        WorldServer world = new WorldServer(182, dir);
        Chunk chunk = new Chunk(0, 0);
        world.chunks.loadedChunks.add(chunk);
        WorldRuntime runtime = new WorldRuntime(world);
        org.smogmod.world.Worlds.current = runtime;
        runtime.load(chunk);

        CubeKey key = new CubeKey(182, 0, 0, 0);
        runtime.provider.snapshotIfReady(key);
        runtime.provider.snapshotIfReady(key);
        check(runtime.provider.diagnostics().misses == 1
                && runtime.provider.diagnostics().hits == 1, "Cache hit/miss not counted");
        runtime.changed(2, 8, 3);
        check(runtime.provider.diagnostics().invalidations > 0
                && runtime.provider.diagnostics().evictedSnapshots >= 1, "Invalidate not recorded");
        runtime.provider.snapshotIfReady(key);
        check(runtime.provider.diagnostics().misses == 2, "Cache rebuild not recorded");
        SmogCommand command = new SmogCommand();
        Sender sender = new Sender(world);
        command.processCommand(sender, new String[] { "sample", "0", "0", "0" });
        check(sender.messages.toString().contains("concentration=NOT_READY"),
                "Unbuilt cell sample incorrectly reported clean air");
        runtime.state.requestBuild(new CellKey(182, 0, 0, 0));
        for (int i = 0; runtime.state.geometryPending() && i < 100000; i++) {
            runtime.state.geometry(300000L);
        }
        check(!runtime.state.geometryPending(), "Geometry did not drain");
        check(runtime.state.geometryMetrics.publishCalls == 1, "Publication not recorded");
        check(runtime.state.geometryMetrics.phaseCalls[GeometryMetrics.PARTITION] > 0,
                "Partition phase not recorded");

        command.processCommand(sender, new String[] { "sample", "0", "0", "0" });
        check(sender.messages.get(sender.messages.size() - 1).contains("concentration=0.0")
                && sender.messages.get(sender.messages.size() - 1).contains("status=PUBLISHED"),
                "Published sample did not expose ready status");
        double massBefore = runtime.state.liveMass() + runtime.state.parkedMass();
        int cacheBefore = runtime.provider.cachedSnapshotCount();
        command.processCommand(sender, new String[] { "perf" });
        check(sender.messages.size() >= 10, "Missing perf report lines");
        check(sender.messages.toString().contains("buildPhasesMs")
                && sender.messages.toString().contains("cubeRecords")
                && sender.messages.toString().contains("modelLagSeconds")
                && sender.messages.toString().contains("geometryExit")
                && sender.messages.toString().contains("safeWindows")
                && sender.messages.toString().contains("publishedAge")
                && sender.messages.toString().contains("preparedLimit")
                && sender.messages.toString().contains("perfLog attempts/queued/written"),
                "Missing essential performance counters");
        command.processCommand(sender, new String[] { "perf", "reset" });
        check(runtime.state.geometryMetrics.publishCalls == 0
                && runtime.provider.diagnostics().misses == 0, "Reset failed");
        check(runtime.provider.cachedSnapshotCount() == cacheBefore, "Reset destroyed cache");
        check(runtime.state.liveMass() + runtime.state.parkedMass() == massBefore,
                "Reset modified simulation mass");
        command.processCommand(sender, new String[] { "perf", "log", "on" });
        for (int tick = 1; tick <= 25; tick++) {
            world.time = tick;
            runtime.tick();
        }
        // The FML INFO channel is intentionally suppressed in this fake runtime. The
        // independent file sink must still contain the periodic samples.
        check(cpw.mods.fml.common.FMLLog.lines.isEmpty(),
                "Boundary logger unexpectedly accepted disabled INFO");
        String fileName = "data/smog/diagnostics/perf-dimension_182.log";
        File diagnosticFile = new File(dir, fileName);
        String contents = waitForContent(diagnosticFile, "SMOG-PERF SAMPLE", 3000);
        check(contents.contains("SMOG-PERF EVENT enabled")
                && contents.contains("periodTicks=20")
                && contents.contains("geomExit(")
                && contents.contains("lawParts(")
                && contents.contains("safeWindows(")
                && contents.contains("windowPublications=")
                && contents.contains("validationMs(")
                && contents.contains("contactsRefreshMs(")
                && contents.contains("agePublishedTicks("),
                "Dedicated logfile lacks periodic scheduler data");
        command.processCommand(sender, new String[] { "perf", "log", "test" });
        check(cpw.mods.fml.common.FMLLog.lines.size() == 1
                && cpw.mods.fml.common.FMLLog.lines.get(0).contains("SMOG-PERF TEST"),
                "Immediate log test did not reach Forge warning channel");
        check(waitForContent(diagnosticFile, "SMOG-PERF TEST", 3000)
                .contains("expectedFile="), "Log test not written independently");
        command.processCommand(sender, new String[] { "perf", "log", "off" });
        for (int tick = 26; tick <= 50; tick++) {
            world.time = tick;
            runtime.tick();
        }
        String stopped = waitForContent(diagnosticFile, "SMOG-PERF EVENT disabled", 3000);
        check(count(stopped, "SMOG-PERF SAMPLE") == 1,
                "Log-off still emits periodic samples");
        check(runtime.performanceLogStatus().contains("/0 lastAttemptTick="),
                "Unexpected diagnostic file errors");
        check(runtime.state.geometryMetrics.geometryCalls > 0,
                "No counters after reset and subsequent ticks");
        runtime.close();
        System.out.println("SMOG_PERF_BOUNDARY_PASS");
    }
}

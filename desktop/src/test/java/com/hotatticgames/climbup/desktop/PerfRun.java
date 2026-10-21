package com.hotatticgames.climbup.desktop;

import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.graphics.GL20;
import com.hotatticgames.climbup.ClimbGame;
import com.hotatticgames.climbup.PerfProbe;
import com.hotatticgames.climbup.PlayScreen;
import com.hotatticgames.climbup.desktop.Fakes.FakeSources;
import com.hotatticgames.climbup.sim.Sim;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Performance audit harness (Build 71 frame-pacing audit): the REAL game (ClimbGame + PlayScreen + WorldRenderer + HUD) with the autopilot climbing a
 * seeded tower, every frame logged: the frame interval (what a player sees, including buffer swap and GPU work), the CPU time of each stage
 * (course streaming, simulation steps, world render submission, HUD) from {@link PerfProbe}, GC pauses, bytes allocated on the render thread,
 * sim steps, events and the climber's mode. Optional forced falls (real respawn path) and restarts (real new-run path).
 *
 *   xvfb-run -a -s "-screen 0 1920x1080x24" ./gradlew :desktop:perfRun -Dperf.seconds=600 -Dperf.cap=60
 *
 * Properties: perf.seconds, perf.w / perf.h (1280 x 720), perf.cap (frame cap, 0 = uncapped), perf.vsync, perf.seed (7), perf.fallEvery (s, 0 = none),
 * perf.restartEvery (s, 0 = none), perf.finish (glFinish after each frame so the stage times include the GPU), perf.out (CSV), perf.tag.
 */
public final class PerfRun implements ApplicationListener {
    private static final int SECONDS = Integer.getInteger("perf.seconds", 300), W = Integer.getInteger("perf.w", 1280), H = Integer.getInteger("perf.h", 720);
    private static final int CAP = Integer.getInteger("perf.cap", 0), FALL_EVERY = Integer.getInteger("perf.fallEvery", 0), RESTART_EVERY = Integer.getInteger("perf.restartEvery", 0);
    private static final boolean FINISH = Boolean.getBoolean("perf.finish");
    private static final long SEED = Long.getLong("perf.seed", 7L);
    private static final String OUT = System.getProperty("perf.out", "build/perf/perf.csv"), TAG = System.getProperty("perf.tag", "run");
    private static final float WARMUP = 10f;

    private final File data;
    private final FakeSources src = new FakeSources();
    private ClimbGame game;
    private long last, startNs, allocLast; private long gcCountLast, gcTimeLast;
    private float clock, nextFall, nextRestart, nextMem;
    private int restarts, falls, skips;
    private double bestY = -1e9; private float lastProgress;
    private final com.sun.management.ThreadMXBean tmx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    private final List<double[]> rows = new ArrayList<>();
    private final List<String> memLog = new ArrayList<>();
    private final List<String> restartLog = new ArrayList<>();
    private boolean restartFrame;

    private PerfRun(File data) { this.data = data; }

    public static void main(String[] args) {
        System.setProperty("climb.autopilot", "true"); System.setProperty("climb.perf", "true"); System.setProperty("climb.seed", Long.toString(SEED));
        File data = new File("build/perf/data"); deleteTree(data); data.mkdirs(); new File(OUT).getAbsoluteFile().getParentFile().mkdirs();
        Lwjgl3ApplicationConfiguration c = new Lwjgl3ApplicationConfiguration();
        c.setTitle("Upwardly perf"); c.setWindowedMode(W, H); c.useVsync(Boolean.getBoolean("perf.vsync")); c.setForegroundFPS(CAP); c.setIdleFPS(CAP == 0 ? 0 : CAP); c.disableAudio(true);
        PerfRun r = new PerfRun(data);
        new Lwjgl3Application(r, c);
        System.out.println("PERF_DONE");
        System.exit(0);
    }
    private static void deleteTree(File f) { File[] k = f.listFiles(); if (k != null) for (File c : k) deleteTree(c); f.delete(); }

    @Override public void create() {
        DesktopPlatform dp = new DesktopPlatform(data, () -> src);
        game = new ClimbGame(data, dp);
        game.create();
        startRun();
        GL20 gl = Gdx.gl;
        System.out.println("PERF env GL_RENDERER=" + gl.glGetString(GL20.GL_RENDERER) + " GL_VERSION=" + gl.glGetString(GL20.GL_VERSION) + " java=" + System.getProperty("java.version")
                + " cpus=" + Runtime.getRuntime().availableProcessors() + " maxHeapMB=" + Runtime.getRuntime().maxMemory() / 1048576 + " window=" + W + "x" + H + " cap=" + CAP + " vsync=" + Boolean.getBoolean("perf.vsync") + " seed=" + SEED);
        startNs = last = System.nanoTime(); allocLast = tmx.getCurrentThreadAllocatedBytes(); long[] g = gc(); gcCountLast = g[0]; gcTimeLast = g[1];
        nextFall = FALL_EVERY > 0 ? WARMUP + FALL_EVERY : Float.MAX_VALUE; nextRestart = RESTART_EVERY > 0 ? WARMUP + RESTART_EVERY : Float.MAX_VALUE; nextMem = 0f;
    }

    /** A new seeded climb through the game's own path (the NEW RUN button does the same). */
    private void startRun() {
        bestY = -1e9; lastProgress = clock;
        com.badlogic.gdx.Screen old = game.getScreen();
        game.beginFreshRun(SEED);
        game.setScreen(new PlayScreen(game, false));
        if (old instanceof PlayScreen) old.dispose();
    }

    private static long[] gc() { long n = 0, t = 0; for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) { n += Math.max(0, b.getCollectionCount()); t += Math.max(0, b.getCollectionTime()); } return new long[]{n, t}; }

    @Override public void render() {
        long t0 = System.nanoTime();
        double interval = (t0 - last) / 1e6; last = t0;
        clock = (t0 - startNs) / 1e9f;
        if (clock >= nextFall) { PerfProbe.forceFall = true; falls++; nextFall += FALL_EVERY; }
        boolean restarting = false;
        if (clock >= nextRestart) { long r0 = System.nanoTime(); startRun(); restartLog.add(String.format(Locale.US, "%.1f", (System.nanoTime() - r0) / 1e6)); restarts++; nextRestart += RESTART_EVERY; restarting = true; }
        long g0 = System.nanoTime();
        game.render();
        if (FINISH) Gdx.gl.glFinish();
        long g1 = System.nanoTime();
        src.endFrame();
        long alloc = tmx.getCurrentThreadAllocatedBytes(); long[] g = gc();
        double[] r = new double[]{clock, interval, (g1 - g0) / 1e6,
                PerfProbe.tStream > PerfProbe.tStart ? (PerfProbe.tStream - PerfProbe.tStart) / 1e6 : 0, PerfProbe.tSim > PerfProbe.tStream ? Math.max(0, PerfProbe.tSim - PerfProbe.tStream - PerfProbe.tDrive) / 1e6 : 0,
                PerfProbe.tWorld > PerfProbe.tSim ? (PerfProbe.tWorld - PerfProbe.tSim) / 1e6 : 0, PerfProbe.tHud > PerfProbe.tWorld ? (PerfProbe.tHud - PerfProbe.tWorld) / 1e6 : 0,
                PerfProbe.steps, PerfProbe.events, PerfProbe.saved ? 1 : 0, g[0] - gcCountLast, g[1] - gcTimeLast, (alloc - allocLast) / 1024.0, PerfProbe.mode, PerfProbe.absY, restarting ? 1 : 0, PerfProbe.generating ? 1 : 0, PerfProbe.tDrive / 1e6};
        rows.add(r);
        if (PerfProbe.absY > bestY + 0.5) { bestY = PerfProbe.absY; lastProgress = clock; }
        else if (clock - lastProgress > 15f) { PerfProbe.skipAhead = true; skips++; lastProgress = clock; }
        allocLast = alloc; gcCountLast = g[0]; gcTimeLast = g[1];
        if (clock >= nextMem) { memLog.add(String.format(Locale.US, "%.0fs heapAfterGC=%.1fMB used=%.1fMB", clock, oldGenAfterGc() / 1048576.0, (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1048576.0)); nextMem += 30f; }
        if (clock >= SECONDS) { report(); Gdx.app.exit(); }
    }

    /** Heap in use right after the most recent collection (all pools, from the collector's own GC info): the live set, a leak shows as a rising floor. */
    private static long oldGenAfterGc() {
        long best = 0, when = -1;
        for (java.lang.management.GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (!(b instanceof com.sun.management.GarbageCollectorMXBean)) continue;
            com.sun.management.GcInfo i = ((com.sun.management.GarbageCollectorMXBean) b).getLastGcInfo();
            if (i == null || i.getEndTime() < when) continue;
            long v = 0; for (java.lang.management.MemoryUsage u : i.getMemoryUsageAfterGc().values()) v += u.getUsed();
            if (v > 0) { best = v; when = i.getEndTime(); }
        }
        return best;
    }

    private static double pct(double[] sorted, double q) { if (sorted.length == 0) return 0; int i = (int) Math.ceil(q * sorted.length) - 1; return sorted[Math.max(0, Math.min(sorted.length - 1, i))]; }

    private void report() {
        try (PrintWriter w = new PrintWriter(new FileWriter(OUT))) {
            w.println("t,interval_ms,render_ms,stream_ms,sim_ms,world_ms,hud_ms,steps,events,saved,gc_count,gc_ms,alloc_kb,mode,absY,restart,generating,autopilot_ms");
            for (double[] r : rows) { StringBuilder sb = new StringBuilder(); for (int i = 0; i < r.length; i++) { if (i > 0) sb.append(','); sb.append(String.format(Locale.US, "%.3f", r[i])); } w.println(sb); }
        } catch (IOException e) { System.out.println("PERF could not write " + OUT + ": " + e); }
        List<double[]> s = new ArrayList<>(); for (double[] r : rows) if (r[0] >= WARMUP) s.add(r);
        int n = s.size(); if (n == 0) return;
        double span = s.get(n - 1)[0] - s.get(0)[0];
        double[] iv = new double[n], rd = new double[n]; for (int i = 0; i < n; i++) { iv[i] = s.get(i)[1]; rd[i] = s.get(i)[2]; }
        double[] ivs = iv.clone(); Arrays.sort(ivs);
        double low1 = 0, low01 = 0; int k1 = Math.max(1, n / 100), k01 = Math.max(1, n / 1000);
        for (int i = 0; i < k1; i++) low1 += ivs[n - 1 - i]; for (int i = 0; i < k01; i++) low01 += ivs[n - 1 - i];
        low1 /= k1; low01 /= k01;
        int over17 = 0, over25 = 0, over33 = 0, over50 = 0; for (double v : iv) { if (v > 16.7) over17++; if (v > 25) over25++; if (v > 33.4) over33++; if (v > 50) over50++; }
        System.out.printf(Locale.US, "PERF %s frames=%d over %.0fs avgFPS=%.1f 1%%low=%.1f 0.1%%low=%.1f | interval ms: median=%.2f p95=%.2f p99=%.2f max=%.2f | >16.7ms=%d >25ms=%d >33.3ms=%d >50ms=%d | restarts=%d falls=%d stuckSkips=%d%n",
                TAG, n, span, n / span, 1000 / low1, 1000 / low01, pct(ivs, 0.5), pct(ivs, 0.95), pct(ivs, 0.99), ivs[n - 1], over17, over25, over33, over50, restarts, falls, skips);
        String[] names = {"render(cpu+submit)", "stream", "sim", "world", "hud"}; int[] col = {2, 3, 4, 5, 6};
        for (int c = 0; c < names.length; c++) {
            double[] v = new double[n]; for (int i = 0; i < n; i++) v[i] = s.get(i)[col[c]]; Arrays.sort(v);
            double mean = 0; for (double x : v) mean += x; mean /= n;
            System.out.printf(Locale.US, "PERF %s stage %-18s mean=%.3f median=%.3f p95=%.3f p99=%.3f max=%.3f ms%n", TAG, names[c], mean, pct(v, 0.5), pct(v, 0.95), pct(v, 0.99), v[n - 1]);
        }
        double drv = 0; for (double[] r : s) drv += r[17];
        System.out.printf(Locale.US, "PERF %s test autopilot (not game work, included in render above): mean %.3f ms/frame%n", TAG, drv / n);
        double allocTot = 0, gcMs = 0; int gcN = 0, saves = 0; for (double[] r : s) { allocTot += r[12]; gcN += (int) r[10]; gcMs += r[11]; saves += (int) r[9]; }
        System.out.printf(Locale.US, "PERF %s alloc=%.1f KB/frame (%.1f MB/s) gc=%d collections, %.0f ms total, autosaves=%d%n", TAG, allocTot / n, allocTot / 1024 / span, gcN, gcMs, saves);
        String[] modes = {"GROUND", "AIR", "ROPE", "CABLE", "LEDGE", "PULLUP", "BEAM"};
        for (int m = 0; m < modes.length; m++) {
            List<Double> v = new ArrayList<>(); for (double[] r : s) if ((int) r[13] == m) v.add(r[1]);
            if (v.size() < 30) continue;
            double[] a = new double[v.size()]; for (int i = 0; i < a.length; i++) a[i] = v.get(i); Arrays.sort(a);
            System.out.printf(Locale.US, "PERF %s mode %-6s frames=%d median=%.2f p99=%.2f max=%.2f ms%n", TAG, modes[m], a.length, pct(a, 0.5), pct(a, 0.99), a[a.length - 1]);
        }
        // the worst frames, and what was going on in them
        Integer[] idx = new Integer[n]; for (int i = 0; i < n; i++) idx[i] = i;
        Arrays.sort(idx, (a, b) -> Double.compare(s.get(b)[1], s.get(a)[1]));
        for (int j = 0; j < Math.min(15, n); j++) {
            double[] r = s.get(idx[j]);
            System.out.printf(Locale.US, "PERF %s spike t=%.1fs interval=%.1f render=%.1f stream=%.2f sim=%.2f world=%.2f hud=%.2f steps=%d ev=%d saved=%d gc=%d/%.0fms alloc=%.0fKB mode=%s y=%.0f restart=%d gen=%d%n",
                    TAG, r[0], r[1], r[2], r[3], r[4], r[5], r[6], (int) r[7], (int) r[8], (int) r[9], (int) r[10], r[11], r[12], modes[Math.max(0, Math.min(6, (int) r[13]))], r[14], (int) r[15], (int) r[16]);
        }
        // frames while the background generator was busy versus idle
        for (int gflag = 0; gflag < 2; gflag++) {
            List<Double> v = new ArrayList<>(); for (double[] r : s) if ((int) r[16] == gflag) v.add(r[1]);
            if (v.size() < 30) continue;
            double[] a = new double[v.size()]; for (int i = 0; i < a.length; i++) a[i] = v.get(i); Arrays.sort(a);
            int sp = 0; for (double x : a) if (x > 33.4) sp++;
            System.out.printf(Locale.US, "PERF %s generator %-4s frames=%d median=%.2f p99=%.2f max=%.2f ms, >33.3ms=%d (%.2f%%)%n", TAG, gflag == 1 ? "BUSY" : "idle", a.length, pct(a, 0.5), pct(a, 0.99), a[a.length - 1], sp, 100.0 * sp / a.length);
        }
        for (String m : memLog) System.out.println("PERF " + TAG + " mem " + m);
        if (!restartLog.isEmpty()) System.out.println("PERF " + TAG + " restart setup ms: " + restartLog);
        double maxY = 0; for (double[] r : s) maxY = Math.max(maxY, r[14]);
        System.out.printf(Locale.US, "PERF %s climbed to %.0f m%n", TAG, maxY);
    }

    @Override public void resize(int w, int h) { game.resize(w, h); }
    @Override public void pause() { }
    @Override public void resume() { }
    @Override public void dispose() { game.dispose(); }
}

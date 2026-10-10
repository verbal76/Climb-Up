package com.hotatticgames.climbup.desktop;

import com.hotatticgames.climbup.desktop.input.DesktopConfig;
import com.hotatticgames.climbup.sim.InputState;
import com.hotatticgames.climbup.sim.Sim;
import com.hotatticgames.climbup.sim.Tower;
import com.hotatticgames.climbup.sim.Tuning;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * No-window self-check of the packaged build, run as {@code UPWARDLY.exe --smoke}: bundled runtime, assets next to the jar, native libraries, save location,
 * settings round trip, and a short deterministic climb through the real simulation. Prints one KEY=VALUE line per check and exits non-zero on any failure.
 */
final class Smoke {
    private static int failures;

    private static final StringBuilder LOG = new StringBuilder();
    private static void say(String line) { System.out.println(line); LOG.append(line).append('\n'); }
    private static void check(String name, boolean ok, String detail) { say((ok ? "SMOKE_OK " : "SMOKE_FAIL ") + name + " " + detail); if (!ok) failures++; }

    static int run() {
        say("SMOKE_INFO java.version=" + System.getProperty("java.version") + " java.home=" + System.getProperty("java.home") + " os=" + System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        File assets = DesktopLauncher.assetRoot();
        File root = assets != null ? assets : new File("assets");
        File tuningFile = new File(root, "data/tuning.json");
        check("assets", tuningFile.isFile(), root.getAbsolutePath());
        File data = DataDirs.dataDir();
        check("saveDir", true, data.getAbsolutePath());
        try {
            data.mkdirs();
            File probe = new File(data, "smoke-probe.tmp");
            Files.write(probe.toPath(), "ok".getBytes(StandardCharsets.UTF_8));
            check("saveDirWritable", probe.delete(), data.getAbsolutePath());
            DesktopConfig c = new DesktopConfig(); c.deadzone = 0.35f;
            File f = new File(data, "smoke-config.cfg");
            check("configSave", c.save(f), f.getName());
            check("configRoundTrip", Math.abs(DesktopConfig.load(f).deadzone - 0.35f) < 1e-4f, "deadzone");
            f.delete(); new File(data, "smoke-config.cfg.bak").delete();
        } catch (Exception e) { check("saveDirWritable", false, e.toString()); }
        try {
            com.badlogic.gdx.utils.GdxNativesLoader.load();
            check("gdxNatives", true, "gdx64 loaded");
        } catch (Throwable t) { check("gdxNatives", false, t.toString()); }
        try {
            boolean ok = org.lwjgl.glfw.GLFW.glfwInit();
            String v = ok ? org.lwjgl.glfw.GLFW.glfwGetVersionString() : "init failed";
            if (ok) org.lwjgl.glfw.GLFW.glfwTerminate();
            if (ok || System.getProperty("os.name", "").toLowerCase().contains("win")) check("glfw", ok, v); else say("SMOKE_SKIP glfw " + v + " (no display on this machine; required on Windows)");
        } catch (Throwable t) { check("glfw", false, t.toString()); }
        try {
            com.studiohartman.jamepad.ControllerManager m = new com.studiohartman.jamepad.ControllerManager();
            m.initSDLGamepad();
            int n = m.getNumControllers();
            m.quitSDLGamepad();
            check("controllerLibrary", true, "SDL game-controller library loaded, controllers=" + n);
        } catch (Throwable t) { check("controllerLibrary", false, t.toString()); }
        try {
            Tuning t = Tuning.parse(new String(Files.readAllBytes(tuningFile.toPath()), StandardCharsets.UTF_8));
            Tower tower = new Tower(11L, t);
            Sim sim = Sim.startOn(tower.world, t, 0);
            sim.setRange(0, tower.world.size() - 1);
            InputState in = new InputState();
            double h0 = sim.y;
            for (int i = 0; i < 1200; i++) { in.moveX = (i / 90) % 2 == 0 ? 1f : -1f; in.jumpHeld = i % 60 < 20; in.jumpPressed = i % 60 == 0; sim.step(in); in.jumpPressed = false; }
            check("simulation", Double.isFinite(sim.y) && Double.isFinite(sim.s), "steps=1200 y0=" + h0 + " y=" + sim.y);
        } catch (Throwable t) { check("simulation", false, t.toString()); }
        say(failures == 0 ? "SMOKE_RESULT PASS" : "SMOKE_RESULT FAIL " + failures);
        try { Files.write(new File(data, "smoke.log").toPath(), LOG.toString().getBytes(StandardCharsets.UTF_8)); } catch (Exception ignored) { }      // the packaged launcher has no console: the log file is the evidence
        return failures == 0 ? 0 : 1;
    }
}

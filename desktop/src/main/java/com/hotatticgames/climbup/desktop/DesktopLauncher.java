package com.hotatticgames.climbup.desktop;

import com.badlogic.gdx.Files;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3WindowAdapter;
import com.hotatticgames.climbup.ClimbGame;
import com.hotatticgames.climbup.desktop.input.DesktopConfig;
import java.io.File;

/** Upwardly for Windows (and a development launcher for other desktops): the shared game inside an LWJGL3 window. */
public final class DesktopLauncher {
    private static File assetRoot;
    /** --exit-after=SECONDS: the game closes itself (used by CI to prove a clean shutdown of the packaged EXE); 0 = never. */
    static float exitAfter;

    public static void main(String[] args) {
        for (String a : args) {
            if (a.equals("--smoke")) { System.exit(Smoke.run()); return; }
            if (a.startsWith("--exit-after=")) { try { exitAfter = Float.parseFloat(a.substring(13)); } catch (NumberFormatException ignored) { } }
        }
        Lwjgl3ApplicationConfiguration c = new Lwjgl3ApplicationConfiguration();
        c.setTitle(DesktopPlatform.TITLE);
        if (System.getProperty("climb.iconShot") != null) { c.setWindowedMode(256, 256); new Lwjgl3Application(new IconShot(), c); return; }     // dev tool: render a character to a transparent PNG
        assetRoot = assetRoot();
        File dir = DataDirs.dataDir();
        DesktopLog.start(dir);
        DesktopPlatform platform = new DesktopPlatform(dir);
        DesktopConfig cfg = platform.cfg;
        if (!cfg.graphicsDecided) {          // first run: a large display (a TV) starts full screen, a small one in a window; the rest of the defaults follow once the graphics chip is known
            com.badlogic.gdx.Graphics.DisplayMode nat = Lwjgl3ApplicationConfiguration.getDisplayMode();
            cfg.display = GraphicsProfile.defaultDisplay(nat.width, nat.height);
            DesktopLog.append("first run: display " + nat.width + "x" + nat.height + " @" + nat.refreshRate + " -> " + cfg.display);
        }
        DisplayManager.configureStartup(c, cfg, System.getProperty("climb.w") != null ? Integer.getInteger("climb.w", cfg.width) : 0, Integer.getInteger("climb.h", cfg.height));
        c.setBackBufferConfig(8, 8, 8, 8, 24, 0, cfg.aa);
        c.setWindowSizeLimits(640, 360, -1, -1);
        c.setWindowIcon(Files.FileType.Classpath, "icons/upwardly_256.png", "icons/upwardly_128.png", "icons/upwardly_64.png", "icons/upwardly_48.png", "icons/upwardly_32.png", "icons/upwardly_16.png");
        c.useVsync(cfg.vsync);
        c.setForegroundFPS(GraphicsProfile.effectiveFrameLimit(cfg.vsync, cfg.frameLimit, Lwjgl3ApplicationConfiguration.getDisplayMode().refreshRate));       // with vsync the monitor paces the frames; the simulation runs on its own fixed step either way
        c.setIdleFPS(30);
        final ClimbGame game = new ClimbGame(dir, platform);
        c.setWindowListener(new Lwjgl3WindowAdapter() {
            @Override public void focusLost() { try { game.pause(); } catch (RuntimeException ignored) { } }      // alt-tab pauses the run (the pause menu is shown on return)
            @Override public void focusGained() { try { game.resume(); } catch (RuntimeException ignored) { } }
        });
        try {
            run(game, c);
            DesktopLog.append("clean exit");
        } catch (Throwable t) {
            if (cfg.aa > 0 && !platform.attached) {          // the window could not be made with anti-aliasing: try once more without it
                DesktopLog.error("the window could not be created with " + cfg.aa + "x anti-aliasing; trying again without it", t);
                cfg.aa = 0; c.setBackBufferConfig(8, 8, 8, 8, 24, 0, 0);
                try { run(game, c); DesktopLog.append("clean exit"); return; } catch (Throwable t2) { t = t2; }
            }
            DesktopLog.error("the game stopped with an error", t); t.printStackTrace(); System.exit(1);       // never leave a half-started process behind
        }
    }

    private static void run(ClimbGame game, Lwjgl3ApplicationConfiguration c) {
        new Lwjgl3Application(game, c) {
            @Override protected Files createFiles() { return new AssetFiles(assetRoot); }
        };
    }

    /** -Dclimb.assets, else an "assets" folder next to the jar (the packaged game), else the working directory (development: gradle run uses ../assets). */
    static File assetRoot() {
        String p = System.getProperty("climb.assets");
        if (p != null && !p.isEmpty()) return new File(p).getAbsoluteFile();
        try {
            File jar = new File(DesktopLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            File beside = new File(jar.isFile() ? jar.getParentFile() : jar, "assets");
            if (new File(beside, "data/tuning.json").isFile()) return beside;
        } catch (Exception ignored) { }
        return null;
    }
}

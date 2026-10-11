package com.hotatticgames.climbup.desktop;

import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.hotatticgames.climbup.ClimbGame;
import com.hotatticgames.climbup.SettingsScreen;
import com.hotatticgames.climbup.TitleScreen;
import com.hotatticgames.climbup.desktop.Fakes.FakePad;
import com.hotatticgames.climbup.desktop.Fakes.FakeSources;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Captures the in-game Settings screen (every desktop tab) at a spread of window sizes and aspect ratios, plus the TEXT SIZE
 * stepper at its lowest and highest, so the layout can be eyeballed for clipping and overlap. Drives the real desktop build
 * under a real GL context (CI/dev run it under Xvfb with software OpenGL). No assertions: it just writes PNGs and SHOT lines.
 */
public final class SettingsShots implements ApplicationListener {
    private final File data, shots;
    private final FakeSources src = new FakeSources();
    private ClimbGame game;
    private DesktopPlatform dp;
    private TitleScreen back;

    /** One capture: window size, Settings page index, an optional text-scale to force, and the file tag. */
    private static final class Job {
        final int w, h, page, textScale; final String tag;
        Job(int w, int h, int page, int textScale, String tag) { this.w = w; this.h = h; this.page = page; this.textScale = textScale; this.tag = tag; }
    }
    private final List<Job> jobs = new ArrayList<>();
    private int cur, local, lastW, lastH;

    // Settings page indices: 0 GENERAL, 1 ACCESS, 2 ABOUT, 3 DISPLAY, 4 GRAPHICS, 5 CONTROLS (desktop adds the last three).
    private static final int GENERAL = 0, DISPLAY = 3, GRAPHICS = 4, CONTROLS = 5;

    public static void main(String[] args) {
        File data = new File(args.length > 0 ? args[0] : "build/settingsshots/data"), shots = new File(args.length > 1 ? args[1] : "build/settingsshots/shots");
        deleteTree(data); data.mkdirs(); shots.mkdirs();
        Lwjgl3ApplicationConfiguration c = new Lwjgl3ApplicationConfiguration();
        c.setTitle("Upwardly settings shots"); c.setWindowedMode(1280, 720); c.useVsync(false); c.setForegroundFPS(60); c.disableAudio(true);
        new Lwjgl3Application(new SettingsShots(data, shots), c);
        System.out.println("SETTINGSSHOTS_DONE");
    }

    private SettingsShots(File data, File shots) { this.data = data; this.shots = shots; }

    private static void deleteTree(File f) { File[] k = f.listFiles(); if (k != null) for (File c : k) deleteTree(c); f.delete(); }

    @Override public void create() {
        dp = new DesktopPlatform(data, () -> src);
        game = new ClimbGame(data, dp);
        game.create();
        back = new TitleScreen(game);
        // A controller plugged in so the CONTROLS tab shows its longest realistic content (name, two EDITs, dead zone).
        src.pads.add(new FakePad("shots-pad", "XInput Controller"));

        // Sizes span 4:3 -> 16:9 -> 21:9 -> 32:9, plus a 4K TV and small windows. The three desktop tabs are shot at each.
        int[][] sizes = {
            {1280, 720},   // 16:9 baseline
            {1920, 1080},  // 16:9, 1080p (deviceScale 1.5)
            {3840, 2160},  // 16:9, 4K TV
            {1024, 768},   // 4:3
            {2560, 1080},  // 21:9 ultrawide
            {3840, 1080},  // 32:9 super ultrawide
            {1024, 600},   // small, wide window
            {800, 600},    // small 4:3 window
        };
        for (int[] s : sizes) {
            String sz = s[0] + "x" + s[1];
            jobs.add(new Job(s[0], s[1], DISPLAY, -1, "display_" + sz));
            jobs.add(new Job(s[0], s[1], GRAPHICS, -1, "graphics_" + sz));
            jobs.add(new Job(s[0], s[1], CONTROLS, -1, "controls_" + sz));
            jobs.add(new Job(s[0], s[1], GENERAL, -1, "general_" + sz));
        }
        // TEXT SIZE stepper at its lowest and highest, on the GENERAL tab, at two sizes.
        jobs.add(new Job(1280, 720, GENERAL, lowestTextScale(), "textsize_lowest_1280x720"));
        jobs.add(new Job(1280, 720, GENERAL, highestTextScale(), "textsize_highest_1280x720"));
        jobs.add(new Job(1920, 1080, GENERAL, lowestTextScale(), "textsize_lowest_1920x1080"));
    }

    /** The text-scale value that renders the smallest / largest percentage, found through the public helpers so this harness need not know the encoding. */
    private int lowestTextScale() { return extremeTextScale(true); }
    private int highestTextScale() { return extremeTextScale(false); }
    private int extremeTextScale(boolean smallest) {
        com.hotatticgames.climbup.Settings s = new com.hotatticgames.climbup.Settings();
        int best = s.textScale; float bestMul = Float.NaN;
        for (int i = 0; i < com.hotatticgames.climbup.Settings.textStepCount(); i++) {
            s.textScale = com.hotatticgames.climbup.Settings.textScaleForStep(i);
            float m = s.textMul();
            if (Float.isNaN(bestMul) || (smallest ? m < bestMul : m > bestMul)) { bestMul = m; best = s.textScale; }
        }
        return best;
    }

    @Override public void render() {
        if (cur >= jobs.size()) { report(); Gdx.app.exit(); return; }
        Job j = jobs.get(cur);
        if (local == 0) {
            if (lastW != j.w || lastH != j.h) { Gdx.graphics.setWindowedMode(j.w, j.h); lastW = j.w; lastH = j.h; }
        }
        if (local == 3) {
            if (j.textScale >= 0) game.settings.textScale = j.textScale; else game.settings.textScale = 0;
            game.setScreen(new SettingsScreen(game, back, j.page));
        }
        game.render();
        src.endFrame();
        local++;
        if (local >= 16) { takeShot(j.tag); cur++; local = 0; }
    }

    private void takeShot(String name) {
        try {
            Pixmap raw = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
            Pixmap pm = new Pixmap(raw.getWidth(), raw.getHeight(), Pixmap.Format.RGBA8888);
            for (int row = 0; row < raw.getHeight(); row++) pm.drawPixmap(raw, 0, row, raw.getWidth(), 1, 0, raw.getHeight() - 1 - row, raw.getWidth(), 1);
            raw.dispose();
            PixmapIO.writePNG(Gdx.files.absolute(new File(shots, name + ".png").getAbsolutePath()), pm);
            System.out.println("SETTINGSSHOTS_SHOT " + name + " " + pm.getWidth() + "x" + pm.getHeight());
            pm.dispose();
        } catch (Exception e) { System.out.println("SETTINGSSHOTS_FAIL " + name + " " + e); }
    }

    private void report() { System.out.println("SETTINGSSHOTS_COUNT " + jobs.size()); }

    @Override public void resize(int w, int h) { game.resize(w, h); }
    @Override public void pause() { }
    @Override public void resume() { }
    @Override public void dispose() { game.dispose(); }
}

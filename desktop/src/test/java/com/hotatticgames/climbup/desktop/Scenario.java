package com.hotatticgames.climbup.desktop;

import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.hotatticgames.climbup.ClimbGame;
import com.hotatticgames.climbup.PlayScreen;
import com.hotatticgames.climbup.SettingsScreen;
import com.hotatticgames.climbup.SplashScreen;
import com.hotatticgames.climbup.TitleScreen;
import com.hotatticgames.climbup.desktop.Fakes.FakePad;
import com.hotatticgames.climbup.desktop.Fakes.FakeSources;
import com.hotatticgames.climbup.desktop.input.*;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * An end-to-end run of the real desktop build in a real window with a real GL context (CI runs it under Xvfb with software OpenGL), driven only through the input layer
 * a player uses: keyboard-only menu navigation, rebinding with conflict handling, a short climb, a controller plugged in and pulled out mid-run, pausing into the
 * Controls tab, window resizes, fullscreen, and a clean exit. Prints SCENARIO_OK / SCENARIO_FAIL lines, screenshots (and an ASCII thumbnail of each in the log) and timing.
 */
public final class Scenario implements ApplicationListener {
    interface Step { boolean tick(); }

    private final File data, shots;
    private final FakeSources src = new FakeSources();
    private ClimbGame game; private DesktopPlatform dp;
    private final List<Step> steps = new ArrayList<>();
    private int cur, frame, waitLeft, stuck;
    private int failures, checks;
    private final List<Float> playDeltas = new ArrayList<>();
    private boolean collecting;
    private long seedAtPlay;
    private float playSecondsMark;

    public static void main(String[] args) {
        File data = new File(args.length > 0 ? args[0] : "build/scenario/data"), shots = new File(args.length > 1 ? args[1] : "build/scenario/shots");
        deleteTree(data); data.mkdirs(); shots.mkdirs();      // every run starts from a first-time install
        Lwjgl3ApplicationConfiguration c = new Lwjgl3ApplicationConfiguration();
        c.setTitle("UPWARDLY scenario"); c.setWindowedMode(1280, 720); c.useVsync(false); c.setForegroundFPS(60); c.disableAudio(Boolean.getBoolean("scenario.noaudio"));
        Scenario s = new Scenario(data, shots);
        new Lwjgl3Application(s, c);
        System.out.println(s.failures == 0 ? "SCENARIO_RESULT PASS checks=" + s.checks : "SCENARIO_RESULT FAIL failures=" + s.failures + " checks=" + s.checks);
        System.exit(s.failures == 0 ? 0 : 1);
    }

    private static void deleteTree(File f) { File[] k = f.listFiles(); if (k != null) for (File c : k) deleteTree(c); f.delete(); }

    private Scenario(File data, File shots) { this.data = data; this.shots = shots; }

    // ------------------------------------------------------------------ helpers

    private void check(String name, boolean ok, String detail) { checks++; if (!ok) failures++; System.out.println((ok ? "SCENARIO_OK " : "SCENARIO_FAIL ") + name + " " + detail); }
    private com.badlogic.gdx.Screen screen() { return game.getScreen(); }
    private String screenName() { return screen() == null ? "none" : screen().getClass().getSimpleName(); }
    private MenuNav nav() { return (MenuNav) game.ui.nav; }

    private Step wait(int n) { return new Step() { int left = n; public boolean tick() { return --left <= 0; } }; }
    private Step act(Runnable r) { return () -> { r.run(); return true; }; }
    private Step tap(int key) { return act(() -> src.tapKey(key)); }
    private Step until(java.util.function.BooleanSupplier c, int max, String what) { return new Step() { int n; public boolean tick() { if (c.getAsBoolean()) return true; if (++n > max) { check("until " + what, false, "timed out on " + screenName()); return true; } return false; } }; }
    private Step shot(String name) { return act(() -> takeShot(name)); }

    /** Presses direction keys, one per few frames, until the button whose label matches (the nth such one) has the focus: what a keyboard-only player does. */
    private Step goTo(String label, int nth) {
        return new Step() {
            int pause, tries;
            public boolean tick() {
                if (pause-- > 0) return false;
                FocusModel fm = nav().fm;
                int target = -1, seen = 0;
                for (int i = 0; i < 80; i++) { FocusModel.Item it = fm.lastItem(i); if (it == null) break; if (it.label.equals(label) && seen++ == nth) { target = i; break; } }
                if (target < 0) { if (++tries > 120) { check("goTo " + label, false, "button not found on " + screenName()); return true; } return false; }
                if (fm.focus == target) return true;
                if (++tries > 60) { FocusModel.Item ff = fm.focused(); check("goTo " + label, false, "could not reach on " + screenName() + " focus=" + (ff == null ? null : ff.label + "@" + ff.x + "," + ff.y) + " target=" + fm.lastItem(target).label + "@" + fm.lastItem(target).x + "," + fm.lastItem(target).y + " count=" + fm.count()); return true; }
                FocusModel.Item f = fm.focused(), t = fm.lastItem(target);
                float dx = t.x + t.w / 2 - (f.x + f.w / 2), dy = t.y + t.h / 2 - (f.y + f.h / 2);
                int key = Math.abs(dy) >= Math.abs(dx) * 0.6f ? (dy > 0 ? Input.Keys.UP : Input.Keys.DOWN) : (dx > 0 ? Input.Keys.RIGHT : Input.Keys.LEFT);
                if (Boolean.getBoolean("scenario.trace")) System.out.println("SCENARIO_TRACE goTo " + label + " focus=" + f.label + " key=" + Input.Keys.toString(key));
                src.tapKey(key); pause = 2;
                return false;
            }
        };
    }
    private Step goTo(String label) { return goTo(label, 0); }

    private void takeShot(String name) {
        try {
            Pixmap raw = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
            Pixmap pm = new Pixmap(raw.getWidth(), raw.getHeight(), Pixmap.Format.RGBA8888);
            for (int row = 0; row < raw.getHeight(); row++) pm.drawPixmap(raw, 0, row, raw.getWidth(), 1, 0, raw.getHeight() - 1 - row, raw.getWidth(), 1);
            raw.dispose();
            PixmapIO.writePNG(Gdx.files.absolute(new File(shots, name + ".png").getAbsolutePath()), pm);
            int nonDark = 0, total = 0; long sum = 0;
            for (int y = 0; y < pm.getHeight(); y += 7) for (int x = 0; x < pm.getWidth(); x += 7) { int p = pm.getPixel(x, y); int l = (((p >>> 24) & 255) + ((p >>> 16) & 255) + ((p >>> 8) & 255)) / 3; sum += l; total++; if (l > 24) nonDark++; }
            System.out.println("SCENARIO_SHOT " + name + " " + pm.getWidth() + "x" + pm.getHeight() + " meanLuma=" + (sum / Math.max(1, total)) + " litFraction=" + String.format("%.2f", nonDark / (float) Math.max(1, total)));
            if (Boolean.getBoolean("scenario.ascii")) System.out.println(ascii(pm));
            pm.dispose();
        } catch (Exception e) { check("shot " + name, false, e.toString()); }
    }

    private static String ascii(Pixmap pm) {
        String ramp = " .:-=+*#%@"; StringBuilder sb = new StringBuilder(); int cols = 72, rows = 20;
        for (int r = 0; r < rows; r++) { for (int c = 0; c < cols; c++) { int x = c * pm.getWidth() / cols, y = r * pm.getHeight() / rows; int p = pm.getPixel(x, y); int l = (((p >>> 24) & 255) + ((p >>> 16) & 255) + ((p >>> 8) & 255)) / 3; sb.append(ramp.charAt(Math.min(9, l * 10 / 256))); } sb.append('\n'); }
        return sb.toString();
    }

    private PlayScreen playScreen() { return screen() instanceof PlayScreen ? (PlayScreen) screen() : null; }

    // ------------------------------------------------------------------ the script

    private void build() {
        // --- splash, title, keyboard-only navigation
        steps.add(wait(40)); steps.add(act(() -> check("splash shown first", screen() instanceof SplashScreen, screenName())));
        steps.add(shot("01_splash")); steps.add(tap(Input.Keys.ENTER));
        steps.add(until(() -> screen() instanceof TitleScreen, 200, "title")); steps.add(wait(60));
        steps.add(act(() -> check("title uses the UPWARDLY name", dp.title().equals("UPWARDLY") && Gdx.graphics != null, dp.title())));
        steps.add(shot("02_title"));
        steps.add(tap(Input.Keys.S)); steps.add(wait(3));
        steps.add(act(() -> check("down arrow moves focus to SETTINGS", nav().fm.focused() != null && nav().fm.focused().label.equals("SETTINGS"), String.valueOf(nav().fm.focused() == null ? null : nav().fm.focused().label))));
        steps.add(shot("03_title_focus"));
        // --- mouse: hover focuses, left click activates, right click does not
        steps.add(act(() -> { FocusModel.Item it = null; for (int i = 0; i < 20; i++) { FocusModel.Item c = nav().fm.lastItem(i); if (c == null) break; if (c.label.equals("CREDITS")) it = c; } hover(it.x + it.w / 2, it.y + it.h / 2); }));
        steps.add(wait(3));
        steps.add(act(() -> check("mouse hover focuses CREDITS", nav().fm.focused() != null && nav().fm.focused().label.equals("CREDITS"), String.valueOf(nav().fm.focused() == null ? null : nav().fm.focused().label))));
        steps.add(act(() -> click(Input.Buttons.RIGHT))); steps.add(wait(3));
        steps.add(act(() -> check("right click does not activate", screen() instanceof TitleScreen, screenName())));
        steps.add(act(() -> click(Input.Buttons.LEFT))); steps.add(wait(3));
        steps.add(act(() -> check("left click activates CREDITS", screenName().equals("CreditsScreen"), screenName())));
        steps.add(shot("04_credits")); steps.add(wait(30)); steps.add(tap(Input.Keys.ESCAPE)); steps.add(wait(5));
        steps.add(act(() -> check("escape goes back from credits", screen() instanceof TitleScreen, screenName())));
        steps.add(wait(40));
        // --- settings, controls tab (keyboard only)
        steps.add(goTo("SETTINGS")); steps.add(tap(Input.Keys.ENTER)); steps.add(wait(30));
        steps.add(act(() -> check("settings opened with the desktop tabs", screen() instanceof SettingsScreen && hasButton("CONTROLS") && hasButton("WINDOW"), screenName())));
        steps.add(shot("05_settings_audio"));
        steps.add(goTo("WINDOW")); steps.add(tap(Input.Keys.ENTER)); steps.add(wait(10)); steps.add(shot("06_settings_window"));
        steps.add(goTo("CONTROLS")); steps.add(tap(Input.Keys.ENTER)); steps.add(wait(10)); steps.add(shot("07_settings_controls"));
        steps.add(act(() -> check("controls tab lists the device rows", hasButton("AUTO DETECT") && hasButton("NONE DETECTED"), "input device + connected controller")));
        // --- keyboard rebinding
        steps.add(goTo("EDIT")); steps.add(tap(Input.Keys.ENTER)); steps.add(wait(30)); steps.add(shot("08_bindings_keyboard"));
        steps.add(goTo("SPACE", 0)); steps.add(tap(Input.Keys.ENTER)); steps.add(wait(30)); steps.add(shot("09_rebind_waiting"));
        steps.add(tap(Input.Keys.K)); steps.add(wait(4)); steps.add(shot("10_rebind_proposed"));
        steps.add(goTo("CONFIRM")); steps.add(tap(Input.Keys.ENTER)); steps.add(wait(6));
        steps.add(act(() -> { check("jump rebound to K in memory", dp.cfg.keyboard.get(Act.JUMP).contains(Input.Keys.K) && !dp.cfg.keyboard.get(Act.JUMP).contains(Input.Keys.SPACE), String.valueOf(dp.cfg.keyboard.get(Act.JUMP)));
            check("binding persisted to disk", DesktopConfig.load(dp.configFile).keyboard.get(Act.JUMP).contains(Input.Keys.K), dp.configFile.getName()); }));
        steps.add(shot("11_bindings_after"));
        // a conflicting binding: add A as a second jump key (A moves left)
        steps.add(goTo("+ ADD", 4)); steps.add(tap(Input.Keys.ENTER)); steps.add(wait(30)); steps.add(tap(Input.Keys.A)); steps.add(wait(4)); steps.add(shot("12_rebind_conflict"));
        steps.add(goTo("CONFIRM")); steps.add(tap(Input.Keys.ENTER)); steps.add(wait(6));
        steps.add(act(() -> check("conflict resolved by moving A to JUMP and keeping LEFT usable", dp.cfg.keyboard.get(Act.JUMP).contains(Input.Keys.A) && !dp.cfg.keyboard.get(Act.LEFT).isEmpty() && !dp.cfg.keyboard.get(Act.LEFT).contains(Input.Keys.A), dp.cfg.keyboard.get(Act.JUMP) + " / " + dp.cfg.keyboard.get(Act.LEFT))));
        // cancel leaves everything as it was
        steps.add(goTo("+ ADD", 3)); steps.add(tap(Input.Keys.ENTER)); steps.add(wait(30)); steps.add(tap(Input.Keys.Q)); steps.add(wait(4)); steps.add(goTo("CANCEL")); steps.add(tap(Input.Keys.ENTER)); steps.add(wait(6));
        steps.add(act(() -> check("cancelling a rebind changes nothing", !dp.cfg.keyboard.get(Act.CLIMB_DOWN).contains(Input.Keys.Q) && dp.cfg.keyboard.get(Act.CLIMB_DOWN).size() == 2, String.valueOf(dp.cfg.keyboard.get(Act.CLIMB_DOWN)))));
        // restore defaults (two confirmations)
        steps.add(goTo("RESTORE DEFAULTS")); steps.add(tap(Input.Keys.ENTER)); steps.add(wait(4)); steps.add(goTo("CONFIRM RESTORE")); steps.add(tap(Input.Keys.ENTER)); steps.add(wait(6));
        steps.add(act(() -> check("restore defaults", dp.cfg.keyboard.equals(Defaults.keyboard()) && DesktopConfig.load(dp.configFile).keyboard.equals(Defaults.keyboard()), "keyboard bindings are the defaults again, on disk too")));
        steps.add(shot("13_bindings_restored"));
        steps.add(tap(Input.Keys.ESCAPE)); steps.add(wait(6));        // back to settings
        steps.add(act(() -> check("escape leaves the bindings screen", screen() instanceof SettingsScreen, screenName())));
        steps.add(tap(Input.Keys.ESCAPE)); steps.add(wait(6));        // back to title
        steps.add(act(() -> check("escape leaves settings", screen() instanceof TitleScreen, screenName())));
        steps.add(wait(40));
        // --- play
        steps.add(goTo("PLAY")); steps.add(tap(Input.Keys.ENTER));
        steps.add(until(() -> playScreen() != null, 60, "play screen")); steps.add(wait(90));
        steps.add(act(() -> { seedAtPlay = game.save.seed; playSecondsMark = game.save.playSeconds; collecting = true; }));
        steps.add(shot("14_play_hints"));
        steps.add(act(() -> src.press(Input.Keys.D)));
        for (int i = 0; i < 6; i++) { steps.add(tap(Input.Keys.SPACE)); steps.add(wait(45)); }
        steps.add(shot("15_play_keyboard"));
        steps.add(act(() -> check("the climb advanced while keys were held", game.save.playSeconds - playSecondsMark > 2f, "playSeconds +" + (game.save.playSeconds - playSecondsMark))));
        // controller plugged in mid-run
        steps.add(act(() -> { FakePad p = new FakePad("scenario-pad", "Xbox Wireless Controller"); src.pads.add(p); }));
        steps.add(wait(10));
        steps.add(act(() -> { check("controller discovered during play without touching the run", dp.input.activePad() != null && game.save.seed == seedAtPlay && playScreen() != null, dp.input.activePad() == null ? "none" : dp.input.activePad().name()); src.pads.get(0).set(Ctl.A, 1f); }));
        steps.add(wait(3)); steps.add(act(() -> src.pads.get(0).set(Ctl.A, 0f))); steps.add(wait(20));
        steps.add(act(() -> check("prompts switched to the controller", dp.input.promptDevice() == InputManager.Device.CONTROLLER && dp.input.prompt(Act.JUMP).equals("A"), dp.input.promptDevice() + " " + dp.input.prompt(Act.JUMP)))); steps.add(shot("16_play_controller_prompts"));
        steps.add(act(() -> { src.pads.get(0).connected = false; src.pads.clear(); })); steps.add(wait(10));
        steps.add(act(() -> check("controller removed mid-run: run intact and keyboard still active", game.save.seed == seedAtPlay && playScreen() != null && dp.input.activePad() == null && dp.input.promptDevice() == InputManager.Device.KEYBOARD, dp.input.promptDevice().toString())));
        steps.add(act(() -> src.release(Input.Keys.D)));
        steps.add(act(() -> collecting = false));
        // --- pause, controls during a paused run, resume
        steps.add(tap(Input.Keys.ESCAPE)); steps.add(wait(40)); steps.add(shot("17_pause"));
        steps.add(goTo("SETTINGS")); steps.add(tap(Input.Keys.ENTER)); steps.add(wait(40)); steps.add(goTo("CONTROLS")); steps.add(tap(Input.Keys.ENTER)); steps.add(wait(10));
        steps.add(act(() -> check("controls reachable from a paused run", screen() instanceof SettingsScreen && hasButton("AUTO DETECT"), screenName()))); steps.add(shot("18_controls_from_pause"));
        steps.add(tap(Input.Keys.ESCAPE)); steps.add(wait(40));
        steps.add(act(() -> check("back to the pause menu", playScreen() != null && hasButton("RESUME"), screenName())));
        steps.add(goTo("RESUME")); steps.add(tap(Input.Keys.ENTER)); steps.add(wait(20));
        steps.add(act(() -> check("resume keeps the same run", playScreen() != null && game.save.seed == seedAtPlay && !hasButton("RESUME"), "seed " + game.save.seed)));
        // --- window sizes
        for (int[] s : new int[][]{{1600, 900}, {1920, 1080}, {2560, 1080}, {1024, 768}, {1280, 720}}) {
            steps.add(act(() -> Gdx.graphics.setWindowedMode(s[0], s[1]))); steps.add(wait(20));
            steps.add(act(() -> { check("window resized to " + s[0] + "x" + s[1], Gdx.graphics.getWidth() == s[0] && Gdx.graphics.getHeight() == s[1], Gdx.graphics.getWidth() + "x" + Gdx.graphics.getHeight()); takeShot("20_play_" + s[0] + "x" + s[1]); }));
        }
        // --- fullscreen
        steps.add(act(() -> dp.display.toggle(dp.cfg))); steps.add(wait(30));
        steps.add(act(() -> { check("fullscreen entered", Gdx.graphics.isFullscreen(), Gdx.graphics.getWidth() + "x" + Gdx.graphics.getHeight()); takeShot("21_fullscreen"); }));
        steps.add(act(() -> dp.display.toggle(dp.cfg))); steps.add(wait(30));
        steps.add(act(() -> check("windowed again", !Gdx.graphics.isFullscreen(), Gdx.graphics.getWidth() + "x" + Gdx.graphics.getHeight())));
        // --- exit through the menu, then a clean shutdown
        steps.add(tap(Input.Keys.ESCAPE)); steps.add(wait(40)); steps.add(goTo("MAIN MENU")); steps.add(tap(Input.Keys.ENTER)); steps.add(until(() -> screen() instanceof TitleScreen, 60, "title again"));
        steps.add(act(() -> { check("progress saved", new File(data, "save.json").isFile() && new File(data, "settings.json").isFile() && dp.configFile.isFile(), data.getAbsolutePath()); report(); Gdx.app.exit(); }));
    }

    private boolean hasButton(String label) { for (int i = 0; i < 80; i++) { FocusModel.Item it = nav().fm.lastItem(i); if (it == null) return false; if (it.label.equals(label)) return true; } return false; }

    private void hover(float vx, float vy) {
        com.badlogic.gdx.math.Vector2 v = new com.badlogic.gdx.math.Vector2(vx, vy); game.ui.viewport.project(v);
        src.mx = Math.round(v.x); src.my = Gdx.graphics.getHeight() - Math.round(v.y);      // Camera.project is y-up, mouse coordinates are y-down
    }
    private void click(int button) { game.ui.touchDown(src.mx, src.my, 0, button); game.ui.touchUp(src.mx, src.my, 0, button); }

    private void report() {
        if (playDeltas.isEmpty()) return;
        List<Float> s = new ArrayList<>(playDeltas); java.util.Collections.sort(s);
        double avg = 0; for (float f : s) avg += f; avg /= s.size();
        System.out.println(String.format("SCENARIO_TIMING frames=%d avgMs=%.2f p95Ms=%.2f maxMs=%.2f (software OpenGL, not representative of a GPU)", s.size(), avg * 1000, s.get((int) (s.size() * 0.95)) * 1000, s.get(s.size() - 1) * 1000));
    }

    // ------------------------------------------------------------------ ApplicationListener

    @Override public void create() {
        dp = new DesktopPlatform(data, () -> src);
        game = new ClimbGame(data, dp);
        game.create();
        build();
    }
    @Override public void resize(int w, int h) { game.resize(w, h); }
    @Override public void render() {
        frame++;
        if (collecting) playDeltas.add(Gdx.graphics.getDeltaTime());
        if (cur < steps.size() && steps.get(cur).tick()) cur++;
        game.render();
        src.endFrame();
        if (frame > 20000) { check("scenario finished in time", false, "step " + cur + "/" + steps.size() + " on " + screenName()); Gdx.app.exit(); }
    }
    @Override public void pause() { game.pause(); }
    @Override public void resume() { game.resume(); }
    @Override public void dispose() { game.dispose(); }
}

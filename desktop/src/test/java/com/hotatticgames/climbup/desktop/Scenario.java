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
import com.hotatticgames.climbup.SaveStore;
import com.hotatticgames.climbup.SeedCode;
import com.hotatticgames.climbup.Settings;
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
    /** Build 64: two course codes typed on the picker (one on the pad, one on the keyboard); the climb that follows must be exactly that course. */
    private static final long PAD_SEED = 0x5EED_C0DE_0000_0064L, KEY_SEED = 987654321987654321L;
    private static final String PAD_CODE = SeedCode.encode(PAD_SEED), KEY_CODE = SeedCode.encode(KEY_SEED);

    public static void main(String[] args) {
        File data = new File(args.length > 0 ? args[0] : "build/scenario/data"), shots = new File(args.length > 1 ? args[1] : "build/scenario/shots");
        deleteTree(data); data.mkdirs(); shots.mkdirs();      // every run starts from a first-time install
        Lwjgl3ApplicationConfiguration c = new Lwjgl3ApplicationConfiguration();
        c.setTitle("Upwardly scenario"); c.setWindowedMode(1280, 720); c.useVsync(false); c.setForegroundFPS(60); c.disableAudio(Boolean.getBoolean("scenario.noaudio"));
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
    private Step goTo(String label, int nth) { return goTo(label::equals, nth, label); }
    /** The nth settings toggle (an ON / OFF button) on the shown tab: a toggle's focus item is its value, not its name. */
    private Step goToToggle(int nth) { return goTo(l -> l.equals("ON") || l.equals("OFF"), nth, "toggle " + nth); }
    private Step goTo(java.util.function.Predicate<String> match, int nth, String label) {
        return new Step() {
            int pause, tries;
            public boolean tick() {
                if (pause-- > 0) return false;
                FocusModel fm = nav().fm;
                int target = -1, seen = 0;
                for (int i = 0; i < 80; i++) { FocusModel.Item it = fm.lastItem(i); if (it == null) break; if (match.test(it.label) && seen++ == nth) { target = i; break; } }
                if (target < 0) { if (++tries > 120) { check("goTo " + label, false, "button not found on " + screenName()); return true; } return false; }
                if (fm.focus == target) return true;
                if (++tries > 60) { FocusModel.Item ff = fm.focused(); check("goTo " + label, false, "could not reach on " + screenName() + " focus=" + (ff == null ? null : ff.label + "@" + ff.x + "," + ff.y) + " target=" + fm.lastItem(target).label + "@" + fm.lastItem(target).x + "," + fm.lastItem(target).y + " count=" + fm.count()); return true; }
                FocusModel.Item f = fm.focused(), t = fm.lastItem(target);
                float dx = t.x + t.w / 2 - (f.x + f.w / 2), dy = t.y + t.h / 2 - (f.y + f.h / 2);
                int key = Math.abs(dy) >= Math.abs(dx) * 0.6f ? (dy > 0 ? Input.Keys.UP : Input.Keys.DOWN) : (dx > 0 ? Input.Keys.RIGHT : Input.Keys.LEFT);
                if (isArrow(f) && (key == Input.Keys.LEFT || key == Input.Keys.RIGHT) && Math.abs(dy) > 2f) key = dy > 0 ? Input.Keys.UP : Input.Keys.DOWN;     // left/right on a - / + row adjusts its value (MenuNav): leave the row vertically first
                if (Boolean.getBoolean("scenario.trace")) System.out.println("SCENARIO_TRACE goTo " + label + " focus=" + f.label + " key=" + Input.Keys.toString(key));
                src.tapKey(key); pause = 2;
                return false;
            }
        };
    }
    private Step goTo(String label) { return goTo(label, 0); }
    /** A settings row's - or + button: left/right while it has the focus presses the row's other arrow instead of moving. */
    private static boolean isArrow(FocusModel.Item f) { return f != null && (f.label.equals("-") || f.label.equals("+")); }

    // ---- the same, driven only through a controller (no keyboard, no mouse): what a Steam Deck or couch player does
    private FakePad pad() { return src.pads.isEmpty() ? null : src.pads.get(0); }
    /** One controller press: held for two frames, then released. */
    private Step padTap(Ctl c) { return new Step() { int n; public boolean tick() { FakePad p = pad(); if (p == null) { check("padTap " + c, false, "no controller connected"); return true; } if (n == 0) p.set(c, 1f); if (n == 2) p.set(c, 0f); return ++n > 3; } }; }
    /** Taps the d-pad (press, release, settle) until the button whose label matches has the focus - like goTo, but on the controller. */
    private Step padGoTo(String label, int nth) {
        return new Step() {
            int pause, tries; Ctl held;
            public boolean tick() {
                FakePad p = pad(); if (p == null) { check("padGoTo " + label, false, "no controller connected"); return true; }
                if (held != null) { p.set(held, 0f); held = null; pause = 3; return false; }       // the press lasted one frame: a clean edge, never a held repeat
                if (pause-- > 0) return false;
                FocusModel fm = nav().fm;
                int target = -1, seen = 0;
                for (int i = 0; i < 80; i++) { FocusModel.Item it = fm.lastItem(i); if (it == null) break; if (it.label.equals(label) && seen++ == nth) { target = i; break; } }
                if (target < 0) { if (++tries > 120) { check("padGoTo " + label, false, "button not found on " + screenName()); return true; } return false; }
                if (fm.focus == target) return true;
                if (++tries > 90) { FocusModel.Item ff = fm.focused(); check("padGoTo " + label, false, "could not reach on " + screenName() + " focus=" + (ff == null ? null : ff.label)); return true; }
                FocusModel.Item f = fm.focused(), t = fm.lastItem(target);
                Ctl c;
                if (f == null) c = Ctl.DPAD_DOWN;
                else {
                    float dx = t.x + t.w / 2 - (f.x + f.w / 2), dy = t.y + t.h / 2 - (f.y + f.h / 2);
                    c = Math.abs(dy) >= Math.abs(dx) * 0.6f ? (dy > 0 ? Ctl.DPAD_UP : Ctl.DPAD_DOWN) : (dx > 0 ? Ctl.DPAD_RIGHT : Ctl.DPAD_LEFT);
                    if (isArrow(f) && (c == Ctl.DPAD_LEFT || c == Ctl.DPAD_RIGHT) && Math.abs(dy) > 2f) c = dy > 0 ? Ctl.DPAD_UP : Ctl.DPAD_DOWN;      // as in goTo: a - / + row takes left/right as its own adjustment
                }
                p.set(c, 1f); held = c;
                return false;
            }
        };
    }
    private Step padGoTo(String label) { return padGoTo(label, 0); }
    /** A couple of frames of nothing pressed between a navigation and the press that follows it. */
    private Step padIdle() { return wait(2); }

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
        steps.add(act(() -> check("title uses the Upwardly name", dp.title().equals("Upwardly") && Gdx.graphics != null, dp.title())));
        steps.add(shot("02_title"));
        // --- Build 65: F9 on the title screen: a PNG at the back buffer's size, upright (the orange logo sits in the top part of the picture and nowhere near the bottom)
        steps.add(act(() -> shotsBefore = countShots()));
        steps.add(tap(Input.Keys.F9));
        steps.add(until(() -> countShots() >= shotsBefore + 1, 120, "title screenshot written")); steps.add(wait(5));
        steps.add(act(() -> {
            File[] pngs = newShots(shotsBefore);
            check("F9 on the title wrote a screenshot", pngs.length == 1, pngs.length + " new in " + game.screenshotDir());
            if (pngs.length == 1) {
                int[] dims = pngDims(pngs[0]);
                check("title screenshot is a PNG at the back buffer's size", dims != null && dims[0] == Gdx.graphics.getBackBufferWidth() && dims[1] == Gdx.graphics.getBackBufferHeight(), dims == null ? "unreadable" : dims[0] + "x" + dims[1]);
                check("title screenshot is upright (orange logo pixels in the top part, none in the mirrored bottom row)", logoUpright(pngs[0]), orangeNote(pngs[0]));
            }
        }));
        steps.add(tap(Input.Keys.S)); steps.add(wait(3));
        steps.add(act(() -> check("down arrow moves focus to NEW RUN (always on the title since build 64)", nav().fm.focused() != null && nav().fm.focused().label.equals("NEW RUN"), String.valueOf(nav().fm.focused() == null ? null : nav().fm.focused().label))));
        steps.add(tap(Input.Keys.S)); steps.add(wait(3));
        steps.add(act(() -> check("a second down moves focus to SETTINGS", nav().fm.focused() != null && nav().fm.focused().label.equals("SETTINGS"), String.valueOf(nav().fm.focused() == null ? null : nav().fm.focused().label))));
        steps.add(shot("03_title_focus"));
        // --- mouse: hover focuses, left click activates, right click does not (MORE opens the panel that holds PLAYERS, HERO, TIMES, CREDITS, EXIT since build 60)
        steps.add(act(() -> { FocusModel.Item it = null; for (int i = 0; i < 20; i++) { FocusModel.Item c = nav().fm.lastItem(i); if (c == null) break; if (c.label.equals("MORE")) it = c; } if (it == null) check("MORE button on the title", false, screenName()); else hover(it.x + it.w / 2, it.y + it.h / 2); }));
        steps.add(wait(3));
        steps.add(act(() -> check("mouse hover focuses MORE", nav().fm.focused() != null && nav().fm.focused().label.equals("MORE"), String.valueOf(nav().fm.focused() == null ? null : nav().fm.focused().label))));
        steps.add(act(() -> click(Input.Buttons.RIGHT))); steps.add(wait(3));
        steps.add(act(() -> check("right click does not activate", screen() instanceof TitleScreen && !hasButton("CREDITS"), screenName())));
        steps.add(act(() -> click(Input.Buttons.LEFT))); steps.add(wait(3));
        steps.add(act(() -> check("left click opens the MORE panel", screen() instanceof TitleScreen && hasButton("CREDITS") && hasButton("PLAYERS") && hasButton("TIMES"), screenName())));
        steps.add(shot("04_more"));
        steps.add(goTo("CREDITS")); steps.add(tap(Input.Keys.ENTER)); steps.add(wait(5));
        steps.add(act(() -> check("CREDITS opens from the MORE panel", screenName().equals("CreditsScreen"), screenName())));
        steps.add(shot("05_credits")); steps.add(wait(30)); steps.add(tap(Input.Keys.ESCAPE)); steps.add(wait(5));
        steps.add(act(() -> check("escape goes back from credits", screen() instanceof TitleScreen, screenName())));
        steps.add(wait(40));
        // --- settings, controls tab (keyboard only)
        steps.add(goTo("SETTINGS")); steps.add(tap(Input.Keys.ENTER)); steps.add(wait(30));
        steps.add(act(() -> check("settings opened with the desktop tabs", screen() instanceof SettingsScreen && hasButton("CONTROLS") && hasButton("DISPLAY"), screenName())));
        steps.add(shot("05_settings_audio"));
        steps.add(goTo("DISPLAY")); steps.add(tap(Input.Keys.ENTER)); steps.add(wait(10)); steps.add(shot("06_settings_window"));
        // --- Build 66: DISPLAY MODE has - / + (WINDOWED -> FULLSCREEN -> BORDERLESS, clamped), the windowed picker never offers the desktop-covering size, STREAMER MODE is a per-machine switch
        steps.add(goTo("+", 0)); steps.add(tap(Input.Keys.ENTER)); steps.add(wait(30));
        steps.add(act(() -> check("DISPLAY MODE + goes to FULLSCREEN", dp.cfg.display == DesktopConfig.DisplayMode.FULLSCREEN && Gdx.graphics.isFullscreen(), dp.cfg.display + " " + Gdx.graphics.getWidth() + "x" + Gdx.graphics.getHeight())));
        steps.add(goTo("+", 0)); steps.add(tap(Input.Keys.ENTER)); steps.add(wait(30));
        steps.add(act(() -> check("DISPLAY MODE + again goes to BORDERLESS (a window at the screen's size)", dp.cfg.display == DesktopConfig.DisplayMode.BORDERLESS && !Gdx.graphics.isFullscreen(), dp.cfg.display + " " + Gdx.graphics.getWidth() + "x" + Gdx.graphics.getHeight())));
        steps.add(shot("06b_settings_borderless"));
        steps.add(goTo("+", 0)); steps.add(tap(Input.Keys.ENTER)); steps.add(wait(10));
        steps.add(act(() -> check("+ past BORDERLESS stays there (no wrap-around)", dp.cfg.display == DesktopConfig.DisplayMode.BORDERLESS, String.valueOf(dp.cfg.display))));
        // the keyboard way down a - / + row: LEFT while its + has the focus presses the row's - (MenuNav's volume-style rows); twice brings the window back
        steps.add(tap(Input.Keys.LEFT)); steps.add(wait(30));
        steps.add(act(() -> check("LEFT on the DISPLAY MODE row steps back to FULLSCREEN", dp.cfg.display == DesktopConfig.DisplayMode.FULLSCREEN, String.valueOf(dp.cfg.display))));
        steps.add(tap(Input.Keys.LEFT)); steps.add(wait(30));
        steps.add(act(() -> check("LEFT again is back in the 1280x720 window", dp.cfg.display == DesktopConfig.DisplayMode.WINDOWED && !Gdx.graphics.isFullscreen() && Gdx.graphics.getWidth() == 1280 && Gdx.graphics.getHeight() == 720, dp.cfg.display + " " + Gdx.graphics.getWidth() + "x" + Gdx.graphics.getHeight())));
        steps.add(goTo("+", 1)); steps.add(tap(Input.Keys.ENTER)); steps.add(wait(20));
        steps.add(act(() -> check("the windowed RESOLUTION picker never offers the size that covers the desktop (this display has only that one, so the window stays 1280x720)", dp.cfg.width == 1280 && dp.cfg.height == 720 && Gdx.graphics.getWidth() == 1280 && Gdx.graphics.getHeight() == 720, dp.cfg.width + "x" + dp.cfg.height + " desktop " + dp.display.nativeSize()[0] + "x" + dp.display.nativeSize()[1])));
        steps.add(goToToggle(1)); steps.add(tap(Input.Keys.ENTER)); steps.add(wait(6));
        steps.add(act(() -> check("STREAMER MODE switched on and saved; a lost focus no longer pauses", dp.cfg.streamerMode && DesktopConfig.load(dp.configFile).streamerMode && !DesktopLauncher.pauseOnFocusLoss(dp.cfg, false), "memory " + dp.cfg.streamerMode + " disk " + DesktopConfig.load(dp.configFile).streamerMode)));
        steps.add(shot("06c_settings_streamer_on"));
        steps.add(goToToggle(1)); steps.add(tap(Input.Keys.ENTER)); steps.add(wait(6));
        steps.add(act(() -> check("STREAMER MODE off again (the default), on disk too; a lost focus pauses as before", !dp.cfg.streamerMode && !DesktopConfig.load(dp.configFile).streamerMode && DesktopLauncher.pauseOnFocusLoss(dp.cfg, false), String.valueOf(dp.cfg.streamerMode))));
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
        // --- Build 65: F9 twice in the same second during play: two PNGs at the back buffer's size, upright, with distinct names, the confirmation only after the write
        steps.add(act(() -> { shotsBefore = countShots(); shotSeedAtPlay = game.save.seed; }));
        steps.add(tap(Input.Keys.F9)); steps.add(wait(2)); steps.add(tap(Input.Keys.F9));
        steps.add(until(() -> countShots() >= shotsBefore + 2, 120, "two F9 screenshots written")); steps.add(wait(5));
        steps.add(shot("15b_screenshot_saved_line"));          // the SCREENSHOT SAVED plate with the folder, as the player sees it (never in the captured file itself)
        steps.add(act(() -> {
            File[] pngs = newShots(shotsBefore);
            check("F9 wrote two new screenshots", pngs.length == 2, pngs.length + " new in " + game.screenshotDir());
            if (pngs.length == 2) {
                check("screenshot names are timestamped and distinct", pngs[0].getName().matches("upwardly-\\d{8}-\\d{6}(-\\d+)?\\.png") && pngs[1].getName().matches("upwardly-\\d{8}-\\d{6}(-\\d+)?\\.png") && !pngs[0].getName().equals(pngs[1].getName()), pngs[0].getName() + " " + pngs[1].getName());
                int[] dims = pngDims(pngs[0]);
                check("screenshot is a PNG at the back buffer's size", dims != null && dims[0] == Gdx.graphics.getBackBufferWidth() && dims[1] == Gdx.graphics.getBackBufferHeight(), dims == null ? "unreadable" : dims[0] + "x" + dims[1] + " vs " + Gdx.graphics.getBackBufferWidth() + "x" + Gdx.graphics.getBackBufferHeight());
            }
            check("the confirmation line shows once the file is written", game.screenshotMessage().startsWith("SCREENSHOT SAVED"), game.screenshotMessage());
            check("a screenshot changes nothing about the run", game.save.seed == shotSeedAtPlay && playScreen() != null && !hasButton("RESUME"), screenName());
        }));
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
        // --- the controller that was in use is gone: the run paused itself (build 62); the pause menu says so
        steps.add(wait(10));
        steps.add(act(() -> check("unplugging the controller in use paused the run by itself", playScreen() != null && hasButton("RESUME") && game.save.seed == seedAtPlay, screenName())));
        steps.add(shot("17_pause"));
        // --- controls during a paused run, resume
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
        // --- controller only, from here on: the keyboard and mouse are never touched again (Steam Deck / couch play)
        steps.add(act(() -> { src.pads.clear(); src.pads.add(new FakePad("deck", "Steam Deck Controller")); })); steps.add(wait(5));
        steps.add(act(() -> check("a Steam Deck's own controls are a controller with the Xbox layout", dp.input.activePad() != null && dp.input.family() == PadFamily.XBOX, String.valueOf(dp.input.family()))));
        steps.add(padTap(Ctl.A)); steps.add(wait(3));         // a deliberate press makes it the device in use
        steps.add(act(() -> check("prompts follow the Deck's controller", dp.input.promptDevice() == InputManager.Device.CONTROLLER, String.valueOf(dp.input.promptDevice()))));
        // unplug the pad that is being used mid-run: the run pauses by itself; plug it back in and resume with the pad
        steps.add(act(() -> { pad().connected = false; src.pads.clear(); })); steps.add(wait(8));
        steps.add(act(() -> check("unplugging the controller in use pauses the run", playScreen() != null && hasButton("RESUME") && game.save.seed == seedAtPlay, screenName())));
        steps.add(shot("22_pad_unplugged_pause"));
        steps.add(act(() -> src.pads.add(new FakePad("deck", "Steam Deck Controller")))); steps.add(wait(5));
        steps.add(padGoTo("RESUME")); steps.add(padIdle()); steps.add(padTap(Ctl.A)); steps.add(wait(10));
        steps.add(act(() -> check("plugged back in: the pad resumes the same run", playScreen() != null && !hasButton("RESUME") && game.save.seed == seedAtPlay && dp.input.promptDevice() == InputManager.Device.CONTROLLER, screenName() + " " + dp.input.promptDevice())));
        // pause with the pad, every pause-menu screen, back out, and the 1280 x 800 Deck panel
        steps.add(act(() -> Gdx.graphics.setWindowedMode(1280, 800))); steps.add(wait(20));
        steps.add(act(() -> check("window at the Steam Deck's 1280x800", Gdx.graphics.getWidth() == 1280 && Gdx.graphics.getHeight() == 800, Gdx.graphics.getWidth() + "x" + Gdx.graphics.getHeight())));
        steps.add(shot("23_deck_play_1280x800"));
        steps.add(padTap(Ctl.START)); steps.add(wait(30));
        steps.add(act(() -> check("the pad's Menu button pauses", playScreen() != null && hasButton("RESUME"), screenName()))); steps.add(shot("24_deck_pause_1280x800"));
        steps.add(padGoTo("TIMES")); steps.add(padIdle()); steps.add(padTap(Ctl.A)); steps.add(wait(30));
        steps.add(act(() -> check("TIMES opens from the pause menu on the pad", screenName().equals("TimesScreen"), screenName()))); steps.add(shot("25_deck_times_1280x800"));
        steps.add(padTap(Ctl.B)); steps.add(wait(30));
        steps.add(act(() -> check("B backs out of TIMES to the pause menu", playScreen() != null && hasButton("RESUME"), screenName())));
        steps.add(padGoTo("SETTINGS")); steps.add(padIdle()); steps.add(padTap(Ctl.A)); steps.add(wait(30));
        steps.add(act(() -> check("SETTINGS opens on the pad", screen() instanceof SettingsScreen, screenName())));
        for (String tab : new String[]{"ACCESS", "ABOUT", "DISPLAY", "HUD", "GRAPHICS", "CONTROLS", "GENERAL"}) {
            steps.add(padGoTo(tab)); steps.add(padIdle()); steps.add(padTap(Ctl.A)); steps.add(wait(12));
            steps.add(act(() -> check("settings tab " + tab + " reachable on the pad", screen() instanceof SettingsScreen && hasButton(tab), screenName())));
            steps.add(shot("26_deck_settings_" + tab.toLowerCase(java.util.Locale.ROOT) + "_1280x800"));
        }
        steps.add(padGoTo("CONTROLS")); steps.add(padIdle()); steps.add(padTap(Ctl.A)); steps.add(wait(12));
        steps.add(padGoTo("EDIT", 1)); steps.add(padIdle()); steps.add(padTap(Ctl.A)); steps.add(wait(20));        // the CONTROLLER BINDINGS row's EDIT (the keyboard row's is the first)
        steps.add(act(() -> check("the controller bindings editor opens on the pad", screenName().equals("BindingsScreen") && hasButton("BACK"), screenName()))); steps.add(shot("27_deck_bindings_1280x800"));
        steps.add(padTap(Ctl.B)); steps.add(wait(20));
        steps.add(act(() -> check("B leaves the bindings editor", screen() instanceof SettingsScreen, screenName())));
        steps.add(padTap(Ctl.B)); steps.add(wait(30));
        steps.add(act(() -> check("B leaves settings back to the pause menu", playScreen() != null && hasButton("RESUME"), screenName())));
        steps.add(padGoTo("SAVE & EXIT")); steps.add(padIdle()); steps.add(padTap(Ctl.A)); steps.add(until(() -> screen() instanceof TitleScreen, 90, "title on the pad")); steps.add(wait(40));
        steps.add(shot("28_deck_title_1280x800"));
        // the title's MORE panel, the players list and a new player typed on the picker - all on the pad
        steps.add(padGoTo("MORE")); steps.add(padIdle()); steps.add(padTap(Ctl.A)); steps.add(wait(20));
        steps.add(act(() -> check("MORE panel opens on the pad", screen() instanceof TitleScreen && hasButton("PLAYERS") && hasButton("CREDITS"), screenName()))); steps.add(shot("29_deck_more_1280x800"));
        steps.add(padGoTo("PLAYERS")); steps.add(padIdle()); steps.add(padTap(Ctl.A)); steps.add(wait(20));
        steps.add(act(() -> check("PLAYERS opens on the pad", screenName().equals("PlayersScreen"), screenName()))); steps.add(shot("30_deck_players_1280x800"));
        steps.add(padGoTo("NEW PLAYER")); steps.add(padIdle()); steps.add(padTap(Ctl.A)); steps.add(wait(20));
        steps.add(act(() -> check("the name picker opens on the pad", screenName().equals("NameScreen") && hasButton("OK") && hasButton("D"), screenName())));
        for (String key : new String[]{"D", "E", "C", "K"}) { steps.add(padGoTo(key)); steps.add(padIdle()); steps.add(padTap(Ctl.A)); steps.add(wait(4)); }
        steps.add(shot("31_deck_name_1280x800"));
        steps.add(padGoTo("OK")); steps.add(padIdle()); steps.add(padTap(Ctl.A)); steps.add(wait(30));
        steps.add(act(() -> check("a player named on the pad alone exists and is selected", screen() instanceof TitleScreen && game.profiles.last() != null && "DECK".equals(game.profiles.last().name), screenName() + " " + (game.profiles.last() == null ? "none" : game.profiles.last().name))));
        steps.add(padGoTo("MORE")); steps.add(padIdle()); steps.add(padTap(Ctl.A)); steps.add(wait(10));
        steps.add(padGoTo("CREDITS")); steps.add(padIdle()); steps.add(padTap(Ctl.A)); steps.add(wait(20));
        steps.add(act(() -> check("CREDITS opens on the pad", screenName().equals("CreditsScreen"), screenName())));
        steps.add(padTap(Ctl.B)); steps.add(wait(20));
        steps.add(act(() -> check("B leaves credits", screen() instanceof TitleScreen, screenName())));
        steps.add(padGoTo("PLAY")); steps.add(padIdle()); steps.add(padTap(Ctl.A)); steps.add(until(() -> playScreen() != null, 90, "play on the pad")); steps.add(wait(30));
        steps.add(act(() -> check("a climb starts for the new player on the pad", playScreen() != null, screenName())));
        steps.add(padTap(Ctl.START)); steps.add(wait(30));
        steps.add(padGoTo("SAVE & EXIT")); steps.add(padIdle()); steps.add(padTap(Ctl.A)); steps.add(until(() -> screen() instanceof TitleScreen, 90, "title again on the pad")); steps.add(wait(20));
        // --- Build 64 on the pad at 1280x800: NEW RUN asks RANDOM or SEEDED; a friend's course code typed on the picker starts exactly that course
        steps.add(padGoTo("NEW RUN")); steps.add(padIdle()); steps.add(padTap(Ctl.A)); steps.add(wait(8));
        steps.add(act(() -> check("NEW RUN on the pad asks RANDOM RUN or SEEDED RUN", screen() instanceof TitleScreen && hasButton("RANDOM RUN") && hasButton("SEEDED RUN") && hasButton("CANCEL"), screenName()))); steps.add(shot("32_deck_new_run_1280x800"));
        steps.add(padGoTo("SEEDED RUN")); steps.add(padIdle()); steps.add(padTap(Ctl.A)); steps.add(wait(8));
        steps.add(act(() -> check("SEEDED RUN opens the course code picker on the pad", screenName().equals("SeedScreen") && hasButton("OK") && hasButton("DELETE") && hasButton("CANCEL") && hasButton("2") && hasButton("Z"), screenName())));
        for (char ch : SeedCode.bare(PAD_CODE).toCharArray()) { steps.add(padGoTo(String.valueOf(ch))); steps.add(padIdle()); steps.add(padTap(Ctl.A)); steps.add(wait(3)); }
        steps.add(shot("33_deck_seed_1280x800"));
        steps.add(padGoTo("OK")); steps.add(padIdle()); steps.add(padTap(Ctl.A)); steps.add(until(() -> playScreen() != null, 90, "seeded play on the pad")); steps.add(wait(60));
        steps.add(act(() -> check("the code typed on the pad is the course being climbed", playScreen() != null && game.save.seed == PAD_SEED && SeedCode.encode(game.save.seed).equals(PAD_CODE), "seed " + game.save.seed + " code " + SeedCode.encode(game.save.seed) + " wanted " + PAD_CODE)));
        steps.add(shot("34_deck_play_seed_1280x800"));
        steps.add(padTap(Ctl.START)); steps.add(wait(30));
        steps.add(act(() -> check("paused on the pad after the seeded start", playScreen() != null && hasButton("RESUME"), screenName()))); steps.add(shot("35_deck_pause_seed_1280x800"));
        // Build 70 (owner): TAKE SCREENSHOT is no longer in the pause menu; F9, LB + RB and the phone's own screenshot remain
        steps.add(act(() -> check("the pause menu has no TAKE SCREENSHOT button", playScreen() != null && hasButton("RESUME") && !hasButton("TAKE SCREENSHOT"), screenName())));
        // Build 66: LB + RB together on the pad takes a screenshot (fixed, like F9): once per joint press, locked until both bumpers are up again
        steps.add(act(() -> shotsBefore = countShots()));
        steps.add(act(() -> { pad().set(Ctl.L1, 1f); pad().set(Ctl.R1, 1f); }));
        steps.add(until(() -> countShots() >= shotsBefore + 1, 120, "LB+RB screenshot written")); steps.add(wait(20));
        steps.add(act(() -> check("LB+RB held together wrote exactly one screenshot and holding it adds none", newShots(shotsBefore).length == 1, newShots(shotsBefore).length + " new")));
        steps.add(act(() -> pad().set(Ctl.R1, 0f))); steps.add(wait(3)); steps.add(act(() -> pad().set(Ctl.R1, 1f))); steps.add(wait(20));
        steps.add(act(() -> check("RB tapped again while LB stays held: still one (locked until both are up)", newShots(shotsBefore).length == 1, newShots(shotsBefore).length + " new")));
        steps.add(act(() -> { pad().set(Ctl.L1, 0f); pad().set(Ctl.R1, 0f); })); steps.add(wait(3));
        steps.add(act(() -> { pad().set(Ctl.L1, 1f); pad().set(Ctl.R1, 1f); }));
        steps.add(until(() -> countShots() >= shotsBefore + 2, 120, "second LB+RB screenshot written")); steps.add(wait(5));
        steps.add(act(() -> { pad().set(Ctl.L1, 0f); pad().set(Ctl.R1, 0f); check("both released and pressed together again: a second screenshot, the run still paused", newShots(shotsBefore).length == 2 && playScreen() != null && hasButton("RESUME"), newShots(shotsBefore).length + " new " + screenName()); }));
        steps.add(padGoTo("SAVE & EXIT")); steps.add(padIdle()); steps.add(padTap(Ctl.A)); steps.add(until(() -> screen() instanceof TitleScreen, 90, "title after the seeded run on the pad")); steps.add(wait(20));
        steps.add(act(() -> { src.pads.clear(); })); steps.add(wait(5));
        steps.add(act(() -> Gdx.graphics.setWindowedMode(1280, 720))); steps.add(wait(20));
        // --- Build 64 on the keyboard: the same through NEW RUN, an empty code refused, the HUD course line, the pause menu, the times table and the two HUD switches
        steps.add(until(() -> screen() instanceof TitleScreen, 60, "title for the keyboard seeded run")); steps.add(wait(20));
        steps.add(goTo("NEW RUN")); steps.add(tap(Input.Keys.ENTER)); steps.add(wait(5));
        steps.add(act(() -> check("NEW RUN asks RANDOM RUN or SEEDED RUN", screen() instanceof TitleScreen && hasButton("RANDOM RUN") && hasButton("SEEDED RUN") && hasButton("CANCEL"), screenName()))); steps.add(shot("36_new_run"));
        steps.add(goTo("SEEDED RUN")); steps.add(tap(Input.Keys.ENTER)); steps.add(wait(5));
        steps.add(act(() -> check("SEEDED RUN opens the course code picker", screenName().equals("SeedScreen") && hasButton("OK") && hasButton("2") && hasButton("Z"), screenName())));
        steps.add(goTo("OK")); steps.add(tap(Input.Keys.ENTER)); steps.add(wait(5));
        steps.add(act(() -> check("OK with nothing typed is refused and stays on the picker", screenName().equals("SeedScreen"), screenName()))); steps.add(shot("37_seed_empty_refused"));
        for (char ch : SeedCode.bare(KEY_CODE).toCharArray()) { steps.add(goTo(String.valueOf(ch))); steps.add(tap(Input.Keys.ENTER)); steps.add(wait(2)); }
        steps.add(shot("38_seed_typed"));
        steps.add(goTo("OK")); steps.add(tap(Input.Keys.ENTER)); steps.add(until(() -> playScreen() != null, 90, "seeded play")); steps.add(wait(60));
        steps.add(act(() -> check("the typed code is the course being climbed", playScreen() != null && game.save.seed == KEY_SEED && SeedCode.encode(game.save.seed).equals(KEY_CODE) && game.settings.showSeed, "seed " + game.save.seed + " code " + SeedCode.encode(game.save.seed) + " wanted " + KEY_CODE)));
        steps.add(shot("39_play_seed_hud"));
        steps.add(tap(Input.Keys.ESCAPE)); steps.add(wait(20));
        steps.add(act(() -> check("escape pauses the seeded run", playScreen() != null && hasButton("RESUME") && hasButton("TIMES"), screenName()))); steps.add(shot("40_pause_seed"));
        // the times table with something on it: an earlier run on this very course (two towers) and this run's first tower, recorded through the records' own API (what the castles call)
        steps.add(act(() -> { com.hotatticgames.climbup.RecordsData rec = game.save.records(); rec.legDone(KEY_SEED, 1, 0, 70.0f); rec.legDone(KEY_SEED, 1, 1, 81.3f); rec.endRun(KEY_SEED); rec.legDone(KEY_SEED, 1, 0, 65.2f); game.save.syncRecords();
            check("the course keeps its own bests and this run's first tower is faster than the course best before it", rec.seedBests(KEY_SEED, 1) != null && rec.seedBests(KEY_SEED, 1).bestLegs[0] == 65.2f && rec.current != null && rec.current.legSeedPrev[0] == 70.0f && rec.current.legSeedPb[0], String.valueOf(rec.seedBests(KEY_SEED, 1))); }));
        steps.add(goTo("TIMES")); steps.add(tap(Input.Keys.ENTER)); steps.add(wait(30));
        steps.add(act(() -> check("TIMES opens from the pause menu with the course", screenName().equals("TimesScreen"), screenName()))); steps.add(shot("41_times_seed"));
        steps.add(tap(Input.Keys.ESCAPE)); steps.add(wait(20));
        steps.add(act(() -> check("escape leaves TIMES for the pause menu", playScreen() != null && hasButton("RESUME"), screenName())));
        // SHOW COURSE SEED off: HUD only, saved to disk when settings close; then on again. Build 67: the HUD switches have their own HUD tab (REDUCED HUD, SHOW COURSE SEED, SHOW BEST TIMES)
        steps.add(goTo("SETTINGS")); steps.add(tap(Input.Keys.ENTER)); steps.add(wait(40)); steps.add(goTo("HUD")); steps.add(tap(Input.Keys.ENTER)); steps.add(wait(10));
        steps.add(act(() -> check("the HUD tab opens with the three HUD switches", screen() instanceof SettingsScreen && hasButton("HUD") && !hasButton("ANTI-ALIASING"), screenName()))); steps.add(shot("42_settings_hud_switches"));
        steps.add(goToToggle(1)); steps.add(tap(Input.Keys.ENTER)); steps.add(wait(4));        // REDUCED HUD, then SHOW COURSE SEED and SHOW BEST TIMES
        steps.add(act(() -> check("SHOW COURSE SEED switched off, SHOW BEST TIMES and REDUCED HUD untouched", !game.settings.showSeed && game.settings.showBestTimes && !game.settings.reducedHud, game.settings.showSeed + "/" + game.settings.showBestTimes + "/" + game.settings.reducedHud)));
        steps.add(goToToggle(2)); steps.add(tap(Input.Keys.ENTER)); steps.add(wait(4));
        steps.add(act(() -> check("SHOW BEST TIMES switched off on its own", !game.settings.showSeed && !game.settings.showBestTimes, game.settings.showSeed + "/" + game.settings.showBestTimes)));
        steps.add(tap(Input.Keys.ESCAPE)); steps.add(wait(40));
        steps.add(act(() -> { Settings onDisk = new SaveStore(data).loadSettings(); check("both switches saved to settings.json on leaving settings", playScreen() != null && hasButton("RESUME") && !onDisk.showSeed && !onDisk.showBestTimes, screenName() + " " + onDisk.showSeed + "/" + onDisk.showBestTimes); }));
        steps.add(goTo("RESUME")); steps.add(tap(Input.Keys.ENTER)); steps.add(wait(20));
        steps.add(act(() -> check("resume keeps the seeded run with the HUD switches off", playScreen() != null && game.save.seed == KEY_SEED && !hasButton("RESUME"), "seed " + game.save.seed))); steps.add(shot("43_play_seed_hud_off"));
        // Build 67: REDUCED HUD on - the height plate goes (height, tower bar, keys only); a screenshot taken then shows no HUD at all (the top-left corner of the PNG is sky, not the plate)
        steps.add(tap(Input.Keys.ESCAPE)); steps.add(wait(20));
        steps.add(goTo("SETTINGS")); steps.add(tap(Input.Keys.ENTER)); steps.add(wait(40)); steps.add(goTo("HUD")); steps.add(tap(Input.Keys.ENTER)); steps.add(wait(10));
        steps.add(goToToggle(0)); steps.add(tap(Input.Keys.ENTER)); steps.add(wait(4));
        steps.add(act(() -> check("REDUCED HUD switched on, the other two untouched", game.settings.reducedHud && !game.settings.showSeed && !game.settings.showBestTimes, game.settings.reducedHud + "/" + game.settings.showSeed + "/" + game.settings.showBestTimes)));
        steps.add(tap(Input.Keys.ESCAPE)); steps.add(wait(40));
        steps.add(act(() -> check("REDUCED HUD saved to settings.json on leaving settings", new SaveStore(data).loadSettings().reducedHud, screenName())));
        steps.add(goTo("RESUME")); steps.add(tap(Input.Keys.ENTER)); steps.add(wait(20)); steps.add(shot("43b_play_reduced_hud"));
        steps.add(act(() -> shotsBefore = countShots()));
        steps.add(tap(Input.Keys.F9));
        steps.add(until(() -> countShots() >= shotsBefore + 1, 120, "reduced-HUD screenshot written")); steps.add(wait(5));
        steps.add(act(() -> {
            File[] pngs = newShots(shotsBefore);
            check("F9 with REDUCED HUD wrote a screenshot", pngs.length == 1, pngs.length + " new in " + game.screenshotDir());
            if (pngs.length == 1) check("the reduced-HUD screenshot has nothing drawn in its top-left corner (smooth sky: luminance spread under 24; the height and bar read 38, the full plate 56)", cornerSpread(pngs[0]) < 24, "spread " + cornerSpread(pngs[0]) + " mean " + cornerLuminance(pngs[0]));
        }));
        steps.add(tap(Input.Keys.ESCAPE)); steps.add(wait(20));
        steps.add(goTo("SETTINGS")); steps.add(tap(Input.Keys.ENTER)); steps.add(wait(40)); steps.add(goTo("HUD")); steps.add(tap(Input.Keys.ENTER)); steps.add(wait(10));
        steps.add(goToToggle(0)); steps.add(tap(Input.Keys.ENTER)); steps.add(wait(4)); steps.add(goToToggle(1)); steps.add(tap(Input.Keys.ENTER)); steps.add(wait(4)); steps.add(goToToggle(2)); steps.add(tap(Input.Keys.ENTER)); steps.add(wait(4));
        steps.add(tap(Input.Keys.ESCAPE)); steps.add(wait(40));
        steps.add(act(() -> { Settings onDisk = new SaveStore(data).loadSettings(); check("both switches back on and REDUCED HUD off, in memory and on disk", game.settings.showSeed && game.settings.showBestTimes && !game.settings.reducedHud && onDisk.showSeed && onDisk.showBestTimes && !onDisk.reducedHud, game.settings.showSeed + "/" + game.settings.showBestTimes + "/" + game.settings.reducedHud + " disk " + onDisk.showSeed + "/" + onDisk.showBestTimes + "/" + onDisk.reducedHud); }));
        steps.add(goTo("SAVE & EXIT")); steps.add(tap(Input.Keys.ENTER)); steps.add(until(() -> screen() instanceof TitleScreen, 90, "title after the keyboard seeded run")); steps.add(wait(20));
        steps.add(act(() -> check("the seeded climb was saved with its course", game.save.seed == KEY_SEED && game.save.climbRules > 0, "seed " + game.save.seed + " rules " + game.save.climbRules)));
        // --- exit through the menu, then a clean shutdown
        steps.add(until(() -> screen() instanceof TitleScreen, 60, "title again"));
        steps.add(act(() -> { check("progress saved", new File(data, "players.json").isFile() && new File(new File(data, "players/p1"), "save.json").isFile() && new File(data, "settings.json").isFile() && dp.configFile.isFile(), data.getAbsolutePath()); report(); Gdx.app.exit(); }));
    }

    private boolean hasButton(String label) { for (int i = 0; i < 80; i++) { FocusModel.Item it = nav().fm.lastItem(i); if (it == null) return false; if (it.label.equals(label)) return true; } return false; }

    // ---- Build 65 screenshot checks: the files the game wrote, their size and orientation
    private int shotsBefore; private long shotSeedAtPlay;
    private File[] allShots() { File[] f = game.screenshotDir().listFiles((d, n) -> n.endsWith(".png")); if (f == null) return new File[0]; java.util.Arrays.sort(f); return f; }
    private int countShots() { return allShots().length; }
    private File[] newShots(int before) { File[] all = allShots(); return java.util.Arrays.copyOfRange(all, Math.min(before, all.length), all.length); }
    private static java.awt.image.BufferedImage readPng(File f) { try { return javax.imageio.ImageIO.read(f); } catch (Exception e) { return null; } }
    private static int[] pngDims(File f) { java.awt.image.BufferedImage i = readPng(f); return i == null ? null : new int[]{i.getWidth(), i.getHeight()}; }
    private static int luma(java.awt.image.BufferedImage i, int x, int y) { int p = i.getRGB(x, y); return (((p >> 16) & 255) + ((p >> 8) & 255) + (p & 255)) / 3; }
    /** Orange logo pixels along one row of the picture (the UPWARDLY logo is orange on sky; nothing else that colour sits at the bottom of the title). */
    private static int orangeOnRow(java.awt.image.BufferedImage i, int y) { int n = 0; for (int x = 0; x < i.getWidth(); x += 2) { int p = i.getRGB(x, y), r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255; if (r > 180 && g > 100 && g < 210 && b < 90) n++; } return n; }
    /**
     * Upright title: the logo row (18 % down) carries plenty of orange, clearly more than its mirror (82 % down, the MORE button and the platforms). Build 72: since
     * the colour grade the platforms' warm sides count as a little orange too, so a flipped picture is told by the logo's row, not by an orange-free bottom.
     */
    private static boolean logoUpright(File f) {
        java.awt.image.BufferedImage i = readPng(f); if (i == null) return false;
        int top = orangeOnRow(i, (int) (i.getHeight() * 0.18f)), bottom = orangeOnRow(i, (int) (i.getHeight() * 0.82f));
        // A mirrored picture swaps the two rows exactly, so upright means the logo row clearly beats its mirror. Build 77: the morning sky's violet fog warms
        // the platforms' sides, so the 82% row reads more orange (upright 107 vs 63 = 1.70; that picture flipped would read 63 vs 107 = 0.59); 1.5x still
        // separates the two with a wide gap (the old 2x margin left the Build 76 sky at 109 vs 50, barely above it).
        return top > 20 && top * 2 > bottom * 3;
    }
    private static String orangeNote(File f) { java.awt.image.BufferedImage i = readPng(f); return i == null ? "unreadable" : "orange at 18%: " + orangeOnRow(i, (int) (i.getHeight() * 0.18f)) + ", at 82%: " + orangeOnRow(i, (int) (i.getHeight() * 0.82f)); }
    private static int centreLuma(File f) { java.awt.image.BufferedImage i = readPng(f); return i == null ? -1 : luma(i, i.getWidth() / 2, i.getHeight() / 2); }
    /** Mean luminance of the top-left corner where the height plate sits (x 30..300, y 30..140 of a 1280-wide picture, scaled with it); the vignette makes bare sky read about 135 there, the plate about 90. */
    private static int cornerLuminance(File f) {
        java.awt.image.BufferedImage i = readPng(f); if (i == null) return -1;
        float k = i.getWidth() / 1280f; long sum = 0; int n = 0;
        for (int y = (int) (30 * k); y < (int) (140 * k); y += 2) for (int x = (int) (30 * k); x < (int) (300 * k); x += 2) { sum += luma(i, x, y); n++; }
        return n == 0 ? -1 : (int) (sum / n);
    }
    /** Spread (standard deviation) of the luminance in that corner: a smooth sky gradient is about 15, the reduced HUD's height and bar about 38, the full plate with its text about 56. */
    private static int cornerSpread(File f) {
        java.awt.image.BufferedImage i = readPng(f); if (i == null) return -1;
        float k = i.getWidth() / 1280f; double sum = 0, sq = 0; int n = 0;
        for (int y = (int) (30 * k); y < (int) (140 * k); y += 2) for (int x = (int) (30 * k); x < (int) (300 * k); x += 2) { int l = luma(i, x, y); sum += l; sq += (double) l * l; n++; }
        if (n == 0) return -1;
        double mean = sum / n;
        return (int) Math.round(Math.sqrt(Math.max(0, sq / n - mean * mean)));
    }
    private static String lumaNote(File f) { java.awt.image.BufferedImage i = readPng(f); return i == null ? "unreadable" : "top-left " + luma(i, 40, 40) + " bottom-left " + luma(i, 40, i.getHeight() - 40) + " centre " + luma(i, i.getWidth() / 2, i.getHeight() / 2); }

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

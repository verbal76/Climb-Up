package com.hotatticgames.climbup;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.InputMultiplexer;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.ScreenAdapter;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.hotatticgames.climbup.ui.Ui;

/**
 * Volume, display, accessibility, controls and data reset. Every change applies immediately.
 * Tabs: AUDIO + DISPLAY (page 0), ACCESS (1), ABOUT (2) are shared by every platform; a phone adds CONTROLS + DATA ({@link #MOBILE_CONTROLS}); the desktop
 * launcher adds its own DISPLAY / GRAPHICS / CONTROLS tabs (pages 3 and up, see {@link com.hotatticgames.climbup.platform.Platform#settingsTabs}).
 * A page holds at most 7 rows: that is what fits under the tabs on a 720-unit-high screen.
 */
public final class SettingsScreen extends ScreenAdapter {
    public static final int MOBILE_CONTROLS = 10;
    private final ClimbGame g;
    private final Screen back;
    private int page;
    private boolean confirmReset;

    public SettingsScreen(ClimbGame g, Screen back) { this(g, back, Integer.getInteger("climb.page", 0)); }
    /** @param page 0..2 are the shared tabs, {@link #MOBILE_CONTROLS} the phone's controls tab, 3 and up the launcher's extra tabs. */
    public SettingsScreen(ClimbGame g, Screen back, int page) { this.g = g; this.back = back; this.page = page; }

    @Override public void show() { Gdx.input.setInputProcessor(new InputMultiplexer(g.ui)); }
    @Override public void resize(int w, int h) { g.ui.resize(w, h); }

    private float rowY;

    /** A centred, dimmed info line that wraps inside the screen (72 px kept clear at each side for rounded corners); returns the row position below it. */
    private float aboutLines(String text, float px, float W, float y, float step) {
        java.util.List<String> lines = com.hotatticgames.climbup.ui.TextWrap.wrap(text, str -> g.ui.font.width(str, px), W - 144f);
        for (int i = 0; i < lines.size(); i++) g.ui.textC(lines.get(i), W / 2, y + 30 - i * (g.ui.font.height(px) + 10), px, Ui.DIM);
        return y - step - Math.max(0, lines.size() - 1) * (g.ui.font.height(px) + 10);
    }
    private static final String[] Q = {"LOW", "MEDIUM", "HIGH"}, HAP = {"OFF", "LOW", "HIGH"};

    // ---- Build 74 (owner): the hidden determinism check. Replays the bundled seed 12 climb (ReplayCheck) on this device's own runtime, on a background
    // thread, and shows its finish step, run clock and hashes next to what Linux, Windows and the Android emulator print, so a real phone can be compared.
    private int versionTaps;
    private static volatile boolean checkRunning;
    private static volatile String checkStatus = "REPLAYS A RECORDED CLIMB TO THE FINISH (A FEW MINUTES) AND COMPARES IT WITH THE DESKTOP.";
    private void startCheck() {
        if (checkRunning) return;
        checkRunning = true; checkStatus = "STARTING...";
        Thread t = new Thread(() -> {
            try {
                com.hotatticgames.climbup.sim.Tuning tuning = com.hotatticgames.climbup.sim.Tuning.parse(Gdx.files.internal("data/tuning.json").readString("UTF-8"));        // the bundled tuning, never an update's
                // the Android build unpacks .gz assets and drops the extension (Build 74 APK holds replays/seed12.bin), the desktop keeps the .gz
                com.badlogic.gdx.files.FileHandle gz = Gdx.files.internal(ReplayCheck.BUNDLED), raw = Gdx.files.internal(ReplayCheck.BUNDLED.substring(0, ReplayCheck.BUNDLED.length() - 3));
                boolean packed = gz.exists();
                byte[] in; try (java.io.InputStream i = packed ? new java.util.zip.GZIPInputStream(gz.read()) : raw.read()) { java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream(); byte[] b = new byte[65536]; int n; while ((n = i.read(b)) > 0) o.write(b, 0, n); in = o.toByteArray(); }      // (readAllBytes needs Android 13)
                long t0 = System.nanoTime();
                ReplayCheck.Climb c = ReplayCheck.replay(tuning, ReplayCheck.BUNDLED_SEED, in, false, 150f, f -> checkStatus = "RUNNING " + (int) (f * 100) + "%");
                String ev = ReplayCheck.hex(c.events.digest()), st = ReplayCheck.hex(c.states.digest());
                boolean same = c.finishStep == ReplayCheck.EXPECT_FINISH_STEP && c.finishClock == ReplayCheck.EXPECT_CLOCK_STEPS && ev.equals(ReplayCheck.EXPECT_EVENTS) && st.equals(ReplayCheck.EXPECT_STATES);
                checkStatus = (same ? "MATCHES THE DESKTOP. " : "DIFFERS FROM THE DESKTOP. ") + "FINISH STEP " + c.finishStep + "  CLOCK " + c.finishClock + " STEPS  TIME "
                    + ClockText.time((float) (c.finishClock / (double) RunRecord.STEPS_PER_SECOND)) + "  EVENTS " + ev + "  STATES " + st + "  (" + (System.nanoTime() - t0) / 1_000_000_000L + " S)";
            } catch (Throwable e) { checkStatus = "CHECK FAILED: " + e; }
            finally { checkRunning = false; }
        }, "determinism-check");
        t.setDaemon(true); t.setPriority(Thread.MIN_PRIORITY); t.start();
    }

    // ---- Build 76 (owner: "game play footage from all the different areas"): the hidden world picker, next to the determinism check. Starts a new climb in
    // the chosen world: the tower is grown up to that world on a background thread (a few seconds to a minute for Deep Space), then the climb starts on the
    // first route platform there. Such a climb is assisted (no best times or splits) and earns no achievements (SaveData.devPicked).
    private int pickWorld;
    private static volatile boolean pickBusy, pickReady;
    private static volatile String pickStatus = "";
    private void startPick() {
        if (pickBusy || checkRunning) return;
        pickBusy = true; final int w = pickWorld; final String name = com.hotatticgames.climbup.render.Palette.NAMES[w].toUpperCase();
        final int h = w == 0 ? 0 : (int) (w * g.tuning.zoneHeight) + 5;
        pickStatus = "PREPARING " + name + "...";
        Thread t = new Thread(() -> {
            try {
                g.beginFreshRun(null);
                ClimbGame.Run r = g.openRun(true);
                while (r.tower.topAbsY() < h + 80f) { r.tower.extend(); pickStatus = "PREPARING " + name + "  " + (int) Math.min(h, r.tower.topAbsY()) + " / " + h + " M"; }
                r.startHeight = h;
                g.save.devPicked = true; RunRecord.noteAssist(g.save, true);
                g.preparedRun = r; g.runFresh = false; pickStatus = ""; pickReady = true;
            } catch (Throwable e) { pickStatus = "WORLD PICKER FAILED: " + e; }
            finally { pickBusy = false; }
        }, "world-picker");
        t.setDaemon(true); t.start();
    }

    public float rowTop() { return rowY; }
    public void skip(float dy) { rowY -= dy; }

    public void row(String label, String value, Runnable minus, Runnable plus, Runnable tap) {
        Ui ui = g.ui; float W = ui.w();
        float cw = Math.min(980, W - 100), x = W / 2 - cw / 2, h = 64;
        float px = ui.fitSize(Math.max(3.2f, Math.min(4f * ui.tm(), (cw * 0.5f) / (label.length() * 6f))));   // fitSize keeps the label in its column under desktop pixel-snapping (mobile: no-op)
        ui.text(label, x, rowY + h / 2 - 3.5f * px, px, Ui.TEXT);
        float bx = x + cw * 0.56f, bw = cw * 0.44f;
        if (minus != null) {
            if (ui.button("-", bx, rowY, 86, h)) { minus.run(); g.audio.play("click"); g.audio.applyVolume(); }
            float vpx = ui.fitSize(Math.min(4f, (bw - 172 - 12) / Math.max(1f, value.length() * 6f)));      // a long value ("NATIVE (AUTO)") shrinks to stay between the arrows (Build 66)
            ui.textC(value, bx + 86 + (bw - 172) / 2, rowY + h / 2 - 3.5f * vpx, vpx, Ui.ACCENT);
            if (ui.button("+", bx + bw - 86, rowY, 86, h)) { plus.run(); g.audio.play("click"); g.audio.applyVolume(); }
        } else if (ui.button(value, bx, rowY, bw, h)) { tap.run(); g.audio.play("click"); }
        rowY -= h + 10;
    }

    public void toggle(String label, boolean v, Runnable flip) { row(label, v ? "ON" : "OFF", null, null, flip); }

    /** A character portrait (Build 67) in the gap between the next row's label and its value button, a little taller than the row; nothing when there is no picture. */
    private void portrait(com.badlogic.gdx.graphics.g2d.TextureRegion r) {
        if (r == null) return;
        Ui ui = g.ui; float W = ui.w();
        float cw = Math.min(980, W - 100), x = W / 2 - cw / 2, h = 64, bx = x + cw * 0.56f;
        float ps = 92f;                                                   // 28 units taller than the row: it reaches into the gaps above and below (4 lower, clear of the tab row), never over a neighbour's text
        ui.image(r, bx - ps - 12, rowY + h / 2 - ps / 2 - 4, ps, ps, 1f);
    }

    /** The ERASE ALL PLAYERS row: a first tap arms it, a second one erases (the data-reset item the spec's menu list asks for). */
    private void eraseRow() {
        row("ERASE ALL PLAYERS", confirmReset ? g.platform.tip("TAP TO CONFIRM") : "ERASE", null, null, () -> {
            if (confirmReset) { g.eraseEverything(); confirmReset = false; }
            else confirmReset = true;
        });
    }

    @Override public void render(float dt) {
        Settings s = g.settings;
        Gdx.gl.glClearColor(0.06f, 0.08f, 0.16f, 1f); Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        Ui ui = g.ui; ui.begin();
        float W = ui.w(), H = ui.h();
        ui.textC("SETTINGS", W / 2, H - 78, 7f, Ui.TEXT);
        if (ui.button("BACK", 40, H - 100, 200, 64, true)) { g.audio.play("back"); g.persist(); if (back instanceof PlayScreen) ((PlayScreen) back).resumeFromSettings(); g.setScreen(back); ui.end(); return; }
        java.util.List<com.hotatticgames.climbup.platform.Platform.SettingsTab> extra = g.platform.settingsTabs();
        boolean desktop = g.platform.desktop();
        if (extra.isEmpty()) {                                      // phone: four tabs in a centred row, the two long names wider
            String[] names = {"AUDIO + DISPLAY", "ACCESS", "CONTROLS + DATA", "ABOUT"}; int[] pages = {0, 1, MOBILE_CONTROLS, 2}; float[] weight = {1.45f, 0.85f, 1.45f, 0.85f};
            float total = Math.min(W - 120, 1160), gap = 12, x = W / 2 - total / 2, unit = (total - gap * 3) / 4.6f;
            for (int i = 0; i < names.length; i++) { float bw = unit * weight[i]; if (ui.button(names[i], x, H - 168, bw, 60, page == pages[i])) page = pages[i]; x += bw + gap; }
        } else {                                                    // desktop: the three shared tabs plus the launcher's own (display, graphics, controls) on one row
            String[] names = new String[3 + extra.size()];
            names[0] = "GENERAL"; names[1] = "ACCESS"; names[2] = "ABOUT";
            for (int i = 0; i < extra.size(); i++) names[3 + i] = extra.get(i).name();
            float total = Math.min(W - 160, 1180), gap = 10, bw = (total - gap * (names.length - 1)) / names.length, x0 = W / 2 - total / 2;   // keep a gutter from both edges (TV safe area)
            for (int i = 0; i < names.length; i++) if (ui.button(names[i], x0 + i * (bw + gap), H - 168, bw, 60, page == i)) page = i;
        }
        rowY = H - 176 - 64;
        if (page >= 3 && page - 3 < extra.size()) {
            extra.get(page - 3).draw(g, this);
        } else if (page == MOBILE_CONTROLS && !desktop) {           // CONTROLS + DATA (phone)
            row("HAPTICS", HAP[s.haptics], null, null, () -> s.haptics = (s.haptics + 1) % 3);
            toggle("LEFT-HANDED LAYOUT", s.leftHanded, () -> s.leftHanded = !s.leftHanded);
            toggle("HIGH-CONTRAST CONTROLS", s.highContrast, () -> s.highContrast = !s.highContrast);
            toggle("REDUCED HUD", s.reducedHud, () -> s.reducedHud = !s.reducedHud);          // Build 67: just the height, the tower bar and a carried key; screenshots then show no HUD
            toggle("SHOW COURSE SEED", s.showSeed, () -> s.showSeed = !s.showSeed);
            toggle("SHOW BEST TIMES", s.showBestTimes, () -> s.showBestTimes = !s.showBestTimes);
            eraseRow();
        } else if (page == 0) {                                     // AUDIO + DISPLAY / GENERAL
            portrait(com.hotatticgames.climbup.render.Portraits.of(s.character));             // Build 67: who you are choosing, left of the name
            row("CHARACTER", com.hotatticgames.climbup.render.Characters.name(s.character), null, null, () -> s.character = com.hotatticgames.climbup.render.Characters.next(s.character));
            row("MUSIC VOLUME", String.valueOf(s.music), () -> s.music = Math.max(0, s.music - 1), () -> s.music = Math.min(10, s.music + 1), null);
            row("EFFECTS VOLUME", String.valueOf(s.sfx), () -> s.sfx = Math.max(0, s.sfx - 1), () -> s.sfx = Math.min(10, s.sfx + 1), null);
            row("GRAPHICS QUALITY", Q[s.quality], null, null, () -> s.quality = (s.quality + 1) % 3);
            row("TEXT SIZE", s.textPercent(), () -> s.setTextStep(s.textStep() - 1), () -> s.setTextStep(s.textStep() + 1), null);
            toggle("REDUCED MOTION", s.reducedMotion, () -> s.reducedMotion = !s.reducedMotion);
            toggle("CONTEXTUAL TIPS", s.tips, () -> s.tips = !s.tips);
        } else if (page == 2) {                                     // ABOUT (+ the data reset on desktop, whose controls live in the launcher's own tab)
            rowY -= 24; ui.textC(g.platform.title() + "  " + Legacy.versionLine(ClimbGame.VERSION, ClimbGame.appBuild), W / 2, rowY + 30, 4.5f, Ui.TEXT);
            if (ui.tappedIn(0, rowY, W, 72)) versionTaps++;                 // Build 74: seven taps on the version line show the determinism check
            rowY -= 72;
            if (versionTaps >= 7) {
                row("DETERMINISM CHECK", checkRunning ? "RUNNING" : "RUN", null, null, this::startCheck);
                rowY = aboutLines(checkStatus, 2.4f, W, rowY, 54);
                row("WORLD PICKER", com.hotatticgames.climbup.render.Palette.NAMES[pickWorld].toUpperCase(), () -> pickWorld = (pickWorld + 4) % 5, () -> pickWorld = (pickWorld + 1) % 5, null);
                row("START IN THIS WORLD", pickBusy ? "PREPARING" : "GO", null, null, this::startPick);
                rowY = aboutLines(pickStatus.isEmpty() ? "A NEW CLIMB IN THAT WORLD, FOR FOOTAGE: IT REPLACES THE CURRENT CLIMB AND SETS NO RECORDS." : pickStatus, 2.4f, W, rowY, 54);
            }
            if (g.platform.networkAllowed()) {
                rowY = aboutLines("CONTENT: " + g.ota.describe(), 3.0f, W, rowY, 54);
                rowY = aboutLines("UPDATES ARE SIGNED AND VERIFIED. " + (g.otaClient.status.isEmpty() ? "" : g.otaClient.status.toUpperCase()), 2.4f, W, rowY, 54);
                toggle("CHECK FOR GAME UPDATES", s.otaEnabled, () -> s.otaEnabled = !s.otaEnabled);
                row("UPDATE NOW", "CHECK", null, null, () -> g.startOtaCheck(true));
            } else { ui.textC("UPDATES COME THROUGH STEAM.", W / 2, rowY + 30, 2.8f, Ui.DIM); rowY -= 64; }     // the desktop build has no update code path of its own (networkAllowed() is false)
            ui.textC(g.audio.diag(), W / 2, rowY + 30, 2.8f, Ui.DIM); rowY -= 64;
            if (g.canRestartApp()) row("APPLY UPDATE / RESTART GAME", "RESTART", null, null, () -> { g.persist(); g.restartApp(); });
            else row("RESTART AUDIO", "RESTART", null, null, () -> g.audio.restart());
            if (desktop) eraseRow();
        } else {                                                    // ACCESS: captions, colour, assists (spec section 12)
            toggle("CAPTIONS FOR SOUNDS", s.captions, () -> s.captions = !s.captions);
            row("CAPTION SIZE", s.captionSizeLabel(), null, null, () -> s.captionSize = (s.captionSize + 1) % Settings.CAPTION_SIZES.length);
            row("CAPTION BACKGROUND", s.captionBgLabel(), null, null, () -> s.captionBg = (s.captionBg + 1) % Settings.CAPTION_BGS.length);
            toggle("COLOURBLIND-SAFE COLOURS", s.colourblind, () -> s.colourblind = !s.colourblind);
            toggle("ASSIST: SLOWER SPEED", s.assistSlow, () -> s.assistSlow = !s.assistSlow);
            toggle("ASSIST: JUMP FORGIVENESS", s.assistForgive, () -> s.assistForgive = !s.assistForgive);
            row("DIFFICULTY (NEW CLIMBS)", com.hotatticgames.climbup.sim.Tuning.HARD_NAMES[Math.max(0, Math.min(com.hotatticgames.climbup.sim.Tuning.PLAYABLE_MODES - 1, s.difficulty))], null, null, () -> s.difficulty = (Math.min(s.difficulty, com.hotatticgames.climbup.sim.Tuning.PLAYABLE_MODES - 1) + 1) % com.hotatticgames.climbup.sim.Tuning.PLAYABLE_MODES);      // the modes Tuning.PLAYABLE_MODES offers (Build 81 private playtest: all three); a climb keeps the one it began with
        }
        ui.end();
        if (pickReady) { pickReady = false; if (back instanceof PlayScreen) back.dispose(); g.setScreen(new PlayScreen(g, false)); return; }      // the world picker's climb is ready
        g.autoShot("settings", dt);
    }
}

package com.hotatticgames.climbup;

import com.badlogic.gdx.Game;
import com.badlogic.gdx.Gdx;
import com.hotatticgames.climbup.audio.Audio;
import com.hotatticgames.climbup.render.Models;
import com.hotatticgames.climbup.sim.Course;
import com.hotatticgames.climbup.sim.Tower;
import com.hotatticgames.climbup.sim.CourseIO;
import com.hotatticgames.climbup.sim.Tuning;
import com.hotatticgames.climbup.ui.Ui;
import java.io.File;

/** Application root: shared services, screen flow (studio splash -> title -> play). */
public class ClimbGame extends Game {
    public static final String VERSION = "0.1.0";
    /** Android versionCode of the running APK (set by the launcher before the game starts; 0 on desktop). Used only by the test-only OTA compatibility gate. */
    public static int appBuild;

    public final File dataDir;
    public SaveStore store;
    public Settings settings;
    public SaveData save;
    public Tuning tuning;
    public com.hotatticgames.climbup.ota.OtaStore ota;
    public com.hotatticgames.climbup.ota.OtaClient otaClient;
    public Ui ui;
    public Audio audio;
    public Models models;
    /** Optional scripted run for attract mode / headless screenshots: -Dclimb.demo=true */
    public boolean demo;
    public String shotDir;
    public boolean runFresh;                 // set by the title screen's NEW CLIMB: the next PlayScreen starts a new seed

    public ClimbGame(File dataDir) { this.dataDir = dataDir; }

    @Override public void create() {
        demo = "true".equals(System.getProperty("climb.demo"));
        shotDir = System.getProperty("climb.shots");
        store = new SaveStore(dataDir);
        settings = store.loadSettings();
        save = store.loadGame();
        if (System.getProperty("climb.character") != null) settings.character = Integer.getInteger("climb.character");
        String bundled = Gdx.files.internal("data/tuning.json").readString("UTF-8");
        // FAMILY-TEST OTA (see docs/OTA.md): an applied, verified payload may replace the bundled tuning numbers; anything wrong falls back to the bundled file
        ota = new com.hotatticgames.climbup.ota.OtaStore(new File(dataDir, "ota"));
        String over = null;
        try { over = ota.startup(); } catch (Throwable t) { over = null; }
        Tuning tn = null;
        if (over != null) { try { tn = Tuning.parse(over); } catch (Throwable t) { tn = null; } }
        tuning = tn != null ? tn : Tuning.parse(bundled);
        otaClient = new com.hotatticgames.climbup.ota.OtaClient(ota, new com.hotatticgames.climbup.ota.Fetcher.Http(), appBuild);
        if (settings.otaEnabled && !demo && System.getProperty("climb.shots") == null) startOtaCheck(false);
        ui = new Ui(settings);
        audio = new Audio(settings);
        models = new Models();
        String start = System.getProperty("climb.start", demo ? "play" : "splash");
        switch (start) {
            case "title": setScreen(new TitleScreen(this)); break;
            case "settings": setScreen(new SettingsScreen(this, new TitleScreen(this))); break;
            case "credits": setScreen(new CreditsScreen(this, new TitleScreen(this))); break;
            case "play": setScreen(new PlayScreen(this, demo)); break;
            default: setScreen(new SplashScreen(this));
        }
    }

    /** Silent background check (never blocks play, never shows anything; the result only appears in Settings > About). */
    public void startOtaCheck(boolean force) {
        final com.hotatticgames.climbup.ota.OtaClient c = otaClient;
        Thread t = new Thread(() -> { try { c.check(System.currentTimeMillis(), force); } catch (Throwable ignored) { } }, "ota-check");
        t.setDaemon(true); t.start();
    }

    private int shotCount;
    private float shotClock;
    @Override public void render() { super.render(); if (audio != null) audio.update(Math.min(Gdx.graphics.getDeltaTime(), 0.25f)); }

    /** Desktop test hook: -Dclimb.shots=DIR writes a numbered screenshot about every 1.5s and exits after climb.shotCount frames. */
    public void autoShot(String prefix, float dt) {
        if (shotDir == null) return;
        shotClock += dt;
        if (shotClock < Float.parseFloat(System.getProperty("climb.shotEvery", "1.5"))) return;
        shotClock = 0;
        try {
            com.badlogic.gdx.graphics.Pixmap raw = com.badlogic.gdx.graphics.Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
            com.badlogic.gdx.graphics.Pixmap pm = new com.badlogic.gdx.graphics.Pixmap(raw.getWidth(), raw.getHeight(), com.badlogic.gdx.graphics.Pixmap.Format.RGBA8888);
            for (int row = 0; row < raw.getHeight(); row++) pm.drawPixmap(raw, 0, row, raw.getWidth(), 1, 0, raw.getHeight() - 1 - row, raw.getWidth(), 1);
            raw.dispose();
            File dir = new File(shotDir); dir.mkdirs();
            com.badlogic.gdx.graphics.PixmapIO.writePNG(Gdx.files.absolute(new File(dir, String.format("%s_%02d.png", prefix, shotCount)).getAbsolutePath()), pm);
            pm.dispose();
        } catch (Exception e) { e.printStackTrace(); }
        if (++shotCount >= Integer.getInteger("climb.shotCount", 3)) Gdx.app.exit();
    }

    /** A climb in progress: the growing tower plus the world element the player stands on. */
    public static final class Run { public Tower tower; public int startIdx; public boolean resumed; }

    /** Opens the saved climb, or starts a new one (fresh seed) when there is none or {@code fresh} is set. */
    public Run openRun(boolean fresh) {
        Run r = new Run();
        if (!fresh && save.seed != 0 && save.sliceJson != null) {
            try {
                Course d = CourseIO.fromJson(save.sliceJson);
                r.tower = new Tower(save.seed, tuning, save.slice, d);
                r.startIdx = r.tower.slices.get(0).toWorld(Math.max(0, Math.min(save.sliceCheckpoint, d.routeSize() - 1)));
                r.resumed = true;
                return r;
            } catch (Exception e) { save.seed = 0; save.sliceJson = null; }      // unreadable climb: start a new one rather than crash
        }
        String sd = System.getProperty("climb.seed");
        long seed = sd != null ? Long.parseLong(sd) : (System.nanoTime() ^ (System.currentTimeMillis() * 0x9E3779B97F4A7C15L)) & 0x7fffffffL | 1L;
        r.tower = new Tower(seed, tuning); r.startIdx = 0;
        save.seed = seed; save.slice = 0; save.sliceCheckpoint = 0; save.sliceJson = CourseIO.toJson(r.tower.slices.get(0).data);
        return r;
    }

    /** Remembers the checkpoint (world element index) so the climb can be resumed after the app is killed. */
    public void rememberCheckpoint(Tower tower, int worldIdx) {
        Tower.Slice sl = tower.sliceOf(worldIdx);
        if (save.seed != tower.seed) return;
        if (save.slice != sl.index || save.sliceJson == null) { save.slice = sl.index; save.sliceJson = CourseIO.toJson(sl.data); }
        save.sliceCheckpoint = sl.toLocal(worldIdx);
    }

    public void forgetRun() { RunRecord.forgetClimb(save); }      // records (bestSplit, bestTotals, bestFinish, lastFinish) are kept

    public void persist() { store.saveGame(save); store.saveSettings(settings); }

    @Override public void dispose() {
        persist();
        if (getScreen() != null) getScreen().dispose();
        audio.dispose(); models.dispose(); ui.dispose();
    }
}

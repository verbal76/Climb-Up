package com.hotatticgames.climbup;

import com.badlogic.gdx.Game;
import com.badlogic.gdx.Gdx;
import com.hotatticgames.climbup.audio.Audio;
import com.hotatticgames.climbup.render.Models;
import com.hotatticgames.climbup.sim.Course;
import com.hotatticgames.climbup.sim.Tower;
import com.hotatticgames.climbup.sim.Tuning;
import com.hotatticgames.climbup.ui.Ui;
import java.io.File;

/** Application root: shared services, screen flow (studio splash -> title -> play). */
public class ClimbGame extends Game {
    public static final String VERSION = "1.1.3";
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
        store = new SaveStore(dataDir); history = new HistoryStore(dataDir);
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

    public HistoryStore history;
    /** True if there is a climb in progress whose history is on disk and complete up to its checkpoint. */
    public boolean climbValid() {
        if (save.seed == 0 || history == null) return false;
        java.util.List<byte[]> h = history.read(save.seed);
        return h != null && h.size() > save.cpSlice;
    }

    /** Opens the saved climb, or starts a new one (fresh seed) when there is none or {@code fresh} is set. */
    public Run openRun(boolean fresh) {
        Run r = new Run();
        if (!fresh && save.seed != 0) {
            try {
                java.util.List<byte[]> hist = history.read(save.seed);
                if (hist != null && hist.size() > save.cpSlice) {
                    r.tower = new Tower(save.seed, tuning, hist, save.cpSlice);
                    Tower.Ref ref = new Tower.Ref(save.cpSlice, save.cpLocal);
                    int idx = r.tower.worldIndex(ref);
                    if (idx >= 0) { r.tower.setCheckpointRef(ref); r.startIdx = idx; r.resumed = true; return r; }
                }
            } catch (Exception e) { /* unreadable: fall through to a new climb */ }
            save.seed = 0;
        }
        String sd = System.getProperty("climb.seed");
        long seed = sd != null ? Long.parseLong(sd) : (System.nanoTime() ^ (System.currentTimeMillis() * 0x9E3779B97F4A7C15L)) & 0x7fffffffL | 1L;
        r.tower = new Tower(seed, tuning); r.startIdx = 0;
        Legacy.stampNewClimb(save, VERSION, appBuild, Legacy.today());
        save.seed = seed; save.cpSlice = 0; save.cpLocal = 0;
        try { history.reset(seed); syncHistory(r.tower); } catch (java.io.IOException e) { /* cannot store the climb: it still plays, it just cannot be resumed */ save.seed = 0; }
        return r;
    }

    /** Appends every slice the tower has generated since the last call to the history file. */
    public void syncHistory(Tower tower) {
        if (history == null || save.seed != tower.seed) return;
        try { for (int k = history.count(); k < tower.sliceCount(); k++) history.append(tower.blob(k)); }
        catch (java.io.IOException e) { /* keep playing; resume will use what was written */ }
    }

    /** Remembers the checkpoint so the climb can be resumed after the app is killed. */
    public void rememberCheckpoint(Tower tower, int worldIdx) {
        if (save.seed != tower.seed) return;
        Tower.Ref ref = tower.refOf(worldIdx);
        save.cpSlice = ref.slice; save.cpLocal = ref.local;
    }

    public void forgetRun() { RunRecord.forgetClimb(save); if (history != null) history.delete(); }      // records (bestSplit, bestTotals, bestFinish, lastFinish) are kept

    public void persist() { store.saveGame(save); store.saveSettings(settings); }

    @Override public void dispose() {
        persist();
        if (getScreen() != null) getScreen().dispose();
        audio.dispose(); models.dispose(); ui.dispose();
    }
}

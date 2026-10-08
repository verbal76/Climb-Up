package com.hotatticgames.climbup;

import com.badlogic.gdx.Game;
import com.badlogic.gdx.Gdx;
import com.hotatticgames.climbup.audio.Audio;
import com.hotatticgames.climbup.render.Models;
import com.hotatticgames.climbup.sim.Course;
import com.hotatticgames.climbup.sim.CourseIO;
import com.hotatticgames.climbup.sim.Tuning;
import com.hotatticgames.climbup.ui.Ui;
import java.io.File;

/** Application root: shared services, screen flow (studio splash -> title -> play). */
public class ClimbGame extends Game {
    public static final int TOWERS = 6;
    public static final String VERSION = "0.1.0";

    public final File dataDir;
    public SaveStore store;
    public Settings settings;
    public SaveData save;
    public Tuning tuning;
    public Ui ui;
    public Audio audio;
    public Models models;
    /** Optional scripted run for attract mode / headless screenshots: -Dclimb.demo=true */
    public boolean demo;
    public String shotDir;

    public ClimbGame(File dataDir) { this.dataDir = dataDir; }

    @Override public void create() {
        demo = "true".equals(System.getProperty("climb.demo"));
        shotDir = System.getProperty("climb.shots");
        store = new SaveStore(dataDir);
        settings = store.loadSettings();
        save = store.loadGame();
        tuning = Tuning.parse(Gdx.files.internal("data/tuning.json").readString("UTF-8"));
        ui = new Ui(settings);
        audio = new Audio(settings);
        models = new Models();
        String tower = System.getProperty("climb.tower"), startElem = System.getProperty("climb.startElem");
        if (tower != null) save.courseIndex = Integer.parseInt(tower);
        if (startElem != null) save.checkpoint = Integer.parseInt(startElem);
        String start = System.getProperty("climb.start", demo ? "play" : "splash");
        switch (start) {
            case "title": setScreen(new TitleScreen(this)); break;
            case "settings": setScreen(new SettingsScreen(this, new TitleScreen(this))); break;
            case "credits": setScreen(new CreditsScreen(this, new TitleScreen(this))); break;
            case "play": setScreen(new PlayScreen(this, demo)); break;
            default: setScreen(new SplashScreen(this));
        }
    }

    private int shotCount;
    private float shotClock;
    /** Desktop test hook: -Dclimb.shots=DIR writes a numbered screenshot about every 1.5s and exits after climb.shotCount frames. */
    public void autoShot(String prefix, float dt) {
        if (shotDir == null) return;
        shotClock += dt;
        if (shotClock < 1.5f) return;
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

    public Course loadCourse(int index) {
        int i = ((index % TOWERS) + TOWERS) % TOWERS + 1;
        return CourseIO.fromJson(Gdx.files.internal(String.format("courses/tower%02d.json", i)).readString("UTF-8"));
    }

    public void persist() { store.saveGame(save); store.saveSettings(settings); }

    @Override public void dispose() {
        persist();
        if (getScreen() != null) getScreen().dispose();
        audio.dispose(); models.dispose(); ui.dispose();
    }
}

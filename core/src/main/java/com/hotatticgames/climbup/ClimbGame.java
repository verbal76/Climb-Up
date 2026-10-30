package com.hotatticgames.climbup;

import com.badlogic.gdx.Game;
import com.badlogic.gdx.Gdx;
import com.hotatticgames.climbup.audio.Audio;
import com.hotatticgames.climbup.platform.Platform;
import com.hotatticgames.climbup.render.Models;
import com.hotatticgames.climbup.sim.Course;
import com.hotatticgames.climbup.sim.Tower;
import com.hotatticgames.climbup.sim.Tuning;
import com.hotatticgames.climbup.ui.Ui;
import java.io.File;

/** Application root: shared services, screen flow (studio splash -> title -> play). */
public class ClimbGame extends Game {
    public static final String VERSION = "1.3.0";
    /** Android versionCode of the running APK (set by the launcher before the game starts; 0 on desktop). Used only by the OTA compatibility gate. */
    public static int appBuild;

    public final File dataDir;
    public SaveStore store;              // the current player's files (save.json, run.json)
    public SaveStore settingsStore;      // the shared data folder: settings are not per player
    public Profiles profiles;
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
    public Long pendingSeed;                 // Build 64: the seed behind a typed course code for the next fresh climb (null = draw a random one as always)

    /** Build 64: forgets the saved climb (if any) and arranges for the next PlayScreen to start a fresh one: random ({@code seed} null) or on a typed course code. */
    public void beginFreshRun(Long seed) { forgetRun(); save.falls = 0; runFresh = true; pendingSeed = seed; persist(); }

    /** The launcher's platform services (Android: {@link Platform#MOBILE}, unchanged behaviour). */
    public final Platform platform;

    public ClimbGame(File dataDir) { this(dataDir, Platform.MOBILE); }
    public ClimbGame(File dataDir, Platform platform) { this.dataDir = dataDir; this.platform = platform; }

    @Override public void create() {
        demo = "true".equals(System.getProperty("climb.demo"));
        shotDir = System.getProperty("climb.shots");
        initStores();
        if (System.getProperty("climb.character") != null) settings.character = Integer.getInteger("climb.character");
        String bundled = Gdx.files.internal("data/tuning.json").readString("UTF-8");
        // OTA (see docs/OTA.md): an applied payload (signed manifest, verified checksum, validated numbers) may replace the bundled tuning numbers; anything wrong falls back to the bundled file
        ota = new com.hotatticgames.climbup.ota.OtaStore(new File(dataDir, "ota"));
        String over = null;
        try { over = platform.networkAllowed() ? ota.startup() : null; } catch (Throwable t) { over = null; }
        Tuning tn = null;
        if (over != null) { try { tn = Tuning.parse(over); } catch (Throwable t) { tn = null; } }
        tuning = tn != null ? tn : Tuning.parse(bundled);
        otaClient = new com.hotatticgames.climbup.ota.OtaClient(ota, new com.hotatticgames.climbup.ota.Fetcher.Http(), appBuild, Tuning.parse(bundled));
        if (settings.otaEnabled && !demo && System.getProperty("climb.shots") == null) startOtaCheck(false);
        ui = new Ui(settings);
        audio = new Audio(settings);
        models = new Models();
        platform.attach(this);
        prepareNextClimb();                        // the first slice of a new climb is made while the splash and the title are up
        String start = System.getProperty("climb.start", demo ? "play" : "splash");
        switch (start) {
            case "title": setScreen(new TitleScreen(this)); break;
            case "settings": setScreen(new SettingsScreen(this, new TitleScreen(this))); break;
            case "credits": setScreen(new CreditsScreen(this, new TitleScreen(this))); break;
            case "players": setScreen(new PlayersScreen(this, new TitleScreen(this))); break;      // test hooks for screenshots
            case "name": { TitleScreen t = new TitleScreen(this); setScreen(new NameScreen(this, new PlayersScreen(this, t), t)); break; }
            case "times": setScreen(new TimesScreen(this, new TitleScreen(this))); break;          // Build 64 screenshot hooks: the times table, the legacy list and the course code picker
            case "legacy": setScreen(new LegacyScreen(this, new TitleScreen(this))); break;
            case "seed": setScreen(new SeedScreen(this, new TitleScreen(this))); break;
            case "play": setScreen(new PlayScreen(this, demo)); break;
            default: setScreen(new SplashScreen(this));
        }
    }

    /** Silent background check (never blocks play, never shows anything; the result only appears in Settings > About). */
    public void startOtaCheck(boolean force) {
        if (!platform.networkAllowed()) return;           // the Windows build makes no network call at all
        final com.hotatticgames.climbup.ota.OtaClient c = otaClient;
        Thread t = new Thread(() -> { try { c.check(System.currentTimeMillis(), force); } catch (Throwable ignored) { } }, "ota-check");
        t.setDaemon(true); t.start();
    }

    private int shotCount;
    private float shotClock;
    @Override public void render() {
        platform.frame(Gdx.graphics.getDeltaTime());
        super.render();
        if (shotRequested) { shotRequested = false; takeScreenshot(); }      // after the screen drew its frame, before the confirmation line: the line is never in the picture
        drawScreenshotLine();
        if (audio != null) audio.update(Math.min(Gdx.graphics.getDeltaTime(), 0.25f));
    }

    // ---- player screenshots (Build 65): F9 and LB + RB on desktop (the pause-menu button was removed in Build 70)
    private boolean shotRequested;
    private volatile String shotMsg = "", shotWhere = "";
    private volatile float shotMsgT;
    private final java.util.concurrent.ExecutorService shotWriter = java.util.concurrent.Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "screenshot-writer"); t.setDaemon(true); return t; });
    /** Captures this frame once the active screen has finished drawing it. */
    public void requestScreenshot() { shotRequested = true; }
    /** True while the frame being drawn is the one that will be captured (a screen can leave its HUD out of the picture). */
    public boolean screenshotPending() { return shotRequested; }
    /** The confirmation line showing now ("" when none): "SCREENSHOT SAVED" or "SCREENSHOT FAILED: ...". */
    public String screenshotMessage() { return shotMsgT > 0f ? shotMsg : ""; }
    /** Where a player's screenshots go when the platform does not store them itself: its folder (desktop: Pictures/Upwardly/Screenshots), else the data folder's Screenshots. */
    public File screenshotDir() { File d = platform.screenshotDir(); return d != null ? d : new File(dataDir, "Screenshots"); }

    private void takeScreenshot() {
        final com.badlogic.gdx.graphics.Pixmap pm;
        try { pm = Screenshots.captureUpright(); } catch (RuntimeException e) { showScreenshotLine("SCREENSHOT FAILED: " + reason(e), ""); return; }
        final String name = Screenshots.fileName(System.currentTimeMillis());
        final File dir = screenshotDir(), fallback = new File(dataDir, "Screenshots");
        shotWriter.execute(() -> {
            try {
                byte[] png = Screenshots.encodePng(pm);
                String where = platform.saveScreenshot(name, png);
                if (where == null) {
                    File f;
                    try { f = Screenshots.writeTo(dir, name, png); }
                    catch (java.io.IOException e) { if (dir.equals(fallback)) throw e; f = Screenshots.writeTo(fallback, name, png); }      // Pictures not writable: the data folder
                    where = f.getParentFile().getAbsolutePath();
                }
                showScreenshotLine("SCREENSHOT SAVED", where);
            } catch (Throwable e) { showScreenshotLine("SCREENSHOT FAILED: " + reason(e), ""); }
            finally { Gdx.app.postRunnable(pm::dispose); }
        });
    }
    private static String reason(Throwable e) { String m = e.getMessage(); return (m == null || m.isEmpty() ? e.getClass().getSimpleName() : m).toUpperCase(java.util.Locale.ROOT); }
    private void showScreenshotLine(String msg, String where) { shotMsg = msg; shotWhere = where; shotMsgT = 3f; }

    /** The confirmation after the file is on disk: a small plate at the bottom of the screen for three seconds, drawn after everything else (and after any capture). */
    private void drawScreenshotLine() {
        if (shotMsgT <= 0f || ui == null) return;
        shotMsgT -= Math.min(Gdx.graphics.getDeltaTime(), 0.1f);
        float W = ui.w(), H = ui.h();
        String l1 = shotMsg, l2 = shotWhere.isEmpty() ? "" : Screenshots.printable(shotWhere, 70);
        float px1 = Math.min(3.4f, (W - 80f) / (Math.max(1, l1.length()) * 6f)), px2 = l2.isEmpty() ? 0f : Math.min(2.4f, (W - 80f) / (l2.length() * 6f));
        float h1 = ui.font.height(px1), h2 = l2.isEmpty() ? 0f : ui.font.height(px2) + 10f;
        float w = Math.max(ui.font.width(l1, px1), l2.isEmpty() ? 0f : ui.font.width(l2, px2)) + 48f, h = h1 + h2 + 28f;
        float x = W / 2 - w / 2, y = Math.min(H - h - 20f, platform.playHintsHeight() + 24f);
        ui.viewport.apply(); ui.batch.setProjectionMatrix(ui.viewport.getCamera().combined); ui.batch.begin();
        ui.rect(x, y, w, h, new com.badlogic.gdx.graphics.Color(0.05f, 0.07f, 0.14f, 0.85f));
        ui.textC(l1, W / 2, y + h - 14f - h1, px1, l1.startsWith("SCREENSHOT SAVED") ? com.hotatticgames.climbup.ui.Ui.GOOD : com.hotatticgames.climbup.ui.Ui.ACCENT);
        if (!l2.isEmpty()) ui.textC(l2, W / 2, y + 14f, px2, com.hotatticgames.climbup.ui.Ui.DIM);
        ui.batch.end();
    }

    @Override public void setScreen(com.badlogic.gdx.Screen screen) { super.setScreen(screen); platform.screenChanged(); }

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
    public static final class Run { public Tower tower; public int startIdx; public boolean resumed; public RunSnapshot snapshot; public int startHeight; }
    /** Build 76: a climb the hidden world picker prepared on a background thread (the tower already grown to its world); the next PlayScreen takes it. */
    public volatile Run preparedRun;

    public HistoryStore history;
    /** Save repair H2: a message for the player about their saved climb (shown once by the next play screen), or null. */
    public String saveNotice;
    /** True if there is a climb in progress whose history is on disk and complete up to its checkpoint. */
    public boolean climbValid() {
        if (save.seed == 0 || history == null) return false;
        java.util.List<byte[]> h = history.read(save.seed);
        return h != null && h.size() > save.cpSlice;
    }

    /**
     * True if ANY player has a climb in progress whose history is on disk (not only the one playing now). The OTA host holds back module switches that would change a climb,
     * so it must be told about every player's climb: another player's unseen slices would otherwise be produced by a different generator.
     */
    public boolean anyClimbInProgress() {
        if (climbValid()) return true;
        if (profiles == null) return false;
        for (Profiles.Entry e : profiles.players()) {
            if (e.id == currentPlayerId()) continue;
            File dir = profiles.dirOf(e.id);
            try {
                SaveData d = new SaveStore(dir).loadGame();
                if (d.seed == 0) continue;
                java.util.List<byte[]> h = new HistoryStore(dir).read(d.seed);
                if (h != null && h.size() > d.cpSlice) return true;
            } catch (RuntimeException ignored) { }
        }
        return false;
    }

    private final Tuning[] hardTuning = new Tuning[3];
    /** Build 79: the tuning a climb at difficulty {@code d} runs with (one shared copy per difficulty, so a prepared first slice is found again). */
    public Tuning tuningFor(int d) {
        d = Math.max(0, Math.min(2, d));
        if (d == 0) return tuning;
        Tuning t = hardTuning[d];
        if (t == null || t.radius != tuning.radius) hardTuning[d] = t = tuning.hardCopy(d);
        return t;
    }
    /** The tuning of the climb in progress, at the difficulty it began with. */
    public Tuning runTuning() { return tuningFor(save != null ? save.difficulty : 0); }
    /** The tuning the next new climb starts with (the DIFFICULTY setting). */
    public Tuning newClimbTuning() { return tuningFor(settings != null ? Math.min(settings.difficulty, Tuning.PLAYABLE_MODES - 1) : 0); }      // a saved NEVER GONNA HAPPEN setting starts PUNISHER climbs (owner 2026-10-24)

    /** Opens the saved climb, or starts a new one (fresh seed) when there is none or {@code fresh} is set. */
    public Run openRun(boolean fresh) {
        Run r = new Run();
        if (!fresh && save.seed != 0) {
            try {
                java.util.List<byte[]> hist = readableHistory(save.seed);
                RunSnapshot snap = store.loadRun();          // SAVE & EXIT: the exact moment of the climb (fits only the climb it was taken in)
                if (snap != null && (snap.seed != save.seed || hist == null || hist.size() <= snap.maxSlice)) { store.deleteRun(); snap = null; }
                if (snap != null) { save.cpSlice = snap.cpSlice; save.cpLocal = snap.cpLocal; }
                if (hist != null && hist.size() > save.cpSlice) {
                    r.tower = new Tower(save.seed, runTuning(), hist, save.cpSlice);
                    Tower.Ref ref = new Tower.Ref(save.cpSlice, save.cpLocal);
                    int idx = r.tower.worldIndex(ref);
                    if (idx >= 0) {
                        checkRulesOnResume();          // (R5)
                        try { repairHistory(save.seed, hist); } catch (java.io.IOException e) { /* the climb still resumes; the next resume cuts at the same record again */ }          // (H2: only now, once the climb really resumes from the readable start)
                        r.tower.setCheckpointRef(ref); r.startIdx = idx; r.resumed = true;
                        if (snap != null) { snap.restoreWindow(r.tower); r.snapshot = snap; }
                        return r;
                    }
                }
            } catch (Exception e) { /* unreadable: kept below, then a new climb */ }
            keepUnresumableClimb();
        }
        String sd = System.getProperty("climb.seed");
        boolean typed = pendingSeed != null || sd != null;
        long seed = pendingSeed != null ? pendingSeed : sd != null ? Long.parseLong(sd) : takeRandomSeed();   // Build 64: a typed course code wins; the random draw is unchanged (Build 71: drawn ahead, see prepareNextClimb)
        pendingSeed = null;
        Tuning nt = newClimbTuning();
        for (int tries = 0; ; tries++) {
            try { r.tower = new Tower(seed, nt); break; }
            catch (Tower.FirstSliceFailed e) {          // R6 (2026-10-28): the first slice could not be built on any attempt or backtrack
                recordGenerationFailure(seed, nt.hard, nt, "seed " + seed + " first slice: " + e.getMessage());
                if (typed || tries >= 4) {          // a typed course code is refused, never quietly swapped for another seed
                    saveNotice = "COURSE " + SeedCode.encode(seed) + " CANNOT BE BUILT ON THESE RULES";
                    throw e;
                }
                seed = drawSeed();          // a random climb: nobody chose this seed, so another random one is fine
            }
        }
        r.startIdx = 0;
        Legacy.stampNewClimb(save, VERSION, appBuild, Legacy.today());
        save.climbTuning = tuningStamp(nt); save.climbNotComparable = false;          // (R5)
        save.achZone = -1; save.achZoneFell = false;          // (achievement policy: a fresh climb tracks its first world from the start)
        save.difficulty = nt.hard;
        save.seed = seed; save.cpSlice = 0; save.cpLocal = 0;
        try { history.reset(seed); syncHistory(r.tower); } catch (java.io.IOException e) { /* cannot store the climb: it still plays, it just cannot be resumed */ save.seed = 0; }
        return r;
    }

    /**
     * Save repair H2 (2026-10-28): the stored slices of the climb, cut at the first one that does not decode (a damaged record; {@link #historyCut}
     * then says so). Every slice after it is a pure function of the slices before it, so the climb can resume from the readable start and the tower
     * makes the rest again exactly as it was. Nothing on disk is changed here: {@link #repairHistory} does that once the climb has really resumed.
     */
    private java.util.List<byte[]> readableHistory(long seed) {
        historyCut = false;
        java.util.List<byte[]> hist = history.read(seed);
        if (hist == null) return null;
        int good = 0;
        for (byte[] b : hist) { try { com.hotatticgames.climbup.sim.SliceCodec.decode(b); } catch (RuntimeException e) { break; } good++; }
        if (good == hist.size()) return hist;
        historyCut = true;
        return new java.util.ArrayList<>(hist.subList(0, good));
    }
    private boolean historyCut;

    /** Save repair H2: after a resume from the readable start, the damaged file is kept aside (copied) and the history rewritten to that start, so later appends follow good records. */
    private void repairHistory(long seed, java.util.List<byte[]> readable) throws java.io.IOException {
        if (!historyCut) return;
        java.io.File aside = new java.io.File(dataDir, "history-damaged-" + Long.toHexString(seed) + "-" + System.currentTimeMillis() + ".bin");
        java.nio.file.Files.copy(new java.io.File(dataDir, "history.bin").toPath(), aside.toPath());
        history.rewrite(seed, readable);
        historyCut = false;
    }

    /**
     * Save repair H2 (2026-10-28): a saved climb that cannot be resumed (its history is missing, unreadable or ends before its checkpoint) is never
     * silently replaced. Its history file is kept aside (renamed, not deleted), the climb is archived like any ended climb (height, castles, clock,
     * seed), and the player is told once. Only then does a new climb start.
     */
    private void keepUnresumableClimb() {
        long old = save.seed;
        java.io.File aside = history != null ? history.keepAside(old, "unresumable") : null;
        Legacy.archive(save, Legacy.today());
        save.seed = 0;
        if (store != null) store.saveGame(save);
        saveNotice = "SAVED CLIMB " + SeedCode.encode(old) + " COULD NOT BE RESUMED: ARCHIVED" + (aside != null ? ", FILE KEPT" : "");
    }

    /**
     * R5 (2026-10-28): CRC32 of the tuning a climb is generated with (part of what a course is, with the seed, the difficulty and the rules version).
     * Build 82 review (finding 8): every instance field by name, in name order, with its exact bits, so the stamp is the same on every runtime (the
     * reflection order the Json text followed is not specified, and differs between JVMs and Android).
     */
    public static int tuningStamp(Tuning t) {
        java.util.List<java.lang.reflect.Field> fs = new java.util.ArrayList<>();
        for (java.lang.reflect.Field f : Tuning.class.getDeclaredFields()) if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) fs.add(f);
        fs.sort(java.util.Comparator.comparing(java.lang.reflect.Field::getName));
        StringBuilder b = new StringBuilder("tuning-stamp-2;");
        try {
            for (java.lang.reflect.Field f : fs) {
                Class<?> k = f.getType();
                long v = k == float.class ? Float.floatToIntBits(f.getFloat(t)) : k == int.class ? f.getInt(t) : k == boolean.class ? (f.getBoolean(t) ? 1 : 0)
                        : k == double.class ? Double.doubleToLongBits(f.getDouble(t)) : k == long.class ? f.getLong(t)
                        : java.util.Arrays.deepToString(new Object[]{f.get(t)}).hashCode();          // (any other type by its contents, never an identity hash)
                b.append(f.getName()).append('=').append(v).append(';');
            }
        } catch (IllegalAccessException e) { throw new IllegalStateException(e); }
        java.util.zip.CRC32 c = new java.util.zip.CRC32();
        c.update(b.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return (int) c.getValue() | 1;          // never 0 (0 = unknown)
    }
    /** The stamp Build 82 stored (CRC32 of libGDX's Json text of the tuning): still accepted on resume, so a climb begun before this repair stays comparable on the runtime it began on. */
    static int legacyTuningStamp(Tuning t) {
        java.util.zip.CRC32 c = new java.util.zip.CRC32();
        c.update(new com.badlogic.gdx.utils.Json().toJson(t).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return (int) c.getValue() | 1;
    }

    /**
     * R5 (2026-10-28): a climb resumed on other generation rules or tuning than it began with goes on (its stored slices are kept exactly; new ones
     * follow the current rules), but its course is now mixed, so it sets no record from here on and the player is told once. Owner decision B of the
     * recovery audit: this is the recommended default.
     */
    private void checkRulesOnResume() {
        if (save.climbNotComparable) return;
        boolean rules = save.climbRules > 0 && save.climbRules != com.hotatticgames.climbup.sim.CourseGenerator.RULES_VERSION;
        int stamp = tuningStamp(runTuning());
        if (save.climbTuning != 0 && save.climbTuning != stamp && save.climbTuning == legacyTuningStamp(runTuning())) save.climbTuning = stamp;          // (finding 8: a climb stamped before the repair, same tuning: restamped, still comparable)
        boolean tune = save.climbTuning != 0 && save.climbTuning != stamp;
        if (!rules && !tune) return;
        save.climbNotComparable = true;
        saveNotice = "THIS CLIMB BEGAN ON " + (rules ? "OLDER RULES" : "OTHER TUNING") + ": ITS TIMES ARE NOT RECORDED";
    }

    /** R1 (2026-10-28): one line per generation failure (seed, difficulty, rules, tuning, what failed) in generation_failures.txt; never thrown, never shown. */
    public void recordGenerationFailure(String what) {          // the climb in play: the saved climb's seed, difficulty and tuning
        recordGenerationFailure(save != null ? save.seed : 0, save != null ? save.difficulty : -1, runTuning(), what);
    }
    /** Build 82 review (finding 7): a failure of a climb that is not (yet) the saved one - a new climb's first slice - names its own seed, difficulty and tuning. */
    void recordGenerationFailure(long seed, int difficulty, Tuning t, String what) {
        try (java.io.FileWriter w = new java.io.FileWriter(new java.io.File(dataDir, "generation_failures.txt"), true)) {
            w.write(System.currentTimeMillis() + " seed=" + seed + " difficulty=" + difficulty + " rules=" + com.hotatticgames.climbup.sim.CourseGenerator.RULES_VERSION
                + " tuning=" + Integer.toHexString(tuningStamp(t)) + " build=" + appBuild + " " + what + "\n");
        } catch (Exception ignored) { }
    }

    private Long nextRandomSeed;
    private static long drawSeed() { return (System.nanoTime() ^ (System.currentTimeMillis() * 0x9E3779B97F4A7C15L)) & 0x7fffffffL | 1L; }
    /** The seed of the next random climb: the one drawn ahead by {@link #prepareNextClimb}, or a fresh draw. */
    private long takeRandomSeed() { Long s = nextRandomSeed; nextRandomSeed = null; return s != null ? s : drawSeed(); }

    /**
     * Build 71 (owner: no freeze when a climb begins, no extra loading screen): the first slice of the climb a RANDOM RUN would start is made in the background
     * while the splash and the title are up, so starting it is instant. The seed is drawn now instead of on the tap (equally random); a typed course code
     * prepares its own slice (SeedScreen). Calling it again before that climb starts does nothing.
     */
    /**
     * Build 82 review (findings 1 and 6): the seed a fresh climb will take - a typed course code, the climb.seed hook, else the random seed drawn
     * ahead - without taking it (openRun takes it).
     */
    long freshSeed() {
        if (pendingSeed != null) return pendingSeed;
        String sd = System.getProperty("climb.seed");
        if (sd != null) return Long.parseLong(sd);
        if (nextRandomSeed == null) nextRandomSeed = drawSeed();
        return nextRandomSeed;
    }

    /**
     * Build 82 review (findings 1 and 6): after {@link #beginFreshRun}, starts making the fresh climb's first slices in the background (the same pure
     * generation {@code new Tower} does, so the course is unchanged), so a typed course code no longer freezes the frame that begins the climb.
     */
    public void prepareFreshRun() { if (tuning != null) com.hotatticgames.climbup.sim.Tower.prepare(freshSeed(), newClimbTuning()); }

    /**
     * Build 82 review (findings 1 and 6): the fresh climb once its first slices are made, else null (call again next frame). Throws
     * {@link Tower.FirstSliceFailed} (with {@link #saveNotice} set) for a typed course code that cannot be built, before any play screen exists.
     */
    public Run takeFreshRunIfReady() {
        // the selection now (seed, difficulty, the tuning instance it uses): if what is being prepared is for anything else (a stale request, the
        // title preparing the next random climb, a tuning reload), it is requested again - prepare() is idempotent for the same seed and tuning, and a
        // result for another selection is never taken (Tower keys it on both)
        prepareFreshRun();
        if (!com.hotatticgames.climbup.sim.Tower.ready(freshSeed(), newClimbTuning())) return null;
        Run r = openRun(true);
        runFresh = false;
        return r;
    }

    public void prepareNextClimb() {
        if (demo || tuning == null) return;
        String sd = System.getProperty("climb.seed");
        long seed;
        if (sd != null) seed = Long.parseLong(sd);
        else { if (nextRandomSeed == null) nextRandomSeed = drawSeed(); seed = nextRandomSeed; }
        com.hotatticgames.climbup.sim.Tower.prepare(seed, newClimbTuning());
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

    /** Hosting hook: a host that can relaunch the app (the OTA module host) overrides both. The packaged / desktop game has no behaviour here. */
    /** The version text on the title screen. The packaged game shows version and build; a hosted game may add what it is running. */
    public String versionLabel() { return Legacy.versionLine(VERSION, appBuild); }

    public boolean canRestartApp() { return false; }
    public void restartApp() { }

    public void forgetRun() { RunRecord.forgetClimb(save); if (history != null) history.delete(); if (store != null) store.deleteRun(); }

    /** Writes the exact state of the climb in progress (SAVE &amp; EXIT, pause, autosave). Nothing is written when the state cannot be saved faithfully; the previous snapshot then stays. */
    public void saveRun(Tower tower, com.hotatticgames.climbup.sim.Sim sim) {
        if (tower == null || save.seed == 0 || save.seed != tower.seed || store == null) return;
        syncHistory(tower);
        RunSnapshot r = RunSnapshot.capture(sim, tower, save.seed);
        if (r == null) return;          // DC3: nothing faithful to write this frame: the last snapshot stays (deleting it turned a pause during a fall into a checkpoint-only resume)
        store.saveRun(r);
        ResumeState.capture(save, sim);
        save.cpSlice = r.cpSlice; save.cpLocal = r.cpLocal;
        store.saveGame(save);
    }      // records (bestSplit, bestTotals, bestFinish, lastFinish) are kept

    public void persist() { if (store != null) store.saveGame(save); if (settingsStore != null) settingsStore.saveSettings(settings); }

    /**
     * Build 71 (owner): the periodic autosave. The same files as {@link #persist} and {@link #saveRun}, built now from the live state, written by the
     * background writer in order (DiskWriter), so the render thread never waits on the disk. Every other save (pause, leaving, checkpoints, the app going to
     * the background) still writes directly and first lets the queued writes finish, so nothing older can land after it.
     */
    public void autosave(Tower tower, com.hotatticgames.climbup.sim.Sim sim) {
        if (store != null) store.saveGameLater(save);
        if (settingsStore != null) settingsStore.saveSettingsLater(settings);
        if (tower == null || save.seed == 0 || save.seed != tower.seed || store == null) return;
        if (history != null) for (int k = history.count(); k < tower.sliceCount(); k++) history.appendLater(tower.blob(k));
        RunSnapshot r = RunSnapshot.capture(sim, tower, save.seed);
        if (r == null) return;          // DC3: nothing faithful to write this frame: the last snapshot stays (deleting it turned a pause during a fall into a checkpoint-only resume)
        store.saveRunLater(r);
        ResumeState.capture(save, sim);
        save.cpSlice = r.cpSlice; save.cpLocal = r.cpLocal;
        store.saveGameLater(save);
    }

    /** Loads the shared settings and the player list, and opens the player who played last (also what the tests start from). */
    void initStores() {
        settingsStore = new SaveStore(dataDir);
        settings = settingsStore.loadSettings();
        profiles = Profiles.load(dataDir);
        openPlayer(profiles.last().id);
    }

    /** Opens a player's files: their save, their stored climb (history.bin) and their run snapshot. */
    private void openPlayer(int id) {
        File dir = profiles.dirOf(id);
        store = new SaveStore(dir); history = new HistoryStore(dir);
        save = store.loadGame();
        Profiles.Entry e = profiles.get(id);
        if (e != null && !e.name.equals(save.name)) save.name = e.name;
        runFresh = false;
    }

    /** Saves the current player and opens another (their last checkpoint and records; nothing of the previous player carries over). That player is remembered for the next start. */
    public void switchPlayer(int id) {
        if (profiles.get(id) == null) return;
        if (id == currentPlayerId()) return;
        persist();
        profiles.select(id);
        openPlayer(id);
    }

    public int currentPlayerId() { return profiles.lastId(); }

    /** Makes a new player and switches to them. Returns false if the name is empty, taken, or the list is full. */
    public boolean addPlayer(String name) {
        Profiles.Entry e = profiles.create(name);
        if (e == null) return false;
        persist(); profiles.select(e.id); openPlayer(e.id);
        return true;
    }

    /** Deletes a player and everything they saved. If it was the current one, the first remaining player is opened. */
    public void deletePlayer(int id) {
        boolean current = id == currentPlayerId();
        if (!current) persist();
        profiles.delete(id);
        if (current || profiles.get(currentPlayerId()) == null) openPlayer(profiles.last().id);
    }

    /** SETTINGS > ERASE ALL PLAYERS: every player and the settings. */
    public void eraseEverything() {
        settingsStore.eraseAll(); profiles.deleteAll();
        settings = new Settings();
        openPlayer(profiles.last().id);
    }

    @Override public void dispose() {
        persist();
        if (getScreen() != null) getScreen().dispose();
        audio.dispose(); models.dispose(); com.hotatticgames.climbup.render.Portraits.dispose(); ui.dispose(); platform.dispose();
    }
}

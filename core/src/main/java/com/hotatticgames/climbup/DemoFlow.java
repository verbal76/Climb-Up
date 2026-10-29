package com.hotatticgames.climbup;

import com.badlogic.gdx.Gdx;
import com.hotatticgames.climbup.sim.Tower;
import com.hotatticgames.climbup.sim.Tuning;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The demo's one path (docs/DEMO.md): NORMAL, then PUNISHER, then NOT GONNA HAPPEN, each a fixed course from just past castle 2's door to walking
 * through castle 3's, each opened by a title card, then the promotional screen. There is no other way into play: no difficulty choice, no course
 * choice, no start height, no skipping. Each stage is a fresh tower, simulation and HUD (nothing - enemies, projectiles, keys, the club - carries over);
 * the player's settings and character do. A fall goes back to the last checkpoint as in the full game; RESTART STAGE replays only the current stage.
 */
public final class DemoFlow {
    /** Metres of course a baked stage must hold above its last castle (the window's reach; the sealed tower never makes more). */
    public static final float HEADROOM = 120f;
    /** Metres under the start castle's deck a climber may drop before the fall ends (no earlier section is ever playable). */
    public static final float FLOOR_BELOW_DECK = 4f;

    private final ClimbGame g;
    public final DemoRecipe recipe;
    public final DemoRecords records;
    private final DemoCourse[] courses = new DemoCourse[3];
    private final Tuning[] simTuning = new Tuning[3];
    private final ExecutorService prep = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "demo-stage-prep"); t.setDaemon(true); return t; });
    private Future<ClimbGame.Run> next; private int nextFor = -1;

    /** The stage being played: what PlayScreen needs to run it as a demo stage. */
    public static final class Stage {
        public final int difficulty; public final long seed; public final String title, fingerprint; public final double floorAbs; public final int fromCastle, toCastle;
        Stage(int d, long seed, String title, String fp, double floorAbs, int from, int to) { difficulty = d; this.seed = seed; this.title = title; fingerprint = fp; this.floorAbs = floorAbs; fromCastle = from; toCastle = to; }
    }

    /** Reads a bundled file (the game's internal assets; tests read the repository's assets folder). */
    private final java.util.function.Function<String, byte[]> files;

    DemoFlow(ClimbGame g, DemoRecipe recipe, DemoRecords records) { this(g, recipe, records, p -> Gdx.files.internal(p).readBytes()); }
    DemoFlow(ClimbGame g, DemoRecipe recipe, DemoRecords records, java.util.function.Function<String, byte[]> files) { this.g = g; this.recipe = recipe; this.records = records; this.files = files; }

    /** Reads the recipe from the game's assets and the records from the data folder. Throws (with the reason) when the recipe cannot be read. */
    static DemoFlow open(ClimbGame g) {
        DemoRecipe r = DemoRecipe.parse(Gdx.files.internal("demo/recipe.json").readString("UTF-8"));
        return new DemoFlow(g, r, DemoRecords.load(g.dataDir));
    }

    /** The tuning stage {@code d}'s course was generated with (this build's own, never an update's: the demo has no OTA). */
    Tuning genTuning(int d) { return g.tuningFor(d); }
    /** The same numbers, except that walking through the stage's last castle ends it (the simulation's finish castle). Generation never reads finishCastle. */
    public Tuning simTuning(int d) {
        Tuning t = simTuning[d];
        if (t == null) { t = genTuning(d).hardCopy(d); t.finishCastle = recipe.toCastle; simTuning[d] = t; }
        return t;
    }

    /** Stage {@code d}'s checked course (loaded once). Throws {@link DemoCourse.Invalid} when it does not match this build. */
    public synchronized DemoCourse course(int d) {
        if (courses[d] == null) {
            DemoRecipe.Stage st = recipe.stages.get(d);
            courses[d] = DemoCourse.load(files.apply(st.course), recipe, st, genTuning(d));
        }
        return courses[d];
    }

    /** Every stage's course checked (the title screen runs this once; a mismatch is shown, never played around). Returns null, or the reason. */
    public String verifyAll() {
        try { for (int d = 0; d < 3; d++) course(d); return null; } catch (DemoCourse.Invalid e) { return e.getMessage(); } catch (RuntimeException e) { return "demo course files: " + e; }
    }

    /** The demo's combined generator fingerprint (About, the build manifest). */
    public String fingerprint() {
        StringBuilder sb = new StringBuilder("rcp" + recipe.recipe);
        for (int d = 0; d < 3; d++) { try { sb.append(' ').append(course(d).fingerprint()); } catch (RuntimeException e) { sb.append(" INVALID"); } }
        return sb.toString();
    }

    public Stage stage(int d) {
        DemoCourse c = course(d);
        return new Stage(d, c.seed, recipe.stages.get(d).title, c.fingerprint(), recipe.fromCastle * (double) genTuning(d).castleSpacing - FLOOR_BELOW_DECK, recipe.fromCastle, recipe.toCastle);
    }

    /** A new run of stage {@code d}: its tower from the baked course, sealed, its window around the start castle; PlayScreen starts it just past that castle's door. */
    ClimbGame.Run buildRun(int d) {
        DemoCourse c = course(d);
        ClimbGame.Run r = new ClimbGame.Run();
        r.tower = new Tower(c.seed, genTuning(d), c.slices, c.startSlice);
        r.tower.sealed = true;
        r.startIdx = 0; r.startHeight = (int) (recipe.fromCastle * genTuning(d).castleSpacing);
        if (r.tower.afterCastle(recipe.fromCastle) < 0) throw new DemoCourse.Invalid("castle " + recipe.fromCastle + " is not in the window of stage " + (d + 1));
        return r;
    }

    /** The stage floor on a running simulation (PlayScreen, before every step): a fall past it ends like a fall past the bottom of the tower. */
    public static void applyFloor(com.hotatticgames.climbup.sim.Sim sim, Tower tower, Stage stage) {
        float fl = tower.local(stage.floorAbs);
        if (Float.isNaN(sim.floorOverride) || sim.floorOverride < fl) sim.floorOverride = fl;
        if (sim.floorY < fl) sim.floorY = fl;
    }

    /** Starts building stage {@code d}'s run in the background (the title card, or the stage before it, runs meanwhile). */
    public synchronized void prepare(int d) {
        if (d < 0 || d > 2 || (nextFor == d && next != null)) return;
        nextFor = d; next = prep.submit(() -> buildRun(d));
    }
    /** True when stage {@code d}'s run is ready (or failed: {@link #take} then says why). */
    public synchronized boolean ready(int d) { return nextFor == d && next != null && next.isDone(); }
    /** Stage {@code d}'s prepared run (built here if it was not prepared). */
    public synchronized ClimbGame.Run take(int d) {
        try {
            if (nextFor == d && next != null) { Future<ClimbGame.Run> f = next; next = null; nextFor = -1; return f.get(); }
            return buildRun(d);
        } catch (java.util.concurrent.ExecutionException e) { Throwable c = e.getCause(); if (c instanceof RuntimeException) throw (RuntimeException) c; throw new IllegalStateException(c); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }

    /**
     * Clears the save's run state for a new stage (the demo save is the demo's own): no climb on disk to resume (a stage always starts at its beginning),
     * no records board (stage times live in {@link DemoRecords}), the clock at zero, castles below the start counted as open, nothing carried.
     */
    void beginStage(int d) {
        SaveData s = g.save;
        RunRecord.forgetClimb(s);
        s.seed = 0; s.difficulty = d; s.climbNotComparable = true; s.devPicked = false; s.assisted = false;
        s.runTicks = 0; s.runClock = 0f; s.towers = 0; s.splits = new float[0]; s.towerTotals = new float[0]; s.finished = false; s.finishTime = 0f;
        s.towerStartClock = 0f; s.towerStartHeight = recipe.fromCastle * genTuning(d).castleSpacing; s.climbHeight = 0f;
        s.openedUpTo = recipe.fromCastle; s.keysHeld = 0; s.climbFalls = 0; s.climbHits = 0;
    }

    /** The screen that plays stage {@code d}: its title card (which then starts the stage). */
    public com.badlogic.gdx.Screen stageScreen(int d) { prepare(d); return new DemoCardScreen(g, this, d); }

    /** What comes after stage {@code d} was finished: the next stage's card, or the promotional screen. */
    public com.badlogic.gdx.Screen after(int d) { records.save(g.dataDir); return d < 2 ? stageScreen(d + 1) : new DemoPromoScreen(g, this); }

    /** Where PLAY goes: the stage the sequence has reached (the promo screen once it is finished). */
    public com.badlogic.gdx.Screen resume() { int s = resumeStage(); return s >= 3 ? new DemoPromoScreen(g, this) : stageScreen(s); }
    /** The stage PLAY continues at (3 = the promotional screen): the first stage of the sequence not yet cleared. */
    public int resumeStage() { return records.stage; }

    /** PLAY DEMO AGAIN: back to NORMAL; the bests stay. */
    public com.badlogic.gdx.Screen playAgain() { records.restartSequence(); records.save(g.dataDir); return stageScreen(0); }
}

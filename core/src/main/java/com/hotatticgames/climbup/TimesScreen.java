package com.hotatticgames.climbup;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.InputMultiplexer;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.ScreenAdapter;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.hotatticgames.climbup.sim.CourseGenerator;
import com.hotatticgames.climbup.ui.Ui;
import java.util.ArrayList;
import java.util.List;

/**
 * The times table: the ten castle-to-castle legs, the total for all ten, and the best distance in the endless climb beyond them. Best times next to the last run.
 * Build 64: every time belongs to a course (its seed code and generation rules), so the table is shown one course at a time with the full code in its header:
 * BEST (over every course), COURSE BEST (on this one), the run on it (this run, or the last run) and the signed difference run minus course best. PREV / NEXT COURSE
 * walk through every course with a recorded time, newest first; a course from other rules says so and is never compared.
 * Reachable from the title screen, the pause menu and the finish screen; BACK (or Escape / Backspace / the Android back key) returns to where it was opened from.
 */
public final class TimesScreen extends ScreenAdapter {
    private final ClimbGame g; private final Screen back;
    private boolean leave;
    private int page;                 // 0 = the run's own course (or the newest), then the other recorded courses
    private int boardNo;              // Build 79: which difficulty's times table is shown (it opens on the climb in progress's)
    public TimesScreen(ClimbGame g, Screen back) { this.g = g; this.back = back; }

    @Override public void show() {
        leave = false; page = 0; boardNo = Math.max(0, Math.min(2, g.save.difficulty));
        Gdx.input.setInputProcessor(new InputMultiplexer(g.ui, new InputAdapter() {
            @Override public boolean keyDown(int k) { if (k == Input.Keys.ESCAPE || k == Input.Keys.BACK || k == Input.Keys.BACKSPACE) { leave = true; return true; } return false; }
        }));
    }
    @Override public void resize(int w, int h) { g.ui.resize(w, h); }

    /** m:ss.t or a dash for "no time yet". */
    static String time(float s) { return s > 0f ? PlayScreen.fmtTime(s) : "--:--.---"; }
    static String metres(float m) { return m > 0f ? (long) m + " M" : "--"; }
    /** The signed difference of a run's time against a best ("-0:01.500" faster, "+0:03.200" slower), or a dash when either is missing. */
    static String diff(float run, float best) { return run > 0f && best > 0f ? PlayScreen.fmtDelta(run - best) : "--"; }
    /** Green for faster, red for slower; the "--" placeholder and FIRST are dim (a dash is not a minus). */
    /** A time (or distance) with its NEW / PB tag after it: the tag is smaller and sits a short gap after the number, never touching it or the next column (Build 67). */
    static void tagged(Ui ui, String value, String tag, float x, float y, float px, Color valueColour, Color tagColour) {
        ui.text(value, x, y, px, valueColour);
        if (tag.isEmpty()) return;
        float tpx = px * 0.72f;
        ui.text(tag, x + ui.font.width(value, px) + 0.8f * 6f * px, y + (ui.font.height(px) - ui.font.height(tpx)) * 0.5f, tpx, tagColour);
    }

    static Color diffColour(String d, Color faster, Color slower) { return d.equals("--") || d.equals("FIRST") ? Ui.DIM : d.startsWith("-") ? faster : d.startsWith("+") ? slower : Ui.DIM; }

    /** One course to show: its seed and rules, its bests, and (when it is the shown run's course) that run. */
    static final class Page {
        long seed; int rules; RecordsData.SeedBests bests; RecordsData.Run run;
        String label(boolean current) { return current ? "THIS RUN" : run != null ? "LAST RUN" : "RUN"; }
    }

    /** The courses in order: the shown run's course first (even with no times yet), then every other recorded course newest first. */
    static List<Page> pages(RecordsData rec, RecordsData.Run shown, long climbSeed, int climbRules) {
        List<Page> out = new ArrayList<>();
        long firstSeed = shown != null ? shown.seed : climbSeed; int firstRules = shown != null ? shown.rules : climbRules;
        if (firstSeed != 0) { Page p = new Page(); p.seed = firstSeed; p.rules = firstRules; p.bests = rec.seedBests(firstSeed, firstRules); p.run = shown; out.add(p); }
        for (RecordsData.SeedBests s : rec.seeds) {
            if (!out.isEmpty() && s.seed == out.get(0).seed && s.rules == out.get(0).rules) continue;
            Page p = new Page(); p.seed = s.seed; p.rules = s.rules; p.bests = s; p.run = null; out.add(p);
        }
        return out;
    }

    @Override public void render(float dt) {
        Gdx.gl.glClearColor(0.06f, 0.08f, 0.16f, 1f); Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        Ui ui = g.ui; ui.begin();
        float W = ui.w(), H = ui.h();
        RecordsData rec = g.save.board(boardNo);
        long climbSeed = boardNo == g.save.difficulty ? g.save.seed : 0L;             // the climb in progress belongs to its own mode's board only
        RecordsData.Run lr = rec.shown(climbSeed);
        int climbRules = g.save.climbRules > 0 ? g.save.climbRules : CourseGenerator.rulesOfBuild(g.save.climbBuild);
        List<Page> pages = pages(rec, lr, climbSeed, climbRules);
        if (page >= pages.size()) page = Math.max(0, pages.size() - 1);
        Page pg = pages.isEmpty() ? null : pages.get(page);
        boolean current = pg != null && pg.run != null && pg.run == rec.current;
        int legs = Math.max(1, Math.min(RecordsData.LEGS, (int) g.tuning.finishCastle));
        ui.textC(boardNo == 0 ? "TIMES TABLE" : com.hotatticgames.climbup.sim.Tuning.HARD_NAMES[boardNo] + " TIMES", W / 2, H - 56, Math.min(5.6f, (W - 360f) / ((boardNo == 2 ? 24 : 14) * 6f)), boardNo > 0 ? new Color(1f, 0.45f, 0.4f, 1f) : Ui.TEXT);
        // the course header: the full code (never shortened: two codes must never look alike) and its rules; other-rules courses are shown but not compared
        String head = pg == null ? "NO COURSE TIMED YET" : "SEED " + SeedCode.encode(pg.seed) + (pg.rules == CourseGenerator.RULES_VERSION ? "   RULES " + pg.rules : pg.rules > 0 ? "   RULES " + pg.rules + " (NOT THESE)" : "   OLD RULES");
        ui.textC(head, W / 2, H - 100, Math.min(3.6f, (W - 320f) / (head.length() * 6f)), Ui.ACCENT);
        if (pages.size() > 1) {                                                       // words, not arrows: the pixel font has no left / right angle brackets
            if (ui.button("PREV", 40, H - 112, 130, 56)) { g.audio.play("click"); page = (page + pages.size() - 1) % pages.size(); }
            if (ui.button("NEXT", W - 170, H - 112, 130, 56)) { g.audio.play("click"); page = (page + 1) % pages.size(); }
            ui.textC("COURSE " + (page + 1) + " OF " + pages.size(), W / 2, H - 128, 2.4f, Ui.DIM);
        }
        // the ten towers, the total and the infinity row share the height left between the header and the two legend lines over the BACK button; two rules of 22 px (plus a text height) separate the header, the towers and the total
        float top = H - 170, bottom = 166f, avail = top - bottom;
        float step = Math.min(58f, (avail - 44f) / (legs + 2 * 0.77f));
        int cols = 55;                                                                 // label 9, BEST 10, COURSE 13, RUN 12, DIFF 10: room for an hour-long time and its NEW / PB tag (Build 67: the tag used to rub the next column)
        float px = Math.max(2.2f, Math.min(Math.min(4.4f * Math.max(1f, ui.tm()), step * 0.11f), (W - 100f) / (cols * 6f)));
        float tw = cols * 6 * px, x0 = W / 2 - tw / 2, xBest = x0 + 10 * 6 * px, xSeed = x0 + 20 * 6 * px, xRun = x0 + 33 * 6 * px, xDiff = x0 + 45 * 6 * px, th = ui.font.height(px);
        Color gold = new Color(1f, 0.82f, 0.3f, 1f), faster = Ui.GOOD, slower = new Color(1f, 0.45f, 0.4f, 1f);
        boolean comparable = pg != null && pg.rules == CourseGenerator.RULES_VERSION;
        ui.text("TOWER", x0, top, px, Ui.DIM); ui.text("BEST", xBest, top, px, Ui.DIM); ui.text("COURSE", xSeed, top, px, Ui.DIM);
        ui.text(pg == null ? "RUN" : pg.label(current), xRun, top, px, Ui.DIM); ui.text("DIFF", xDiff, top, px, Ui.DIM);
        ui.rect(x0 - 10, top - 12, tw + 20, 3, Ui.EDGE);
        float y = top - 22 - th;
        RecordsData.SeedBests sb = pg == null ? null : pg.bests; RecordsData.Run run = pg == null ? null : pg.run;
        for (int i = 0; i < legs; i++) {
            boolean pb = run != null && run.legPb[i], spb = run != null && run.legSeedPb[i];
            float runT = run != null ? run.legs[i] : 0f, seedT = sb != null ? sb.bestLegs[i] : 0f;
            float prev = run != null && i < RecordsData.LEGS ? run.legSeedPrev[i] : 0f;        // the course best BEFORE this run's leg: what the run was measured against
            ui.text("TOWER " + (i + 1), x0, y, px, Ui.TEXT);
            ui.text(time(rec.bestLegs[i]), xBest, y, px, gold);
            tagged(ui, time(seedT), spb && seedT == runT && runT > 0f ? "NEW" : "", xSeed, y, px, Ui.TEXT, Ui.ACCENT);      // NEW: this run set the course best (the pixel font has no asterisk)
            tagged(ui, runT > 0f ? time(runT) : "--:--.---", pb ? "PB" : "", xRun, y, px, pb ? Ui.GOOD : runT > 0f ? Ui.TEXT : Ui.DIM, Ui.GOOD);
            String d = runT > 0f && prev > 0f ? diff(runT, prev) : runT > 0f && sb != null ? "FIRST" : "--";
            ui.text(d, xDiff, y, px, diffColour(d, faster, slower));
            if (i < legs - 1) y -= step;
        }
        ui.rect(x0 - 10, y - 12, tw + 20, 3, Ui.EDGE);
        y -= 22 + th;
        boolean fin = run != null && run.finished;
        float seedTotal = sb != null ? sb.bestTotal : 0f;
        ui.text("TOTAL", x0, y, px, Ui.TEXT);
        ui.text(time(rec.bestTotal), xBest, y, px, gold);
        tagged(ui, time(seedTotal), fin && run.totalSeedPb && seedTotal == run.total ? "NEW" : "", xSeed, y, px, Ui.TEXT, Ui.ACCENT);
        tagged(ui, fin ? time(run.total) : "--:--.---", fin && run.totalPb ? "PB" : "", xRun, y, px, fin && run.totalPb ? Ui.GOOD : fin ? Ui.TEXT : Ui.DIM, Ui.GOOD);
        String dt2 = fin && run.totalSeedPrev > 0f ? diff(run.total, run.totalSeedPrev) : fin && sb != null ? "FIRST" : "--";
        ui.text(dt2, xDiff, y, px, diffColour(dt2, faster, slower));
        y -= step;
        ui.text("INFINITY", x0, y, px, Ui.TEXT);
        ui.text(metres(rec.bestInfinity), xBest, y, px, gold);
        boolean inf = run != null && run.infinity > 0f;
        tagged(ui, inf ? metres(run.infinity) : "--", inf && run.infinityPb ? "PB" : "", xRun, y, px, inf && run.infinityPb ? Ui.GOOD : Ui.DIM, Ui.GOOD);
        // the legend, two short lines so it reads at 1280 wide; a course from other rules gets its warning instead
        String foot1 = !comparable && pg != null ? "TIMES FROM OTHER RULES ARE KEPT BUT NEVER COMPARED WITH THESE." : "PB = BEST OVER EVERY COURSE.   NEW = BEST ON THIS COURSE.";
        String foot2 = !comparable && pg != null ? "" : "DIFF = THIS RUN MINUS THE COURSE BEST BEFORE IT (- FASTER, + SLOWER).";
        ui.textC(foot1, W / 2, 134f, Math.min(2.4f, (W - 80f) / (foot1.length() * 6f)), Ui.DIM);
        if (!foot2.isEmpty()) ui.textC(foot2, W / 2, 110f, Math.min(2.4f, (W - 80f) / (foot2.length() * 6f)), Ui.DIM);
        if (ui.button(new String[]{"PUNISHER", "NEVER...", "NORMAL"}[boardNo], W - 360, 22, 330, 78)) { g.audio.play("click"); boardNo = (boardNo + 1) % 3; page = 0; }      // Build 79: the next difficulty's board
        if (ui.button("BACK", W / 2 - 150, 22, 300, 78, true) || leave) { g.audio.play("back"); g.setScreen(back); }
        ui.end();
        g.autoShot("times", dt);
    }
}

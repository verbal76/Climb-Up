package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import com.hotatticgames.climbup.sim.*;
import java.io.File;
import java.nio.file.Files;
import org.junit.Test;

/** The official 5,000 m run: ten castles exactly 500 m apart, five 1,000 m worlds, random obstacles between them, a one-time finish at castle 10, END RUN / CONTINUE INFINITY. */
public class OfficialRunTest {
    // ------------------------------------------------------------------ the course
    @Test public void tenCastlesStandExactly500mApartAndEverySliceIsSolvableAcrossAllFiveWorlds() throws Exception {
        Tuning t = TestUtil.tuning();
        assertEquals(1000f, t.zoneHeight, 0f); assertEquals(5, t.zoneCount);
        long seed = 7L; Course prev = null; float y = 0; int k = 0, castles = 0, slices = 0, elements = 0;
        float[] castleY = new float[11]; int[] perWorld = new int[5]; int[][] families = new int[5][Element.Type.values().length];
        while (y < 5300f) {
            Course c = CourseGenerator.chunk(seed, k++, prev, t); prev = c; slices++; y = c.get(c.routeSize() - 1).y; elements += c.size();
            Autopilot.Report r = Autopilot.run(c, t, 4000f);
            assertTrue("slice " + (k - 1) + " must be completable (link " + r.failedLink + ": " + r.failInfo + ")", r.completed);
            for (Element h : c.hazards) if (h.type == Element.Type.GATE) { castles++; assertTrue(h.skin >= 1 && h.skin <= 11); castleY[h.skin] = h.y; }
            for (int i = 1; i < c.routeSize(); i++) {
                Element e = c.get(i);
                if (e.y < 5000f - 1f && e.y >= 25f && (e.y % 1000f) >= 25f && (e.y % 1000f) <= 975f) assertEquals("world of an element at " + e.y, (int) (e.y / 1000f), e.zone);        // (a module that starts just below a boundary keeps the old world for a few metres, and one that reaches over it takes the new one)
                if (e.y < 5000f) { perWorld[Math.min(4, (int) (e.y / 1000f))]++; families[Math.min(4, (int) (e.y / 1000f))][e.type.ordinal()]++; }
            }
        }
        assertTrue("at least the ten official castles", castles >= 10);
        for (int n = 1; n <= 10; n++) assertEquals("castle " + n, n * 500f, castleY[n], 1e-3f);
        for (int n = 2; n <= 10; n++) assertEquals("spacing " + (n - 1) + " -> " + n, 500f, castleY[n] - castleY[n - 1], 1e-3f);
        for (int w = 0; w < 5; w++) {
            assertTrue("world " + (w + 1) + " has a full climb of platforms (" + perWorld[w] + ")", perWorld[w] > 150);
            int kinds = 0; for (int f : families[w]) if (f > 0) kinds++;
            assertTrue("world " + (w + 1) + " keeps a varied set of obstacle types (" + kinds + ")", kinds >= 6);
        }
        System.out.println("official course: " + slices + " slices, " + elements + " elements over " + (int) y + " m, castles " + castles);
    }

    @Test public void obstaclesDifferBetweenRunsAndAreRepeatableForATestSeed() throws Exception {
        Tuning t = TestUtil.tuning();
        String a = sig(CourseGenerator.chunk(101L, 3, CourseGenerator.chunk(101L, 2, CourseGenerator.chunk(101L, 1, CourseGenerator.chunk(101L, 0, null, t), t), t), t));
        String b = sig(CourseGenerator.chunk(202L, 3, CourseGenerator.chunk(202L, 2, CourseGenerator.chunk(202L, 1, CourseGenerator.chunk(202L, 0, null, t), t), t), t));
        String a2 = sig(CourseGenerator.chunk(101L, 3, CourseGenerator.chunk(101L, 2, CourseGenerator.chunk(101L, 1, CourseGenerator.chunk(101L, 0, null, t), t), t), t));
        assertNotEquals("two runs get different obstacle arrangements", a, b);
        assertEquals("a fixed test seed repeats", a, a2);
    }
    private static String sig(Course c) { StringBuilder sb = new StringBuilder(); for (int i = 0; i < c.routeSize(); i++) { Element e = c.get(i); sb.append(e.type).append(String.format("%.1f/%.1f;", e.s, e.y)); } return sb.toString(); }

    // ------------------------------------------------------------------ the finish
    private static Sim gateSim(int castleNo, boolean open) throws Exception {
        Tuning t = TestUtil.tuning(); Course c = TestUtil.flat(t);
        Element gate = new Element(Element.Type.GATE, 10f, 0f, 3.4f); gate.len = 7.5f; gate.color = 1; gate.skin = castleNo; gate.anchor = 0; c.hazards.add(gate);
        Sim s = Sim.startOn(c, t, 0); s.keysFree = false; s.keys = open ? 1 << 1 : 0; s.s = 6.5f; return s;
    }
    private static int walk(Sim s, float dir, int steps) { InputState in = new InputState(); int ev = 0; for (int i = 0; i < steps; i++) { in.clear(); in.moveX = dir; s.step(in); ev |= s.consumeEvents(); } return ev; }

    @Test public void onlyAnOpenedCastle10DoorEndsTheRun() throws Exception {
        Sim noKey = gateSim(10, false); int ev = walk(noKey, 1f, 400);
        assertFalse("blocked at the closed gate, no finish", noKey.finishedRun); assertEquals(0, ev & Sim.EV_FINISH);
        Sim alongside = gateSim(10, true); walk(alongside, 1f, 28);          // reaches the wall and opens it but has not entered yet
        assertFalse("standing at the entrance is not entering", alongside.finishedRun);
        for (int n = 1; n <= 9; n++) { Sim mid = gateSim(n, true); walk(mid, 1f, 400); assertFalse("castle " + n + " is only a milestone", mid.finishedRun); }
        Sim done = gateSim(10, true); int ev2 = walk(done, 1f, 400);
        assertTrue(done.finishedRun); assertEquals(Sim.EV_FINISH, ev2 & Sim.EV_FINISH);
    }

    @Test public void finishFiresOnceEvenAfterARespawnAndWalkingBackThrough() throws Exception {
        Sim s = gateSim(10, true); int finishes = 0;
        InputState in = new InputState();
        for (int i = 0; i < 500; i++) { in.clear(); in.moveX = i < 300 ? 1f : -1f; s.step(in); if ((s.consumeEvents() & Sim.EV_FINISH) != 0) finishes++; if (i == 350) { s.respawn(); s.consumeEvents(); } }
        assertEquals(1, finishes); assertTrue(s.finishedRun);
    }

    // ------------------------------------------------------------------ the record
    @Test public void clockStopsAtTheFinishAndTheTimeIsRecordedOnce() throws Exception {
        SaveData sd = new SaveData();
        for (int i = 0; i < 600; i++) RunRecord.tick(sd, 1f / 60f, true);          // ten seconds
        assertEquals(10f, sd.runClock, 0.01f);
        assertEquals(1, RunRecord.complete(sd)); float t0 = sd.finishTime;
        assertEquals(10f, t0, 0.01f); assertTrue(sd.finished); assertEquals(t0, sd.bestFinish, 0f); assertEquals(t0, sd.lastFinish, 0f);
        for (int i = 0; i < 6000; i++) RunRecord.tick(sd, 1f / 60f, true);         // CONTINUE INFINITY: a hundred more seconds of climbing
        assertEquals("the official clock does not move", t0, sd.runClock, 1e-4f);
        assertEquals("a second completion changes nothing", -1, RunRecord.complete(sd));
        assertEquals(t0, sd.finishTime, 0f); assertEquals(t0, sd.bestFinish, 0f);
    }

    @Test public void endRunKeepsTheTimesAndAFasterRunReplacesTheBest() throws Exception {
        SaveData sd = new SaveData(); sd.runClock = 4000f; assertEquals(1, RunRecord.complete(sd));
        sd.seed = 99; sd.towers = 10; RunRecord.forgetClimb(sd);                                 // END RUN
        assertEquals(4000f, sd.lastFinish, 0f); assertEquals(4000f, sd.bestFinish, 0f);
        assertFalse(sd.finished); assertEquals(0f, sd.runClock, 0f); assertEquals(0, sd.seed); assertEquals(0, sd.towers);
        sd.runClock = 4500f; assertEquals("slower run is not a record", 0, RunRecord.complete(sd));
        assertEquals(4500f, sd.lastFinish, 0f); assertEquals(4000f, sd.bestFinish, 0f);
        RunRecord.forgetClimb(sd); sd.runClock = 3900f; assertEquals(1, RunRecord.complete(sd)); assertEquals(3900f, sd.bestFinish, 0f);
    }

    @Test public void theCompletedRunSurvivesASaveAndRelaunchAndKeepsTheClockFrozen() throws Exception {
        File dir = Files.createTempDirectory("climbsave").toFile(); SaveStore st = new SaveStore(dir);
        SaveData sd = new SaveData(); sd.runClock = 3723.4f; RunRecord.complete(sd); st.saveGame(sd);
        SaveData r = new SaveStore(dir).loadGame();
        assertTrue(r.finished); assertEquals(3723.4f, r.finishTime, 0.001f); assertEquals(3723.4f, r.bestFinish, 0.001f);
        for (int i = 0; i < 600; i++) RunRecord.tick(r, 1f / 60f, true);
        assertEquals(3723.4f, r.runClock, 0.001f);
    }
}

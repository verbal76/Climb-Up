package com.hotatticgames.climbup.sim;

import static org.junit.Assert.*;

import java.nio.file.*;
import org.junit.Test;

/** Priority 1 diagnostic scoring: the owner's table, repetition control, and determinism on one generated slice. */
public class DifficultyScoreTest {
    @Test public void theOwnersScoringTable() {
        assertEquals(1f, DifficultyScore.points(Element.Type.CRAB), 0f);
        assertEquals(2f, DifficultyScore.points(Element.Type.BLOB), 0f);
        assertEquals(3f, DifficultyScore.points(Element.Type.SKULL), 0f);
        assertEquals(2f, DifficultyScore.points(Element.Type.BEE), 0f);
        assertEquals(-1f, DifficultyScore.points(Element.Type.CLUB), 0f);
        for (Element.Type k : new Element.Type[]{Element.Type.ROPE, Element.Type.CABLE, Element.Type.STATIC, Element.Type.PAD, Element.Type.SPRING}) assertEquals(0f, DifficultyScore.points(k), 0f);
        assertFalse("a club is protective, not an enemy family", DifficultyScore.enemy(Element.Type.CLUB));
    }

    @Test public void everyStaticHazardHasAPositiveWeight() {
        Element.Type[] nine = {Element.Type.SAW_H, Element.Type.SAW_V, Element.Type.PENDULUM, Element.Type.CANNON, Element.Type.AIMED,
            Element.Type.MORTAR, Element.Type.SPIKE_TRAP, Element.Type.SPIKE_BLOCK, Element.Type.SPIKE_DROP};
        for (Element.Type k : nine) { assertTrue(k + " is scored", DifficultyScore.points(k) > 0f); assertTrue(DifficultyScore.hazard(k)); assertFalse(DifficultyScore.enemy(k)); }
    }

    /** Route platforms 0-2 (5 m apart) and one decoy hanging off platform 1. */
    private static Course strip() {
        Course c = new Course(1L, 60f);
        for (int i = 0; i < 3; i++) c.add(new Element(Element.Type.STATIC, i * 6f, i * 5f, 3f));
        c.finishRoute();
        Element d = new Element(Element.Type.STATIC, 20f, 5f, 3f); d.anchor = 1; c.add(d); c.indexDecoys();
        return c;
    }
    private static Element on(Course c, Element.Type k, int anchor) { Element h = new Element(k, c.get(anchor).s, c.get(anchor).y + 1f, 1f); h.anchor = anchor; c.hazards.add(h); return h; }

    @Test public void staticHazardsScoreOnlyOnTheRoute() throws Exception {
        Course c = strip(); on(c, Element.Type.SAW_H, 0); on(c, Element.Type.SAW_V, 3);
        DifficultyScore sc = new DifficultyScore(TestTuning.get()); sc.add(c);
        DifficultyScore.Window w = sc.windows().iterator().next();
        assertEquals(1, (int) w.counts.get(Element.Type.SAW_H));
        assertNull("a saw on a decoy does not threaten the route", w.counts.get(Element.Type.SAW_V));
        assertEquals(1, w.offRouteHazards);
        assertEquals(2f, w.pos, 0f); assertEquals(1, w.staticFamilies()); assertEquals(1, w.hazardFamilies());
    }

    @Test public void hazardRepetitionIsCappedAndRawCountsKept() throws Exception {
        Course c = strip(); for (int i = 0; i < 5; i++) { Element h = on(c, Element.Type.SAW_H, 0); h.s += i * 0.5f; }
        DifficultyScore sc = new DifficultyScore(TestTuning.get()); sc.add(c);
        DifficultyScore.Window w = sc.windows().iterator().next();
        assertEquals(5, (int) w.counts.get(Element.Type.SAW_H));
        assertEquals("raw points", 10f, w.pos, 0f);
        assertEquals("adjusted: 3 full + 2 at a quarter", 2f * (3f + 0.5f), w.adjusted, 1e-6f);
    }

    @Test public void twoHazardFamiliesOnOneLinkInteract() throws Exception {
        Course c = strip(); on(c, Element.Type.SAW_H, 0); on(c, Element.Type.PENDULUM, 1);
        DifficultyScore sc = new DifficultyScore(TestTuning.get()); sc.add(c);
        DifficultyScore.Window w = sc.windows().iterator().next();
        assertEquals(DifficultyScore.INTERACTION, w.interaction, 0f);
        assertEquals(2, w.hazardFamilies());
        assertEquals(4f + DifficultyScore.INTERACTION, w.adjustedNet(), 1e-6f);
    }

    static final class TestTuning {
        static Tuning get() throws Exception { return Tuning.parse(new String(Files.readAllBytes(Paths.get("../assets/data/tuning.json")))); }
    }

    @Test public void repeatedIdenticalThreatsHaveDiminishingReturns() {
        assertEquals(3f, DifficultyScore.repeated(3, 1f), 1e-6f);
        assertEquals(3f + 0.25f * 17, DifficultyScore.repeated(20, 1f), 1e-6f);      // 20 crabs: well below 20
        assertTrue(DifficultyScore.repeated(200, 1f) < 200f * 0.3f);
    }

    @Test public void scoringIsDeterministicAndDoesNotTouchTheCourse() throws Exception {
        Tuning t = Tuning.parse(new String(Files.readAllBytes(Paths.get("../assets/data/tuning.json"))));
        Course c = CourseGenerator.chunk(1L, 0, null, t);
        String before = CourseIO.toJson(c);
        DifficultyScore a = new DifficultyScore(t), b = new DifficultyScore(t);
        a.add(c); b.add(c); a.add(c);          // adding the same slice again counts nothing twice
        assertEquals("scoring never changes the course", before, CourseIO.toJson(c));
        assertEquals(a.windows().size(), b.windows().size());
        java.util.Iterator<DifficultyScore.Window> ib = b.windows().iterator();
        for (DifficultyScore.Window w : a.windows()) { DifficultyScore.Window v = ib.next(); assertEquals(w.adjustedNet(), v.adjustedNet(), 0f); assertEquals(w.counts, v.counts); }
    }
}

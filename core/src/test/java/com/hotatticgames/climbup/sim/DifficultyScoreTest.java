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

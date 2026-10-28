package com.hotatticgames.climbup.sim;

import static org.junit.Assert.*;

import java.nio.file.*;
import java.util.*;
import org.junit.Test;

/** Owner correction directive 2026-10-26: each mode has its own profile, spike-drop platforms are protected, generation stays deterministic. */
public class ModeProfileTest {
    static Tuning tuning() throws Exception { return Tuning.parse(new String(Files.readAllBytes(Paths.get("../assets/data/tuning.json")))); }

    @Test public void everyModeHasItsOwnOrderedProfile() throws Exception {
        Tuning t = tuning();
        DifficultyProfile n = DifficultyProfile.of(t), p = DifficultyProfile.of(t.hardCopy(1)), g = DifficultyProfile.of(t.hardCopy(2));
        assertSame(DifficultyProfile.NORMAL, n); assertSame(DifficultyProfile.PUNISHER, p); assertSame(DifficultyProfile.NGH, g);
        assertTrue(n.curveShift < p.curveShift && p.curveShift < g.curveShift);
        assertTrue(n.terrainCap <= p.terrainCap && p.terrainCap <= g.terrainCap);
        assertEquals(8, n.midEnemyFromCastle); assertEquals(4, p.midEnemyFromCastle); assertEquals(1, g.midEnemyFromCastle);
        assertTrue(Double.isInfinite(n.pursuitFromY)); assertEquals(3333.0, p.pursuitFromY, 0); assertEquals(1667.0, g.pursuitFromY, 0);
    }

    @Test public void nghSpikeDropPlatformsCarryNoEnemiesAndRepeat() throws Exception {
        Tuning t = tuning().hardCopy(2);
        CourseGenerator.Framed a = null, b = null; int drops = 0;
        for (int k = 0; k < 4; k++) {
            a = CourseGenerator.chunkFramed(1L, k, a, t); b = CourseGenerator.chunkFramed(1L, k, b, t);
            assertEquals("same seed, same slice", CourseIO.toJson(a.c), CourseIO.toJson(b.c));
            Set<Integer> dropDecks = new HashSet<>();
            for (Element h : a.c.hazards) {
                if (h.type == Element.Type.SPIKE_DROP) drops++;
                if (h.type == Element.Type.SPIKE_DROP || h.type == Element.Type.MORTAR || h.type == Element.Type.CANNON || h.type == Element.Type.AIMED) dropDecks.add(h.anchor);
            }
            for (Element h : a.c.hazards) if (Lanes.lane(h.type) == Lanes.Lane.ENEMIES) assertFalse("enemy on a protected hazard platform, slice " + k, dropDecks.contains(h.anchor));
        }
        assertTrue("NGH spike-drop rows are generated again", drops > 0);
    }
}

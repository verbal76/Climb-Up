package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import com.hotatticgames.climbup.sim.*;
import java.util.EnumMap;
import org.junit.Test;

/** The endless climb: every slice from many seeds must be solvable, fair, deterministic and resumable. */
public class EndlessTest {
    private static final long[] SEEDS = {1, 2, 3, 4, 5, 6};
    private static final int SLICES = 10;

    @Test public void everySliceIsCompletableByTheSolver() throws Exception {
        Tuning t = TestUtil.tuning();
        int slices = 0;
        for (long seed : SEEDS) {
            Course prev = null;
            for (int k = 0; k < SLICES; k++) {
                Course c = CourseGenerator.chunk(seed, k, prev, t);
                Autopilot.Report r = Autopilot.run(c, t, 4000f);
                assertTrue("seed " + seed + " slice " + k + " failed at link " + r.failedLink, r.completed);
                assertTrue("checkpoint at both ends of a slice", c.get(0).checkpoint && c.get(c.goalIndex()).checkpoint);
                prev = c; slices++;
            }
        }
        System.out.println("slices solved: " + slices + " (" + slices * t.chunkHeight + " m of tower)");
    }

    @Test public void sliceIsAPureFunctionOfSeedIndexAndPreviousSlice() throws Exception {
        Tuning t = TestUtil.tuning();
        Course a0 = CourseGenerator.chunk(9, 0, null, t), a1 = CourseGenerator.chunk(9, 1, a0, t);
        Course b0 = CourseGenerator.chunk(9, 0, null, t), b1 = CourseGenerator.chunk(9, 1, b0, t);
        assertEquals(CourseIO.toJson(a1), CourseIO.toJson(b1));
        // resuming from the saved JSON of slice 1 continues with exactly the same slice 2
        Course restored = CourseIO.fromJson(CourseIO.toJson(a1));
        assertEquals(CourseIO.toJson(a1), CourseIO.toJson(restored));
        assertEquals(CourseIO.toJson(CourseGenerator.chunk(9, 2, a1, t)), CourseIO.toJson(CourseGenerator.chunk(9, 2, restored, t)));
        assertNotEquals(CourseIO.toJson(CourseGenerator.chunk(10, 1, CourseGenerator.chunk(10, 0, null, t), t)), CourseIO.toJson(a1));
    }

    @Test public void towerGrowsAndResumesFromASavedSlice() throws Exception {
        Tuning t = TestUtil.tuning();
        Tower tw = new Tower(21, t);
        for (int i = 0; i < 4; i++) tw.extend();
        assertTrue("tower is taller than four slices of climbing", tw.topY() > 4 * t.chunkHeight * 0.9f);
        Tower.Slice s2 = tw.slices.get(2);
        Course saved = CourseIO.fromJson(CourseIO.toJson(s2.data));
        Tower resumed = new Tower(21, t, s2.index, saved);
        int idx = resumed.slices.get(0).toWorld(saved.routeSize() - 1);       // the slice's last rest platform
        assertEquals("same platform after resume", tw.world.get(s2.toWorld(saved.routeSize() - 1)).y, resumed.world.get(idx).y, 1e-4f);
        resumed.extend();
        assertEquals("the tower above a resumed slice is identical", CourseIO.toJson(tw.slices.get(3).data), CourseIO.toJson(resumed.slices.get(1).data));
        // checkpoint round trip: world index -> (slice, local) -> world index
        for (int w = 0; w < tw.world.size(); w++) {
            if (tw.world.get(w).anchor >= 0) continue;
            Tower.Slice sl = tw.sliceOf(w);
            assertEquals(w, sl.toWorld(sl.toLocal(w)));
        }
    }

    @Test public void difficultyAndHazardsRiseButNeverBreakFairness() throws Exception {
        Tuning t = TestUtil.tuning();
        EnumMap<Element.Type, Integer> hz = new EnumMap<>(Element.Type.class);
        int lowHaz = 0, highHaz = 0;
        for (long seed : SEEDS) {
            Course prev = null;
            for (int k = 0; k < 14; k++) {
                Course c = CourseGenerator.chunk(seed, k, prev, t);
                for (Element h : c.hazards) { hz.merge(h.type, 1, Integer::sum); if (k < 3) lowHaz++; else if (k >= 8) highHaz++; }
                prev = c;
            }
        }
        System.out.println("hazard mix: " + hz + " early=" + lowHaz + " late=" + highHaz);
        assertEquals("nothing hazardous in the first 90 m", 0, countBelow(t, 90f));
        assertTrue("later slices carry more hazards than the first ones", highHaz > lowHaz);
        for (Element.Type ty : new Element.Type[]{Element.Type.SAW_V, Element.Type.SAW_H, Element.Type.SPIKE_TRAP, Element.Type.SPIKE_BLOCK, Element.Type.CANNON})
            assertTrue("hazard kind appears: " + ty, hz.getOrDefault(ty, 0) > 0);
    }

    private int countBelow(Tuning t, float y) {
        int n = 0;
        for (long seed : SEEDS) { Course c = CourseGenerator.chunk(seed, 0, null, t); for (Element h : c.hazards) if (h.y < y && (h.isHazard() || h.type == Element.Type.CRAB || h.type == Element.Type.BEE)) n++; }       // castle gates and keys are fine early
        return n;
    }

    @Test public void hazardsSendYouBackWithGraceAndOnlyWhenTouched() throws Exception {
        Tuning t = TestUtil.tuning();
        Course c = TestUtil.flat(t);
        Element saw = new Element(Element.Type.SPIKE_BLOCK, 10f, 0f, 1.2f); saw.len = 1f; saw.anchor = 0; c.hazards.add(saw);
        Sim s = Sim.startOn(c, t, 0);
        s.s = 8f; s.invuln = 0f;
        InputState in = new InputState();
        for (int i = 0; i < 40 && s.hits == 0; i++) { in.clear(); in.moveX = 1f; s.step(in); }
        assertEquals("walking into a spike block hurts", 1, s.hits);
        assertTrue((s.consumeEvents() & Sim.EV_HIT) != 0);
        assertEquals("respawned on the checkpoint", 0, s.onElem);
        assertTrue("grace period after respawn", s.invuln > 0f);
        // a player on another spiral layer below a trap is not touched
        c = TestUtil.flat(t);
        Element trap = new Element(Element.Type.SPIKE_TRAP, 30f, 40f, 2.4f); trap.period = 3f; trap.amp = 0.4f; trap.anchor = 0;
        c.hazards.add(trap);
        Sim low = Sim.startOn(c, t, 0); low.s = 30f; low.y = 2f; low.mode = Sim.Mode.AIR; low.onElem = -1; low.invuln = 0f;
        int hits0 = low.hits;
        for (int i = 0; i < 600; i++) { in.clear(); low.step(in); if (low.hits != hits0) break; }
        assertEquals("trap far above is harmless", hits0, low.hits);
    }

    @Test public void cannonBallsArePureFunctionsOfTime() {
        Element cn = new Element(Element.Type.CANNON, 0f, 2f, 0f); cn.len = 8f; cn.period = 4f; cn.dir = 1;
        float t = 0.35f;
        assertTrue(cn.lethalAt(t)); assertTrue(cn.sAt(t) > 0f && cn.sAt(t) <= 8f);
        float later = t + cn.period;
        assertEquals(cn.sAt(t), cn.sAt(later), 1e-3f);
        assertFalse("ball is gone for part of every cycle", cn.lethalAt(cn.period * (Element.CANNON_FLIGHT + 0.1f)));
    }

    @Test public void gateIsAWallUntilYouCarryItsKeyAndTheKeyIsOneUse() throws Exception {
        Tuning t = TestUtil.tuning();
        Course c = TestUtil.flat(t);
        Element gate = new Element(Element.Type.GATE, 12f, 0f, 3.4f); gate.len = 7.5f; gate.color = 2; gate.anchor = 0; c.hazards.add(gate);
        Element key = new Element(Element.Type.KEY, 7f, 1f, 0f); key.color = 2; key.anchor = 0; c.hazards.add(key);
        Sim s = Sim.startOn(c, t, 0); s.keysFree = false; s.keys = 0; s.s = 9f; s.invuln = 0f;
        InputState in = new InputState();
        for (int i = 0; i < 90; i++) { in.clear(); in.moveX = 1f; s.step(in); }
        assertTrue("stopped at the gate", s.s < 12f - 1.7f);
        assertTrue((s.consumeEvents() & Sim.EV_BLOCKED) != 0);
        for (int i = 0; i < 60; i++) { in.clear(); in.moveX = -1f; s.step(in); }          // walk back to the key
        assertTrue("key picked up", (s.keys & (1 << 2)) != 0);
        for (int i = 0; i < 120; i++) { in.clear(); in.moveX = 1f; s.step(in); }
        assertTrue("walked through the opened gate", s.s > 13f);
        assertEquals("the key is used up", 0, s.keys);
        s.respawn();
        assertEquals("a respawn never un-opens a gate or loses a key you hold", 0, s.keys);
        assertTrue(s.featDone[0]);
    }

    @Test public void everyCastleHasAKeyRoomBeforeIt() throws Exception {
        Tuning t = TestUtil.tuning();
        int castles = 0;
        for (long seed : SEEDS) {
            Course prev = null;
            for (int k = 0; k < 12; k++) {
                Course c = CourseGenerator.chunk(seed, k, prev, t);
                int gates = 0;
                for (Element h : c.hazards) if (h.type == Element.Type.GATE) gates++;
                castles += gates;
                int freeKeys = 0;       // a castle whose side room cannot be proven gets its key on the rest platform in front of it
                for (Element h : c.hazards) if (h.type == Element.Type.KEY && h.anchor >= 0 && c.get(h.anchor).anchor < 0) { boolean inRoom = false; for (int[] kr : c.keyRooms) if (c.hazards.get(kr[4]) == h) inRoom = true; if (!inRoom) freeKeys++; }
                assertEquals("each gate has one key: a provable key room or a free key on the route", gates, c.keyRooms.size() + freeKeys);
                for (int[] kr : c.keyRooms) {
                    Element key = c.hazards.get(kr[4]), gate = c.hazards.get(kr[5]);
                    assertEquals(Element.Type.KEY, key.type); assertEquals(Element.Type.GATE, gate.type);
                    assertEquals("key matches the castle colour", gate.color, key.color);
                    assertTrue("the key room hangs off a route platform before the gate", kr[0] < gate.anchor && c.get(kr[0]).anchor < 0);
                    assertTrue("room platforms are decoys, never route", c.get(kr[1]).anchor == kr[0]);
                }
                prev = c;
            }
        }
        System.out.println("castles with key rooms: " + castles);
        assertTrue("castles actually appear", castles >= 1);
    }

    @Test public void crabsShoveButNeverHurt_andAClubKnocksThemOff() throws Exception {
        Tuning t = TestUtil.tuning();
        Course c = TestUtil.flat(t);
        Element crab = new Element(Element.Type.CRAB, 10f, 0f, 0f); crab.amp = 0.01f; crab.period = 4f; crab.anchor = 0; c.hazards.add(crab);
        Element club = new Element(Element.Type.CLUB, 7f, 1f, 0f); club.anchor = 0; c.hazards.add(club);
        Sim s = Sim.startOn(c, t, 0); s.keysFree = false; s.s = 8.5f; s.invuln = 0f;
        InputState in = new InputState();
        for (int i = 0; i < 40 && s.shoveCd <= 0f; i++) { in.clear(); in.moveX = 1f; s.step(in); }
        assertTrue("a crab shoves you", (s.consumeEvents() & Sim.EV_SHOVE) != 0);
        assertEquals("shoves never hurt", 0, s.hits);
        assertEquals(0, s.falls);
        // now with the club: walk to it, face the crab, swing
        Sim w = Sim.startOn(c, t, 0); w.keysFree = false; w.s = 8.2f; w.invuln = 0f;
        for (int i = 0; i < 60 && w.clubTime <= 0f; i++) { in.clear(); in.moveX = -1f; w.step(in); }
        assertTrue("club picked up", w.clubTime > 0f);
        for (int i = 0; i < 20; i++) { in.clear(); w.step(in); }
        w.s = 8.9f; w.vx = 0f; w.facing = 1; w.shoveCd = 0f;
        in.clear(); in.swingPressed = true; w.step(in);
        for (int i = 0; i < 25; i++) { in.clear(); w.step(in); }
        assertTrue("the crab is gone", w.featDone[1]);
        assertEquals("and never touched you", 0, w.hits);
        // without a club, swinging does nothing
        Sim n = Sim.startOn(c, t, 0); n.keysFree = false; n.s = 8.9f; n.invuln = 0f; n.facing = 1;
        in.clear(); in.swingPressed = true; n.step(in);
        assertEquals(0f, n.swingT, 0f);
    }

    @Test public void crabSlicesStillSolve() throws Exception {
        Tuning t = TestUtil.tuning();
        int crabs = 0, clubs = 0;
        for (long seed : new long[]{31, 32, 33, 34, 35}) {
            Course prev = null;
            for (int k = 0; k < 10; k++) {
                Course c = CourseGenerator.chunk(seed, k, prev, t);
                for (Element h : c.hazards) { if (h.type == Element.Type.CRAB) crabs++; if (h.type == Element.Type.CLUB) clubs++; }
                Autopilot.Report r = Autopilot.run(c, t, 4000f);
                assertTrue("seed " + seed + " slice " + k + " link " + r.failedLink, r.completed);
                prev = c;
            }
        }
        System.out.println("crabs=" + crabs + " clubs=" + clubs);
        assertTrue("crabs appear", crabs > 0);
    }

    @Test public void beesVisitBumpAndLeave_neverHurt() throws Exception {
        Tuning t = TestUtil.tuning();
        Element bee = new Element(Element.Type.BEE, 10f, 1f, 0f); bee.amp = 0.5f; bee.len = 0.8f; bee.period = 8f; bee.anchor = 0;
        assertTrue("present during its visit", bee.beePresent(0.5f));
        assertFalse("gone between visits", bee.beePresent(bee.period * (Element.BEE_VISIT + 0.1f)));
        assertTrue("arrives from far away", Math.abs(bee.beeS(0.01f) - 10f) > 8f);
        assertEquals("back in the middle at the peak of the visit", 10f, bee.beeS(bee.period * Element.BEE_VISIT * 0.5f), 1.2f);
        Course c = TestUtil.flat(t); c.hazards.add(bee);
        Sim s = Sim.startOn(c, t, 0); s.keysFree = false; s.invuln = 0f; s.s = 10f;
        InputState in = new InputState(); int bumps = 0, ev = 0;
        for (int i = 0; i < 60 * 24; i++) { in.clear(); s.step(in); ev |= s.consumeEvents(); }
        assertEquals("a bee never hurts: the only consequence of a bump is where you end up", 0, s.hits);
    }
}

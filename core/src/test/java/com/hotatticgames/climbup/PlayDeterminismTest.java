package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import com.hotatticgames.climbup.sim.*;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * Build 71 (owner directive "Seed Determinism"): the same seed and the same inputs must give the same climb, not just the same
 * geometry (DeterminismTest pins that). Here the PLAY is checked bit for bit: hazard timing (mortar rows with their own random
 * phases, chained spike drops with their own speed and height), the creatures (bees, skulls, blobs), the events the game reacts
 * to, a save restored mid-creature, a checkpoint respawn, and the frame rate the game happens to run at. Cosmetic randomness
 * (particles, idle beats, dust) lives in the renderer and never reaches {@link Sim}; the last test keeps it that way.
 */
public class PlayDeterminismTest {
    private static final long SEED = 7L;           // shares TestUtil's cached slices with OfficialWorldRulesTest
    private static final float TOP = 2600f;        // through world 2 (skulls, the castle 1-2 drops) into world 3 (blobs)

    /** A hash of everything that decides what happens next: the hero, the events of this step and every simulated hazard (its packed state and where it is). */
    private static long stepHash(Sim s, int ev, long h) {
        h = mix(h, Float.floatToIntBits(s.s)); h = mix(h, Float.floatToIntBits(s.y)); h = mix(h, Float.floatToIntBits(s.vx)); h = mix(h, Float.floatToIntBits(s.vy));
        h = mix(h, s.mode.ordinal()); h = mix(h, s.onElem); h = mix(h, ev); h = mix(h, s.falls); h = mix(h, s.hits); h = mix(h, Float.floatToIntBits(s.slipCd));
        int n = s.course.hazards.size();
        for (int i = 0; i < n; i++) {
            Element e = s.course.hazards.get(i);
            h = mix(h, Float.floatToIntBits(e.sAt(s.time))); h = mix(h, Float.floatToIntBits(e.yAt(s.time)));
            for (float f : s.hazardState(i)) h = mix(h, Float.floatToIntBits(f));
        }
        return h;
    }
    private static long mix(long h, long v) { h ^= v + 0x9E3779B97F4A7C15L + (h << 6) + (h >>> 2); return h * 0xBF58476D1CE4E5B9L; }

    private static final class Run { final List<Long> hashes = new ArrayList<>(); final List<Integer> events = new ArrayList<>(); int maxMob; }

    /** The autopilot plays a slice from the platform before the hazard; returns the per-step hashes and events. */
    private static Run play(Course c, Tuning t, int from, int steps, int watch) {
        Sim s = Sim.startOn(c, t, from); s.setRange(0, c.size() - 1);
        Autopilot.Driver d = new Autopilot.Driver(s);
        InputState in = new InputState(); Run r = new Run(); long h = 17;
        for (int i = 0; i < steps; i++) {
            in.clear(); d.drive(s, in); s.step(in); int ev = s.consumeEvents();
            h = stepHash(s, ev, h); r.hashes.add(h); r.events.add(ev);
            if (watch >= 0) r.maxMob = Math.max(r.maxMob, s.mobState[watch]);
            if (d.failed) { s.respawn(); s.consumeEvents(); d = new Autopilot.Driver(s); }
            if (s.won) break;
        }
        return r;
    }

    private static Element.Type[] covered() {
        return new Element.Type[]{Element.Type.SKULL, Element.Type.BLOB, Element.Type.BEE, Element.Type.MORTAR, Element.Type.SPIKE_DROP, Element.Type.CRAB};
    }

    @Test public void theSameSeedAndInputsGiveTheSameHazardsCreaturesAndEvents() throws Exception {
        Tuning t = TestUtil.tuning();
        List<Course> a = TestUtil.slices(SEED, TOP);
        java.util.EnumMap<Element.Type, Integer> seen = new java.util.EnumMap<>(Element.Type.class);
        boolean skullActed = false, blobActed = false;
        for (int k = 0; k < a.size(); k++) {
            Course c = a.get(k);
            int target = -1; Element.Type kind = null;
            for (int i = 0; i < c.hazards.size() && target < 0; i++) {
                Element.Type ty = c.hazards.get(i).type;
                for (Element.Type want : covered()) if (ty == want && seen.getOrDefault(ty, 0) < 2 && c.hazards.get(i).anchor >= 0) { target = i; kind = ty; }
            }
            if (target < 0) continue;
            Element h = c.hazards.get(target);
            // the same slice built again from nothing (a second generator) must play out identically
            Course again = CourseGenerator.chunk(SEED, k, k == 0 ? null : a.get(k - 1), t);
            int from = Math.max(0, h.anchor - 1), watch = kind == Element.Type.SKULL || kind == Element.Type.BLOB ? target : -1;
            Run r1 = play(c, t, from, 900, watch), r2 = play(again, t, from, 900, watch);
            assertEquals(kind + " slice " + k + ": step count", r1.hashes.size(), r2.hashes.size());
            for (int i = 0; i < r1.hashes.size(); i++) {
                assertEquals(kind + " slice " + k + " step " + i + ": events", r1.events.get(i), r2.events.get(i));
                assertEquals(kind + " slice " + k + " step " + i + ": state", r1.hashes.get(i), r2.hashes.get(i));
            }
            if (kind == Element.Type.SKULL && r1.maxMob >= 3) skullActed = true;       // it dashed
            if (kind == Element.Type.BLOB && r1.maxMob >= 3) blobActed = true;         // it leapt
            seen.merge(kind, 1, Integer::sum);
        }
        for (Element.Type ty : covered()) assertTrue("no " + ty + " was exercised below " + TOP + " m on seed " + SEED, seen.getOrDefault(ty, 0) > 0);
        assertTrue("a skull dashed in the replayed runs", skullActed);
        assertTrue("a blob leapt in the replayed runs", blobActed);
    }

    @Test public void mortarAndSpikeDropTimingIsAPureFunctionOfTheSeed() throws Exception {
        Tuning t = TestUtil.tuning();
        List<Course> a = TestUtil.slices(SEED, TOP);
        int mortars = 0, drops = 0;
        for (int k = 0; k < a.size(); k++) {
            Course c = a.get(k), b = CourseGenerator.chunk(SEED, k, k == 0 ? null : a.get(k - 1), t);
            assertEquals("slice " + k + " hazards", c.hazards.size(), b.hazards.size());
            for (int i = 0; i < c.hazards.size(); i++) {
                Element x = c.hazards.get(i), y = b.hazards.get(i);
                assertEquals(x.type, y.type);
                if (x.type != Element.Type.MORTAR && x.type != Element.Type.SPIKE_DROP) continue;
                if (x.type == Element.Type.MORTAR) mortars++; else drops++;
                assertEquals("period", x.period, y.period, 0f); assertEquals("phase", x.phase, y.phase, 0f); assertEquals("height", x.amp, y.amp, 0f);
                for (float tm = 0f; tm < 30f; tm += 0.25f) {          // where the drop / shell is at every moment of half a minute
                    assertEquals(x.type + " s at " + tm, x.sAt(tm), y.sAt(tm), 0f);
                    assertEquals(x.type + " y at " + tm, x.yAt(tm), y.yAt(tm), 0f);
                }
            }
        }
        assertTrue("mortar rows exercised", mortars > 0);
        assertTrue("spike drops exercised", drops > 0);
    }

    @Test public void differentSeedsPlayDifferently() throws Exception {
        Tuning t = TestUtil.tuning();
        Course c7 = TestUtil.slices(7L, 200f).get(2), c8 = CourseGenerator.chunk(8L, 0, null, t);
        Run r7 = play(c7, t, 0, 600, -1), r8 = play(c8, t, 0, 600, -1);
        assertNotEquals("two seeds gave the same climb", r7.hashes.get(Math.min(r7.hashes.size(), r8.hashes.size()) - 1), r8.hashes.get(Math.min(r7.hashes.size(), r8.hashes.size()) - 1));
    }

    @Test public void aCopiedSimMidCreatureContinuesExactlyAsTheOriginal() throws Exception {
        Tuning t = TestUtil.tuning();
        int checked = 0;
        for (Course c : TestUtil.slices(SEED, TOP)) {
            int target = -1;
            for (int i = 0; i < c.hazards.size(); i++) { Element.Type ty = c.hazards.get(i).type; if ((ty == Element.Type.SKULL || ty == Element.Type.BLOB) && c.hazards.get(i).anchor >= 0) { target = i; break; } }
            if (target < 0) continue;
            Sim a = Sim.startOn(c, t, Math.max(0, c.hazards.get(target).anchor - 1)); a.setRange(0, c.size() - 1);
            Autopilot.Driver d = new Autopilot.Driver(a); InputState in = new InputState();
            int i = 0;
            for (; i < 1200 && a.mobState[target] < 2; i++) { in.clear(); d.drive(a, in); a.step(in); a.consumeEvents(); }
            if (a.mobState[target] < 2) continue;                  // never woke up (the bot passed it): try the next one
            Sim b = a.copy();                                         // the snapshot the planners and the save path rely on
            for (int j = 0; j < 400; j++) {
                in.clear(); d.drive(a, in); InputState same = in;     // identical inputs to both
                a.step(same); b.step(same);
                assertEquals("events at +" + j, a.consumeEvents(), b.consumeEvents());
                assertEquals("state at +" + j, stepHash(a, 0, 1), stepHash(b, 0, 1));
            }
            if (++checked == 4) break;
        }
        assertTrue("a creature was caught mid-move", checked > 0);
    }

    @Test public void aSaveRestoredMidCreatureContinuesExactlyLikeTheClimbThatNeverStopped() throws Exception {
        Tuning t = TestUtil.tuning();
        Tower probe = new Tower(SEED, t); while (probe.topAbsY() < 1500) probe.extend();
        List<byte[]> blobs = new ArrayList<>(); for (int k = 0; k < probe.sliceCount(); k++) blobs.add(probe.blob(k));
        int checked = 0;
        for (int k = 0; k < blobs.size() && checked < 2; k++) {
            Tower ta = new Tower(SEED, t, blobs, 0);
            ta.windowAroundAbs((probe.botAbsY(k) + probe.topAbsY(k)) / 2);
            int target = -1;
            for (int i = 0; i < ta.world.hazards.size(); i++) { Element h = ta.world.hazards.get(i); if ((h.type == Element.Type.SKULL || h.type == Element.Type.BEE) && h.anchor >= 0 && ta.hazKeyAt(i) >> 16 == k) { target = i; break; } }
            if (target < 0) continue;
            Sim a = Sim.startOn(ta.world, t, Math.max(0, ta.world.hazards.get(target).anchor - 1));
            a.keysFree = false; a.deferRespawn = true; a.floorOverride = ta.floorLocal(); a.setRange(0, ta.world.size() - 1);
            Autopilot.Driver d = new Autopilot.Driver(a); InputState in = new InputState();
            Element.Type ty = ta.world.hazards.get(target).type;
            int i = 0;
            for (; i < 1200; i++) {
                in.clear(); d.drive(a, in); ta.maintain(a); a.step(in); a.consumeEvents();
                boolean mid = ty == Element.Type.SKULL ? a.mobState[target] >= 2 : Math.abs(a.mobS[target]) > 0.05f;
                if (mid && !a.respawnPending && a.mode == Sim.Mode.GROUND) break;
            }
            RunSnapshot snap = RunSnapshot.capture(a, ta, SEED);
            if (snap == null || i == 1200) continue;
            RunSnapshot back = RunSnapshot.fromJson(snap.toJson());
            assertNotNull("the stored text parses", back);
            assertTrue("the moving creature is in the save", back.hKey.length > 0);
            Tower tb = new Tower(SEED, t, blobs, 0); tb.setCheckpointRef(new Tower.Ref(back.cpSlice, back.cpLocal)); tb.windowAroundAbs(back.centreAbs());
            Sim b = new Sim(tb.world, t); b.keysFree = false; b.deferRespawn = true; b.floorOverride = tb.floorLocal(); b.setRange(0, tb.world.size() - 1);
            assertTrue("applies", back.apply(b, tb));
            for (int j = 0; j < 600; j++) {
                in.clear(); d.drive(a, in);
                ta.maintain(a); tb.maintain(b);
                a.step(in); b.step(in);
                assertEquals(ty + " save at slice " + k + ", events at +" + j, a.consumeEvents(), b.consumeEvents());
                assertEquals(ty + " save at slice " + k + ", height at +" + j, ta.absY(a.y), tb.absY(b.y), 1e-4);
                assertEquals(ty + " save at slice " + k + ", vx at +" + j, a.vx, b.vx, 1e-5f);
                assertEquals(ty + " save at slice " + k + ", vy at +" + j, a.vy, b.vy, 1e-5f);
                int hb = tb.indexOfHazKey(ta.hazKeyAt(target));
                if (hb >= 0) assertArrayEquals(ty + " state at +" + j, a.hazardState(target), b.hazardState(hb), 1e-4f);
            }
            checked++;
        }
        assertTrue("a save was taken with a creature mid-move", checked > 0);
    }

    @Test public void aCheckpointRespawnIsDeterministic() throws Exception {
        Tuning t = TestUtil.tuning();
        Course c = TestUtil.slices(SEED, TOP).get(3);
        long[] out = new long[2];
        for (int run = 0; run < 2; run++) {
            Sim s = Sim.startOn(c, t, 0); s.setRange(0, c.size() - 1);
            InputState in = new InputState(); long h = 5;
            for (int i = 0; i < 1500; i++) {                         // run straight off the edge again and again: falls, respawns, the world carrying on
                in.clear(); in.moveX = (i / 240) % 2 == 0 ? 1f : -1f; in.jumpHeld = i % 50 < 10; in.jumpPressed = i % 50 == 0;
                s.step(in); int ev = s.consumeEvents(); h = stepHash(s, ev, h);
                if (s.respawnPending) s.respawn();
            }
            assertTrue("the test actually fell", s.falls > 0);
            out[run] = h;
        }
        assertEquals("two identical flailing runs with respawns", out[0], out[1]);
    }

    /** The game steps the simulation at a fixed 60 Hz from an accumulator (PlayScreen.render); this is the same loop, run at several display rates. */
    private static long atFrameRate(Course c, Tuning t, float frameDt, int simSteps) {
        Sim s = Sim.startOn(c, t, 0); s.setRange(0, c.size() - 1);
        Autopilot.Driver d = new Autopilot.Driver(s); InputState in = new InputState();
        float acc = 0f; int done = 0; long h = 3;
        while (done < simSteps) {
            acc += frameDt; int steps = 0;
            while (acc >= Sim.DT && steps < 6 && done < simSteps) { in.clear(); d.drive(s, in); s.step(in); h = stepHash(s, s.consumeEvents(), h); acc -= Sim.DT; steps++; done++; }
            if (steps == 6) acc = 0;
        }
        return h;
    }

    @Test public void theOutcomeDoesNotDependOnTheFrameRate() throws Exception {
        Tuning t = TestUtil.tuning();
        Course c = TestUtil.slices(SEED, TOP).get(5);
        long ref = atFrameRate(c, t, 1f / 60f, 1200);
        for (float fps : new float[]{30f, 50f, 75f, 90f, 120f, 144f, 165f, 240f, 360f, 8f})
            assertEquals("the same 1200 steps at " + fps + " fps", ref, atFrameRate(c, t, 1f / fps, 1200));
    }

    /** Cosmetic randomness stays out of the simulation: no wall clock, no unseeded or shared random source in the sim package. */
    @Test public void theSimulationHasNoWallClockOrUnseededRandomness() throws Exception {
        File dir = new File("src/main/java/com/hotatticgames/climbup/sim");
        File[] files = dir.listFiles((d, n) -> n.endsWith(".java"));
        assertNotNull("sim sources at " + dir.getAbsolutePath(), files);
        for (File f : files) {
            String src = new String(Files.readAllBytes(f.toPath()), "UTF-8");
            for (String bad : new String[]{"Math.random", "MathUtils.random", "System.nanoTime", "System.currentTimeMillis", "new Random()", "new java.util.Random()", "ThreadLocalRandom", "Gdx.graphics"})
                assertFalse(f.getName() + " uses " + bad, src.contains(bad));
        }
    }
}

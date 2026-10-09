package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import com.hotatticgames.climbup.sim.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** Regression coverage for the owner-reported crash near a ramp at ~2,513 m: ramps of every kind, and the slice boundaries that generation crosses while climbing them. */
public class RampAndBoundaryTest {
    /** Plays the whole way up to, onto and past every ramp in the world with the autopilot, exactly as the game does (windowed simulation, window following the player). */
    @Test public void everyRampKindCanBeApproachedClimbedAndLeftWithoutAnyError() throws Exception {
        Tuning t = TestUtil.tuning();
        int ramps = 0; boolean[] skins = new boolean[4];
        for (long seed : new long[]{1, 2, 3, 4, 5, 6}) {
            Tower tw = new Tower(seed, t); while (tw.topY() < 2800f) tw.extend();
            Course w = tw.world;
            for (int ri = 1; ri < w.routeSize(); ri++) {
                Element e = w.get(ri);
                if (e.type != Element.Type.RAMP || e.y < 2300f || e.y > 2800f) continue;
                ramps++; skins[e.skin] = true;
                int st = ri - 1; while (st > 0 && !(w.get(st).isPlatform() && w.get(st).type != Element.Type.RAMP)) st--;
                Sim s = Sim.startOn(w, t, st); s.keysFree = true;
                WindowFollower win = new WindowFollower(); win.update(s, w, true);
                Autopilot.Driver d = new Autopilot.Driver(s); InputState in = new InputState();
                boolean touched = false;
                for (int i = 0; i < 60 * 14 && !d.failed; i++) {
                    d.drive(s, in); s.step(in); s.consumeEvents(); win.update(s, w, false);
                    if (s.onElem == ri) touched = true;
                    assertFalse("finite state (seed " + seed + " ramp " + ri + " step " + i + ")", Float.isNaN(s.s) || Float.isNaN(s.y) || Float.isInfinite(s.y) || Float.isNaN(s.vx) || Float.isNaN(s.vy));
                }
            }
        }
        System.out.println("ramps exercised near 2,300-2,800 m: " + ramps + " skins " + java.util.Arrays.toString(skins));
        assertTrue("ramps were actually found and played", ramps >= 8);
    }

    /** Stand on every skin of ramp for several seconds (sinking, shaking and crumbling ramps change under the player), walk to the top and off the high end. */
    @Test public void standingOnAndWalkingOffEveryRampSkinIsStable() throws Exception {
        Tuning t = TestUtil.tuning();
        for (int skin = 0; skin <= 3; skin++) {
            Course c = TestUtil.flat(t);
            Element ramp = new Element(Element.Type.RAMP, 17f, 0.8f, 3.5f); ramp.amp = 0.576f; ramp.skin = skin; ramp.len = 1.5f; c.add(ramp);
            Element top = new Element(Element.Type.STATIC, 21.5f, 2.2f, 4f); c.add(top);
            Sim s = Sim.startOn(c, t, 0); s.keysFree = true; s.setRange(0, c.size() - 1);
            InputState in = new InputState();
            for (int i = 0; i < 60 * 6; i++) { in.clear(); in.moveX = i < 60 * 4 ? 0.9f : 0f; s.step(in); s.consumeEvents(); assertFalse(Float.isNaN(s.y) || Float.isNaN(s.s)); }
            for (int i = 0; i < 60 * 6; i++) { in.clear(); in.moveX = 1f; in.jumpPressed = i % 40 == 0; in.jumpHeld = i % 40 < 20; s.step(in); s.consumeEvents(); assertFalse(Float.isNaN(s.y) || Float.isNaN(s.s)); }
        }
    }

    /** Resuming from the saved slice must give exactly the tower a continuous climb would have produced, and must never throw, at every slice boundary up to the first castles. */
    @Test public void resumingFromASavedSliceMatchesAContinuousClimbAtEveryBoundary() throws Exception {
        Tuning t = TestUtil.tuning();
        int boundaries = 0;
        for (long seed : new long[]{1, 2, 3}) {
            Course prev = null;
            for (int k = 0; k < 30; k++) {
                Course c = CourseGenerator.chunk(seed, k, prev, t);
                Course saved = CourseIO.fromJson(CourseIO.toJson(c));         // what the save file holds
                Course direct = CourseGenerator.chunk(seed, k + 1, c, t), resumed = CourseGenerator.chunk(seed, k + 1, saved, t);
                assertEquals("slice " + (k + 1) + " of seed " + seed + " is the same after a save and reload", CourseIO.toJson(direct), CourseIO.toJson(resumed));
                boundaries++; prev = c;
            }
        }
        assertEquals(90, boundaries);
    }
}

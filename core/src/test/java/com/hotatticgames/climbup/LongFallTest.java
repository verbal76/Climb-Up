package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import com.hotatticgames.climbup.sim.*;
import org.junit.Test;

/** Falling through the tower: whatever the player is standing on, and the platforms around it, must stay simulated, however far below the best height he lands. */
public class LongFallTest {
    @Test public void aLongFallKeepsTheWorldAroundTheLandingSpot() throws Exception {
        Tuning t = TestUtil.tuning();
        for (long seed : new long[]{1, 2, 3}) {
            Tower tw = new Tower(seed, t); while (tw.topY() < 1700f) tw.extend();
            Course w = tw.world;
            for (int back : new int[]{150, 300, 600}) {
                int top = tw.lastRouteIndex() - 3, low = top - back;
                while (low > 1 && !(w.get(low).isPlatform() && w.get(low).w >= 3f && w.get(low).type == Element.Type.STATIC)) low--;
                Sim s = Sim.startOn(w, t, top); s.keysFree = true; s.bestElem = top;
                WindowFollower f = new WindowFollower(); f.update(s, w, true);
                s.setRange(low - 160, low + 120); f.center = low - 40; f.size = w.size();          // the fall-follow rule has moved the window down to where the player is
                Element le = w.get(low); s.mode = Sim.Mode.AIR; s.onElem = -1; s.s = w.wrap(le.s); s.y = le.y + 0.5f; s.vy = -3f; s.vx = 0;
                InputState in = new InputState();
                for (int i = 0; i < 600 && s.mode != Sim.Mode.GROUND; i++) { in.clear(); s.step(in); f.update(s, w, false); }
                assertEquals("landed on the platform below", Sim.Mode.GROUND, s.mode);
                for (int i = 0; i < 60; i++) { in.clear(); s.step(in); f.update(s, w, false); }
                assertTrue("the platform he stands on is simulated (seed " + seed + ", landed " + back + " elements below his best: window [" + s.winLo + "," + s.winHi + "], on " + s.onElem + ")", s.onElem >= s.winLo && s.onElem <= s.winHi);
                assertTrue("with room on both sides, so neighbours can catch him", s.onElem - s.winLo >= 60 && s.winHi - s.onElem >= 60);
                // and he can carry on playing from there: the autopilot climbs on without ever being sent back to a checkpoint
                Autopilot.Driver d = new Autopilot.Driver(s); int f0 = s.falls;
                for (int i = 0; i < 60 * 15 && !d.failed; i++) { if (s.onElem >= w.routeSize() - 1) break; d.drive(s, in); s.step(in); s.consumeEvents(); f.update(s, w, false); }
                assertEquals("no fall through missing geometry after the long fall (seed " + seed + ", back " + back + ")", f0, s.falls);
            }
        }
    }

    @Test public void theWindowFollowsAFallAndNeverLeavesThePlayerOutsideIt() throws Exception {
        Tuning t = TestUtil.tuning();
        Tower tw = new Tower(4, t); while (tw.topY() < 1200f) tw.extend();
        Course w = tw.world;
        int top = tw.lastRouteIndex() - 3;
        Sim s = Sim.startOn(w, t, top); s.keysFree = true; s.bestElem = top;
        WindowFollower f = new WindowFollower(); f.update(s, w, true);
        // knocked off a platform at the very top: fall and keep falling for as long as it takes; every step the nearest platforms below must be inside the window
        Element te = w.get(top); s.mode = Sim.Mode.AIR; s.onElem = -1; s.s = w.wrap(te.s + te.w); s.y = te.y + 0.2f; s.vy = 0; s.vx = 0;
        InputState in = new InputState();
        for (int i = 0; i < 60 * 60 && s.mode == Sim.Mode.AIR && s.falls == 0; i++) {
            in.clear(); s.step(in); f.update(s, w, false);
            if (s.vy < -2f) assertTrue("window reaches below the falling player", s.winLo <= 0 || w.get(s.winLo).y < s.y - 20f);
        }
        assertEquals("no respawn while there is ground to land on", 0, s.falls);
    }
}

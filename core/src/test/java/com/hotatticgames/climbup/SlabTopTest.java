package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import com.hotatticgames.climbup.sim.*;
import org.junit.Test;

/** The spike slab: its spiked underside hurts exactly as before, its top is a surface that carries the hero like an elevator, and a slam throws him off gently instead of riding him down or hurting him. */
public class SlabTopTest {
    private static Sim world(float cycleAtZero) throws Exception {
        Tuning t = TestUtil.tuning();
        Course c = new Course(0, t.circumference());
        Element p = new Element(Element.Type.STATIC, 10f, 0f, 8f); p.checkpoint = true; c.add(p);
        Element h = new Element(Element.Type.SPIKE_DROP, 10f, 0f, 1.8f); h.amp = 3.3f; h.period = 4f; h.phase = cycleAtZero * 2f * (float) Math.PI; h.anchor = 0;
        c.hazards.add(h);
        Sim s = Sim.startOn(c, t, 0);
        s.s = 14f;              // beside the slab, on the platform
        return s;
    }

    private static Element slab(Sim s) { return s.course.hazards.get(0); }

    @Test public void landingOnTheTopIsSafeAndTheSlabCarriesTheHeroUp() throws Exception {
        Sim s = world(0.80f);              // slab at its lowest, about to rise (top 1.0 m up, rising 4 m/s)
        s.s = 10f; s.mode = Sim.Mode.AIR; s.onElem = -1; s.y = 2.2f; s.vy = -1f; s.vx = 0f;
        InputState in = new InputState(); boolean landed = false; float firstTop = 0; int risen = 0;
        for (int i = 0; i < 70; i++) {
            in.clear(); s.step(in); s.consumeEvents();
            if (s.mode == Sim.Mode.GROUND && s.onElem < 0 && s.onSlab == 0) {
                if (!landed) firstTop = s.y; landed = true;
                assertEquals("feet on the top face, step " + i, slab(s).dropTop(s.time), s.y, 1e-3f);
                if (s.y > firstTop + 0.5f) risen++;
            }
        }
        assertTrue("landed on the slab", landed); assertEquals("never hurt", 0, s.hits); assertTrue("was carried upward", risen > 3);
    }

    @Test public void aSlamThrowsAHeroStandingOnTopOffGentlyAndHurtsNoOne() throws Exception {
        Sim s = world(0.60f);              // slab hovering at the top of its travel, about to tremble and slam
        s.mode = Sim.Mode.GROUND; s.onElem = -1; s.onSlab = 0; s.s = 10f; s.y = slab(s).dropTop(s.time); s.vx = s.vy = 0f;
        InputState in = new InputState(); boolean bounced = false; float peakVy = 0;
        for (int i = 0; i < 400; i++) {
            in.clear(); s.step(in); s.consumeEvents();
            if (s.mode == Sim.Mode.AIR && s.vy > 0f && !bounced) { bounced = true; peakVy = s.vy; }
        }
        assertTrue("thrown off by the slam", bounced);
        assertTrue("gently (" + peakVy + " m/s)", peakVy > 2f && peakVy <= Sim.SLAB_BOUNCE_SPEED + 0.01f);
        assertEquals("no hit, ever", 0, s.hits); assertEquals("no fall to a checkpoint", 0, s.falls);
    }

    @Test public void theSpikedUndersideStillHurts() throws Exception {
        Sim s = world(0.60f);
        s.s = 10f; s.mode = Sim.Mode.GROUND; s.onElem = 0; s.y = 0f; s.vx = 0f;
        InputState in = new InputState();
        for (int i = 0; i < 90; i++) { in.clear(); s.step(in); s.consumeEvents(); }
        assertTrue("standing under the slam is a hit", s.hits >= 1);
    }

    @Test public void theBodyOfTheSlabAboveItsSpikesDoesNotHurt() throws Exception {
        Sim s = world(0.80f);              // slab down: spikes 0..0.5, body 0.5..1.0 above the platform
        s.s = 10f; s.mode = Sim.Mode.AIR; s.onElem = -1; s.y = 0.6f; s.vy = 0f; s.vx = 0f; s.invuln = 0f;
        InputState in = new InputState(); in.clear(); s.step(in);
        assertEquals("beside the stone body, above the spikes", 0, s.hits);
    }
}

package com.hotatticgames.climbup.sim;

import static org.junit.Assert.*;

import java.nio.file.*;
import org.junit.Test;

/** Pendulum experiment (owner directive 2026-10-28, branch experiment/pendulums): motion, collision, determinism and generation of the spiked pendulum. */
public class PendulumExperimentTest {
    static Tuning T() throws Exception { return Tuning.parse(new String(Files.readAllBytes(Paths.get("../assets/data/tuning.json")))); }
    static InputState idle() { return new InputState(); }

    static Element pendulum(float s, float y, float len, float amp, float period) {
        Element h = new Element(Element.Type.PENDULUM, s, y, 0f); h.len = len; h.amp = amp; h.period = period; h.anchor = 0; return h;
    }

    /** The ball swings symmetrically about its pivot, lowest at y, never beyond the arc the generator reserves for it, and repeats exactly each period. */
    @Test public void theBallSwingsDeterministicallyInsideItsReservedArc() {
        Element h = pendulum(10f, 2f, 2.4f, 0.6f, 3.2f); h.phase = 1.1f;
        float reachS = h.len * (float) StrictMath.sin(h.amp), riseY = h.len * (1f - (float) StrictMath.cos(h.amp));
        float minS = 1e9f, maxS = -1e9f, minY = 1e9f, maxY = -1e9f;
        for (int i = 0; i < 2000; i++) {
            float t = i * Sim.DT;
            float s = h.sAt(t), y = h.yAt(t);
            assertEquals("same time, same place", s, h.sAt(t), 0f); assertEquals(y, h.yAt(t), 0f);
            float d2 = (s - h.s) * (s - h.s) + (y - (h.y + h.len)) * (y - (h.y + h.len));
            assertEquals("the ball stays on its chain", h.len, (float) Math.sqrt(d2), 1e-4f);
            minS = Math.min(minS, s); maxS = Math.max(maxS, s); minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            assertEquals("periodic", s, h.sAt(t + h.period), 2e-4f);
        }
        assertEquals("lowest point at y", h.y, minY, 1e-3f);
        assertTrue("never beyond the reserved sweep", maxS - h.s <= reachS + 1e-4f && h.s - minS <= reachS + 1e-4f);
        assertTrue("never above the reserved rise", maxY - h.y <= riseY + 1e-4f);
        assertEquals("symmetric swing", maxS - h.s, h.s - minS, 0.02f);
        assertEquals("hit disc is the ball", Element.BALL_R, h.discR(), 0f);
    }

    /** A climber standing under the swing is hit as the ball passes; one standing beyond its reach never is. */
    @Test public void theBallHitsInsideItsReachAndNeverOutside() throws Exception {
        Tuning t = T();
        for (float standAt : new float[]{0f, 4.5f}) {
            Course c = new Course(1, t.circumference());
            Element deck = new Element(Element.Type.STATIC, 0f, 0f, 12f); c.add(deck);
            c.hazards.add(pendulum(0f, 0.9f, 2.4f, 0.6f, 3.2f));
            Sim m = new Sim(c, t); m.syncElements();
            m.mode = Sim.Mode.GROUND; m.onElem = 0; m.s = standAt; m.y = 0f; m.vx = 0; m.vy = 0; m.invuln = 0;
            for (int i = 0; i < (int) (3.3f / Sim.DT) && m.hits == 0; i++) m.step(idle());
            if (standAt == 0f) assertTrue("hit under the swing", m.hits > 0);
            else assertEquals("never hit beyond its reach (" + standAt + " m)", 0, m.hits);
        }
    }

    /** The generator places pendulums, every slice holding one is accepted by its usual proofs, and the same seed gives the same slices. */
    @Test public void pendulumsAreGeneratedDeterministically() throws Exception {
        Tuning t = T().hardCopy(1);
        int pend = 0; Course prev = null; byte[][] first = new byte[4][];
        for (int k = 0; k < 14; k++) {
            CourseGenerator.Framed f = CourseGenerator.chunkFramed(7301L, k, k == 0 ? null : prevF, t); prevF = f;
            if (k < 4) first[k] = SliceCodec.encode(f);
            for (Element h : f.c.hazards) if (h.type == Element.Type.PENDULUM) pend++;
        }
        assertTrue("pendulums are generated (" + pend + ")", pend > 0);
        CourseGenerator.Framed g = null;
        for (int k = 0; k < 4; k++) { g = CourseGenerator.chunkFramed(7301L, k, g, t); assertArrayEquals("slice " + k + " repeats exactly", first[k], SliceCodec.encode(g)); }
    }
    private CourseGenerator.Framed prevF;
}

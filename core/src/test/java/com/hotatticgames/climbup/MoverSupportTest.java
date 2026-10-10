package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import com.hotatticgames.climbup.render.WorldRenderer;
import com.hotatticgames.climbup.sim.*;
import java.nio.file.*;
import org.junit.Test;

/**
 * Blue (horizontal) and warm-tinted "purple" falling movers: the hero must stand ON the visible surface, stay supported while it moves, and be able to jump during the whole shaking window.
 * The sinking seen in play was a rendering offset (the 0.5 m blue block was placed as if it were 0.3 m tall, 0.2 m proud of the simulation surface), so the first test pins the art to the physics;
 * the others pin the physics (support, carry, reversal, edge departure, jump window, collapse) so a later collision change cannot reintroduce a real sink.
 */
public class MoverSupportTest {
    private static float modelMaxY(String name) throws Exception {
        float max = -1e9f;
        for (String line : Files.readAllLines(Paths.get("../assets/models/" + name + ".obj"))) {
            if (!line.startsWith("v ")) continue;
            String[] p = line.trim().split("\\s+");
            max = Math.max(max, Float.parseFloat(p[2]));
        }
        return max;
    }

    @Test public void moverBlockTopsMeetTheSimulationSurface() throws Exception {
        assertEquals("blue block top vs surface", 0f, WorldRenderer.moverBlockDy(Element.Type.MOVE_H) + modelMaxY("block-moving-blue"), 1e-4f);
        assertEquals("vertical block top vs surface", 0f, WorldRenderer.moverBlockDy(Element.Type.MOVE_V) + modelMaxY("block-moving"), 1e-4f);
        assertEquals("depth block top vs surface", 0f, WorldRenderer.moverBlockDy(Element.Type.MOVE_Z) + modelMaxY("block-moving"), 1e-4f);
        Element blue = new Element(Element.Type.MOVE_H, 0, 0, 3f);
        assertEquals("blue block thickness matches the sim slab", modelMaxY("block-moving-blue"), blue.slab(), 1e-4f);
    }

    private static Sim world(Element.Type type, float amp, float period, float phase, boolean crumbles) throws Exception {
        Tuning t = TestUtil.tuning();
        Course c = new Course(0, t.circumference());
        Element a = new Element(Element.Type.STATIC, 10f, 0f, 4f); a.checkpoint = true; c.add(a);
        Element m = new Element(type, 20f, 0f, 3f); m.amp = amp; m.period = period; m.phase = phase; m.skin = crumbles ? 1 : 0; c.add(m);
        Sim s = Sim.startOn(c, t, 1);
        return s;
    }

    private static void drop(Sim s, float above, float vx) {
        s.mode = Sim.Mode.AIR; s.onElem = -1; s.s = s.es1[1] + 0.3f; s.y = s.ey1[1] + above; s.vy = -1f; s.vx = vx; s.crumbleT[1] = -1f;
    }

    @Test public void landingOnAMovingBlockPutsTheFeetExactlyOnItsSurfaceAtEveryPhase() throws Exception {
        InputState in = new InputState();
        for (Element.Type type : new Element.Type[]{Element.Type.MOVE_H, Element.Type.MOVE_V}) {
            for (int ph = 0; ph < 24; ph++) {
                Sim s = world(type, type == Element.Type.MOVE_H ? 0.8f : 3f, type == Element.Type.MOVE_H ? 6f : 4f, ph * (float) Math.PI / 12f, false);     // a drop onto a slow slider (a fast one simply slides away from under a falling body)
                drop(s, type == Element.Type.MOVE_H ? 1.0f : 2.5f, 0f);
                boolean landed = false;
                for (int i = 0; i < 400; i++) {
                    in.clear(); s.step(in);
                    if (s.mode == Sim.Mode.GROUND && s.onElem == 1) { landed = true; assertEquals(type + " phase " + ph + " step " + i, s.ey1[1], s.y, 1e-4f); }
                    if (landed) assertTrue("left the platform without input", s.mode == Sim.Mode.GROUND && s.onElem == 1);
                }
                assertTrue(type + " phase " + ph + " never landed", landed);
            }
        }
    }

    @Test public void aMovingBlockCarriesAndKeepsSupportingThroughReversalsAndASecondCycle() throws Exception {
        InputState in = new InputState();
        for (Element.Type type : new Element.Type[]{Element.Type.MOVE_H, Element.Type.MOVE_V}) {
            Sim s = world(type, 3f, 4f, 0.7f, false);
            s.s = s.es1[1]; s.y = s.ey1[1]; s.mode = Sim.Mode.GROUND; s.onElem = 1;
            float lastDs = Float.NaN; int reversals = 0;
            for (int i = 0; i < 60 * 9; i++) {
                in.clear(); s.step(in);
                assertEquals(type + " step " + i, Sim.Mode.GROUND, s.mode);
                assertEquals(type + " step " + i, s.ey1[1], s.y, 1e-4f);
                assertEquals(type + " carried step " + i, 0f, s.course.dsWrap(s.s, s.es1[1]), 1e-3f);
                float ds = s.platformVs(1) + s.platformVy(1);
                if (!Float.isNaN(lastDs) && Math.signum(ds) != Math.signum(lastDs) && ds != 0f && lastDs != 0f) reversals++;
                lastDs = ds;
            }
            assertTrue(type + " reversed at least twice in 9 s", reversals >= 2);
        }
    }

    @Test public void jumpingFromAMovingBlockWorksAtEveryPointOfItsCycle() throws Exception {
        InputState in = new InputState();
        for (Element.Type type : new Element.Type[]{Element.Type.MOVE_H, Element.Type.MOVE_V}) {
            for (int k = 0; k < 240; k += 7) {
                Sim s = world(type, 3f, 4f, 0.3f, false);
                s.s = s.es1[1]; s.y = s.ey1[1]; s.mode = Sim.Mode.GROUND; s.onElem = 1; s.coyote = s.T.coyote;
                for (int i = 0; i < k; i++) { in.clear(); s.step(in); }
                in.clear(); in.jumpPressed = true; in.jumpHeld = true; s.step(in);
                assertEquals(type + " jump at " + k, Sim.Mode.AIR, s.mode);
                assertTrue(type + " jump at " + k + " vy=" + s.vy, s.vy >= s.T.jumpVel - 1e-3f);
            }
        }
    }

    @Test public void walkingOffTheEdgeOfAMovingBlockLeavesItCleanly() throws Exception {
        InputState in = new InputState();
        Sim s = world(Element.Type.MOVE_H, 0.01f, 4f, 0f, false);
        s.s = s.es1[1]; s.y = s.ey1[1]; s.mode = Sim.Mode.GROUND; s.onElem = 1;
        boolean off = false;
        for (int i = 0; i < 240 && !off; i++) { in.clear(); in.moveX = 1f; s.step(in); off = s.mode != Sim.Mode.GROUND; }
        assertTrue("walked off the edge", off);
        assertTrue("the feet were never below the surface when leaving", s.y >= s.ey1[1] - 1e-3f || s.onElem != 1);
    }

    @Test public void aFallingBlockAllowsAJumpThroughoutItsShakeAndThenGivesNoSupport() throws Exception {
        InputState in = new InputState();
        Tuning t = TestUtil.tuning();
        int collapseStep = -1;
        for (int k = 0; k < 200; k++) {
            Sim s = world(Element.Type.MOVE_H, 0.05f, 4f, 0f, true);
            drop(s, 0.4f, 0f);
            int land = -1;
            for (int i = 0; i < 40 && land < 0; i++) { in.clear(); s.step(in); if (s.mode == Sim.Mode.GROUND) land = i; }
            assertTrue("landed", land >= 0);
            boolean wasGone = false;
            for (int i = 0; i < k; i++) { in.clear(); s.step(in); if (s.gone[1]) { wasGone = true; collapseStep = collapseStep < 0 ? i : collapseStep; break; } }
            in.clear(); in.jumpPressed = true; in.jumpHeld = true; s.step(in);
            if (!wasGone) assertEquals("jump while it shakes (step " + k + ")", Sim.Mode.AIR, s.mode);
            if (!wasGone) assertTrue("jump height is the normal jump (step " + k + ")", s.vy >= t.jumpVel - 1e-3f);
        }
        assertTrue("the block did collapse inside the window", collapseStep > 0);
        // once collapsed it supports nothing: a body that stays on it is dropped and a body arriving over it falls on through
        Sim s = world(Element.Type.MOVE_H, 0.05f, 4f, 0f, true);
        drop(s, 0.4f, 0f);
        for (int i = 0; i < 40 && s.mode != Sim.Mode.GROUND; i++) { in.clear(); s.step(in); }
        int guard = 0; while (!s.gone[1] && guard++ < 600) { in.clear(); s.step(in); }
        assertTrue("collapsed", s.gone[1]);
        in.clear(); s.step(in);
        assertEquals("no longer standing on the fallen block", Sim.Mode.AIR, s.mode);
        for (int i = 0; i < 20 && s.gone[1]; i++) { in.clear(); s.step(in); assertNotEquals("landed on a fallen block", 1, s.onElem); }
    }
}

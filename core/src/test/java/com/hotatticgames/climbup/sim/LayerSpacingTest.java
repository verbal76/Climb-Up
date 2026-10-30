package com.hotatticgames.climbup.sim;

import static org.junit.Assert.*;

import java.nio.file.*;
import org.junit.Test;

/**
 * Experiment rules 17 (owner decision 2026-10-28): the 9.5 m spacing between spiral revolutions applies where the LOWER platform can launch or carry the
 * climber, or where a plain lower platform's climber can rise past plain reach (a skull's launch, a spike-drop slab). Positive and negative cases for
 * {@link CourseGenerator#layersClash}, and the physics they rest on.
 */
public class LayerSpacingTest {
    static Tuning T() throws Exception { return Tuning.parse(new String(Files.readAllBytes(Paths.get("../assets/data/tuning.json")))); }

    /** A course with a plain platform at the bottom of one revolution and {@code up} one revolution later at the same arc, {@code dy} higher. */
    static Course pair(Tuning t, Element.Type lowType, Element.Type upType, float dy) {
        Course c = new Course(1, t.circumference());
        Element lo = new Element(lowType, 10f, 0f, 3f); c.add(lo);
        Element up = new Element(upType, 10.5f + t.circumference(), dy, 3f); c.add(up);
        return c;
    }
    static boolean clash(Course c) { return CourseGenerator.layersClash(c, c.get(1), c.get(0)); }

    static final Element.Type[] LAUNCHERS = {Element.Type.PAD, Element.Type.SPRING, Element.Type.SEESAW, Element.Type.RAMP, Element.Type.SWING,
        Element.Type.MOVE_H, Element.Type.MOVE_V, Element.Type.MOVE_Z};

    /** 1. A launcher above a plain platform is no longer refused for the shortcut spacing (body clearance still holds: 6 m is clear of it). */
    @Test public void upperLaunchersAbovePlainPlatformsAreAllowed() throws Exception {
        Tuning t = T();
        for (Element.Type up : new Element.Type[]{Element.Type.PAD, Element.Type.SPRING}) {
            Course c = pair(t, Element.Type.STATIC, up, 6f);
            assertFalse(up + " 6 m above a plain platform", clash(c));
            assertFalse("either order", CourseGenerator.layersClash(c, c.get(0), c.get(1)));
        }
    }

    /** 2 and 3. Every launching or moving lower platform still keeps the full 9.5 m to whatever lies above it. */
    @Test public void lowerLaunchersAndMoversKeepTheFullSpacing() throws Exception {
        Tuning t = T();
        for (Element.Type lo : LAUNCHERS) {
            for (float dy : new float[]{7f, 9.4f}) {
                Course c = pair(t, lo, Element.Type.STATIC, dy);
                assertTrue(lo + " " + dy + " m below a plain platform", clash(c));
            }
            // a pad's measured reach (10.25 m + 0.5) is above the spacing: from the pad gap fix (2026-10-30) C1 refuses it up to 10.75 m at any small gap
            if (lo == Element.Type.PAD) {
                assertTrue("PAD 9.6 m below: within the pad's measured reach (C1)", clash(pair(t, lo, Element.Type.STATIC, 9.6f)));
                assertFalse("PAD 10.8 m below", clash(pair(t, lo, Element.Type.STATIC, 10.8f)));
            } else assertFalse(lo + " 9.6 m below", clash(pair(t, lo, Element.Type.STATIC, 9.6f)));
        }
    }

    /**
     * Rules 19 (owner decision 2026-10-29, emergent movement): a skull's launch or a ridden spike-drop slab carrying the climber up to a launcher above is
     * collision-assisted movement the player may discover; it no longer keeps the full spacing (rules 17 did). Ordinary reach still decides (C1).
     */
    @Test public void liftingHazardsNoLongerKeepTheFullSpacing() throws Exception {
        Tuning t = T();
        for (Element.Type hz : new Element.Type[]{Element.Type.SPIKE_DROP, Element.Type.SKULL}) {
            Course c = pair(t, Element.Type.STATIC, Element.Type.PAD, 6f);
            Element h = new Element(hz, 10f, 0f, 2f); h.anchor = 0; c.hazards.add(h);
            assertFalse("pad 6 m above a platform with a " + hz + ": ordinary reach (3.75 m + margin) is below it", clash(c));
        }
        Course c = pair(t, Element.Type.STATIC, Element.Type.PAD, 6f);
        Element saw = new Element(Element.Type.SAW_H, 10f, 0.6f, 0f); saw.anchor = 0; c.hazards.add(saw);
        assertFalse("a saw adds no lift", clash(c));
    }

    /** Unchanged: plain over plain keeps 4 m, bodies of two revolutions never overlap, platforms far apart along the arc never clash. */
    @Test public void plainSpacingAndBodyClearanceAreUnchanged() throws Exception {
        Tuning t = T();
        assertTrue("plain 3.5 m", clash(pair(t, Element.Type.STATIC, Element.Type.STATIC, 3.5f)));
        assertTrue("body: a pad 4.5 m over a plain platform is inside its standing room + margin", clash(pair(t, Element.Type.STATIC, Element.Type.PAD, 4.5f)));
        Course far = new Course(1, t.circumference());
        far.add(new Element(Element.Type.STATIC, 10f, 0f, 3f)); far.add(new Element(Element.Type.PAD, 20f + t.circumference(), 6f, 2f));
        assertFalse("far apart along the arc", clash(far));
    }

    /** The physics the plain reach rests on: from plain footing (jump, ledge reach, the strongest non-skull knock-up at the top of the jump) a climber rises less than 4 m. */
    @Test public void plainReachIsBelowFourMetres() throws Exception {
        Tuning t = T();
        float jump = t.jumpVel * t.jumpVel / (2f * t.gravity), ledge = t.handHeight + t.ledgeReachAbove;
        float knock = Math.max(Math.max(t.hitKnockUp, Element.BLOB_PUSH_Y), Math.max(Element.ENF_KICK_Y, Math.max(Element.BEE_PUSH_Y, 6f)));
        float reach = jump + knock * knock / (2f * t.gravity) + ledge;
        assertTrue("plain reach " + reach + " m", reach < CourseGenerator.PLAIN_LAYER_DY);
        float skull = jump + Element.SKULL_LAUNCH_Y * Element.SKULL_LAUNCH_Y / (2f * t.gravity) + ledge;
        assertTrue("a skull's launch exceeds plain reach (" + skull + " m), so skull decks keep the full spacing", skull >= CourseGenerator.PLAIN_LAYER_DY);
    }

    /**
     * In the real Sim: from plain footing a climber reaches a surface 3 m up (so the search can succeed) but never one at the plain spacing, 4 m, by any
     * run, direction and jump timing tried - landing or catching its ledge. (A pad is the launcher in question, but it bounces the climber off at once, so
     * reach is measured onto a plain surface at the same height; the generator's body clearance keeps any surface within 2.5 m of arc 5.1 m above.)
     */
    @Test public void aClimberOnPlainFootingCannotReachTheLauncherAbove() throws Exception {
        Tuning t = T();
        assertTrue("3 m up is reachable", reachTries(t, 3.0f) > 0);
        assertEquals("4 m up (the plain spacing) is never reached", 0, reachTries(t, CourseGenerator.PLAIN_LAYER_DY));
    }
    static int reachTries(Tuning t, float h) {
        Course c = new Course(1, t.circumference());
        c.add(new Element(Element.Type.STATIC, 0f, 0f, 6f));
        c.add(new Element(Element.Type.STATIC, 4.6f, h, 2f));
        int reached = 0;
        for (float start = -2.5f; start <= 2.5f; start += 0.5f) for (int dir = -1; dir <= 1; dir += 2) for (float hold = 0f; hold <= 0.4f; hold += 0.1f) {
            Sim m = new Sim(c, t); m.syncElements();
            m.mode = Sim.Mode.GROUND; m.onElem = 0; m.s = start; m.y = 0f; m.vx = 0; m.vy = 0;
            InputState in = new InputState();
            for (int i = 0; i < (int) (2.5f / Sim.DT); i++) {
                float tt = i * Sim.DT;
                in.clear(); in.moveX = dir; if (tt >= hold && tt < hold + 0.5f) { in.jumpHeld = true; in.jumpPressed = tt < hold + Sim.DT; }
                m.step(in);
                if (m.onElem == 1 && m.mode != Sim.Mode.AIR) { reached++; break; }
            }
        }
        return reached;
    }
}

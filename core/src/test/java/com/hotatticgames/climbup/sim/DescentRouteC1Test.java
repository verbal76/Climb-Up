package com.hotatticgames.climbup.sim;

import static org.junit.Assert.*;

import java.nio.file.*;
import org.junit.Test;

/**
 * Owner question 2026-10-29 (before C1 is finalised): does C1 reject legitimate drop-and-bounce routes? Three cases on the real Sim (T5Probe's
 * independent input search):
 * 1. an intended main-route transition: a deck at 8.5 m, a pad 4.2 m below it, the next route deck at 13.2 m;
 * 2. an intended branch that drops onto a pad and rejoins the route;
 * 3. an unintended shortcut: the same lowered pad with a deck of the NEXT revolution within its reach.
 * C1 (CourseGenerator.reachShortcut) only compares platforms of different revolutions (a raw arc difference of half a lap or more); consecutive route
 * elements and a branch with its take-off and rejoin are on the same revolution, so it never sees them. These fixtures pin that down.
 */
public class DescentRouteC1Test {
    static Tuning T() throws Exception { return Tuning.parse(new String(Files.readAllBytes(Paths.get("../assets/data/tuning.json")))); }

    /** Route 0 (deck 8.5 m) -> 1 (pad 4.2 m) -> 2 (deck 13.2 m), all on one revolution. */
    static Course dropAndBounce(Tuning t) {
        Course c = new Course(1, t.circumference());
        c.add(new Element(Element.Type.STATIC, 0f, 8.5f, 3f));
        c.add(new Element(Element.Type.PAD, 3.4f, 4.2f, 2f));
        c.add(new Element(Element.Type.STATIC, 7.4f, 13.2f, 3f));
        return c;
    }
    static boolean anyClash(Course c) {
        for (int i = 0; i < c.size(); i++) for (int j = 0; j < c.size(); j++)
            if (i != j && (CourseGenerator.layersClash(c, c.get(i), c.get(j)) || CourseGenerator.forkLayersClash(c, c.get(i), c.get(j)))) return true;
        return false;
    }

    /** Case 1: the real Sim drops onto the pad and lands on the next deck; no rule (C1 included) refuses any pair of the sequence. */
    @Test public void anIntendedDropAndBounceIsAcceptedAndPlayable() throws Exception {
        Tuning t = T(); T5Probe.T = t;
        Course c = dropAndBounce(t);
        assertTrue("8.5 m deck -> pad at 4.2 m -> deck at 13.2 m is playable", T5Probe.searchReaches(c, 0, 1, 2));
        assertFalse("no layer rule refuses it (same revolution)", anyClash(c));
    }

    /** Case 2: a branch that drops from the route onto a lower pad and bounces back onto the route further on (registered as a fork). */
    @Test public void anIntendedBranchDropAndRejoinIsAccepted() throws Exception {
        Tuning t = T(); T5Probe.T = t;
        Course c = new Course(1, t.circumference());
        c.add(new Element(Element.Type.STATIC, 0f, 8.5f, 3f));            // 0 route: take-off
        c.add(new Element(Element.Type.STATIC, 4.6f, 9.6f, 3f));          // 1 route
        c.add(new Element(Element.Type.STATIC, 9.2f, 11.0f, 3f));         // 2 route: rejoin
        Element pad = new Element(Element.Type.PAD, 3.6f, 4.4f, 2f); c.add(pad);          // 3 branch: lowered pad
        c.routeCount = 3; c.forks.add(new int[]{0, 3, 1, 2});
        assertTrue("the branch is playable: take-off -> pad -> rejoin", T5Probe.searchReaches(c, 0, 3, 2));
        assertFalse("no layer rule refuses it", anyClash(c));
    }

    /** Case 3: the lowered pad with a deck of the next revolution 9.8 m above it, 4.5 m along: reachable, so C1 refuses that pair; 11.5 m up it does not. */
    @Test public void aShortcutFromTheLoweredPadIsStillRefused() throws Exception {
        Tuning t = T(); T5Probe.T = t;
        Course c = dropAndBounce(t);
        Element up = new Element(Element.Type.STATIC, c.get(1).s + t.circumference() + 4.5f, 4.2f + 9.8f, 3f); c.add(up);
        Course direct = new Course(1, t.circumference());          // the pad alone and that deck: the shortcut move itself
        direct.add(new Element(Element.Type.PAD, 0f, 0f, 2f)); direct.add(new Element(Element.Type.STATIC, t.circumference() + 4.5f, 9.8f, 3f)); direct.add(new Element(Element.Type.STATIC, -3.2f, 0.1f, 4f));
        assertTrue("the next revolution's deck is reachable from the pad (positive control)", T5Probe.searchReaches(direct, 2, 0, 1));
        assertTrue("C1 refuses the pad against it", CourseGenerator.layersClash(c, up, c.get(1)));
        Element high = new Element(Element.Type.STATIC, c.get(1).s + t.circumference() + 4.5f, 4.2f + 11.5f, 3f);
        Course c2 = dropAndBounce(t); c2.add(high);
        assertFalse("11.5 m above the pad is outside its measured reach + margin: allowed", CourseGenerator.layersClash(c2, high, c2.get(1)));
    }
}

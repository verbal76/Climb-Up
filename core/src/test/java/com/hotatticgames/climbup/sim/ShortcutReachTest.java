package com.hotatticgames.climbup.sim;

import static org.junit.Assert.*;

import java.nio.file.*;
import org.junit.Test;

/**
 * Correction C1 (owner master directive 2026-10-29; docs/verification/build83/T5_FINDINGS.md): two platforms of different spiral revolutions clash
 * when the upper one is within the measured reach of the lower one, wherever along the arc it lies. Each case is checked against the real Sim with
 * the independent input search of T5Probe (a success proves the move; a miss within the search is only evidence, so the negative cases are boundary
 * checks of the rule, not proofs of impossibility).
 */
public class ShortcutReachTest {
    static Tuning T() throws Exception { return Tuning.parse(new String(Files.readAllBytes(Paths.get("../assets/data/tuning.json")))); }

    /** Lower platform at arc 0 (a pad gets its run-on deck), a plain 3 m deck one revolution up at arc offset {@code off}, {@code dy} higher. */
    static Course pair(Tuning t, Element.Type low, float off, float dy) {
        Course c = new Course(1, t.circumference());
        Element lo = new Element(low, 0f, 0f, low == Element.Type.PAD ? 2f : 3f); c.add(lo);
        c.add(new Element(Element.Type.STATIC, t.circumference() + off, dy, 3f));
        if (low == Element.Type.PAD) c.add(new Element(Element.Type.STATIC, -3.2f, 0.1f, 4f));
        return c;
    }
    static boolean clash(Course c) { return CourseGenerator.layersClash(c, c.get(1), c.get(0)); }
    static boolean reachable(Tuning t, Course c) { T5Probe.T = t; return T5Probe.searchReaches(c); }

    /** The accepted shortcut found in NGH seed 7321 slice 2 (pad 30 to deck 42: 9.81 m up, 4.2 m along the arc, 12 route platforms skipped). */
    @Test public void thePadShortcutFromTheAuditIsRefused() throws Exception {
        Tuning t = T();
        Course c = pair(t, Element.Type.PAD, 4.2f, 9.81f);
        assertTrue("the real Sim gets there (positive control)", reachable(t, c));
        assertTrue("so the generator refuses the pair", clash(c));
    }

    /** Beyond the old arc gate (5 m for a pad under a 3 m deck): a pad 6 m along still reaches 10 m up, and the pair is refused. */
    @Test public void padReachBeyondTheOldArcGateIsRefused() throws Exception {
        Tuning t = T();
        Course c = pair(t, Element.Type.PAD, 6f, 9.75f);
        assertTrue("reachable", reachable(t, c));
        assertTrue("refused", clash(c));
    }

    /**
     * Pad gap fix (geometry audit 2026-10-30, owner decision): pad pairs 9.5-10.3 m up at a small or overlapping edge gap were refused by neither
     * the 9.5 m spacing nor C1, and the T5Probe audit reached them in generated courses (PUNISHER 7321 slices 25 and 29, PUNISHER 7323 slices 13
     * and 25, NGH 7323 slices 3 and 17; docs/geometry_audit/GEOMETRY_AUDIT_REPORT.md section 6). This bare fixture lacks those courses' run-on, so
     * the evidence is the audit; the test pins the rule.
     */
    @Test public void padPairsAtSmallGapsUpToTheFullPadReachAreRefused() throws Exception {
        Tuning t = T();
        float[][] audit = {{0.15f, 9.90f}, {0.19f, 10.09f}, {0.49f, 10.02f}, {1.37f, 9.53f}, {2.01f, 10.19f}, {-3.92f, 9.84f}};
        for (float[] k : audit) assertTrue("pad pair " + k[0] + " m along, " + k[1] + " m up", clash(pair(t, Element.Type.PAD, k[0], k[1])));
        assertTrue("10.7 m up directly above is still within the pad's reach + margin", clash(pair(t, Element.Type.PAD, 0f, 10.7f)));
    }

    /** Above the measured reach plus the margin (10.25 + 0.5 m) the pair stays allowed, as before; the search does not get there either. */
    @Test public void aPadPairAboveTheReachStaysAllowed() throws Exception {
        Tuning t = T();
        Course c = pair(t, Element.Type.PAD, 4.5f, 11.0f);
        assertFalse("allowed", clash(c));
        assertFalse("not reached by the search (evidence, not proof)", reachable(t, c));
    }

    /** A plain deck 6 m along the arc (outside the old 5.5 m gate): 3.75 m up is reachable and refused; 4.5 m up stays allowed. */
    @Test public void plainReachOutsideTheOldGate() throws Exception {
        Tuning t = T();
        Course near = pair(t, Element.Type.STATIC, 6f, 3.75f);
        assertTrue("3.75 m up, 6 m along is reachable", reachable(t, near));
        assertTrue("refused", clash(near));
        Course high = pair(t, Element.Type.STATIC, 6f, 4.5f);
        assertFalse("4.5 m up is above plain reach + margin", clash(high));
        assertFalse("and not reached by the search", reachable(t, high));
    }

    /** Only additions: a pair the old rule refused is still refused (the plain 4 m spacing and the launcher 9.5 m within the gate). */
    @Test public void nothingRefusedBeforeIsAllowedNow() throws Exception {
        Tuning t = T();
        assertTrue("plain 3.5 m directly above", clash(pair(t, Element.Type.STATIC, 0f, 3.5f)));
        assertTrue("pad 9.0 m within the gate", clash(pair(t, Element.Type.PAD, 2f, 9.0f)));
        assertTrue("mover 7 m within the gate", clash(pair(t, Element.Type.MOVE_H, 2f, 7.0f)));
    }
}

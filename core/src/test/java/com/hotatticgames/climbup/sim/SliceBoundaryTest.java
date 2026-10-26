package com.hotatticgames.climbup.sim;

import static org.junit.Assert.*;

import java.nio.file.*;
import java.util.*;
import org.junit.Test;

/**
 * Owner directive 2026-10-26 (generator reliability): a slice must leave the next one a valid continuation, so no slice falls back to the relaxed
 * attempts, and the bodies of different spiral revolutions never overlap, across slice boundaries as well as inside a slice. (PUNISHER seed 2 slice 48
 * closed 1.4 m above its own previous revolution; slice 49 failed twelve attempts on it and was built in relaxed mode, which then also ignored the
 * previous slice's bodies.)
 */
public class SliceBoundaryTest {
    static Tuning tuning() throws Exception { return Tuning.parse(new String(Files.readAllBytes(Paths.get("../assets/data/tuning.json")))); }

    @Test public void slicesLeaveTheNextOneRoomAndRevolutionsNeverOverlap() throws Exception {
        Tuning base = tuning();
        int slices = 0, pairs = 0;
        for (int mode = 0; mode <= 1; mode++) {
            Tuning t = mode == 0 ? base : base.hardCopy(mode);
            for (long seed : new long[]{1L, 2L}) {
                CourseGenerator.Framed f = null; List<Element> prevAbs = null;
                for (int k = 0; k < 6; k++) {
                    GenTrace tr = new GenTrace(); GenTrace.GEN.set(tr);
                    try { f = CourseGenerator.chunkFramed(seed, k, f, t); } finally { GenTrace.GEN.remove(); }
                    slices++;
                    assertTrue("mode " + mode + " seed " + seed + " slice " + k + " was built in a normal attempt (final attempt " + tr.finalAttempt + ")", tr.finalAttempt >= 0 && tr.finalAttempt < 12);
                    assertNotNull("mode " + mode + " seed " + seed + " slice " + k + " leaves the next slice two ordinary first steps",
                        CourseGenerator.continuationSteps(f.c, 0, f.c.routeSize(), f.c.routeSize()));
                    List<Element> abs = new ArrayList<>();
                    for (int i = 0; i < f.c.size(); i++) { Element e = f.c.get(i); Element a = new Element(e.type, (float) (f.sBase + e.s), (float) (f.yBase + e.y), e.w); a.len = e.len; a.amp = e.amp; a.period = e.period; a.phase = e.phase; a.skin = e.skin; a.color = e.color; a.anchor = e.anchor; a.dir = e.dir; abs.add(a); }
                    Course frame = new Course(f.c.seed, f.c.circumference);
                    for (List<Element> group : prevAbs == null ? List.of(abs) : List.of(abs, prevAbs)) for (Element a : group) frame.add(a);
                    for (int i = 0; i < abs.size(); i++) for (int j = 0; j < frame.size(); j++) {
                        Element a = abs.get(i), b = frame.get(j);
                        if (a == b) continue;
                        pairs++;
                        assertFalse("mode " + mode + " seed " + seed + " slice " + k + ": " + a.type + " at y " + a.y + " overlaps a " + b.type + " at y " + b.y + " one revolution away",
                            CourseGenerator.layersBodiesClash(frame, a, b));
                    }
                    prevAbs = abs;
                }
            }
        }
        System.out.println("slice boundaries: " + slices + " slices, " + pairs + " element pairs checked");
    }

    /**
     * Owner directive 2026-10-27 (NORMAL seed 2 stopped at 881 m): slice 13 as rules 10 generated it (fixture, commit 350328f) closed 3.7 m above a
     * deck of the revolution below and 2 cm clear of it; slice 14 then failed all 24 attempts at its first module (layer rule). The rules 10 end check
     * accepted it with a single 2.4 m step that no ordinary module makes; the rules 11 check (two ordinary steps) refuses it.
     */
    @Test public void aSliceEndBoxedInByTheRevolutionBelowIsRefused() throws Exception {
        Course c = CourseIO.fromJson(new String(Files.readAllBytes(Paths.get("src/test/resources/fixtures/normal_seed2_slice13_350328f.json"))));
        int rs = c.routeSize(); Element rest = c.get(rs - 1);
        assertEquals(381.23f, rest.y, 0.01f);
        Element old = new Element(Element.Type.STATIC, rest.s + rest.w / 2f + 2f + 1.5f, rest.y + 2.4f, 3f);          // the rules 10 probe that let it through
        boolean oldClear = true; for (int i = 0; i < rs - 3; i++) if (CourseGenerator.layersClash(c, old, c.get(i))) oldClear = false;
        assertTrue("the rules 10 single 2.4 m probe passed this end", oldClear);
        for (float rise = 0f; rise <= 1.41f; rise += 0.1f) for (float gap = 1.5f; gap <= 3.01f; gap += 0.5f) {          // every hop or stair a first module makes from it is refused
            Element p = new Element(Element.Type.STATIC, rest.s + rest.w / 2f + gap + 1.5f, rest.y + rise, 3f);
            boolean clear = true; for (int i = 0; i < rs - 3; i++) if (CourseGenerator.layersClash(c, p, c.get(i))) clear = false;
            assertFalse("an ordinary first step " + rise + " m up, " + gap + " m out clears the revolution below", clear);
        }
        assertNull("the rules 11 end check refuses it", CourseGenerator.continuationSteps(c, 0, rs, rs));
    }

    /** The course that stopped: NORMAL seed 2 now builds past slice 14 (881 m) and every slice leaves the next one its first steps. */
    @Test public void normalSeed2ClimbsPastTheOld881mStop() throws Exception {
        Tuning t = tuning();
        CourseGenerator.Framed f = null;
        for (int k = 0; k <= 16; k++) {
            GenTrace tr = new GenTrace(); GenTrace.GEN.set(tr);
            try { f = CourseGenerator.chunkFramed(2L, k, f, t); } finally { GenTrace.GEN.remove(); }
            assertTrue("slice " + k + " was built in a normal attempt (final attempt " + tr.finalAttempt + ")", tr.finalAttempt >= 0 && tr.finalAttempt < 12);
            assertNotNull("slice " + k + " leaves the next slice two ordinary first steps", CourseGenerator.continuationSteps(f.c, 0, f.c.routeSize(), f.c.routeSize()));
        }
        assertTrue("reached " + (f.yBase + f.c.get(f.c.routeSize() - 1).y) + " m", f.yBase + f.c.get(f.c.routeSize() - 1).y > 1000.0);
    }
}

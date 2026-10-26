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
}

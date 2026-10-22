package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import com.hotatticgames.climbup.sim.*;
import org.junit.Test;

/** Slices generated in their own small frame: deterministic, losslessly storable, correct absolute castle heights and still completable. */
public class FramedGenerationTest {
    @Test public void aFramedChainIsDeterministicAndLosslesslyStorable() throws Exception {
        Tuning t = TestUtil.tuning(); long totalBytes = 0; int n = 0;
        for (long seed : new long[]{3, 11}) {
            CourseGenerator.Framed direct = null, viaStore = null;
            for (int k = 0; k < 40; k++) {
                direct = CourseGenerator.chunkFramed(seed, k, direct, t);                       // a continuous climb
                viaStore = CourseGenerator.chunkFramed(seed, k, viaStore, t);                   // a climb whose every previous slice was unloaded and reloaded from its stored bytes
                assertEquals(direct.yBase, viaStore.yBase, 0.0); assertEquals(direct.sBase, viaStore.sBase, 0.0);
                assertTrue("slice " + k + " (seed " + seed + ") is identical whether or not its predecessor went through the store", SliceCodec.same(direct.c, viaStore.c));
                byte[] bytes = SliceCodec.encode(viaStore); totalBytes += bytes.length; n++;
                CourseGenerator.Framed d = SliceCodec.decode(bytes);
                assertTrue("a stored slice decodes to exactly what was generated (slice " + k + ")", SliceCodec.same(viaStore.c, d.c));
                assertEquals(viaStore.yBase, d.yBase, 0.0); assertEquals(viaStore.sBase, d.sBase, 0.0);
                viaStore = d;
            }
        }
        System.out.println("slices " + n + ", bytes per stored slice " + totalBytes / n);
        assertTrue("under 4 KB per slice", totalBytes / n < 4096);
    }

    @Test public void framedCastlesAndGemsStandAtExactAbsoluteHeights() throws Exception {
        Tuning t = TestUtil.tuning();
        CourseGenerator.Framed f = null; int castles = 0, gems = 0;
        for (int k = 0; k < 90; k++) {
            f = CourseGenerator.chunkFramed(21, k, f, t);
            assertTrue("local heights stay small (" + f.c.get(f.c.routeSize() - 1).y + ")", Math.abs(f.c.get(f.c.routeSize() - 1).y) < 700f);
            assertEquals("frame is a multiple of the castle spacing", 0.0, f.yBase % t.castleSpacing, 0.0);
            for (Element h : f.c.hazards) if (h.type == Element.Type.GATE) { assertEquals("castle " + h.skin + " at its exact absolute height", h.skin * (double) t.castleSpacing, f.yBase + h.y, 0.001); castles++; }
            for (int i = 1; i < f.c.routeSize(); i++) { Element e = f.c.get(i); if (e.type == Element.Type.STATIC && e.skin == 3) { assertEquals(250.0, (f.yBase + e.y) % 500.0, 5.0); gems++; } }
        }
        assertTrue("several castles and gems in 90 slices", castles >= 5 && gems >= 5);
    }

    @Test public void everyFramedSliceIsCompletableByTheAutopilot() throws Exception {
        Tuning t = TestUtil.tuning();
        CourseGenerator.Framed f = null;
        for (int k = 0; k < 45; k++) {
            f = CourseGenerator.chunkFramed(5, k, f, t);
            Autopilot.Report r = Autopilot.run(f.c, t, 4000f);
            assertTrue("framed slice " + k + " link " + r.failedLink + " " + r.failInfo, r.completed);
        }
    }
}

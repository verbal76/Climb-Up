package com.hotatticgames.climbup.sim;

import static org.junit.Assert.*;

import java.nio.file.*;
import java.util.*;
import org.junit.Test;

/**
 * Owner correction directive 2026-10-27, DC6 and DC9: the live tower and the generated slices name the same decks and keep the same structure. A
 * creature's or an avalanche's "k decks from its anchor" is the same deck in the slice its proof ran on and in the stitched world (it used to be
 * another slice's decoy near a seam); forks survive the slice codec and are stitched into the world; the codec keeps wide fields exactly.
 */
public class WorldConsistencyTest {
    static Tuning T() throws Exception { return Tuning.parse(new String(Files.readAllBytes(Paths.get("../assets/data/tuning.json")))); }

    static Tower towerUpTo(Tuning t, long seed, double upTo) {
        Tower a = new Tower(seed, t); while (a.topAbsY() < upTo) a.extend();
        List<byte[]> blobs = new ArrayList<>(); for (int k = 0; k < a.sliceCount(); k++) blobs.add(a.blob(k));
        Tower w = new Tower(seed, t, blobs, 0); w.windowAroundAbs(0);
        return w;
    }
    /** The world key of slice-local element {@code local} of slice {@code k} (a slice's start platform is the previous slice's last route element). */
    static long keyOf(List<Course> slices, int k, int local) { return local == 0 && k > 0 ? (long) (k - 1) * 65536L + (slices.get(k - 1).routeSize() - 1) : (long) k * 65536L + local; }

    @Test public void routeDeckStepsAgreeBetweenEachSliceAndTheLiveTower() throws Exception {
        Tuning t = T().hardCopy(1);
        Tower w = towerUpTo(t, 2, 320);
        List<Course> slices = new ArrayList<>(); for (int k = 0; k < w.sliceCount(); k++) slices.add(SliceCodec.decode(w.blob(k)).c);
        int checked = 0, seamSteps = 0;
        for (int k = 0; k < slices.size(); k++) {
            Course s = slices.get(k); int rs = s.routeSize();
            for (int i = 0; i < rs; i++) {
                int wi = w.indexOfElemKey(keyOf(slices, k, i)); if (wi < 0) continue;
                for (int d = -3; d <= 3; d++) {
                    int lj = s.deck(i, d);
                    if (lj < 0 || lj >= rs) continue;          // beyond this slice: the world goes on into the next one, the slice cannot say
                    if (w.indexOfElemKey(keyOf(slices, k, lj)) < 0) continue;          // (that deck's slice is not resident in this window)
                    int wj = w.world.deck(wi, d);
                    assertEquals("slice " + k + " deck " + i + " step " + d, keyOf(slices, k, lj), w.elemKeyAt(wj));
                    checked++; if (lj + 3 >= rs || lj <= 2) seamSteps++;
                }
            }
        }
        assertTrue("decks compared " + checked, checked > 500);
        assertTrue("steps near a seam compared " + seamSteps, seamSteps > 20);
    }

    @Test public void forksSurviveTheCodecAndAreStitchedIntoTheWorld() throws Exception {
        Tuning t = T().hardCopy(1);
        Tower w = towerUpTo(t, 2, 320);
        int sliceForks = 0;
        List<Course> slices = new ArrayList<>(); for (int k = 0; k < w.sliceCount(); k++) slices.add(SliceCodec.decode(w.blob(k)).c);
        for (int k = 0; k < slices.size(); k++) {
            Course s = slices.get(k);
            for (int[] f : s.forks) {
                sliceForks++;
                int wf = w.indexOfElemKey(keyOf(slices, k, f[1]));
                if (wf < 0) continue;          // not resident
                boolean found = false; for (int[] g : w.world.forks) if (g[1] == wf && g[2] == f[2] && w.elemKeyAt(g[3]) == keyOf(slices, k, f[3])) found = true;
                assertTrue("slice " + k + " fork at " + f[1] + " is in the world with its rejoin", found);
            }
        }
        assertTrue("the generated slices have forks (PUNISHER)", sliceForks > 0);
    }

    @Test public void theCodecKeepsWideFieldsAndEveryStoredStructure() throws Exception {
        Tuning t = T();
        Course c = new Course(7, t.circumference());
        Element bridge = new Element(Element.Type.STATIC, 0f, 0f, 12f); bridge.skin = 1; bridge.color = 0x84; c.add(bridge);          // a 9-section bridge, mask bit 7
        Element gate = new Element(Element.Type.GATE, 3f, 0f, 1f); gate.skin = 130; gate.anchor = 0; c.hazards.add(gate);            // castle 130
        c.add(new Element(Element.Type.STATIC, 8f, 1f, 3f)); c.finishRoute();
        Element br = new Element(Element.Type.STATIC, 4f, -3f, 2f); br.anchor = 0; c.add(br); c.forks.add(new int[]{0, 2, 1, 1});
        c.gemCheckpoints = true; c.prog = new float[]{1f, 2f, 3f};
        Course back = SliceCodec.decode(SliceCodec.encode(new CourseGenerator.Framed(c, 1000.0, 240.0))).c;
        assertTrue("every stored field, forks included", SliceCodec.same(c, back));
        assertEquals(0x84, back.get(0).color); assertEquals(130, back.hazards.get(0).skin);
        Course io = CourseIO.fromJson(CourseIO.toJson(c));
        assertTrue("CourseIO keeps the gem rule", io.gemCheckpoints);
        assertArrayEquals("and the progression state", c.prog, io.prog, 0f);
        assertEquals(1, io.forks.size());
    }
}

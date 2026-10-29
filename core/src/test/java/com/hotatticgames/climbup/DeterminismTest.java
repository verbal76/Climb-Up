package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import com.hotatticgames.climbup.sim.CourseGenerator;
import com.hotatticgames.climbup.sim.CourseIO;
import com.hotatticgames.climbup.sim.Element;
import com.hotatticgames.climbup.sim.SliceCodec;
import com.hotatticgames.climbup.sim.Tower;
import com.hotatticgames.climbup.sim.Tuning;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * Build 64: the same seed gives the same endless course - every slice, bit for bit - however the tower is grown (fresh, resumed from stored slices, or from a
 * code a friend typed in), including the slices beyond castle 10, and the generation rules are pinned by a fingerprint so a change cannot slip in without
 * bumping {@link CourseGenerator#RULES_VERSION}.
 */
public class DeterminismTest {
    private static final double BEYOND_CASTLE_10 = 5600.0;      // castle 10 is at 5,000 m (finishCastle * castleSpacing); keep going past it

    private static Tower grow(long seed, Tuning t, double toAbsY) { Tower tw = new Tower(seed, t); while (tw.topAbsY() < toAbsY) tw.extend(); return tw; }
    private static List<byte[]> blobs(Tower tw) { List<byte[]> l = new ArrayList<>(); for (int k = 0; k < tw.sliceCount(); k++) l.add(tw.blob(k)); return l; }
    private static String hex(byte[] d) { StringBuilder sb = new StringBuilder(); for (byte b : d) sb.append(String.format("%02x", b)); return sb.toString(); }
    private static String fingerprint(List<byte[]> blobs, int upTo) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        for (int k = 0; k < Math.min(upTo, blobs.size()); k++) md.update(blobs.get(k));
        return hex(md.digest()).substring(0, 24);
    }

    @Test public void theSameSeedGrowsTheSameTowerTwiceIncludingBeyondCastleTen() throws Exception {
        Tuning t = TestUtil.tuning();
        long seed = 2024L;
        Tower a = grow(seed, t, BEYOND_CASTLE_10), b = grow(seed, t, BEYOND_CASTLE_10);
        assertEquals(a.sliceCount(), b.sliceCount());
        assertTrue("well past castle 10", a.topAbsY() >= BEYOND_CASTLE_10);
        int hazards = 0, crabs = 0, cannons = 0;
        for (int k = 0; k < a.sliceCount(); k++) {
            assertArrayEquals("slice " + k + " bit for bit", a.blob(k), b.blob(k));
            CourseGenerator.Framed f = SliceCodec.decode(a.blob(k)), g = SliceCodec.decode(b.blob(k));
            assertEquals(CourseIO.toJson(f.c), CourseIO.toJson(g.c));                       // platforms, ropes, pads, castles ... and every hazard with its phase
            assertEquals(f.yBase, g.yBase, 0.0); assertEquals(f.sBase, g.sBase, 0.0);
            for (Element h : f.c.hazards) { hazards++; if (h.type == Element.Type.CRAB) crabs++; if (h.type == Element.Type.CANNON || h.type == Element.Type.AIMED || h.type == Element.Type.MORTAR) cannons++; }
        }
        assertTrue("the course holds enemies and cannons to compare (" + hazards + ")", hazards > 20 && crabs > 0 && cannons > 0);
    }

    @Test public void aTowerResumedFromStoredSlicesContinuesExactlyAsTheFreshOne() throws Exception {
        Tuning t = TestUtil.tuning();
        long seed = 77L;
        Tower fresh = grow(seed, t, 1600.0);
        List<byte[]> all = blobs(fresh);
        List<byte[]> half = new ArrayList<>(all.subList(0, all.size() / 2));              // what the history file holds when the app is killed half way up
        Tower resumed = new Tower(seed, t, half, 0);
        while (resumed.sliceCount() < fresh.sliceCount()) resumed.extend();                // the rest is generated again, later and in another order of events
        for (int k = 0; k < fresh.sliceCount(); k++) assertArrayEquals("slice " + k, fresh.blob(k), resumed.blob(k));
    }

    @Test public void aTypedCourseCodeGivesTheSameTowerAsTheRandomDrawItCameFrom() throws Exception {
        Tuning t = TestUtil.tuning();
        long drawn = (System.nanoTime() ^ (System.currentTimeMillis() * 0x9E3779B97F4A7C15L)) & 0x7fffffffL | 1L;      // the game's own random draw
        long typed = SeedCode.decode(SeedCode.encode(drawn).toLowerCase());
        assertEquals(drawn, typed);
        Tower a = grow(drawn, t, 1100), b = grow(typed, t, 1100);
        for (int k = 0; k < a.sliceCount(); k++) assertArrayEquals(a.blob(k), b.blob(k));
    }

    @Test public void differentSeedsGiveDifferentTowers() throws Exception {
        Tuning t = TestUtil.tuning();
        Tower a = grow(2024L, t, 600), b = grow(2025L, t, 600);
        assertFalse(SliceCodec.same(SliceCodec.decode(a.blob(0)).c, SliceCodec.decode(b.blob(0)).c));
        assertNotEquals(SeedCode.encode(2024L), SeedCode.encode(2025L));
    }

    @Test public void theRulesVersionIsStampedAndOlderBuildsAreNotComparable() {
        assertEquals(18, CourseGenerator.RULES_VERSION);          // rules 18: Build 83 avalanche overhaul 2026-10-29 (PUNISHER 8-10 / NGH 9-12 rigid-cube blocks, momentum trigger, no warning), unreleased; rules 17: pendulum experiment, unreleased; rules 16: owner repair authorisations 2026-10-28 (saw fairness at acceptance, solver recovery, checkpoint and vanished-deck physics fixes), unreleased; rules 15: owner correction directive 2026-10-28 (audit DC1-DC10), unreleased; rules 14: NOT GONNA HAPPEN modernisation 2026-10-27 (NORMAL fingerprint unchanged); 13: owner progression directive 2026-10-27 (castle-to-castle controller), unreleased; 12: owner amendments 2026-10-27 (Enforcer, avalanches, PUNISHER variety memory, NORMAL rope-fallback correction); 11 never released          // Build 82: owner directive 2026-10-27 (pitch controller governs the fallbacks, slice endings leave two ordinary steps); 10 (release requirements 2026-10-26) and 9 were never released           // Build 82: owner correction directive 2026-10-26 (terrain curve over the whole climb, NGH profile, protected spike-drop platforms, skull hop timing)
        assertEquals(9, CourseGenerator.rulesOfBuild(82));           // Build 81: owner decisions 2026-10-25 (keys on the main route, the threat evaluator uses the simulation's reach rules)
        assertEquals(8, CourseGenerator.rulesOfBuild(81));
        assertEquals(7, CourseGenerator.rulesOfBuild(80));        // Build 80: the owner's generator directive (no level gates, PUNISHER's own curve, conflicts, splits with reconnecting branches)
        assertEquals(6, CourseGenerator.rulesOfBuild(79));        // Build 79: the owner's play-through notes, the difficulties, dead ends both ways, route dips (DECISIONS.md)
        assertEquals(5, CourseGenerator.rulesOfBuild(78));        // Build 77-78: faster skull with loop+swoop, key detours proved with the skull at either end
        assertEquals(5, CourseGenerator.rulesOfBuild(77));
        assertEquals(4, CourseGenerator.rulesOfBuild(76));        // Build 76: faster plank conveyor bridges
        assertEquals(3, CourseGenerator.rulesOfBuild(75));        // Build 72-75: flush ramps, faster crumbling, the wilder shaking ramp, the spinning ramp
        assertEquals(3, CourseGenerator.rulesOfBuild(72));
        assertEquals(2, CourseGenerator.rulesOfBuild(71));        // Build 71: creatures, chained drops, un-sequenced mortars, the late ramp, ice on tiny platforms
        assertEquals(1, CourseGenerator.rulesOfBuild(70));
        assertEquals(1, CourseGenerator.rulesOfBuild(63));
        assertEquals(1, CourseGenerator.rulesOfBuild(64));
        assertEquals(0, CourseGenerator.rulesOfBuild(62));
        assertEquals(0, CourseGenerator.rulesOfBuild(0));
    }

    /**
     * The generation rules fingerprint: the first six slices of seed 1 hashed. If this fails, generation (or its proofs, or the bundled tuning) changed:
     * bump {@link CourseGenerator#RULES_VERSION}, take the new fingerprint from the GENERATION_FINGERPRINT line in the test report, and say so in docs/DECISIONS.md.
     */
    @Test public void theGenerationRulesAreFingerprinted() throws Exception {
        Tuning t = TestUtil.tuning();
        Tower a = grow(1L, t, 1.0);
        while (a.sliceCount() < 6) a.extend();
        String fp = fingerprint(blobs(a), 6);
        System.out.println("GENERATION_FINGERPRINT rules=" + CourseGenerator.RULES_VERSION + " " + fp);      // in the test report, for the refresh
        assertEquals("generation changed: bump RULES_VERSION and refresh this fingerprint", RULES_5_FINGERPRINT, fp);
    }
    private static final String RULES_5_FINGERPRINT = "3e79526889137dc34e1edeb9";     // rules 18 (Build 83: avalanche overhaul on the rules 17 pendulum experiment; NORMAL seed 1 slices 0-5 change through rules 17 and the proofs from two decks back); rules 16: 978eaa5128ad2201d29dabbf, rules 16 (owner repair authorisations 2026-10-28: NORMAL seed 1 slice 1 is built again because its floor saw before a swing left 0.40 of its cycle from the real arrival); rules 15: a7a4dff4ddd8016c31424dde, rules 15 (owner correction directive 2026-10-28, audit DC1-DC10: physics, verdicts, arrivals, generator validation order; every mode changes); rules 14: 15edb1415cf18f57de1cc289, rules 14 (owner directive 2026-10-27: NGH modernisation changes no NORMAL slice; NORMAL seed 1 slices 0-5 change only by the SawFairnessTest correction: gap saws need a clear take-off deck of 2.5 m); rules 13: 7253bae077fbd88b48286046, rules 13 (owner progression directive 2026-10-27: castle-to-castle controller; NORMAL seed 1 slices 0-5 change through its variety memory, castle-1 partial feedback and continuations at demand); rules 12: 2b116711a13019934b3f28d8, rules 12 (owner amendments 2026-10-27: NORMAL changes only by the rope-fallback correction in these slices; avalanches start at castle 9); rules 11: 44abd13449ca6ba1468c4811, rules 11 (owner directive 2026-10-27: pitch controller governs the fallbacks, slice endings leave two ordinary steps); rules 10: 6a71174602775d7fdb2a5100, rules 10 (owner release requirements 2026-10-26: layer-gap pitch controller, slice continuation, integrity, challenge-first fallbacks); rules 9, local backtracking 2026-10-26: 01009205b6548c964ad51f22 (before it 7fcfd635ba7e9ed15fd63dd6); rules 9 (Build 82, owner difficulty clarification 2026-10-26: plain-platform pressure, threat fallbacks, creature scale; validation recovery: 0c530fbdb725b35552a8526c; before it b2ec27180d751028a40b25cb, correction batches 1-5, CI validate on b62b873 and 32b0872; batch 1 alone: a4fbc2381f92c3bca09db9f1); rules 5-8: b5fe0da76c74ccd56af3281d, Build 77 generation (rules version 5; unchanged from rules 4, Build 76: the skull rule and the extra key-detour proof change no seed-1 slice 0-5), seed 1, slices 0-5 (rules 3, Build 72-75: 58ece4dd45fb787dfae1f63d; rules 2, Build 71: 1111401d1974d639878f2d4e; rules 1, Build 63-70: 9812f0f035707f8b19824fa8)
}

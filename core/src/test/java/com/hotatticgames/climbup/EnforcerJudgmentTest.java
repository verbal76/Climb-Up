package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import com.hotatticgames.climbup.sim.*;
import java.util.*;
import org.junit.Test;

/**
 * Enforcer jump judgment (owner directive 2026-10-27, "no lemmings, no trained assassins", rules 14). A robot swats only when it can reach you and
 * has a deck of its encounter it accepts to come down on (reasonable, or risky within its own boldness; never a jump with nothing under it), steers
 * only a little in the air, lands on whichever deck it actually comes down on, falls only past all of them, and chases on foot or leaves otherwise.
 * Each scenario puts one robot straight into its chase on a short run of decks, with the climber thrown into the air near it or standing somewhere.
 */
public class EnforcerJudgmentTest {
    /** What one scenario did (Sim's report-only counters plus the robot's last state and deck). */
    static final class Out {
        int considered, rejected, risky, swats, hits, landOwn, landNear, hops, retreats, falls, fallEdge, fallChase, fallNoDeck, kicks, state, deck, runs;
        float maxDvFirst;          // largest change of its lane speed in the first 0.1 s of a swat (m/s)
        final List<Float> trace = new ArrayList<>();
        void add(Out o) {
            considered += o.considered; rejected += o.rejected; risky += o.risky; swats += o.swats; hits += o.hits; landOwn += o.landOwn; landNear += o.landNear;
            hops += o.hops; retreats += o.retreats; falls += o.falls; fallEdge += o.fallEdge; fallChase += o.fallChase; fallNoDeck += o.fallNoDeck; kicks += o.kicks; runs++;
        }
        @Override public String toString() {
            return String.format(Locale.ROOT, "considered=%d rejected=%d risky=%d swats=%d hits=%d landOwn=%d landNear=%d hops=%d retreats=%d falls=%d (edge %d chase %d none %d) kicks=%d state=%d deck=%d",
                considered, rejected, risky, swats, hits, landOwn, landNear, hops, retreats, falls, fallEdge, fallChase, fallNoDeck, kicks, state, deck);
        }
    }

    /** Decks {s, y, w} (the first is the robot's own and the climber's checkpoint; a 4th value 1 makes it a sideways mover). */
    static Course decks(Tuning t, float[]... d) {
        Course c = new Course(0, t.circumference());
        for (int i = 0; i < d.length; i++) {
            boolean mover = d[i].length > 3 && d[i][3] == 1f;
            Element e = new Element(mover ? Element.Type.MOVE_H : Element.Type.STATIC, d[i][0], d[i][1], d[i][2]);
            if (mover) { e.amp = 1.5f; e.period = 3f; }
            if (i == 0) e.checkpoint = true;
            c.add(e);
        }
        return c;
    }

    /** The climber in the air at ({@code ps}, {@code py}) moving ({@code pvx}, {@code pvy}), holding the stick its way (the other way from {@code reverseAt} s; negative: never). */
    static Out air(Tuning t, Course c, int tier, float phase, float at, float ps, float py, float pvx, float pvy, float reverseAt, float secs) {
        return run(t, c, tier, phase, at, 0, true, ps, py, pvx, pvy, reverseAt, secs, null);
    }
    /** The climber standing still on deck {@code on}. */
    static Out stand(Tuning t, Course c, int tier, float phase, float at, int on, float secs) {
        return run(t, c, tier, phase, at, on, false, 0f, 0f, 0f, 0f, -1f, secs, null);
    }
    /** Robot of {@code tier} (seeded by {@code phase}) chasing on deck 0 at arc {@code at}; {@code copyAtSwat}, if given, receives a copy of the Sim when its first swat starts. */
    static Out run(Tuning t, Course c, int tier, float phase, float at, int on, boolean air, float ps, float py, float pvx, float pvy, float reverseAt, float secs, Sim[] copyAtSwat) {
        Element r = new Element(Element.Type.ENFORCER, c.get(0).s, c.get(0).y, c.get(0).w);
        r.anchor = 0; r.len = c.size(); r.skin = tier; r.phase = phase;
        c.hazards.add(r);
        int hi = c.hazards.indexOf(r);
        Sim s = Sim.startOn(c, t, on);
        InputState in = new InputState();
        s.step(in);
        s.enforcerChaseAt(hi, 0, at);
        if (air) { s.s = c.wrap(ps); s.y = py; s.vx = pvx; s.vy = pvy; s.mode = Sim.Mode.AIR; s.onElem = -1; }
        Out o = new Out();
        float swatV = Float.NaN, swatT = -1f;
        for (int i = 0; i < (int) (secs / Sim.DT); i++) {
            in.clear(); if (air) in.moveX = Math.signum(pvx) * (reverseAt >= 0f && s.time >= reverseAt ? -1f : 1f);
            s.step(in); s.events = 0;
            int st = s.enforcerState(hi);
            if (st == 4) {
                if (Float.isNaN(swatV)) { swatV = s.enforcerVs(hi); swatT = s.time; if (copyAtSwat != null && copyAtSwat[0] == null) copyAtSwat[0] = s.copy(); }
                else if (s.time - swatT <= 0.1f + 1e-4f) o.maxDvFirst = Math.max(o.maxDvFirst, Math.abs(s.enforcerVs(hi) - swatV));
            } else swatV = Float.NaN;
            if (i % 6 == 0) { o.trace.add(s.enforcerS(hi, r)); o.trace.add(s.enforcerY(hi, r)); o.trace.add((float) st); }
        }
        o.considered = s.enfSwatConsidered; o.rejected = s.enfSwatRejected; o.risky = s.enfSwatRisky; o.swats = s.enfSwatTries; o.hits = s.enfSwatHits;
        o.landOwn = s.enfLandOwn; o.landNear = s.enfLandNear; o.hops = s.enfHops; o.retreats = s.enfRetreats; o.falls = s.enfFalls;
        o.fallEdge = s.enfFallEdge; o.fallChase = s.enfFallChase; o.fallNoDeck = s.enfFallNoDeck; o.kicks = s.enfKickTries;
        o.state = s.enforcerState(hi); o.deck = s.enforcerDeck(hi);
        return o;
    }

    static Tuning pun() throws Exception { return TestUtil.tuning().hardCopy(1); }
    static final float[] PHASES = {0.11f, 0.37f, 0.63f, 0.89f};
    /** A jump across a gap toward a 6 m deck ({@code far} = its centre). */
    static Course gap(Tuning t, float far) { return decks(t, new float[]{10f, 0f, 6f}, new float[]{far, 0f, 6f}); }

    /**
     * Jumps across a gap toward a deck of width w (0.9-2.6 m) with a wide deck beyond it, the climber sometimes turning back mid-air, for every robot
     * seed in PHASES: totals for {@code tier}.
     */
    static Out sweep(Tuning t, int tier, float[] widths) {
        Out sum = new Out();
        for (float w : widths) for (float far : new float[]{15.2f, 16f, 17f}) for (float rev : new float[]{-1f, 0.12f, 0.25f}) for (float ph : PHASES)
            sum.add(air(t, decks(t, new float[]{10f, 0f, 6f}, new float[]{far + w / 2f, 0.4f, w}, new float[]{far + w + 3.5f, 0.8f, 5f}), tier, ph, 12.2f, 13.3f, 1.1f, 5.6f, 5.5f, rev, 3f));
        return sum;
    }
    static final float[] ALL_WIDTHS = {0.9f, 1.3f, 1.8f, 2.6f};

    /** 1. A reasonable jump (a wide deck one short gap away): it swats and comes down on that deck, no fall. */
    @Test public void anEasyJumpIsTakenAndLandsSafely() throws Exception {
        Tuning t = pun();
        for (int tier = 1; tier <= 2; tier++) {
            Out o = air(t, gap(t, 17f), tier, 0f, 12.0f, 13.4f, 1.2f, 5.6f, 5f, -1f, 3f);
            assertEquals("tier " + tier + " swats: " + o, 1, o.swats);
            assertEquals("tier " + tier + " comes down on a deck: " + o, 1, o.landOwn + o.landNear);
            assertEquals("tier " + tier + " no fall: " + o, 0, o.falls);
        }
    }

    /** 2. An impossible gap (nothing to come down on within its reach): never attempted; it keeps after you along its deck instead. */
    @Test public void anImpossibleGapIsNeverAttempted() throws Exception {
        Tuning t = pun();
        for (int tier = 1; tier <= 2; tier++) for (float ph : PHASES) {
            Out o = air(t, gap(t, 30f), tier, ph, 12.0f, 13.4f, 1.2f, 5.6f, 5f, -1f, 3f);
            assertEquals("tier " + tier + " phase " + ph + ": no swat " + o, 0, o.swats);
            assertTrue("tier " + tier + " phase " + ph + ": it sized one up and turned it down " + o, o.rejected > 0);
            assertEquals("tier " + tier + " phase " + ph + ": no fall " + o, 0, o.falls);
            float far = 0f; for (int i = 0; i < o.trace.size(); i += 3) far = Math.max(far, o.trace.get(i));
            assertTrue("tier " + tier + " phase " + ph + ": it moves toward you along its deck, not standing (from 12.0 to " + far + ")", far > 12.2f);
        }
    }

    /** 3. A narrow landing: a bold red robot takes more of these risky jumps than an orange one, and every taken jump had a landing it accepted. */
    @Test public void aNarrowLandingIsTakenOnlyByTheBold() throws Exception {
        Tuning t = pun();
        float[] narrow = {0.9f, 1.3f};
        Out o = sweep(t, 1, narrow), r = sweep(t, 2, narrow);
        assertTrue("red takes more risky jumps than orange (red " + r + " / orange " + o + ")", r.risky > o.risky);
        assertTrue("orange turns more of them down (share)", o.rejected / (float) Math.max(1, o.considered) > r.rejected / (float) Math.max(1, r.considered));
        for (Out x : new Out[]{o, r}) assertEquals("every swat ends on a deck or in an explained fall: " + x, x.swats, x.landOwn + x.landNear + x.falls);
    }

    /** 4. A moving platform is never planned as a landing (a robot knows nothing of where it will be): with only a mover across the gap, no swat. */
    @Test public void aMovingPlatformIsNeverPlannedAsALanding() throws Exception {
        Tuning t = pun();
        for (int tier = 1; tier <= 2; tier++) for (float ph : PHASES) {
            Out o = air(t, decks(t, new float[]{10f, 0f, 6f}, new float[]{17f, 0f, 6f, 1f}), tier, ph, 12.0f, 13.4f, 1.2f, 5.6f, 5f, -1f, 3f);
            assertEquals("tier " + tier + " phase " + ph + ": " + o, 0, o.swats);
            assertEquals(0, o.falls);
        }
    }

    /** 5. It swats only at a climber in the air; one standing on its deck gets the ground attack (a telegraphed kick) instead. */
    @Test public void itSwatsOnlyAtAnAirborneClimber() throws Exception {
        Tuning t = pun();
        for (int tier = 1; tier <= 2; tier++) {
            Out o = stand(t, decks(t, new float[]{10f, 0f, 6f}), tier, 0f, 12.0f, 0, 2f);
            assertEquals("tier " + tier + ": " + o, 0, o.considered);
            assertTrue("tier " + tier + " kicks: " + o, o.kicks > 0);
        }
        assertTrue("an airborne climber in reach gets sized up", air(t, gap(t, 17f), 2, 0f, 12.0f, 13.4f, 1.2f, 5.6f, 5f, -1f, 1f).considered > 0);
    }

    /** 6. A climber who turns back mid-air: the robot's steering is limited (no perfect retargeting), at most its trait's m/s^2 for 0.1 s. */
    @Test public void aClimberWhoTurnsBackIsNotPerfectlyRetargeted() throws Exception {
        Tuning t = pun();
        boolean steered = false;
        for (int tier = 1; tier <= 2; tier++) for (float ph : PHASES) {
            Out o = air(t, gap(t, 17f), tier, ph, 12.0f, 13.4f, 1.2f, 5.6f, 5f, 0.15f, 2f);
            if (o.swats == 0) continue;
            float cap = Element.ENF_STEER_HI[tier] * 0.1f + 1e-3f;
            assertTrue("tier " + tier + " phase " + ph + ": lane speed changed by " + o.maxDvFirst + " in 0.1 s (cap " + cap + ")", o.maxDvFirst <= cap);
            steered |= o.maxDvFirst > 0f;
        }
        assertTrue("it does steer a little toward you", steered);
    }

    /** 7. After a swat it lands, recovers and is back on the chase (it attacks again: a kick on the deck it came down on). */
    @Test public void afterASwatItLandsAndChasesAgain() throws Exception {
        Tuning t = pun();
        Out o = air(t, gap(t, 17f), 2, 0f, 12.0f, 13.4f, 1.2f, 5.6f, 5f, -1f, 3f);
        assertEquals(o.toString(), 1, o.swats);
        assertEquals(o.toString(), 0, o.falls);
        assertTrue("back on the chase after landing: " + o, o.kicks > 0 && o.state >= 2 && o.state <= 5);
    }

    /** 8. Coming down on the neighbouring deck (lower than its own) is a landing, not a fall (the Rules 14 Round 1 defect: only its own deck counted). */
    @Test public void itLandsOnANeighbouringDeckInsteadOfFalling() throws Exception {
        Tuning t = pun();
        for (float dy : new float[]{-1.2f, 0f, 0.6f}) {
            Out o = air(t, decks(t, new float[]{10f, 0f, 6f}, new float[]{17f, dy, 6f}), 2, 0f, 12.0f, 13.4f, 1.2f, 5.6f, 5f, -1f, 3f);
            assertEquals("deck " + dy + " m: " + o, 1, o.landNear);
            assertEquals("deck " + dy + " m: " + o, 0, o.falls);
            assertEquals("deck " + dy + " m: it stands on the neighbouring deck", 1, o.deck);
        }
    }

    /** 9. Falls stay possible but only after a committed jump to a landing it accepted, and every one has a cause. */
    @Test public void aFallOnlyFollowsACommittedJumpAndIsExplained() throws Exception {
        Tuning t = pun();
        Out r = sweep(t, 2, ALL_WIDTHS);
        assertEquals("falls by cause add up: " + r, r.falls, r.fallEdge + r.fallChase + r.fallNoDeck);
        assertEquals("each swat ends on a deck or in a fall: " + r, r.swats, r.landOwn + r.landNear + r.falls);
        assertTrue("red still makes mistakes now and then: " + r, r.falls > 0);
        assertTrue("but mostly lands: " + r, r.landOwn + r.landNear > 10 * r.falls);
    }

    /** 10. It pursues across several decks (hops from deck to deck toward the one you stand on). */
    @Test public void itPursuesAcrossSeveralDecks() throws Exception {
        Tuning t = pun();
        Course c = decks(t, new float[]{10f, 0f, 6f}, new float[]{17f, 0f, 6f}, new float[]{24f, 0.5f, 6f});
        Out o = stand(t, c, 2, 0f, 8f, 2, 4f);
        assertTrue("two hops: " + o, o.hops >= 2);
        assertEquals("it reached your deck: " + o, 2, o.deck);
        assertEquals(0, o.falls);
    }

    /** 11. It leaves on its own once its chase time is up (a retreat, not a fall). */
    @Test public void itRetreatsWhenItsChaseIsOver() throws Exception {
        Tuning t = pun();
        for (int tier = 0; tier <= 2; tier++) for (float ph : PHASES) {
            Course c = decks(t, new float[]{10f, 0f, 6f}, new float[]{17f, 0f, 6f}, new float[]{40f, 0f, 6f});
            Out o = stand(t, c, tier, ph, 8f, 2, 16f);          // you wait out of its reach
            assertEquals("tier " + tier + " phase " + ph + ": " + o, 1, o.retreats);
            assertEquals("tier " + tier + " phase " + ph + ": " + o, 0, o.falls);
        }
    }

    /** 12. Tiers: yellow never swats, red goes for more than orange and accepts more risk. */
    @Test public void theTiersDiffer() throws Exception {
        Tuning t = pun();
        Out y = sweep(t, 0, ALL_WIDTHS), o = sweep(t, 1, ALL_WIDTHS), r = sweep(t, 2, ALL_WIDTHS);
        assertEquals("yellow: " + y, 0, y.swats);
        assertTrue("red swats more than orange (" + r + " / " + o + ")", r.swats > o.swats);
        assertTrue("red takes more risky jumps", r.risky > o.risky);
    }

    /** 13. One robot seed always behaves the same; different seeds give different robots (their traits). */
    @Test public void itIsDeterministicAndEachRobotIsItsOwn() throws Exception {
        Tuning t = pun();
        Course a = decks(t, new float[]{10f, 0f, 6f}, new float[]{16.3f, 0.4f, 1.3f}, new float[]{20.3f, 0.8f, 5f});
        Course b = decks(t, new float[]{10f, 0f, 6f}, new float[]{16.3f, 0.4f, 1.3f}, new float[]{20.3f, 0.8f, 5f});
        assertEquals(air(t, a, 2, 0.37f, 12.2f, 13.3f, 1.1f, 5.6f, 5.5f, 0.12f, 3f).trace, air(t, b, 2, 0.37f, 12.2f, 13.3f, 1.1f, 5.6f, 5.5f, 0.12f, 3f).trace);
        Set<List<Float>> seen = new HashSet<>();
        for (float ph : PHASES) seen.add(air(t, decks(t, new float[]{10f, 0f, 6f}, new float[]{16.3f, 0.4f, 1.3f}, new float[]{20.3f, 0.8f, 5f}), 2, ph, 12.2f, 13.3f, 1.1f, 5.6f, 5.5f, 0.12f, 3f).trace);
        assertTrue("different robots move differently (" + seen.size() + " of " + PHASES.length + ")", seen.size() >= 3);
    }

    /** 14. A Sim copied mid-swat (what a save captures: the robot's state lives in the saved creature arrays, its traits come from its seed) continues exactly. */
    @Test public void aCopyTakenMidSwatContinuesExactly() throws Exception {
        Tuning t = pun();
        Course c = gap(t, 17f);
        Sim[] cp = new Sim[1];
        run(t, c, 2, 0.63f, 12.0f, 0, true, 13.4f, 1.2f, 5.6f, 5f, -1f, 1.5f, cp);
        assertNotNull("a swat started", cp[0]);
        Sim x = cp[0], y = cp[0].copy();
        int hi = c.hazards.size() - 1; InputState in = new InputState();
        for (int i = 0; i < (int) (1.5f / Sim.DT); i++) {
            in.clear(); in.moveX = 1f; x.step(in); y.step(in);
            assertEquals("arc at step " + i, x.enforcerS(hi, c.hazards.get(hi)), y.enforcerS(hi, c.hazards.get(hi)), 0f);
            assertEquals("height at step " + i, x.enforcerY(hi, c.hazards.get(hi)), y.enforcerY(hi, c.hazards.get(hi)), 0f);
            assertEquals("state at step " + i, x.enforcerState(hi), y.enforcerState(hi));
        }
        assertEquals(x.enfLandNear + x.enfLandOwn + x.enfFalls, y.enfLandNear + y.enfLandOwn + y.enfFalls);
    }

    /** 15. Generated NOT GONNA HAPPEN encounters (red robots) are still proven passable with the robot active, as the generator and solver require. */
    @Test public void generatedRedEncountersStayPassable() throws Exception {
        Tuning t = TestUtil.tuning().hardCopy(2);
        int checked = 0;
        CourseGenerator.Framed f = null;
        for (int k = 0; k < 5; k++) {
            f = CourseGenerator.chunkFramed(1, k, f, t);
            for (Element h : f.c.hazards) {
                if (h.type != Element.Type.ENFORCER) continue;
                checked++;
                assertEquals("NGH robots are red", 2, h.skin);
                assertTrue("slice " + k + " robot on " + h.anchor + ": a climber gets through clean", Autopilot.chainClean(f.c, t, h.anchor - 1, h.anchor + Math.round(h.len)));
            }
        }
        assertTrue("red encounters appear (" + checked + ")", checked > 0);
    }
}

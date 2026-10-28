package com.hotatticgames.climbup.sim;

import static org.junit.Assert.*;

import java.nio.file.*;
import org.junit.Test;

/**
 * Whole-slice solvability (owner directive 2026-10-27, rules 14). Four PUNISHER slices failed the whole-course solver although every link passed its
 * isolated proof: Campaign A on cbe6415 (seed 1 slice 45, seed 2 slices 19 and 47) and the rules 13 baseline b06dc71 (seed 5 slice 64). Each fixture is
 * the slice exactly as generated there. None was unclimbable: in each the arrival state decided the outcome (two cases are shown below with arrival
 * sweeps that both fail and pass), and the authoritative solver lacked a move a person makes - waiting before a forced launch or a crumbling deck,
 * and carrying on along a fork's branch it was carried onto. The generator now also accepts a slice only once that solver has climbed it end to end
 * (CourseGenerator.continuous).
 */
public class WholeSliceSolvabilityTest {
    private static Tuning pun() throws Exception { return Tuning.parse(new String(Files.readAllBytes(Paths.get("../assets/data/tuning.json")))).hardCopy(1); }
    private static Course fixture(String name) throws Exception { return CourseIO.fromJson(new String(Files.readAllBytes(Paths.get("src/test/resources/fixtures/" + name + ".json")))); }
    /** chainClean from deck {@code from} over {@code to} after waiting 0, 0.1 ... 3.0 s: '+' clean, '.' not. */
    private static String sweep(Course c, Tuning t, int from, int to) {
        StringBuilder b = new StringBuilder();
        for (int w = 0; w <= 30; w++) b.append(Autopilot.chainClean(c, t, from, to, w * 0.1f) ? '+' : '.');
        return b.toString();
    }
    private static void solves(String name) throws Exception {
        Autopilot.Report r = Autopilot.run(fixture(name), pun(), 4000f);
        assertTrue(name + " completes (link " + r.failedLink + ": " + r.failInfo + ")", r.completed);
    }

    /** Seed 2 slice 19: the hop from deck 73 onto spring 74 forces a launch that an aimed cannon's ball (deck 71) met right after take-off. Waiting on 73
     *  first gives a clean pass for some arrival times and not others; the solver's look-ahead now follows the launch, so it waits for a clean one. */
    @Test public void aForcedSpringLaunchIsTimedFromTheDeckBefore() throws Exception {
        Course c = fixture("punisher_seed2_slice19_cbe6415"); Tuning t = pun();
        String s = sweep(c, t, 73, 75);
        assertTrue("some arrival times are clean: " + s, s.indexOf('+') >= 0);
        assertTrue("and some are not (the arrival state matters): " + s, s.indexOf('.') >= 0);
        solves("punisher_seed2_slice19_cbe6415");
    }

    /** Seed 1 slice 45: crumbling mover 48 was reached by a ledge catch the moment deck 47 was reached; in that phase no move off it existed before it went.
     *  The look-ahead now pulls up and sizes up the move off a crumbling deck before committing to it, so the solver waits on 47 for a phase that works. */
    @Test public void aCrumblingMoverIsBoardedOnlyWhenTheMoveOffItWorks() throws Exception {
        Course c = fixture("punisher_seed1_slice45_cbe6415"); Tuning t = pun();
        String s = sweep(c, t, 47, 49);
        assertTrue("some arrival times are clean: " + s, s.indexOf('+') >= 0);
        assertTrue("and some are not: " + s, s.indexOf('.') >= 0);
        solves("punisher_seed1_slice45_cbe6415");
    }

    /** Seed 2 slice 47: heading for deck 58 the climber took fork 57's one-pad branch, which threw them onto deck 87 of a later fork's branch (67-73). The
     *  branch leads forward; the solver now carries on along a branch to its rejoin instead of planning the route link from there. */
    @Test public void aClimberCarriedOntoAForkBranchFollowsItToTheRejoin() throws Exception {
        Course c = fixture("punisher_seed2_slice47_cbe6415");
        assertFalse("the fixture keeps its forks", c.forks.isEmpty());
        assertNotNull("deck 87 is a fork branch deck", Autopilot.branchOf(c, 87));
        solves("punisher_seed2_slice47_cbe6415");
    }

    /** Rules 13 baseline, seed 5 slice 64 (~4,844 m): a bee shoved the climber off deck 7 onto deck 102 of fork 3's branch below; pre-existing, same cure. */
    @Test public void theRules13BaselineFailureIsTheSameBranchCase() throws Exception {
        Course c = fixture("punisher_seed5_slice64_b06dc71");
        assertNotNull("deck 102 is a fork branch deck", Autopilot.branchOf(c, 102));
        solves("punisher_seed5_slice64_b06dc71");
    }

    /** Fixtures keep a slice's forks (they were not written before, so a stored slice lost its branches). */
    @Test public void courseJsonKeepsForks() {
        Course c = new Course(3, 62.83f);
        for (int i = 0; i < 4; i++) c.add(new Element(Element.Type.STATIC, 3f * i, 0f, 2f));
        c.forks.add(new int[]{0, 2, 2, 1});
        Course r = CourseIO.fromJson(CourseIO.toJson(c));
        assertEquals(1, r.forks.size());
        assertArrayEquals(new int[]{0, 2, 2, 1}, r.forks.get(0));
    }

    /** NORMAL seed 3 slice 53 (Campaign A on cbe6415): a spiked block one revolution up, 3.65 m ahead of a pad and ~9 m above it, sits in the launch's
     *  flight to its landing deck though outside the column straight above the pad. The corridor covers the flight; a block behind the pad, or the same
     *  block beside a pad that lands the other way, leaves a legitimate jump alone. */
    @Test public void theLaunchCorridorCoversTheFlightToTheLandingDeck() throws Exception {
        Tuning t = Tuning.parse(new String(Files.readAllBytes(Paths.get("../assets/data/tuning.json"))));
        Course c = new Course(3, t.circumference());
        Element pad = new Element(Element.Type.PAD, 20f, 0f, 2f), land = new Element(Element.Type.STATIC, 26f, 3.5f, 3f);
        Element ahead = new Element(Element.Type.SPIKE_BLOCK, 23.65f, 9f, 0f); ahead.len = 1.3f;
        Element behind = new Element(Element.Type.SPIKE_BLOCK, 16.35f, 9f, 0f); behind.len = 1.3f;
        Element landBack = new Element(Element.Type.STATIC, 14f, 3.5f, 3f);
        assertTrue("the block ahead is in the flight", CourseGenerator.corridorBlocked(c, t, pad, land, ahead));
        assertFalse("a block behind is not", CourseGenerator.corridorBlocked(c, t, pad, land, behind));
        assertFalse("the same block ahead of a pad that lands the other way is not", CourseGenerator.corridorBlocked(c, t, pad, landBack, ahead));
        Element key = new Element(Element.Type.KEY, 20f, 4f, 0f);
        assertFalse("keys are no obstacle", CourseGenerator.corridorBlocked(c, t, pad, land, key));
    }
}

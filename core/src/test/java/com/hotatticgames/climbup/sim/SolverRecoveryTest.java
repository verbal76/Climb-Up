package com.hotatticgames.climbup.sim;

import static org.junit.Assert.*;

import java.nio.file.*;
import org.junit.Test;

/** Owner solver reliability directive 2026-10-26: the exact failing conditions of campaign 40303676032, kept as regressions. */
public class SolverRecoveryTest {
    static Tuning tuning() throws Exception { return Tuning.parse(new String(Files.readAllBytes(Paths.get("../assets/data/tuning.json")))); }

    /** NGH seed 2 slices 0 and 1 threw IndexOutOfBoundsException -1: a move that ended on a spike slab's top (a surface, onElem -1) set the link to -1.
     *  Slice 1 also needs the recovery to step off a slab onto its own platform (stepping on toward the route walked into crabs and fell 15 m). */
    @Test public void movesEndingOnASpikeSlabTopKeepTheRoute() throws Exception {
        Tuning t = tuning().hardCopy(2);
        CourseGenerator.Framed f = null;
        for (int k = 0; k < 2; k++) {
            f = CourseGenerator.chunkFramed(2L, k, f, t);
            Autopilot.Report r = Autopilot.run(f.c, t, 4000f);
            assertTrue("NGH seed 2 slice " + k + " completes (link " + r.failedLink + ": " + r.failInfo + ")", r.completed);
        }
    }

    /** The generator proves every link onto a pad or spring with the arrival it actually gives: some move onto it must leave a launch from which
     *  the launcher's own link is made (the solver's lookahead), not only the launcher's link from a standstill on it. */
    @Test public void linksOntoPadsAndSpringsLeaveAWorkingLaunch() throws Exception {
        Tuning base = tuning();
        int checked = 0;
        for (Tuning t : new Tuning[]{base, base.hardCopy(1)}) {
            CourseGenerator.Framed f = null;
            for (int k = 0; k < 3; k++) {
                f = CourseGenerator.chunkFramed(1L, k, f, t);
                Course c = f.c;
                for (int i = 0; i + 2 < c.routeSize(); i++) {
                    Element land = c.get(c.nextRoute(i));
                    if (c.get(i).anchor >= 0 || (land.type != Element.Type.PAD && land.type != Element.Type.SPRING)) continue;
                    assertTrue("slice " + k + " link " + i + " onto a " + land.type + " leaves a working launch", Autopilot.plan(Sim.startOn(c, t, i), i, false, true).ok);
                    checked++;
                }
            }
        }
        assertTrue("pads and springs were generated and checked (" + checked + ")", checked > 0);
    }

    /** Recovery fixes change no solver result where nothing went wrong: the same slice solves the same way twice. */
    @Test public void solverRunsRepeat() throws Exception {
        Tuning t = tuning();
        CourseGenerator.Framed f = CourseGenerator.chunkFramed(3L, 0, null, t);
        Autopilot.Report a = Autopilot.run(f.c, t, 4000f), b = Autopilot.run(f.c, t, 4000f);
        assertEquals(a.completed, b.completed); assertEquals(a.links, b.links); assertEquals(a.simTime, b.simTime, 0f); assertEquals(a.failInfo, b.failInfo);
    }

    /** NGH seed 2 slice 7 as generated on 2d4388a (fixture): the move planned on a rope beside a blob deck stalled 20 s at the rope top (the planner
     *  simulates only the nearby window). The link is traversable (plans from the rope 10/12, from the deck 120/360); a stalled move is now sized up
     *  again from where the climber is, at most twice per link. */
    @Test public void aMoveThatStallsOnARopeIsPlannedAgain() throws Exception {
        Tuning t = tuning().hardCopy(2);
        Course c = CourseIO.fromJson(new String(Files.readAllBytes(Paths.get("src/test/resources/fixtures/ngh_seed2_slice7_2d4388a.json"))));
        Autopilot.Report r = Autopilot.run(c, t, 4000f);
        assertTrue("NGH seed 2 slice 7 (2d4388a) completes (link " + r.failedLink + ": " + r.failInfo + ")", r.completed);
    }

    /** A crab shoves the climber off platform 96 (just past pad 95) down onto rope 88: the climb goes on from the rope, the ledges and decks below, and the
     *  pad launch above is history (2026-10-26 GenBench, PUNISHER seed 2 slice 53 on e097d29: every hop from 88-90 had counted as reaching pad 95). */
    @Test public void aClimberShovedDownPastAPadClimbsOnFromWhereTheyLanded() throws Exception {
        Tuning t = tuning().hardCopy(1);
        Course c = CourseIO.fromJson(new String(Files.readAllBytes(Paths.get("src/test/resources/fixtures/punisher_seed2_slice53_e097d29.json"))));
        Autopilot.Report r = Autopilot.run(c, t, 4000f);
        assertTrue("PUNISHER seed 2 slice 53 (e097d29) completes (link " + r.failedLink + ": " + r.failInfo + ")", r.completed);
    }

    /** The balance conveyor's exit is proven from the landing of the jump from the middle of the platform before it (CourseGenerator.provenStart); the
     *  climber came off a rope onto that platform and jumped on at speed, landed at the near end, which tips down, and slid off it again and again
     *  (2026-10-27, Difficulty report 40422375969: NORMAL seed 1 slice 55 on 5c09272). Like a person, it now lines up in the middle first. */
    @Test public void theBalanceConveyorIsBoardedFromWhereItsJumpWasProven() throws Exception {
        Course c = CourseIO.fromJson(new String(Files.readAllBytes(Paths.get("src/test/resources/fixtures/normal_seed1_slice55_5c09272.json"))));
        Autopilot.Report r = Autopilot.run(c, tuning(), 4000f);
        assertTrue("NORMAL seed 1 slice 55 (5c09272) completes (link " + r.failedLink + ": " + r.failInfo + ")", r.completed);
    }

    /** The same slice, deck 88 before balance conveyor 89 under aimed cannon 26 (anchor 85). The lineup in the middle of deck 88, right when the move off
     *  rope 87 lands, is hit at every wait (two hits, which the hit-free witness rightly refused, 2026-10-28 smoke gate); jumping on from the landing
     *  reaches the conveyor's near end, which slides you off. Holding on to rope 87 for 0.5-4.8 s first gives a clean crossing. The solver must find
     *  that: a proven, hit-free witness (no hit or fall anywhere, the repositionings included), never a completion with hits. */
    @Test public void theLineupUnderTheAimedCannonIsTimedFromTheRopeAndTheWitnessIsHitFree() throws Exception {
        Course c = CourseIO.fromJson(new String(Files.readAllBytes(Paths.get("src/test/resources/fixtures/normal_seed1_slice55_5c09272.json"))));
        Tuning t = tuning();
        assertTrue("deck 88 is lined up for balance conveyor 89", Autopilot.lineupAhead(c, 88) && c.get(89).tiltBelt());
        assertEquals("aimed cannon 26 watches deck 88", Element.Type.AIMED, c.hazards.get(26).type);
        Autopilot.Report r = Autopilot.run(c, t, 4000f);
        Sim real = Autopilot.lastReal;
        assertEquals("proven solvable (" + r.failInfo + ")", Autopilot.Verdict.PROVEN_SOLVABLE, r.verdict);
        assertEquals("no hit anywhere in the witness", 0, real.hits);
        assertEquals("no fall anywhere in the witness", 0, real.falls);
        assertTrue("and it reached the end of the slice", r.completed);
    }

    /** A repositioning is played for real only after a clean rehearsal on an exact copy; a rehearsal that is hit leaves the climber untouched. */
    @Test public void aRecoveryRehearsalThatIsHitLeavesTheClimbUntouched() throws Exception {
        Course c = CourseIO.fromJson(new String(Files.readAllBytes(Paths.get("src/test/resources/fixtures/normal_seed1_slice55_5c09272.json"))));
        Tuning t = tuning();
        Sim s = Sim.startOn(c, t, 0);
        Sim before = s.copy();
        assertFalse("a move that is hit is not played", Autopilot.rehearsed(s, x -> x.hits++));
        assertEquals(before.time, s.time, 0f); assertEquals(before.s, s.s, 0f); assertEquals(0, s.hits);
        assertTrue("a clean one is played", Autopilot.rehearsed(s, x -> Autopilot.idle(x, 12)));
        Sim ref = before.copy(); Autopilot.idle(ref, 12);
        assertEquals("exactly as rehearsed", ref.time, s.time, 0f); assertEquals(ref.s, s.s, 0f); assertEquals(ref.y, s.y, 0f);
    }

    /** Before a move the solver checks it against every hazard on screen (the planner only sees nearby ones) and waits if one would catch it; that check
     *  ran only on a platform, so the move off rope 11 walked into a ball from the aimed cannon by platform 10 (2026-10-27, Difficulty report 40422375969:
     *  PUNISHER seed 2 slice 57 on 5c09272). It now holds on to ropes and cables as well. */
    @Test public void aClimberOnARopeWaitsForACannonballFromLinksBack() throws Exception {
        Course c = CourseIO.fromJson(new String(Files.readAllBytes(Paths.get("src/test/resources/fixtures/punisher_seed2_slice57_5c09272.json"))));
        Autopilot.Report r = Autopilot.run(c, tuning().hardCopy(1), 4000f);
        assertTrue("PUNISHER seed 2 slice 57 (5c09272) completes (link " + r.failedLink + ": " + r.failInfo + ")", r.completed);
    }

    @Test public void everyUnsuccessfulSliceHasExactlyOneFailureClass() {
        assertEquals("completed", LaneReport.failClass(true, ""));
        assertEquals("solver_exception", LaneReport.failClass(false, "\"solver threw java.lang.IndexOutOfBoundsException\""));
        assertEquals("route_link_failure", LaneReport.failClass(false, "setback while executing the move: hits=1"));
        assertEquals("planning_failure", LaneReport.failClass(false, "plan failed: mode=AIR"));
        assertEquals("recovery_failure", LaneReport.failClass(false, "recover mode=CABLE"));
        assertEquals("recovery_failure", LaneReport.failClass(false, "detour back on the route platform"));
        assertEquals("recovery_failure", LaneReport.failClass(false, "lost the route: link index -1"));
        assertEquals("knockback_loop", LaneReport.failClass(false, "stuck at one link: knocked back again and again"));
        assertEquals("timeout", LaneReport.failClass(false, "[report-derived] planned move did not reach the next link within 20 s"));
        assertEquals("unknown", LaneReport.failClass(false, ""));
    }
}

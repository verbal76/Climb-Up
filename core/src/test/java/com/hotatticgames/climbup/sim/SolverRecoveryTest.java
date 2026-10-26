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

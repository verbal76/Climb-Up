package com.hotatticgames.climbup.sim;

import static org.junit.Assert.*;

import java.nio.file.*;
import java.util.*;
import org.junit.Test;

/** Validation gate for the three-lane reports (owner reporting directive and classification amendment, 2026-10-26). */
public class LaneReportTest {
    static Tuning tuning() throws Exception { return Tuning.parse(new String(Files.readAllBytes(Paths.get("../assets/data/tuning.json")))); }

    @Test public void everyElementTypeAndModuleKindIsClassified() {
        assertEquals("Element.Type count changed: classify the new value in Lanes (owner amendment)", 29, Element.Type.values().length);
        EnumMap<Lanes.Lane, Integer> n = new EnumMap<>(Lanes.Lane.class);
        for (Element.Type k : Element.Type.values()) { n.merge(Lanes.lane(k), 1, Integer::sum); assertFalse(Lanes.describe(k).isEmpty()); }
        assertEquals(12, (int) n.get(Lanes.Lane.TERRAIN)); assertEquals(9, (int) n.get(Lanes.Lane.HAZARDS));
        assertEquals(4, (int) n.get(Lanes.Lane.ENEMIES)); assertEquals(4, (int) n.get(Lanes.Lane.UTILITY));
        CourseGenerator.Kind[] kinds = CourseGenerator.Kind.values();
        assertEquals("CourseGenerator.Kind count changed: add it to Lanes.KINDS", 25, kinds.length);
        Set<String> listed = new HashSet<>(); for (String[] k : Lanes.KINDS) listed.add(k[0]);
        for (CourseGenerator.Kind k : kinds) assertTrue("unlisted module kind " + k, listed.contains(k.name()));
        for (Element.Type k : DifficultyScore.WEIGHTS.keySet()) assertNotEquals("weights only for hazards, enemies and the club", Lanes.Lane.TERRAIN, Lanes.lane(k));
    }

    @Test public void tracingChangesNoCourseAndNoSolverResult() throws Exception {
        Tuning base = tuning();
        for (Tuning t : new Tuning[]{base, base.hardCopy(1)}) {
            CourseGenerator.Framed a = null, b = null;
            for (int k = 0; k < 2; k++) {
                a = CourseGenerator.chunkFramed(1L, k, a, t);
                GenTrace.GEN.set(new GenTrace()); try { b = CourseGenerator.chunkFramed(1L, k, b, t); } finally { GenTrace.GEN.remove(); }
                assertEquals("generation trace must not change slice " + k, CourseIO.toJson(a.c), CourseIO.toJson(b.c));
            }
            Autopilot.Report r1 = Autopilot.run(a.c, t, 4000f), r2;
            GenTrace.SOLVE.set(new GenTrace.Solve()); try { r2 = Autopilot.run(a.c, t, 4000f); } finally { GenTrace.SOLVE.remove(); }
            assertEquals(r1.completed, r2.completed); assertEquals(r1.failedLink, r2.failedLink); assertEquals(r1.links, r2.links);
            assertEquals(r1.simTime, r2.simTime, 0f); assertEquals(r1.minMargin, r2.minMargin, 0f); assertEquals(r1.failInfo, r2.failInfo);
        }
    }

    @Test public void windowsLedgersAndCountsReconcileAndRepeat() throws Exception {
        Tuning t = tuning();
        LaneReport r = new LaneReport(t, 0, 1L, 130f); r.run(true, 1e9);
        LaneReport again = new LaneReport(t, 0, 1L, 130f); again.run(true, 1e9);
        // complete, contiguous 25 m windows up to the measured height
        int n = (int) Math.ceil(Math.min(r.top, r.reached) / LaneReport.WINDOW);
        assertEquals(n, r.windows.size());
        int i = 0; for (int w : r.windows.keySet()) assertEquals("no missing or duplicated window", i++, w);
        // every window total is exactly the sum of its ledger rows, lane by lane
        Map<Integer, double[]> sum = new HashMap<>();
        for (LaneReport.Row row : r.ledger) {
            double[] s = sum.computeIfAbsent(row.window, x -> new double[5]);
            assertEquals(row.weight * row.fraction * row.repetition, row.value, 1e-9);
            switch (row.lane) { case "TERRAIN": s[0] += row.value; break; case "HAZARDS": s[1] += row.value; break; case "ENEMIES": s[2] += row.value; break; case "INTERACTION": s[3] += row.value; break; case "MITIGATION": s[4] += row.value; break; default: break; }
        }
        for (LaneReport.Win w : r.windows.values()) {
            double[] s = sum.getOrDefault(w.index, new double[5]);
            assertEquals(s[0], w.adj[0], 1e-9); assertEquals(s[1], w.adj[1], 1e-9); assertEquals(s[2], w.adj[2], 1e-9); assertEquals(s[3], w.interaction, 1e-9); assertEquals(s[4], w.mitigation, 1e-9);
            assertTrue(w.interaction <= LaneReport.X_CAP + 1e-9);
            assertTrue("missing geometry is flagged, never a silent zero", w.geometry || w.flags.contains("NO_ROUTE_GEOMETRY"));
        }
        // a link crossing a window boundary is split, never duplicated
        for (LaneReport.Link L : r.links) { double f = 0; for (double x : L.fractions) f += x; assertEquals(1.0, f, 1e-9); }
        // every element has exactly one primary lane, and counts reconcile with the course
        int placed = 0; for (int[] v : r.inventory.values()) placed += v[0];
        assertEquals(r.elements.size() + r.hazards.size(), placed);
        for (LaneReport.El e : r.elements) assertEquals(Lanes.lane(e.e.type).name(), e.lane);
        // terrain only from terrain rules, hazards and enemies only from their own element types
        for (LaneReport.Row row : r.ledger) {
            if (row.lane.equals("TERRAIN")) assertTrue(row.rule.startsWith("T-"));
            if (row.lane.equals("HAZARDS")) assertEquals(Lanes.Lane.HAZARDS, Lanes.lane(Element.Type.valueOf(row.rule.substring(2))));
            if (row.lane.equals("ENEMIES")) assertEquals(Lanes.Lane.ENEMIES, Lanes.lane(Element.Type.valueOf(row.rule.substring(2))));
        }
        // deterministic
        assertEquals(r.windows.size(), again.windows.size()); assertEquals(r.ledger.size(), again.ledger.size());
        for (int w : r.windows.keySet()) for (int l = 0; l < 3; l++) assertEquals(r.windows.get(w).adj[l], again.windows.get(w).adj[l], 0);
        // modules own elements (the trace saw the generator) and a solver ran
        int owned = 0; for (LaneReport.El e : r.elements) if (!e.module.isEmpty()) owned++;
        assertTrue("module attribution observed", owned > r.elements.size() / 2);
        assertFalse(r.solverSlices.isEmpty());
    }

    @Test public void apportionsBoundaryCrossingLinksByHeight() {
        LaneReport.Link L = new LaneReport.Link(); L.y0 = 20; L.y1 = 30;
        new LaneReport(null == null ? dummy() : null, 0, 1, 50).apportion(L);
        assertArrayEquals(new int[]{0, 1}, L.windows); assertEquals(0.5, L.fractions[0], 1e-9); assertEquals(0.5, L.fractions[1], 1e-9);
        L.y0 = 10; L.y1 = 10; new LaneReport(dummy(), 0, 1, 50).apportion(L);
        assertArrayEquals(new int[]{0}, L.windows);
    }
    private static Tuning dummy() { try { return tuning(); } catch (Exception e) { throw new RuntimeException(e); } }
}

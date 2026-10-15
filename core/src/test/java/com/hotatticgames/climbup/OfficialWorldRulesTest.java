package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import com.hotatticgames.climbup.sim.*;
import java.util.*;
import org.junit.Test;

/**
 * Validation of the official 5,000 m world: ten fixed castles, everything between them random; one key per castle somewhere random in the section before it, always reachable;
 * one red gem per section at the midpoint on a reachable platform; intentional difficulty variation kept; no fixed side rooms, key spots, platform layouts or repeatable sequences.
 */
public class OfficialWorldRulesTest {
    private static final long[] SEEDS = {1, 2, 3, 4, 5, 6, 7, 8};
    private static final float SP = 500f;

    private static List<Element> all(List<Course> cs, Element.Type type) {
        List<Element> out = new ArrayList<>();
        for (Course c : cs) for (Element h : c.hazards) if (h.type == type) out.add(h);
        return out;
    }

    // ---- castles: exactly ten, fixed heights, same for every run
    @Test public void exactlyTenCastlesAtFixedHeightsInEveryRun() throws Exception {
        for (long seed : new long[]{7L, 8L}) {
            List<Element> gates = all(TestUtil.slices(seed, 5300f), Element.Type.GATE), official = new ArrayList<>();
            for (Element g : gates) if (g.skin <= 10) official.add(g);
            assertEquals("ten official castles (seed " + seed + ")", 10, official.size());
            official.sort(Comparator.comparingInt(g -> g.skin));
            for (int n = 1; n <= 10; n++) { assertEquals(n, official.get(n - 1).skin); assertEquals("castle " + n + " height", n * SP, official.get(n - 1).y, 1e-3f); }
        }
    }

    // ---- keys
    @Test public void everyKeyLiesRandomlyInTheSectionBeforeItsCastleAndMatchesItsGate() throws Exception {
        List<Float> heights = new ArrayList<>();
        for (long seed : SEEDS) {
            List<Course> cs = TestUtil.slices(seed, 1100f);
            Map<Integer, Integer> keysPerCastle = new HashMap<>();
            for (Element key : all(cs, Element.Type.KEY)) {
                int n = (int) Math.floor(key.y / SP) + 1;
                assertTrue("key inside the section before castle " + n + " (y=" + key.y + ")", key.y > SP * (n - 1) && key.y < SP * n);
                assertEquals("key colour matches castle " + n, CourseGenerator.castleColor(seed, n), key.color);
                assertTrue("a key never stands in the castle's own approach (y=" + key.y + ")", key.y <= SP * n - 30f);
                keysPerCastle.merge(n, 1, Integer::sum);
                if (n <= 2) heights.add(key.y - SP * (n - 1));
            }
            for (int n = 1; n <= 2; n++) assertEquals("one key for castle " + n + " (seed " + seed + ")", 1, (int) keysPerCastle.getOrDefault(n, 0));
        }
        float min = Collections.min(heights), max = Collections.max(heights);
        assertTrue("key heights vary widely inside their sections: " + heights, min < 120f && max > 330f);
    }

    @Test public void everyKeyIsReachableAndEveryCastleCompletableAfterCollectingIt() throws Exception {
        Tuning t = TestUtil.tuning(); int keys = 0, branches = 0;
        for (long seed : SEEDS) {
            for (Course c : TestUtil.slices(seed, 1100f)) {
                Autopilot.Report r = Autopilot.run(c, t, 4000f);       // fetches every key in the slice (detours included) and opens the slice's gate with the key carried from before
                assertTrue("slice solvable incl. key detours and gate (seed " + seed + ", link " + r.failedLink + ": " + r.failInfo + ")", r.completed);
                for (int[] kr : c.keyRooms) {
                    keys++;
                    if (kr[3] == 2) continue;                                   // the key lies on a route platform: reachable by walking
                    branches++;
                    assertTrue("out link exists", Autopilot.linkExists(c, t, kr[0], kr[1]));
                    assertTrue("a branch platform is never route", c.get(kr[1]).anchor >= 0);
                }
            }
        }
        assertTrue("keys were exercised (" + keys + ", " + branches + " on branches)", keys >= 12 && branches >= 6);
    }

    @Test public void keyPlacementIsVariedNoFixedSideRoomNoRoutineRestPlatformKey() throws Exception {
        Set<Integer> modes = new HashSet<>(); Set<String> shapes = new HashSet<>(); int total = 0, onRoute = 0, onRestOfCastle = 0;
        for (long seed : SEEDS) for (Course c : TestUtil.slices(seed, 1100f)) for (int[] kr : c.keyRooms) {
            total++; modes.add(kr[3]);
            Element key = c.hazards.get(kr[4]), A = c.get(kr[0]);
            if (kr[3] == 2) onRoute++;
            else { Element first = c.get(kr[1]), last = c.get(kr[1] + kr[2] - 1); shapes.add(String.format("%d:%d:%.1f:%.1f:%.1f", kr[3], kr[2], last.s - A.s, last.y - A.y, last.w)); }
            if (A.checkpoint && A.w >= 5f && key.y > SP * Math.floor(key.y / SP) + SP - 45f) onRestOfCastle++;
        }
        assertTrue("different kinds of key place appear (" + modes + ")", modes.contains(0) && modes.contains(3) && modes.contains(2));
        assertTrue("branch geometry differs from key to key (" + shapes.size() + " shapes)", shapes.size() >= Math.max(5, (total - onRoute) * 3 / 4));
        assertTrue("most keys are not simply handed over on the route (" + onRoute + " of " + total + ")", onRoute * 2 < total);
        assertEquals("no key on the castle's rest platform", 0, onRestOfCastle);
    }

    // ---- red gems
    @Test public void oneGemPlatformHalfwayBetweenEveryTwoCastlesNearTheExactMidpointAndOnTheRoute() throws Exception {
        float worst = 0; int count = 0;
        for (long seed : new long[]{7L, 8L}) {
            List<Float> gems = new ArrayList<>();
            for (Course c : TestUtil.slices(seed, 5300f)) for (int i = 1; i < c.routeSize(); i++) {
                Element e = c.get(i);
                if (e.type == Element.Type.STATIC && e.skin == 3) { assertTrue("a gem stands on a route platform you can stand on", e.anchor < 0 && e.w >= 3.5f && e.checkpoint); gems.add(e.y); }
            }
            assertTrue("a gem for each of the first ten sections: " + gems, gems.size() >= 10);
            for (int j = 0; j < 10; j++) { float dev = Math.abs(gems.get(j) - (250f + SP * j)); worst = Math.max(worst, dev); count++; assertTrue("gem " + (j + 1) + " within 4.5 m of the midpoint (dev " + dev + "; the nearest suitable platform, or a rest added where the climb crosses it, which can sit up to one module above)", dev <= 4.5f); }
            for (int j = 1; j < gems.size(); j++) assertTrue("only one per section", gems.get(j) - gems.get(j - 1) > 400f);
        }
        System.out.println("gems: " + count + " checked, worst deviation from the midpoint " + worst + " m");
    }

    // ---- variation and difficulty
    @Test public void everyRunIsDifferentAndDifficultyIsNotNormalised() throws Exception {
        Set<String> sigs = new HashSet<>(); List<Integer> load = new ArrayList<>();
        for (long seed : SEEDS) {
            StringBuilder sb = new StringBuilder(); int hazards = 0, movers = 0;
            for (Course c : TestUtil.slices(seed, 1100f)) {
                for (int i = 0; i < c.routeSize(); i++) { Element e = c.get(i); sb.append(e.type.ordinal()).append(String.format("%.1f/%.1f,", e.s, e.y)); if (e.isMoving()) movers++; }
                hazards += c.hazards.size();
            }
            sigs.add(sb.toString()); load.add(hazards + movers);
        }
        assertEquals("every run has its own layout", SEEDS.length, sigs.size());
        int lo = Collections.min(load), hi = Collections.max(load);
        assertTrue("runs differ in how hard they are (obstacle load " + load + ")", hi >= lo * 1.1);
    }

    @Test public void noRepeatedObstacleSequencesBetweenRunsOrInsideOne() throws Exception {
        Set<String> windows = new HashSet<>(); int total = 0;
        for (long seed : SEEDS) {
            List<Element> route = new ArrayList<>();
            for (Course c : TestUtil.slices(seed, 1100f)) for (int i = 1; i < c.routeSize(); i++) route.add(c.get(i));
            for (int i = 0; i + 6 < route.size(); i += 3) {               // six-platform windows, as shape (type + exact relative offsets)
                StringBuilder sb = new StringBuilder(); Element b = route.get(i);
                for (int j = 0; j < 6; j++) { Element e = route.get(i + j); sb.append(e.type.ordinal()).append(String.format(":%.1f:%.1f;", e.s - b.s, e.y - b.y)); }
                windows.add(sb.toString()); total++;
            }
        }
        assertTrue("six-platform sequences practically never repeat (" + (total - windows.size()) + " of " + total + " windows)", total - windows.size() <= total / 100);
    }
}

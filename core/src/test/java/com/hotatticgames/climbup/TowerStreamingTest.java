package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import com.hotatticgames.climbup.sim.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** The streaming tower: stored history, a window around the player, a floating origin. Unloading and reloading must never change what is there or how the player moves. */
public class TowerStreamingTest {
    private static Sim managed(Tower tw, Tuning t, int idx) {
        Sim s = Sim.startOn(tw.world, t, idx); s.keysFree = true; s.deferRespawn = true; s.floorOverride = tw.floorLocal(); s.setRange(0, tw.world.size() - 1);
        return s;
    }

    /** One game frame the way PlayScreen runs it: keep the tower in step, then up to six simulation steps. */
    private static void frame(Tower tw, Sim s, InputState in, int steps, int[] simSize) {
        tw.maintain(s);
        if (tw.world.size() != simSize[0]) { s.setRange(0, tw.world.size() - 1); simSize[0] = tw.world.size(); }
        for (int i = 0; i < steps; i++) { s.step(in); s.consumeEvents(); }
    }

    private static List<byte[]> history(Tower tw) { List<byte[]> h = new ArrayList<>(); for (int k = 0; k < tw.sliceCount(); k++) h.add(tw.blob(k)); return h; }

    @Test public void aTowerRebuiltFromItsStoredHistoryIsTheSameTowerAndGrowsTheSameWay() throws Exception {
        Tuning t = TestUtil.tuning();
        Tower tw = new Tower(21, t); for (int i = 0; i < 9; i++) tw.extend();
        Tower back = new Tower(21, t, history(tw), 4);
        assertEquals(tw.sliceCount(), back.sliceCount());
        for (int k = 0; k < tw.sliceCount(); k++) assertArrayEquals("slice " + k + " is stored identically", tw.blob(k), back.blob(k));
        for (int k : new int[]{2, 4, 8}) for (int local : new int[]{0, 1, 5}) {
            Tower.Ref ref = new Tower.Ref(k, local); int a = tw.worldIndex(ref), b = back.worldIndex(ref);
            if (a < 0 || b < 0) continue;
            assertEquals("same platform at slice " + k + " local " + local, tw.absY(tw.world.get(a).y), back.absY(back.world.get(b).y), 1e-3);
        }
        tw.extend(); back.extend();
        assertArrayEquals("the tower above a resumed one is identical (slice " + (tw.sliceCount() - 1) + ")", tw.blob(tw.sliceCount() - 1), back.blob(back.sliceCount() - 1));
        for (int w = 0; w < tw.world.size(); w++) { Tower.Ref r = tw.refOf(w); assertEquals("ref round trip", w, tw.worldIndex(r)); }
    }

    @Test public void rebuildingTheWindowWithADifferentOriginNeverChangesWhatThePlayerDoes() throws Exception {
        Tuning t = TestUtil.tuning();
        Tower a = new Tower(5, t), b = new Tower(5, t);
        for (int i = 0; i < 13; i++) { a.extend(); b.extend(); }
        Sim sa = managed(a, t, 0), sb = managed(b, t, 0);
        int[] za = {a.world.size()}, zb = {b.world.size()}; InputState in = new InputState(); int rebuilds = 0;
        double worst = 0;
        for (int f = 0; f < 60 * 40; f++) {
            in.clear(); in.moveX = (float) Math.sin(f / 37.0) * 1.2f; in.jumpPressed = f % 53 == 0; in.jumpHeld = f % 53 < 22; in.moveY = (f / 211) % 3 == 1 ? 1f : 0f;
            frame(a, sa, in, 1, za);
            if (f % 90 == 45) {          // the other tower is rebuilt around a different set of slices with a different origin, over and over
                int ps = b.sliceAt(b.absY(sb.y)), below = 1 + (f / 90) % 3, above = 1 + (f / 90) % 2;
                b.rebuildExplicit(sb, Math.max(0, ps - below), Math.min(b.sliceCount() - 1, ps + above), (f / 90 % 3) * 500.0); rebuilds++; zb[0] = b.world.size();
            }
            frame(b, sb, in, 1, zb);
            double dy = Math.abs(a.absY(sa.y) - b.absY(sb.y)), ds = Math.abs(a.world.dsWrap(sa.s, sb.s));
            worst = Math.max(worst, Math.max(dy, ds));
            assertTrue("frame " + f + ": same position after " + rebuilds + " rebuilds (dy " + dy + ", ds " + ds + ")", dy < 2e-3 && ds < 2e-3);
            assertEquals("same state at frame " + f, sa.mode, sb.mode);
            assertEquals("same falls", sa.falls, sb.falls);
        }
        System.out.println("rebuilds " + rebuilds + ", worst divergence " + worst + " m; abs height now " + a.absY(sa.y));
        assertTrue(rebuilds >= 20);
    }

    @Test public void fallingThroughManyUnloadedSlicesLandsExactlyWhereAWholeTowerWould() throws Exception {
        Tuning t = TestUtil.tuning();
        Tower tw = new Tower(8, t); for (int i = 0; i < 50; i++) tw.extend();
        Tower oracle = new Tower(8, t); for (int i = 0; i < 50; i++) oracle.extend();
        List<Tower.Ref> starts = new ArrayList<>();                      // every platform we will drop from, named by slice and local index while the whole tower is still resident
        for (int w = tw.world.size() - 1; w > 40 && starts.size() < 40; w -= 37) { Tower.Ref r = tw.refOf(w); if (r.slice >= 20 && tw.world.get(w).isPlatform() && w == tw.worldIndex(r)) starts.add(r); }
        oracle.rebuildExplicit(managed(oracle, t, 0), 0, oracle.sliceCount() - 1, 0.0);
        InputState in = new InputState();
        int drops = 0, deep = 0, respawns = 0; double deepest = 0;
        for (Tower.Ref start : starts) {
            // B: the whole tower resident
            Sim sb = managed(oracle, t, 0); sb.deferRespawn = true; oracle.setCheckpointRef(new Tower.Ref(0, 0)); sb.checkpoint = oracle.worldIndex(new Tower.Ref(0, 0));
            int ib = oracle.worldIndex(start);
            sb.mode = Sim.Mode.AIR; sb.onElem = -1; sb.s = oracle.world.get(ib).s; sb.y = oracle.world.get(ib).y + 0.3f; sb.vy = 0; sb.vx = 0; sb.setRange(0, oracle.world.size() - 1);
            // A: the streaming tower: the window is opened at the start, then driven the way the game does it
            Sim sa = managed(tw, t, 0); tw.windowAround(sa, oracle.absY(oracle.world.get(ib).y));
            int ia = tw.worldIndex(start); assertTrue(ia >= 0);
            tw.setCheckpointRef(new Tower.Ref(0, 0)); sa.checkpoint = -1;
            sa.mode = Sim.Mode.AIR; sa.onElem = -1; sa.s = tw.world.get(ia).s; sa.y = tw.world.get(ia).y + 0.3f; sa.vy = 0; sa.vx = 0;
            double startAbs = tw.absY(sa.y);
            int[] za = {tw.world.size()}, zb = {oracle.world.size()};
            drops++;
            for (int f = 0; f < 60 * 100; f++) {
                frame(tw, sa, in, 6, za); frame(oracle, sb, in, 6, zb);
                if (sa.mode == Sim.Mode.GROUND || sa.falls > 0 || sb.mode == Sim.Mode.GROUND || sb.falls > 0) break;
            }
            assertEquals("same outcome (landed or respawned) for a fall from slice " + start.slice, sb.falls, sa.falls);
            assertEquals(sb.mode, sa.mode);
            if (sa.falls == 0) {
                if (sa.mode == Sim.Mode.GROUND) {
                    Tower.Ref ra = tw.refOf(sa.onElem), rb = oracle.refOf(sb.onElem);
                    assertEquals("lands on the same platform (slice) from " + start.slice + "/" + start.local + " drop#" + drops, rb.slice, ra.slice); assertEquals("... and element", rb.local, ra.local);
                } else {                                                    // still bouncing on a spring after 100 s: the two worlds must agree on where the player is
                    assertEquals("same height, drop#" + drops, oracle.absY(sb.y), tw.absY(sa.y), 2e-3);
                    assertEquals("same arc position, drop#" + drops, sb.s, sa.s, 1e-3);
                }
                double fell = startAbs - tw.absY(sa.y); deepest = Math.max(deepest, fell); if (fell > 100) deep++;
            } else { respawns++; assertEquals("back on the checkpoint after the same fall", oracle.refOf(sb.onElem).slice, tw.refOf(sa.onElem).slice); }
        }
        System.out.println("drops " + drops + ", deeper than 100 m: " + deep + ", respawned: " + respawns + ", deepest landing " + deepest + " m");
        assertTrue("many drops were compared", drops >= 12);
    }

    @Test public void passingBelowTheLowestPlatformOfTheWholeTowerSendsThePlayerToTheCheckpointEvenWhenItsSliceIsNotLoaded() throws Exception {
        Tuning t = TestUtil.tuning();
        Tower tw = new Tower(3, t); for (int i = 0; i < 30; i++) tw.extend();
        Tower.Ref cp = new Tower.Ref(6, 4);
        Sim s = managed(tw, t, tw.world.size() - 1); tw.windowAround(s, tw.topAbsY(28));          // the window is far above the checkpoint
        assertTrue("the checkpoint slice is not resident", tw.worldIndex(cp) < 0);
        tw.setCheckpointRef(cp);
        InputState in = new InputState(); int[] z = {tw.world.size()};
        s.mode = Sim.Mode.AIR; s.onElem = -1; s.y = tw.floorLocal() - 20f; s.vy = -10f;                 // already below everything there is
        for (int f = 0; f < 5 && s.falls == 0; f++) frame(tw, s, in, 6, z);
        assertEquals("sent back", 1, s.falls);
        assertEquals("standing on the checkpoint", Sim.Mode.GROUND, s.mode);
        assertEquals(cp.slice, tw.refOf(s.onElem).slice); assertEquals(cp.local, tw.refOf(s.onElem).local);
        assertTrue("its slice is now resident", tw.worldIndex(cp) >= 0);
    }

    @Test public void theResidentWorldAndLocalCoordinatesStayBoundedAsTheClimbGoesUp() throws Exception {
        Tuning t = TestUtil.tuning();
        Tower tw = new Tower(77, t); Sim s = managed(tw, t, 0); InputState in = new InputState(); int[] z = {tw.world.size()};
        int maxRes = 0, maxWorld = 0; float maxLocalY = 0;
        for (int k = 1; k <= 110; k++) {
            tw.extend();
            int idx = tw.lastRouteIndex() - 2;                   // the player is standing near the top of the tower
            s.mode = Sim.Mode.GROUND; s.onElem = idx; s.y = tw.world.get(idx).y; s.s = tw.world.wrap(tw.world.get(idx).s); s.vx = s.vy = 0; s.checkpoint = idx;
            frame(tw, s, in, 1, z);
            maxRes = Math.max(maxRes, tw.res.size()); maxWorld = Math.max(maxWorld, tw.world.size());
            for (int i = 0; i < tw.world.size(); i++) maxLocalY = Math.max(maxLocalY, Math.abs(tw.world.get(i).y));
            maxLocalY = Math.max(maxLocalY, Math.abs(s.y));
        }
        System.out.printf("after %d slices (%.0f m): resident slices <= %d, world elements <= %d, largest local height %.0f m, history %d bytes (%d per slice)%n", tw.sliceCount(), tw.topAbsY(), maxRes, maxWorld, maxLocalY, tw.bytesStored(), tw.bytesStored() / tw.sliceCount());
        assertTrue("only the slices around the player are expanded (" + maxRes + ")", maxRes <= 16);
        assertTrue("so the world stays small (" + maxWorld + ")", maxWorld <= 1200);
        assertTrue("and local heights stay small however high the climb is (" + maxLocalY + ")", maxLocalY < 1600f);
        assertTrue("history costs about a kilobyte per slice", tw.bytesStored() / tw.sliceCount() < 2048);
        assertTrue(tw.rebuilds >= 8);
    }

    @Test public void keyAndGateStateSurvivesUnloadingAndReloadingASlice() throws Exception {
        Tuning t = TestUtil.tuning();
        Tower tw = new Tower(3, t); while (tw.topAbsY() < 620) tw.extend();
        Sim s = managed(tw, t, 0);
        int gate = -1, key = -1; for (int i = 0; i < tw.world.hazards.size(); i++) { Element h = tw.world.hazards.get(i); if (h.type == Element.Type.GATE && gate < 0) gate = i; if (h.type == Element.Type.KEY && key < 0) key = i; }
        assertTrue(gate >= 0 && key >= 0);
        double gateAbs = tw.absY(tw.world.hazards.get(gate).y), keyAbs = tw.absY(tw.world.hazards.get(key).y);
        s.featDone[key] = true; tw.openedUpTo = 1;                   // the key was taken, castle 1 opened
        tw.windowAround(s, 5.0); tw.windowAround(s, 5.0 + 60.0);       // far away: both are unloaded
        s.y = 0;
        tw.windowAround(s, gateAbs); tw.windowAround(s, keyAbs);       // and back
        int g2 = -1, k2 = -1;
        for (int i = 0; i < tw.world.hazards.size(); i++) { Element h = tw.world.hazards.get(i);
            if (h.type == Element.Type.GATE && Math.abs(tw.absY(h.y) - gateAbs) < 0.01) g2 = i; if (h.type == Element.Type.KEY && Math.abs(tw.absY(h.y) - keyAbs) < 0.01) k2 = i; }
        if (g2 >= 0) assertTrue("castle 1 is still open", s.featDone[g2]);
        if (k2 >= 0) assertTrue("the taken key is still taken", s.featDone[k2]);
        assertTrue("both were resident again", g2 >= 0 || k2 >= 0);
    }
}

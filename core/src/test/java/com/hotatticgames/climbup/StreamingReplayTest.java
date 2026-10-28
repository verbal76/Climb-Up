package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import com.hotatticgames.climbup.sim.*;
import java.util.ArrayList;
import org.junit.Test;

/**
 * Build 72 investigation (owner: separate controller divergence from physics divergence). One autopilot climb's inputs are recorded on a tower, then replayed
 * with no controller at all: (1) into a fresh copy of the same tower, which must match it bit for bit; (2) into a streaming tower that is rebuilt around a
 * new floating origin every few hundred steps. Any difference here is physics, never the controller.
 * <p>
 * What it found (docs/DECISIONS.md, Build 72 root cause): the same frame replays exactly; a different frame re-rounds the float state (heights and arcs are
 * stored relative to the origin, so every sum rounds at a different magnitude). The first difference is one ulp, right after the first origin shift, and it
 * stays around a millimetre; once in a long climb a landing then falls one step apart. The climb itself (every surface stood on, in order) is the same.
 */
public class StreamingReplayTest {
    static final int EPISODE_GAP = 600;      // differing steps closer than this belong to one episode (one move and its recovery)
    static final int STEPS = Integer.parseInt(System.getenv().getOrDefault("REPLAY_STEPS", "26000")), REBUILD_EVERY = 400;

    static final class Rec { float[] mx, my; boolean[] jp, jh, sw; double[] y, arc, maxAbs; Sim.Mode[] mode; long[] on; int n; Outcome outcome = new Outcome(); }

    /** What the climb achieved, compared exactly (owner decision 2026-10-28): checkpoints in the order reached, keys, hazard hits, falls, completion. */
    static final class Outcome {
        final ArrayList<Long> checkpoints = new ArrayList<>(); int keys, hits, falls; boolean won;
        void track(Tower tw, Sim s) { long cp = s.checkpoint >= 0 ? tw.elemKeyAt(s.checkpoint) : -1L; if (cp >= 0 && (checkpoints.isEmpty() || checkpoints.get(checkpoints.size() - 1) != cp)) checkpoints.add(cp); keys = s.keys; hits = s.hits; falls = s.falls; won = s.won; }
        @Override public String toString() { return "checkpoints " + checkpoints + " keys " + keys + " hits " + hits + " falls " + falls + " won " + won; }
    }

    private static Sim managed(Tower tw, Tuning t) {
        Sim s = Sim.startOn(tw.world, t, 0); s.keysFree = true; s.deferRespawn = true; s.floorOverride = tw.floorLocal(); s.setRange(0, tw.world.size() - 1);
        return s;
    }

    /** Keeps a tower in step the way the game does (and the way the recording did): grow ahead of the highest point, maintain the window. */
    private static void keep(Tower tw, Sim s, double maxAbs, int[] size) {
        while (tw.topAbsY() < maxAbs + 150) tw.extend();
        tw.maintain(s);
        if (tw.world.size() != size[0]) { s.setRange(0, tw.world.size() - 1); size[0] = tw.world.size(); }
    }

    private static Rec record(Tuning t) throws Exception {
        Tower b = new Tower(12, t); Sim s = managed(b, t); Autopilot.Driver d = new Autopilot.Driver(s);
        Rec r = new Rec(); r.mx = new float[STEPS]; r.my = new float[STEPS]; r.jp = new boolean[STEPS]; r.jh = new boolean[STEPS]; r.sw = new boolean[STEPS];
        r.y = new double[STEPS]; r.arc = new double[STEPS]; r.maxAbs = new double[STEPS]; r.mode = new Sim.Mode[STEPS]; r.on = new long[STEPS];
        InputState in = new InputState(); int[] size = {b.world.size()}; double maxAbs = 0; int seen = b.rebuilds;
        for (int step = 0; step < STEPS && !d.failed; step++) {
            r.maxAbs[step] = maxAbs;
            if (step % 6 == 0) { keep(b, s, maxAbs, size); if (b.rebuilds != seen) { d.rebase(b.lastRemap); seen = b.rebuilds; } }
            d.drive(s, in);
            r.mx[step] = in.moveX; r.my[step] = in.moveY; r.jp[step] = in.jumpPressed; r.jh[step] = in.jumpHeld; r.sw[step] = in.swingPressed;
            s.step(in); s.consumeEvents();
            r.y[step] = b.absY(s.y); r.arc[step] = b.originS + s.s; r.mode[step] = s.mode; r.on[step] = s.onElem >= 0 ? b.elemKeyAt(s.onElem) : -1L;
            maxAbs = Math.max(maxAbs, r.y[step]); r.n = step + 1; r.outcome.track(b, s);
        }
        return r;
    }

    /** What a replay did against the recording. */
    static final class Out { int firstBit = -1, firstEvent = -1, events, episodes, lastEvent = -1; double worst, worstBeforeEvent, finalGap; ArrayList<Long> surfaces = new ArrayList<>(); long[] on; ArrayList<int[]> episodeSteps = new ArrayList<>(); Outcome outcome = new Outcome(); }

    private static void addSurface(ArrayList<Long> l, long on) { if (on >= 0 && (l.isEmpty() || l.get(l.size() - 1) != on)) l.add(on); }

    /** The surfaces stood on, in order, each with the first and last step of its contact (the same entries {@link #addSurface} makes). */
    static ArrayList<long[]> contacts(long[] on, int n) {
        ArrayList<long[]> l = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (on[i] < 0) continue;
            if (l.isEmpty() || l.get(l.size() - 1)[0] != on[i]) l.add(new long[]{on[i], i, i}); else l.get(l.size() - 1)[2] = i;
        }
        return l;
    }
    /** The surfaces stood on outside the divergence episodes: a contact that begins and ends inside one episode's differing steps is left out. */
    static ArrayList<Long> outsideEpisodes(ArrayList<long[]> contacts, ArrayList<int[]> episodes) {
        ArrayList<Long> out = new ArrayList<>();
        next:
        for (long[] c : contacts) {
            for (int[] e : episodes) if (c[1] >= e[0] && c[2] <= e[1]) continue next;
            if (out.isEmpty() || out.get(out.size() - 1) != c[0]) out.add(c[0]);
        }
        return out;
    }

    /** The surfaces whose contact begins and ends inside episode {@code e}, in order. */
    static ArrayList<Long> inside(ArrayList<long[]> contacts, int[] e) {
        ArrayList<Long> l = new ArrayList<>(); for (long[] c : contacts) if (c[1] >= e[0] && c[2] <= e[1]) l.add(c[0]); return l;
    }
    /** True if the two sequences are equal or one is the other with exactly one contact added (the touch only one frame makes). */
    static boolean oneContactApart(ArrayList<Long> a, ArrayList<Long> b) {
        if (a.equals(b)) return true;
        ArrayList<Long> lo = a.size() < b.size() ? a : b, hi = lo == a ? b : a;
        if (hi.size() != lo.size() + 1) return false;
        for (int i = 0; i < hi.size(); i++) { ArrayList<Long> c = new ArrayList<>(hi); c.remove(i); if (c.equals(lo)) return true; }
        return false;
    }

    /** Replays the recorded inputs (no controller) all the way through. */
    private static Out replay(Tuning t, Rec r, boolean streaming) throws Exception {
        Tower tw = new Tower(12, t); Sim s = managed(tw, t); s.humanHold = Autopilot.HUMAN_HOLD;       // the same stick handling the recording ran under
        InputState in = new InputState(); int[] size = {tw.world.size()}; int rebuilds = 0; Out o = new Out();
        double C = tw.world.circumference; o.on = new long[r.n];
        for (int step = 0; step < r.n; step++) {
            if (step % 6 == 0) keep(tw, s, r.maxAbs[step], size);
            if (streaming && step > 0 && step % REBUILD_EVERY == 0) {           // a floating-origin shift around the hero, at a different origin each time
                double ya = tw.absY(s.y); int ps = tw.sliceAt(ya), below = 1 + rebuilds % 2, above = 1 + rebuilds % 3;
                tw.rebuildExplicit(s, Math.max(0, ps - below), Math.min(tw.sliceCount() - 1, ps + above), (rebuilds % 4) * 500.0 + 37.25);
                size[0] = tw.world.size(); s.setRange(0, tw.world.size() - 1); rebuilds++;
            }
            in.clear(); in.moveX = r.mx[step]; in.moveY = r.my[step]; in.jumpPressed = r.jp[step]; in.jumpHeld = r.jh[step]; in.swingPressed = r.sw[step];
            s.step(in); s.consumeEvents();
            double y = tw.absY(s.y), arc = tw.originS + s.s, gap = Math.max(Math.abs(y - r.y[step]), Math.abs(Math.IEEEremainder(arc - r.arc[step], C)));
            long on = s.onElem >= 0 ? tw.elemKeyAt(s.onElem) : -1L;
            if (o.firstBit < 0 && gap > 0) o.firstBit = step;
            if (s.mode != r.mode[step] || on != r.on[step]) { o.events++; if (o.firstEvent < 0) o.firstEvent = step; if (o.lastEvent < 0 || step - o.lastEvent > EPISODE_GAP) { o.episodes++; o.episodeSteps.add(new int[]{step, step}); } o.lastEvent = step; o.episodeSteps.get(o.episodeSteps.size() - 1)[1] = step; }
            if (o.firstEvent < 0) o.worstBeforeEvent = Math.max(o.worstBeforeEvent, gap);
            o.worst = Math.max(o.worst, gap); o.finalGap = gap;
            addSurface(o.surfaces, on); o.on[step] = on; o.outcome.track(tw, s);
        }
        return o;
    }

    @Test public void recordedInputsReplayIdenticallyOnTheSameTowerAndOnAStreamingTowerWithOriginShifts() throws Exception {
        Tuning t = TestUtil.tuning();
        Rec r = record(t);
        assertTrue("the recorded climb is long enough to mean something (" + r.n + " steps, " + (int) r.maxAbs[r.n - 1] + " m)", r.n > 6000 && r.maxAbs[r.n - 1] > 100);
        ArrayList<Long> recorded = new ArrayList<>(); for (int i = 0; i < r.n; i++) addSurface(recorded, r.on[i]);

        Out same = replay(t, r, false);
        System.out.printf("REPLAY same tower: first bit difference at step %d, %d event differences, largest gap %.3e m%n", same.firstBit, same.events, same.worst);
        assertEquals("replaying the inputs in the same frame reproduces the climb bit for bit", -1, same.firstBit);
        assertEquals(0, same.events);

        Out str = replay(t, r, true);
        System.out.printf("REPLAY streaming tower: first bit difference at step %d, gap before the first event difference %.3e m, first event difference at step %d (%d steps differ), "
            + "in %d episode(s), largest gap %.3e m, final gap %.3e m, %d surfaces stood on (recording %d) over %d steps (%d m)%n",
            str.firstBit, str.worstBeforeEvent, str.firstEvent, str.events, str.episodes, str.worst, str.finalGap, str.surfaces.size(), recorded.size(), r.n, (int) r.maxAbs[r.n - 1]);
        // a different frame rounds differently: that is the only difference, it stays at the millimetre level, and the climb is the same climb
        assertTrue("re-expressing the state around a new origin is the only source of difference (first at " + str.firstBit + ")", str.firstBit < 0 || str.firstBit >= REBUILD_EVERY);
        assertTrue("rounding drift stays below 2 mm until a step-timing difference (" + str.worstBeforeEvent + ")", str.worstBeforeEvent < 2e-3);
        // Build 76: counted in episodes, not steps. One step-timing flip at a platform edge (the frames differ by micrometres exactly where walking off decides it)
        // makes every step of the move that follows differ until the climbs meet again: seed 12's rules-4 tower has one such flip at step 21804 (7.6e-6 m apart),
        // a ledge pull-up in one frame and a direct landing in the other, 29 differing steps over ~340, then the same surfaces again and a 1e-4 m final gap.
        // A step count measures how long that one move lasts, not how often the frames disagree; the episode count does, and the surfaces and the end must still match.
        assertTrue("event timing differs only rarely (" + str.episodes + " episodes, " + str.events + " steps)", str.episodes <= 2);
        // Owner decision 2026-10-28 (option B, docs/verification/OPEN_OWNER_DECISIONS.md): a 32-bit landing boundary can fall on either side in the two
        // frames (rules 15/16 seed 12: step 21389, the whole-world climber 1.22e-4 m above deck top 301.9033813, outside the 0.1 mm landing tolerance; the
        // streamed one inside it: a 2-step touch only one frame makes). Such a contact may differ ONLY when it begins and ends inside one of the counted
        // episodes (at most two, above); every surface outside the episodes is the same and in the same order, the climbs end within 0.1 m, and what the
        // climb achieves - checkpoints in order, keys, hits, falls, completion - is exactly the same.
        ArrayList<int[]> eps = str.episodeSteps;
        ArrayList<Long> recOut = outsideEpisodes(contacts(r.on, r.n), eps), strOut = outsideEpisodes(contacts(str.on, r.n), eps);
        if (!recorded.equals(str.surfaces)) {
            StringBuilder w = new StringBuilder(); for (int[] e : eps) w.append(" [").append(e[0]).append("..").append(e[1]).append("]");
            System.out.printf("REPLAY surface lists differ inside the divergence episode(s)%s: recording %d surfaces, streaming %d; outside the episodes %d and %d%n", w, recorded.size(), str.surfaces.size(), recOut.size(), strOut.size());
        }
        assertEquals("outside the divergence episodes the same surfaces are stood on, in the same order", recOut, strOut);
        ArrayList<long[]> recC = contacts(r.on, r.n), strC = contacts(str.on, r.n);
        for (int[] e : eps) {
            ArrayList<Long> a = inside(recC, e), b = inside(strC, e);
            assertTrue("inside episode " + e[0] + ".." + e[1] + " the frames differ by one contact at most (recording " + a + ", streaming " + b + ")", oneContactApart(a, b));
        }
        assertEquals("the climb achieves exactly the same: checkpoints, keys, hits, falls, completion", r.outcome.toString(), str.outcome.toString());
        assertTrue("the climb ends in the same place (" + str.finalGap + " m)", str.finalGap < 0.1);
    }
}

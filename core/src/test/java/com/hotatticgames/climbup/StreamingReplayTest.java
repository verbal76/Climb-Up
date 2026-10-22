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
    static final int STEPS = Integer.parseInt(System.getenv().getOrDefault("REPLAY_STEPS", "26000")), REBUILD_EVERY = 400;

    static final class Rec { float[] mx, my; boolean[] jp, jh, sw; double[] y, arc, maxAbs; Sim.Mode[] mode; long[] on; int n; }

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
            maxAbs = Math.max(maxAbs, r.y[step]); r.n = step + 1;
        }
        return r;
    }

    /** What a replay did against the recording. */
    static final class Out { int firstBit = -1, firstEvent = -1, events; double worst, worstBeforeEvent, finalGap; ArrayList<Long> surfaces = new ArrayList<>(); }

    private static void addSurface(ArrayList<Long> l, long on) { if (on >= 0 && (l.isEmpty() || l.get(l.size() - 1) != on)) l.add(on); }

    /** Replays the recorded inputs (no controller) all the way through. */
    private static Out replay(Tuning t, Rec r, boolean streaming) throws Exception {
        Tower tw = new Tower(12, t); Sim s = managed(tw, t); s.humanHold = Autopilot.HUMAN_HOLD;       // the same stick handling the recording ran under
        InputState in = new InputState(); int[] size = {tw.world.size()}; int rebuilds = 0; Out o = new Out();
        double C = tw.world.circumference;
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
            if (s.mode != r.mode[step] || on != r.on[step]) { o.events++; if (o.firstEvent < 0) o.firstEvent = step; }
            if (o.firstEvent < 0) o.worstBeforeEvent = Math.max(o.worstBeforeEvent, gap);
            o.worst = Math.max(o.worst, gap); o.finalGap = gap;
            addSurface(o.surfaces, on);
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
            + "largest gap %.3e m, final gap %.3e m, %d surfaces stood on (recording %d) over %d steps (%d m)%n",
            str.firstBit, str.worstBeforeEvent, str.firstEvent, str.events, str.worst, str.finalGap, str.surfaces.size(), recorded.size(), r.n, (int) r.maxAbs[r.n - 1]);
        // a different frame rounds differently: that is the only difference, it stays at the millimetre level, and the climb is the same climb
        assertTrue("re-expressing the state around a new origin is the only source of difference (first at " + str.firstBit + ")", str.firstBit < 0 || str.firstBit >= REBUILD_EVERY);
        assertTrue("rounding drift stays below 2 mm until a step-timing difference (" + str.worstBeforeEvent + ")", str.worstBeforeEvent < 2e-3);
        assertTrue("event timing differs only rarely (" + str.events + " steps)", str.events <= 20);
        assertEquals("the same surfaces are stood on, in the same order", recorded, str.surfaces);
        assertTrue("the climb ends in the same place (" + str.finalGap + " m)", str.finalGap < 0.1);
    }
}

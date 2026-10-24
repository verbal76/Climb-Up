package com.hotatticgames.climbup;

import com.hotatticgames.climbup.sim.*;
import java.util.ArrayList;

/**
 * Build 74 investigation (owner: the same inputs on the same ticks must give the same run on every device). Plays the game's own frame loop headlessly
 * (PlayScreen: once per rendered frame the tower is maintained, then 0-6 fixed simulation steps run, and the run clock counts every step), records one
 * autopilot climb at one step per frame, then replays exactly the same per-tick inputs at other frame pacings (a 30 fps device, a stuttering one) and
 * reports where the run first differs and the clock at every castle door. Run: REPLAY_STEPS=40000 ./gradlew :core:test --tests '*PacingProbe*' -i
 */
public class PacingProbe {
    static final int STEPS = Integer.parseInt(System.getenv().getOrDefault("REPLAY_STEPS", "40000"));
    static final boolean FALLS = System.getenv("PACING_FALLS") != null;
    static final long SEED = Long.parseLong(System.getenv().getOrDefault("REPLAY_SEED", "7"));

    static final class Run {
        final Tower tw; final Sim s; double maxAbs; int size; long clock; int frozen; boolean live;
        final ArrayList<String> doors = new ArrayList<>(); final ArrayList<Long> doorClock = new ArrayList<>();
        Run(Tuning t) {
            tw = new Tower(SEED, t); while (tw.topAbsY() < 150) tw.extend();
            s = Sim.startOn(tw.world, t, 0); s.keysFree = false; s.deferRespawn = true; s.floorOverride = tw.floorLocal(); s.setRange(0, tw.world.size() - 1); size = tw.world.size();
        }
        /** PlayScreen's per-frame part: keep the tower ahead (synchronously here) and maintain the window. */
        void frame() {
            while (tw.topAbsY() < maxAbs + 150) tw.extend();
            tw.maintain(s);
            if (tw.world.size() != size) { s.setRange(0, tw.world.size() - 1); size = tw.world.size(); }
        }
        /** PlayScreen's per-step part: step, count the run clock (from the first input), note castle doors. */
        int step(InputState in, int k) {
            if (s.respawnPending) frozen++;
            s.step(in);
            if (FALLS && k % 4000 == 3999) s.respawn();          // a fall back to the checkpoint (what the sim itself does after a missed jump), at the same step in every run
            if (!live && (in.moveX != 0f || in.jumpPressed || in.moveY != 0f)) live = true;
            if (live) clock++;
            int ev = s.consumeEvents();
            if ((ev & Sim.EV_DOOR) != 0) { doors.add("door@clock " + clock); doorClock.add(clock); }
            maxAbs = Math.max(maxAbs, tw.absY(s.y));
            return ev;
        }
    }

    public static void main(String[] a) throws Exception { new PacingProbe().probe(); }

    @org.junit.Test public void probe() throws Exception {
        if (System.getenv("PACING_PROBE") == null) return;          // an investigation tool, not part of the regression suite
        Tuning t = TestUtil.tuning();
        // record at 60 fps (one step per frame), with the autopilot
        Run r = new Run(t); Autopilot.Driver d = new Autopilot.Driver(r.s);
        float[] mx = new float[STEPS], my = new float[STEPS]; boolean[] jp = new boolean[STEPS], jh = new boolean[STEPS], sw = new boolean[STEPS];
        double[] ry = new double[STEPS], rs = new double[STEPS]; int seen = r.tw.rebuilds; InputState in = new InputState(); int n = 0;
        for (int k = 0; k < STEPS && !d.failed; k++) {
            r.frame(); if (r.tw.rebuilds != seen) { d.rebase(r.tw.lastRemap); seen = r.tw.rebuilds; }
            in.clear(); d.drive(r.s, in);
            mx[k] = in.moveX; my[k] = in.moveY; jp[k] = in.jumpPressed; jh[k] = in.jumpHeld; sw[k] = in.swingPressed;
            r.step(in, k); ry[k] = r.tw.absY(r.s.y); rs[k] = r.tw.originS + r.s.s; n = k + 1;
        }
        System.out.printf("PACING recorded seed %d: %d steps, %.0f m, %d rebuilds, %d frozen steps, doors %s%n", SEED, n, r.maxAbs, r.tw.rebuilds, r.frozen, r.doors);
        int[][] pacings = {{1}, {2}, {1, 2, 1, 3, 1, 1, 6}};
        String[] names = {"60 fps (1 step/frame)", "30 fps (2 steps/frame)", "stutter (1,2,1,3,1,1,6)"};
        for (int p = 0; p < pacings.length; p++) {
            Run q = new Run(t); q.s.humanHold = Autopilot.HUMAN_HOLD; int k = 0, f = 0, firstDiff = -1; double worst = 0, C = q.tw.world.circumference;
            while (k < n) {
                q.frame();
                for (int st = 0; st < pacings[p][f % pacings[p].length] && k < n; st++, k++) {
                    in.clear(); in.moveX = mx[k]; in.moveY = my[k]; in.jumpPressed = jp[k]; in.jumpHeld = jh[k]; in.swingPressed = sw[k];
                    q.step(in, k);
                    double gap = Math.max(Math.abs(q.tw.absY(q.s.y) - ry[k]), Math.abs(Math.IEEEremainder(q.tw.originS + q.s.s - rs[k], C)));
                    if (firstDiff < 0 && gap > 0) firstDiff = k;
                    worst = Math.max(worst, gap);
                }
                f++;
            }
            System.out.printf("PACING %s: first difference at step %d, largest gap %.3e m, %d rebuilds, %d frozen steps, %.0f m, clock %d (recording %d), doors %s%n", names[p], firstDiff, worst, q.tw.rebuilds, q.frozen, q.maxAbs, q.clock, r.clock, q.doors);
        }
    }
}

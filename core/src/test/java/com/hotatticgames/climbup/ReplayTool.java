package com.hotatticgames.climbup;

import com.hotatticgames.climbup.sim.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Build 74 (owner: same seed, same moves on the same steps = same finish step and the same recorded time on Windows, Linux and Android). Plays a climb the
 * way PlayScreen does since Build 74 (before every simulation step the tower is kept ahead and its window maintained, then one step, then the run clock
 * counts it from the first input) from a file of per-step inputs, and prints what a speedrun is judged on: the finish step, the run clock in steps and as
 * the time the game shows, a hash of every gameplay event with its step, and state fingerprints along the way. The same file is replayed on the desktop JVM
 * (Linux, Windows) and, dexed, on Android's runtime by .github/workflows/determinism.yml; the outputs must be identical.
 * <p>
 * record &lt;tuning.json&gt; &lt;seed&gt; &lt;max steps&gt; &lt;out.bin.gz&gt; [falls]  - the autopilot plays and its inputs are written (one byte a step)<br>
 * replay &lt;tuning.json&gt; &lt;seed&gt; &lt;in.bin.gz&gt; [falls] [lookahead m]          - the inputs are played back with no controller
 * <p>
 * Where the autopilot (a stand-in for a player, with known gaps) makes no progress for {@link #STUCK} steps, the recording carries a SKIP step that moves
 * the checkpoint three platforms on and respawns there (as the developer probe in PlayScreen does), replayed at the same step; the climb still runs on the
 * real simulation from start to the castle-10 finish. It is a harness action, the same on every platform, and it is counted in the report.
 * <p>
 * "falls" sends the hero back to the checkpoint every 20,000 steps (what the sim does after a missed jump), so respawns are exercised at fixed steps.
 */
public final class ReplayTool {
    static final int FALL_EVERY = 20000, FINGERPRINT_EVERY = 10000, STUCK = 4000; static final byte SKIP = (byte) 0x80;

    static final class Climb {
        final Tower tw; final Sim s; final float lookahead; final boolean falls;
        double maxAbs; int size; long clock, finishStep = -1, finishClock = -1; boolean live; int doors, respawns, rebuildsSeen, skips;
        final MessageDigest events, states; final StringBuilder doorLog = new StringBuilder();
        Climb(Tuning t, long seed, boolean falls, float lookahead) throws Exception {
            this.falls = falls; this.lookahead = lookahead;
            tw = new Tower(seed, t); tw.admitAbs = PlayScreen.ADMIT_AHEAD; while (tw.topAbsY() < lookahead) tw.extend();
            s = Sim.startOn(tw.world, t, 0); s.deferRespawn = true; s.keysFree = true; s.floorOverride = tw.floorLocal(); s.setRange(0, tw.world.size() - 1); size = tw.world.size();
            events = MessageDigest.getInstance("SHA-256"); states = MessageDigest.getInstance("SHA-256");
        }
        /** PlayScreen.keepTower: before every step. The tower is grown synchronously here (the game grows it ahead on a background thread). */
        void keep() {
            tw.admitAbs = maxAbs + PlayScreen.ADMIT_AHEAD;                                   // as PlayScreen.keepTower
            while (tw.topAbsY() < maxAbs + lookahead) { tw.requestNext(); while (tw.generating()) Thread.onSpinWait(); tw.poll(); }   // generation ahead (here: synchronously, as far as asked)
            tw.maintain(s);
            if (tw.world.size() != size) { s.setRange(0, tw.world.size() - 1); size = tw.world.size(); }
        }
        void step(long k, InputState in) {
            boolean moved = in.moveX != 0f || in.jumpPressed || in.moveY != 0f;
            s.step(in);
            if (!live && moved) live = true;
            if (live && finishStep < 0) clock++;
            int ev = s.consumeEvents();
            if (ev != 0) { putLong(events, k); putLong(events, ev); }
            if ((ev & Sim.EV_RESPAWN) != 0) respawns++;
            if ((ev & Sim.EV_DOOR) != 0) { doors++; doorLog.append(' ').append(k); }
            if ((ev & Sim.EV_FINISH) != 0 && finishStep < 0) { finishStep = k; finishClock = clock; }
            if (falls && k % FALL_EVERY == FALL_EVERY - 1) s.respawn();
            maxAbs = Math.max(maxAbs, tw.absY(s.y));
            if (k % FINGERPRINT_EVERY == FINGERPRINT_EVERY - 1) fingerprint();
        }
        /** The SKIP harness action: checkpoint three platforms on, back to it. */
        void skip() {
            Course w = tw.world; int idx = Math.min(w.routeSize() - 1, Math.max(s.bestElem, s.onElem) + 3);
            while (idx < w.routeSize() - 1 && !w.get(idx).isPlatform()) idx++;
            s.checkpoint = idx; s.respawn(); skips++;
        }
        void fingerprint() {
            putLong(states, Double.doubleToLongBits(tw.absY(s.y))); putLong(states, Double.doubleToLongBits(tw.originS + s.s));
            putLong(states, Float.floatToIntBits(s.vx)); putLong(states, Float.floatToIntBits(s.vy)); putLong(states, s.mode.ordinal());
            putLong(states, s.onElem >= 0 ? tw.elemKeyAt(s.onElem) : -1L); putLong(states, Float.floatToIntBits(s.time));
        }
    }

    static void putLong(MessageDigest md, long v) { for (int i = 0; i < 8; i++) md.update((byte) (v >>> (8 * i))); }
    static String hex(byte[] d) { StringBuilder sb = new StringBuilder(); for (int i = 0; i < 12; i++) sb.append(String.format("%02x", d[i])); return sb.toString(); }

    static int code(float v) { return v > 0f ? 1 : v < 0f ? 2 : 0; }
    static float dir(int c) { return c == 1 ? 1f : c == 2 ? -1f : 0f; }
    static byte pack(InputState in) { return (byte) (code(in.moveX) | code(in.moveY) << 2 | (in.jumpPressed ? 16 : 0) | (in.jumpHeld ? 32 : 0) | (in.swingPressed ? 64 : 0)); }
    static void unpack(byte b, InputState in) { in.clear(); in.moveX = dir(b & 3); in.moveY = dir((b >> 2) & 3); in.jumpPressed = (b & 16) != 0; in.jumpHeld = (b & 32) != 0; in.swingPressed = (b & 64) != 0; }

    public static void main(String[] a) throws Exception {
        Tuning t = Tuning.parse(new String(Files.readAllBytes(Paths.get(a[1])), "UTF-8"));
        long seed = Long.parseLong(a[2]);
        if (a[0].equals("diff")) {             // two replays side by side with different lookaheads: the first step where they differ
            byte[] inputs; try (InputStream i = new GZIPInputStream(new FileInputStream(a[3]))) { inputs = readAll(i); }
            Climb x = new Climb(t, seed, false, Float.parseFloat(a[4])), y = new Climb(t, seed, false, Float.parseFloat(a[5]));
            x.s.humanHold = y.s.humanHold = Autopilot.HUMAN_HOLD; InputState in = new InputState();
            for (int k = 0; k < inputs.length; k++) {
                int rx = x.tw.rebuilds, ry = y.tw.rebuilds;
                x.keep(); y.keep();
                String rb = (x.tw.rebuilds != rx ? " A rebuilt (origin " + x.tw.originY + "/" + x.tw.originS + ")" : "") + (y.tw.rebuilds != ry ? " B rebuilt (origin " + y.tw.originY + "/" + y.tw.originS + ")" : "");
                if (!rb.isEmpty() && k > 0) System.out.println("DIFF step " + k + rb + " sizes " + x.tw.world.size() + "/" + y.tw.world.size() + " hazards " + x.tw.world.hazards.size() + "/" + y.tw.world.hazards.size());
                if (inputs[k] == SKIP) { x.skip(); y.skip(); in.clear(); } else unpack(inputs[k], in);
                InputState in2 = new InputState(); in2.moveX = in.moveX; in2.moveY = in.moveY; in2.jumpPressed = in.jumpPressed; in2.jumpHeld = in.jumpHeld; in2.swingPressed = in.swingPressed;
                if (k >= 56679 && k <= 56683) {
                    int ex = x.s.onElem, ey = y.s.onElem;
                    System.out.printf("DIFF pre %d: y %s/%s vy %s/%s mode %s/%s on %d/%d", k, x.s.y, y.s.y, x.s.vy, y.s.vy, x.s.mode, y.s.mode, ex, ey);
                    if (ex >= 0 && ey >= 0) { Element a1 = x.tw.world.get(ex), b1 = y.tw.world.get(ey);
                        System.out.printf(" elem %s %s/%s y %s/%s s %s/%s ey1 %s/%s es1 %s/%s tilt %s/%s onT %s/%s crumble %s/%s squash %s/%s", a1.type, a1.skin, b1.skin, a1.y, b1.y, a1.s, b1.s, x.s.ey1[ex], y.s.ey1[ey], x.s.es1[ex], y.s.es1[ey], x.s.tilt[ex], y.s.tilt[ey], x.s.onT[ex], y.s.onT[ey], x.s.crumbleT[ex], y.s.crumbleT[ey], x.s.padSquash[ex], y.s.padSquash[ey]); }
                    System.out.println();
                }
                x.step(k, in); y.step(k, in2);
                double ya = x.tw.absY(x.s.y), yb = y.tw.absY(y.s.y), sa = x.tw.originS + x.s.s, sb = y.tw.originS + y.s.s;
                if (ya != yb || sa != sb || x.s.mode != y.s.mode) {
                    int ex = x.s.onElem, ey = y.s.onElem; Element a1 = ex >= 0 ? x.tw.world.get(ex) : null, b1 = ey >= 0 ? y.tw.world.get(ey) : null;
                    if (a1 != null && b1 != null) System.out.printf("DIFF elem idx %d/%d %s skin %s/%s y %s/%s s %s/%s w %s amp %s per %s ph %s len %s anchor %d/%d ey0 %s/%s ey1 %s/%s es1 %s/%s dz %n", ex, ey, a1.type, a1.skin, b1.skin, a1.y, b1.y, a1.s, b1.s, a1.w, a1.amp, a1.period, a1.phase, a1.len, a1.anchor, b1.anchor, x.s.ey0[ex], y.s.ey0[ey], x.s.ey1[ex], y.s.ey1[ey], x.s.es1[ex], y.s.es1[ey]);
                    System.out.printf("DIFF FIRST step %d: y %.6f/%.6f s %.6f/%.6f mode %s/%s on %d/%d vx %s/%s originY %.1f/%.1f originS %.3f/%.3f local y %s/%s%n", k, ya, yb, sa, sb, x.s.mode, y.s.mode,
                        x.s.onElem >= 0 ? x.tw.elemKeyAt(x.s.onElem) : -1, y.s.onElem >= 0 ? y.tw.elemKeyAt(y.s.onElem) : -1, x.s.vx, y.s.vx, x.tw.originY, y.tw.originY, x.tw.originS, y.tw.originS, x.s.y, y.s.y);
                    return;
                }
            }
            System.out.println("DIFF none"); return;
        }
        if (a[0].equals("record")) {
            long max = Long.parseLong(a[3]); boolean falls = a.length > 5 && a[5].equals("falls");
            Climb c = new Climb(t, seed, falls, 150f); Autopilot.Driver d = new Autopilot.Driver(c.s);
            ByteArrayOutputStream buf = new ByteArrayOutputStream(); InputState in = new InputState(); int seen = c.tw.rebuilds; long progress = 0; double best = 0;
            for (long k = 0; k < max && c.finishStep < 0; k++) {
                c.keep(); if (c.tw.rebuilds != seen) { d.rebase(c.tw.lastRemap); seen = c.tw.rebuilds; }
                if (c.maxAbs > best + 0.5) { best = c.maxAbs; progress = k; }
                if (k - progress > STUCK && !c.s.respawnPending) {
                    progress = k; c.skip(); buf.write(SKIP); in.clear(); c.step(k, in);
                    c.keep(); if (c.tw.rebuilds != seen) seen = c.tw.rebuilds; d = new Autopilot.Driver(c.s); continue;
                }
                in.clear(); d.drive(c.s, in);
                if (d.failed) { d = new Autopilot.Driver(c.s); in.clear(); d.drive(c.s, in); }        // as PlayScreen's autopilot does: plan afresh
                buf.write(pack(in));
                c.step(k, in);
            }
            try (OutputStream o = new GZIPOutputStream(new FileOutputStream(a[4]))) { o.write(buf.toByteArray()); }
            report("record", seed, buf.size(), c);
        } else {
            boolean falls = a.length > 4 && a[4].equals("falls"); float look = a.length > 5 ? Float.parseFloat(a[5]) : 150f;
            byte[] inputs; try (InputStream i = new GZIPInputStream(new FileInputStream(a[3]))) { inputs = readAll(i); }
            Climb c = new Climb(t, seed, falls, look); c.s.humanHold = Autopilot.HUMAN_HOLD;             // the stick handling the recording ran under
            InputState in = new InputState();
            for (int k = 0; k < inputs.length && c.finishStep < 0; k++) {
                c.keep();
                if (inputs[k] == SKIP) { c.skip(); in.clear(); } else unpack(inputs[k], in);
                c.step(k, in);
            }
            report("replay", seed, inputs.length, c);
        }
    }

    static void report(String mode, long seed, long steps, Climb c) {
        float shown = (float) (c.finishClock >= 0 ? c.finishClock : c.clock) / RunRecord.STEPS_PER_SECOND;
        System.out.println("REPLAYPRINT " + mode + " seed=" + seed + " steps=" + steps + " falls=" + c.falls + " height=" + (long) c.maxAbs + " doors=" + c.doors + " [" + c.doorLog.toString().trim() + "]"
            + " respawns=" + c.respawns + " skips=" + c.skips + " finishStep=" + c.finishStep + " clockSteps=" + (c.finishClock >= 0 ? c.finishClock : c.clock) + " time=" + ClockText.time(shown)
            + " events=" + hex(c.events.digest()) + " states=" + hex(c.states.digest()));
    }

    static byte[] readAll(InputStream i) throws IOException { ByteArrayOutputStream o = new ByteArrayOutputStream(); byte[] b = new byte[65536]; int n; while ((n = i.read(b)) > 0) o.write(b, 0, n); return o.toByteArray(); }
}

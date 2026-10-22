package com.hotatticgames.climbup;

import com.hotatticgames.climbup.sim.*;
import java.security.MessageDigest;

/**
 * Build 74 (owner: same seed, same moves on the same steps = same finish step and the same recorded time on Windows, Linux and Android). Plays a climb
 * the way PlayScreen does since Build 74 (before every simulation step the tower is kept ahead and its window maintained, then one step, then the run
 * clock counts it from the first input) from per-step inputs, and reports what a speedrun is judged on: the finish step, the run clock in steps and as
 * shown, a hash of every gameplay event with its step, and state fingerprints along the way. The desktop and CI use it through the test tool
 * ReplayTool; the phone runs the bundled seed 12 climb through the hidden check in SETTINGS > ABOUT (tap the version line seven times).
 * <p>
 * Where the recording's autopilot made no progress it carries a SKIP step (checkpoint three platforms on, respawn there), replayed at the same step.
 * "falls" sends the hero back to the checkpoint every FALL_EVERY steps, so respawns are exercised at fixed steps.
 */
public final class ReplayCheck {
    private ReplayCheck() { }
    public static final int FALL_EVERY = 20000, FINGERPRINT_EVERY = 10000, STUCK = 4000; public static final byte SKIP = (byte) 0x80;
    /** The bundled phone check: the seed 12 climb to the castle-10 finish, and what Linux, Windows and the Android emulator print for it. */
    public static final String BUNDLED = "replays/seed12.bin.gz"; public static final long BUNDLED_SEED = 12L;
    public static final long EXPECT_FINISH_STEP = 472941L, EXPECT_CLOCK_STEPS = 472942L;
    public static final String EXPECT_EVENTS = "3c707ee80e8447367acba1e1", EXPECT_STATES = "ca307cb19875318112e846dd";

    public static final class Climb {
        public final Tower tw; public final Sim s; final float lookahead; final boolean falls;
        public double maxAbs; int size; public long clock, finishStep = -1, finishClock = -1; boolean live; public int doors, respawns, skips;
        public final MessageDigest events, states; public final StringBuilder doorLog = new StringBuilder();
        public Climb(Tuning t, long seed, boolean falls, float lookahead) throws Exception {
            this.falls = falls; this.lookahead = lookahead;
            tw = new Tower(seed, t); tw.admitAbs = PlayScreen.ADMIT_AHEAD; while (tw.topAbsY() < lookahead) tw.extend();
            s = Sim.startOn(tw.world, t, 0); s.deferRespawn = true; s.keysFree = true; s.floorOverride = tw.floorLocal(); s.setRange(0, tw.world.size() - 1); size = tw.world.size();
            events = MessageDigest.getInstance("SHA-256"); states = MessageDigest.getInstance("SHA-256");
        }
        /** PlayScreen.keepTower: before every step. The tower is grown synchronously here (the game grows it ahead on a background thread). */
        public void keep() {
            tw.admitAbs = maxAbs + PlayScreen.ADMIT_AHEAD;                                   // as PlayScreen.keepTower
            while (tw.topAbsY() < maxAbs + lookahead) { tw.requestNext(); while (tw.generating()) Thread.onSpinWait(); tw.poll(); }   // generation ahead (here: synchronously, as far as asked)
            tw.maintain(s);
            if (tw.world.size() != size) { s.setRange(0, tw.world.size() - 1); size = tw.world.size(); }
        }
        public void step(long k, InputState in) {
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
        public void skip() {
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

    public static void putLong(MessageDigest md, long v) { for (int i = 0; i < 8; i++) md.update((byte) (v >>> (8 * i))); }
    public static String hex(byte[] d) { StringBuilder sb = new StringBuilder(); for (int i = 0; i < 12; i++) sb.append(String.format("%02x", d[i])); return sb.toString(); }

    static int code(float v) { return v > 0f ? 1 : v < 0f ? 2 : 0; }
    static float dir(int c) { return c == 1 ? 1f : c == 2 ? -1f : 0f; }
    public static byte pack(InputState in) { return (byte) (code(in.moveX) | code(in.moveY) << 2 | (in.jumpPressed ? 16 : 0) | (in.jumpHeld ? 32 : 0) | (in.swingPressed ? 64 : 0)); }
    public static void unpack(byte b, InputState in) { in.clear(); in.moveX = dir(b & 3); in.moveY = dir((b >> 2) & 3); in.jumpPressed = (b & 16) != 0; in.jumpHeld = (b & 32) != 0; in.swingPressed = (b & 64) != 0; }


    /** Plays the inputs back with no controller. {@code progress} (may be null) is told the fraction done now and then. */
    public static Climb replay(Tuning t, long seed, byte[] inputs, boolean falls, float lookahead, java.util.function.DoubleConsumer progress) throws Exception {
        Climb c = new Climb(t, seed, falls, lookahead); c.s.humanHold = Autopilot.HUMAN_HOLD;          // the stick handling the recording ran under
        InputState in = new InputState();
        for (int k = 0; k < inputs.length && c.finishStep < 0; k++) {
            c.keep();
            if (inputs[k] == SKIP) { c.skip(); in.clear(); } else unpack(inputs[k], in);
            c.step(k, in);
            if (progress != null && (k & 4095) == 0) progress.accept(k / (double) inputs.length);
        }
        return c;
    }

    /** The line every platform prints for a climb (compared byte for byte by determinism.yml). */
    public static String report(String mode, long seed, long steps, Climb c) {
        float shown = (float) (c.finishClock >= 0 ? c.finishClock : c.clock) / RunRecord.STEPS_PER_SECOND;
        return "REPLAYPRINT " + mode + " seed=" + seed + " steps=" + steps + " falls=" + c.falls + " height=" + (long) c.maxAbs + " doors=" + c.doors + " [" + c.doorLog.toString().trim() + "]"
            + " respawns=" + c.respawns + " skips=" + c.skips + " finishStep=" + c.finishStep + " clockSteps=" + (c.finishClock >= 0 ? c.finishClock : c.clock) + " time=" + ClockText.time(shown)
            + " events=" + hex(c.events.digest()) + " states=" + hex(c.states.digest());
    }

}

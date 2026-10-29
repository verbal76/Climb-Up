package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import com.hotatticgames.climbup.sim.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * Rules 19 (owner decision 2026-10-29): saving while holding a Tarzan swing stores the grip distance down the rope (Sim.tarzanR) with the rope's identity
 * and the clock; restoring hangs the climber on the same rope, the same distance down it, wherever it has swung to. Checked on a real tower (slice 0
 * re-encoded with a swing in it), through the file format, against a climb that never stopped (owner tests A-H).
 */
public class TarzanSaveRestoreTest {
    private static final long SEED = 33;
    static final float LEN = 4.5f, AMP = 0.7f, PERIOD = 2.4f;

    /** The tower's history with a Tarzan swing added to slice 0, high above its first deck (off the route: anchored to it). */
    private static List<byte[]> cached;
    static synchronized List<byte[]> history(Tuning t) {
        if (cached == null) cached = build(t);
        return cached;
    }
    private static List<byte[]> build(Tuning t) {
        Tower a = new Tower(SEED, t); while (a.topAbsY() < 120) a.extend();
        List<byte[]> l = new ArrayList<>(); for (int k = 0; k < a.sliceCount(); k++) l.add(a.blob(k));
        CourseGenerator.Framed f = SliceCodec.decode(l.get(0));
        Element d = f.c.get(0);
        Element tz = new Element(Element.Type.TARZAN, d.s, d.y + 30f, 1f); tz.len = LEN; tz.amp = AMP; tz.period = PERIOD; tz.phase = 0f; tz.anchor = 0;
        f.c.add(tz); f.c.indexDecoys();
        l.set(0, SliceCodec.encode(f));
        return l;
    }
    static Sim simOn(Tower tw, Tuning t) {
        Sim s = new Sim(tw.world, t);
        s.keysFree = false; s.deferRespawn = true; s.floorOverride = tw.floorLocal(); s.setRange(0, tw.world.size() - 1);
        return s;
    }
    static int swing(Tower tw) { for (int i = 0; i < tw.world.size(); i++) if (tw.world.get(i).type == Element.Type.TARZAN) return i; return -1; }

    /** A climber put on the swing {@code r} down it at time 0, then left hanging for {@code steps}. */
    static Sim hanging(Tower tw, Tuning t, float r, int steps) {
        tw.windowAroundAbs(40); Sim a = simOn(tw, t);
        int i = swing(tw); assertTrue("the swing is in the window", i >= 0);
        a.attachTarzan(i, r);
        InputState none = new InputState();
        for (int k = 0; k < steps; k++) { tw.maintain(a); a.step(none); a.consumeEvents(); }
        assertEquals(Sim.Mode.TARZAN, a.mode);
        return a;
    }
    static Sim restore(RunSnapshot snap, Tower tb, Tuning t) {
        RunSnapshot back = RunSnapshot.fromJson(snap.toJson());
        assertNotNull("parses", back);
        tb.setCheckpointRef(new Tower.Ref(back.cpSlice, back.cpLocal)); back.restoreWindow(tb);
        Sim b = simOn(tb, t);
        assertTrue("applies", back.apply(b, tb));
        return b;
    }
    static void assertSame(String why, Sim a, Tower ta, Sim b, Tower tb) {
        assertEquals(why + " mode", a.mode, b.mode);
        assertEquals(why + " holding", ta.elemKeyAt(a.onElem), tb.elemKeyAt(b.onElem));
        assertEquals(why + " grip", a.tarzanR, b.tarzanR, 0f);
        assertEquals(why + " height", ta.absY(a.y), tb.absY(b.y), 1e-4);
        assertEquals(why + " arc", ta.world.wrap((float) (ta.originS + a.s)), tb.world.wrap((float) (tb.originS + b.s)), 1e-4);
        assertEquals(why + " vx", a.vx, b.vx, 1e-4f);
        assertEquals(why + " vy", a.vy, b.vy, 1e-4f);
    }

    /** Saves after {@code steps}, restores, and checks the climb right after, after 2 s more hanging, and after a release 1.5 s later (A-E, H). */
    static void roundTrip(float r, int steps, float expectSign) throws Exception {
        Tuning t = TestUtil.tuning();
        List<byte[]> h = history(t);
        Tower ta = new Tower(SEED, t, h, 0); Sim a = hanging(ta, t, r, steps);
        float th = ta.world.get(a.onElem).tarzanAngle(a.time);
        if (expectSign != 0f) assertTrue("rope angled " + (expectSign > 0 ? "right" : "left") + " (" + th + ")", th * expectSign > AMP * 0.8f);
        else assertTrue("rope near straight down (" + th + ")", Math.abs(th) < 0.1f);
        assertEquals("the grip", r, a.tarzanR, 1e-6f);
        RunSnapshot snap = RunSnapshot.capture(a, ta, SEED);
        assertNotNull(snap); assertTrue(snap.hasTarzanR);
        Tower tb = new Tower(SEED, t, h, 0); tb.windowAroundAbs(110);          // (F: the restoring tower first opened its window somewhere else)
        Sim b = restore(snap, tb, t);
        String w = "r=" + r + " step " + steps;
        assertSame(w + " restored", a, ta, b, tb);
        InputState none = new InputState(), jump = new InputState(); jump.jumpPressed = true; jump.jumpHeld = true;
        for (int k = 0; k < 120; k++) { ta.maintain(a); tb.maintain(b); a.step(none); b.step(none); }
        assertSame(w + " 2 s later", a, ta, b, tb);
        float vxBefore = b.vx;
        ta.maintain(a); tb.maintain(b); a.step(jump); b.step(jump);
        assertEquals("let go", Sim.Mode.AIR, b.mode);
        assertEquals(w + " release keeps the rope's speed", vxBefore, b.vx, Math.abs(vxBefore) * 0.15f + 0.3f);
        for (int k = 0; k < 90; k++) { ta.maintain(a); tb.maintain(b); a.step(none); b.step(none); }
        assertEquals(w + " flight height", ta.absY(a.y), tb.absY(b.y), 1e-4);
        assertEquals(w + " flight arc", ta.world.wrap((float) (ta.originS + a.s)), tb.world.wrap((float) (tb.originS + b.s)), 1e-4);
        assertEquals(a.mode, b.mode);
    }

    @Test public void A_ropeAtRest() throws Exception { roundTrip(2.5f, 72, 0f); }          // half a period: back through the bottom
    @Test public void B_ropeSwungLeft() throws Exception { roundTrip(2.5f, 108, -1f); }     // three quarters
    @Test public void C_ropeSwungRight() throws Exception { roundTrip(2.5f, 36, 1f); }      // a quarter
    @Test public void D_upperMiddleAndLowerGrips() throws Exception {
        for (float r : new float[]{1.0f, 2.5f, 4.3f}) roundTrip(r, 36, 1f);
    }

    /** G: a file without the grip (from before the field) is restored by projecting the hands onto the angled rope, not by height alone. */
    @Test public void G_aGriplessSaveIsProjectedOntoTheRope() throws Exception {
        Tuning t = TestUtil.tuning(); List<byte[]> h = history(t);
        Tower ta = new Tower(SEED, t, h, 0); Sim a = hanging(ta, t, 3.0f, 36);          // rope swung right: the vertical drop is r cos 0.7 = 2.29, not 3
        RunSnapshot snap = RunSnapshot.capture(a, ta, SEED);
        snap.hasTarzanR = false; snap.tarzanR = 0f;
        Sim b = restore(snap, new Tower(SEED, t, h, 0), t);
        assertEquals(Sim.Mode.TARZAN, b.mode);
        assertEquals("the grip down the rope, from its direction", 3.0f, b.tarzanR, 2e-3f);
        // and an absent field is never read as a grip of 0
        String json = snap.toJson();
        assertFalse(RunSnapshot.fromJson(json).hasTarzanR);
    }

    /** G: a grip-less file whose hands are nowhere near the rope is refused (the checkpoint resume), not snapped onto it. */
    @Test public void G_aGriplessSaveOffTheRopeIsRefused() throws Exception {
        Tuning t = TestUtil.tuning(); List<byte[]> h = history(t);
        Tower ta = new Tower(SEED, t, h, 0); Sim a = hanging(ta, t, 3.0f, 36);
        RunSnapshot snap = RunSnapshot.capture(a, ta, SEED);
        snap.hasTarzanR = false; snap.absS += 2.0;
        RunSnapshot back = RunSnapshot.fromJson(snap.toJson());
        Tower tb = new Tower(SEED, t, h, 0); tb.setCheckpointRef(new Tower.Ref(back.cpSlice, back.cpLocal)); back.restoreWindow(tb);
        assertFalse(back.apply(simOn(tb, t), tb));
    }

    /** G: an ordinary (non-Tarzan) snapshot carries no grip and restores as before. */
    @Test public void G_otherSnapshotsCarryNoGrip() throws Exception {
        Tuning t = TestUtil.tuning(); List<byte[]> h = history(t);
        Tower ta = new Tower(SEED, t, h, 0); ta.windowAroundAbs(0); Sim a = simOn(ta, t);
        InputState none = new InputState(); for (int k = 0; k < 30; k++) { ta.maintain(a); a.step(none); }
        RunSnapshot snap = RunSnapshot.capture(a, ta, SEED);
        assertFalse(snap.hasTarzanR);
        Tower tb = new Tower(SEED, t, h, 0);
        Sim b = restore(snap, tb, t);
        assertEquals(a.mode, b.mode);
    }
}

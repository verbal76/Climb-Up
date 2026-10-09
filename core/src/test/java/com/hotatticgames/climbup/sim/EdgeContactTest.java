package com.hotatticgames.climbup.sim;

import static org.junit.Assert.*;

import java.nio.file.*;
import java.util.*;
import org.junit.Test;

/**
 * Every kind of platform the game generates has solid edges. A body that meets a platform's side, at any height from the ankles to the head, rising or falling, fast or slow, from
 * either side, must never slip into the block and sink through it: it is caught (hang, scramble up, bounce on a pad or spring) or it clears the block.
 * (The first version of this sweep found 6.4-7% of such side contacts falling through on EVERY platform type: a body rising faster than 3.5 m/s was past the catch rule and slid in.)
 */
public class EdgeContactTest {
    private static Tuning tuning() throws Exception { return Tuning.parse(new String(Files.readAllBytes(Paths.get("../assets/data/tuning.json")))); }

    /** One example of every distinct platform (type, skin, width, motion) that the generator produces over many seeds. */
    private static List<Element> specimens(Tuning t) {
        Map<String, Element> m = new TreeMap<>();
        for (int seed = 1; seed <= 6; seed++) {
            Course prev = null;
            for (int k = 0; k < 8; k++) {
                Course c = CourseGenerator.chunk(seed, k, prev, t); prev = c;
                for (int i = 0; i < c.size(); i++) {
                    Element e = c.get(i); if (!e.isPlatform() || e.type == Element.Type.GOAL) continue;
                    m.putIfAbsent(e.type + "/" + e.skin + "/w" + Math.round(e.w * 2) / 2f + "/a" + Math.round(e.amp * 4) / 4f + "/p" + Math.round(e.period * 2) / 2f, e);
                }
            }
        }
        return new ArrayList<>(m.values());
    }


    private static Element mk(Element.Type type, int skin, float w, float amp, float period) { Element e = new Element(type, 12f, 0f, w); e.skin = skin; e.amp = amp; e.period = period; return e; }

    /** Every platform kind at its narrowest, widest and fastest, whatever the generator happened to produce in this run (parameters read off 8 seeds x 1,500 m of generated tower). */
    private static List<Element> constructed() {
        List<Element> l = new ArrayList<>(); Element.Type S = Element.Type.STATIC;
        for (float w : new float[]{1f, 2f, 3f, 5f, 7f}) l.add(mk(S, 0, w, 0f, 4f));
        l.add(mk(S, 1, 7f, 0f, 4f)); l.add(mk(S, 2, 9f, 0f, 4f)); l.add(mk(S, 3, 4f, 0f, 4f)); l.add(mk(S, 3, 6f, 0f, 4f));
        l.add(mk(Element.Type.CRUMBLE, 0, 1f, 0f, 4f)); l.add(mk(Element.Type.CRUMBLE, 0, 2f, 0f, 4f));
        for (float a : new float[]{1.5f, 2.4f}) for (float p : new float[]{3.4f, 4.2f}) { l.add(mk(Element.Type.MOVE_H, 0, 2f, a, p)); l.add(mk(Element.Type.MOVE_H, 1, 2.5f, a, p)); }
        for (float a : new float[]{3.5f, 4.3f}) for (float p : new float[]{5.1f, 6.2f}) l.add(mk(Element.Type.MOVE_V, 0, 3f, a, p));
        for (float a : new float[]{2.4f, 2.7f}) for (float p : new float[]{3.0f, 3.7f}) l.add(mk(Element.Type.MOVE_V, 1, 3f, a, p));
        for (float a : new float[]{3.0f, 3.8f}) for (float p : new float[]{5.2f, 5.9f}) l.add(mk(Element.Type.MOVE_Z, 0, 3f, a, p));
        l.add(mk(Element.Type.PAD, 0, 2f, 0f, 4f));
        for (float a : new float[]{0.37f, 0.53f}) l.add(mk(Element.Type.SPRING, 0, 2f, a, 4f));
        for (int skin = 0; skin < 4; skin++) for (float w : new float[]{3.0f, 3.8f}) l.add(mk(Element.Type.RAMP, skin, w, 0.58f, 4f));
        for (float w : new float[]{7f, 8f}) l.add(mk(Element.Type.SEESAW, 1, w, 0f, 4f));
        for (float p : new float[]{3.7f, 4.4f}) l.add(mk(Element.Type.SWING, 0, 2f, 0.5f, p));
        return l;
    }

    @Test public void everyPlatformTypeHasEdgesAtEveryHeightSpeedAndDirection() throws Exception {
        Tuning t = tuning();
        List<Element> sp = constructed(); sp.addAll(specimens(t));
        Set<String> types = new TreeSet<>(); for (Element e : sp) types.add(e.type + "/" + e.skin);
        float[] depths = {0.1f, -0.2f, -0.5f, -0.8f, -1.1f, -1.4f, -1.8f}, vxs = {1.5f, 3f, 5f, 6.5f}, vys = {-10f, -5f, -1f, 2f, 5f, 8f, 11f}, gaps = {0.1f, 0.8f}, phases = {0.45f, 2.2f};
        long trials = 0, contacts = 0; List<String> bad = new ArrayList<>();
        for (Element spc : sp) {
            for (int side = -1; side <= 1; side += 2) for (float d : depths) for (float vx : vxs) for (float vy : vys) for (float gap : gaps)
                for (float ph : spc.isMoving() || spc.type == Element.Type.SEESAW ? phases : new float[]{0.45f}) {
                    Course c = new Course(1L, t.circumference()); c.add(new Element(Element.Type.STATIC, 0f, 0f, 6f));
                    Element e = new Element(spc.type, 12f, 0f, spc.w); e.amp = spc.amp; e.period = spc.period; e.phase = spc.phase; e.skin = spc.skin; e.dir = spc.dir; e.zone = spc.zone; e.len = spc.len; c.add(e);
                    Sim s = Sim.startOn(c, t, 0); s.keysFree = true; s.setRange(0, 1); s.time = ph; InputState in = new InputState(); s.step(in);
                    float top0 = s.ey1[1], hw = e.halfW(), th = e.slab();
                    s.mode = Sim.Mode.AIR; s.onElem = -1; s.s = c.wrap(s.es1[1] - side * (hw + 0.28f + gap)); s.y = top0 + d; s.vx = side * vx; s.vy = vy; s.lockout = 0f;
                    boolean contact = false; trials++;
                    for (int i = 0; i < 180; i++) {
                        in.clear(); in.moveX = side; s.step(in); s.consumeEvents();
                        if (s.mode != Sim.Mode.AIR && (s.onElem == 1 || s.onElem == -1 || s.mode == Sim.Mode.GROUND)) break;           // caught, stepped up, or sent back after a fall
                        if (s.mode == Sim.Mode.AIR && s.lastPad == 1) break;                                                            // bounced
                        float top = s.ey1[1], dist = c.dsWrap(s.es1[1], s.s), feet = s.y, head = s.y + t.height;
                        boolean overlap = feet < top && head > top - th && !s.gone[1];       // (a depth slider out of the player's plane is deliberately intangible)
                        if (!contact && Math.abs(dist) < hw + 0.28f && Math.abs(dist) >= hw && overlap) { contact = true; contacts++; }
                        if (contact && s.mode == Sim.Mode.AIR && s.vy < 0f && Math.abs(dist) < hw - 0.05f && feet < top - 0.05f && overlap) {      // sinking through the block
                            if (bad.size() < 6) bad.add(String.format("%s: side %+d feet %+.1f vx %.1f vy %+.0f gap %.1f phase %.2f", spc.type + "/" + spc.skin, side, d, vx, vy, gap, ph));
                            trials += 1_000_000_000L; break;       // counted as a failure below
                        }
                        if (s.y < top0 - 15f) break;
                    }
                }
        }
        System.out.println("edge sweep: " + types.size() + " platform kinds, " + sp.size() + " specimens, " + (trials % 1_000_000_000L) + " trials, " + contacts + " side contacts, " + (trials / 1_000_000_000L) + " fall-throughs " + types);
        assertTrue("these platform kinds are covered: " + types, types.containsAll(Arrays.asList("STATIC/0", "CRUMBLE/0", "MOVE_H/0", "MOVE_V/0", "MOVE_Z/0", "SWING/0", "PAD/0", "SPRING/0", "RAMP/0", "RAMP/1", "RAMP/2", "RAMP/3", "SEESAW/1", "STATIC/1", "STATIC/2", "STATIC/3", "MOVE_H/1", "MOVE_V/1")));
        assertTrue("the sweep really makes side contact (" + contacts + ")", contacts > 20000);
        assertEquals("a body slipped into a platform's side and sank through it: " + bad, 0, trials / 1_000_000_000L);
    }

    @Test public void theCaseThatWasReportedARisingJumpIntoTheWaistOfAMovingPlatformIsCaught() throws Exception {
        Tuning t = tuning();
        // (a platform sinking away at 2.7 m/s from a body rising at 9-11 m/s is out of reach: the body flies over it, which is not a fall-through; the sweep above covers it)
        for (float[] pv : new float[][]{{0.45f, 9f}, {0.45f, 11f}}) {
            float phase = pv[0], vy = pv[1];
            Course c = new Course(1L, t.circumference()); c.add(new Element(Element.Type.STATIC, 0f, 0f, 6f));
            Element m = new Element(Element.Type.MOVE_V, 8f, 0f, 3f); m.amp = 3f; m.period = 3.4f; c.add(m);
            Sim s = Sim.startOn(c, t, 0); s.keysFree = true; s.setRange(0, 1); s.time = phase; InputState in = new InputState(); s.step(in);
            s.mode = Sim.Mode.AIR; s.onElem = -1; s.s = 8f - 1.5f - 0.3f; s.y = s.ey1[1] - 0.5f; s.vx = 5f; s.vy = vy;          // feet half a metre below the top: the waist
            boolean caught = false;
            for (int i = 0; i < 200 && !caught; i++) { in.clear(); in.moveX = 1f; s.step(in); s.consumeEvents(); caught = s.onElem == 1 && (s.mode == Sim.Mode.LEDGE || s.mode == Sim.Mode.PULLUP || s.mode == Sim.Mode.GROUND); }
            assertTrue("phase " + phase + " vy " + vy + " must grab or climb onto the platform, not fall through", caught);
        }
    }
}

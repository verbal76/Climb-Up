package com.hotatticgames.climbup.sim;

import java.util.ArrayList;
import java.util.List;

/**
 * Owner release requirement (2026-10-26): no unintended overlapping geometry, ever. The invariants every accepted slice must satisfy, on the collision
 * footprints the generator itself uses ({@link CourseGenerator#arcHalf}, {@link CourseGenerator#vLo}, {@link CourseGenerator#vHi}: a mover's whole travel,
 * a swing's arc, a rope's length, standing room above a deck; hazards by their lethal box over their whole motion):
 * <ul>
 * <li>I1 - bodies of different spiral revolutions never intersect (with standing room and 1.5 m clearance, as the layer rule builds them);</li>
 * <li>I2 - two solid decks of the same revolution never interpenetrate (their arcs overlap and their slabs share height), movers over their travel;</li>
 * <li>I3 - a rope never runs through a solid deck (it may hang to one, or from one);</li>
 * <li>I4 - a hazard's lethal box never lies inside the body of another revolution, nor inside another revolution's hazard.</li>
 * </ul>
 * Interactions that are the game (a trap on its platform, a saw across a gap, a creature on its deck, a cannon lane across a landing) are within one
 * revolution and are proven by the planner; they are not overlaps. Elements are compared in one frame: unwrapped arcs, so a revolution is
 * {@code circumference} of arc.
 */
public final class GeometryIntegrity {
    private GeometryIntegrity() {}

    /** Deck slab thickness under a platform's walking surface (CourseGenerator.vLo's default). */
    static final float DECK = 0.8f;

    /** Violations of elements {@code from..} of {@code c} against every element and hazard of {@code c} and against {@code otherElems}/{@code otherHaz}
     *  (geometry of earlier slices in the same frame). Empty when the slice is sound. At most {@code max} are listed. */
    public static List<String> check(Course c, int from, List<Element> otherElems, List<Element> otherHaz, int max) {
        List<String> out = new ArrayList<>();
        List<Element> all = new ArrayList<>(c.elements); all.addAll(otherElems);
        List<Element> hz = new ArrayList<>(c.hazards); hz.addAll(otherHaz);
        for (int i = Math.max(0, from); i < c.size() && out.size() < max; i++) {
            Element e = c.get(i);
            for (int j = 0; j < all.size() && out.size() < max; j++) {
                Element f = all.get(j);
                if (f == e || (j < c.size() && j >= Math.max(0, from) && j < i)) continue;          // each pair inside the slice once
                String v = pair(c, e, f);
                if (v != null) out.add(v + " [" + describe(e) + " / " + describe(f) + "]");
            }
        }
        for (int i = 0; i < c.hazards.size() && out.size() < max; i++) {
            Element h = c.hazards.get(i);
            if (h.anchor >= 0 && h.anchor < from) continue;          // hazards of the context tail were checked with their own slice
            if (!physical(h)) continue;
            for (Element f : all) if (otherRevolution(c, CourseGenerator.hzS(h), f.s) && hazardInBody(c, h, f)) { out.add("I4 hazard in another revolution's body [" + h.type + " y=" + h.y + " / " + describe(f) + "]"); break; }
            for (Element g : hz) if (g != h && physical(g) && otherRevolution(c, CourseGenerator.hzS(h), CourseGenerator.hzS(g)) && hazardsMeet(c, h, g)) { out.add("I4 hazards of two revolutions meet [" + h.type + " y=" + h.y + " / " + g.type + " y=" + g.y + "]"); break; }
        }
        return out;
    }

    /** DC5: hazard {@code h} passes I4 against everything of {@code c} and of the earlier slices - the same rule as {@link #check}, for one hazard at its placement. */
    static boolean hazardClear(Course c, Element h, List<Element> otherElems, List<Element> otherHaz) {
        if (!physical(h)) return true;
        for (Element f : c.elements) if (otherRevolution(c, CourseGenerator.hzS(h), f.s) && hazardInBody(c, h, f)) return false;
        for (Element f : otherElems) if (otherRevolution(c, CourseGenerator.hzS(h), f.s) && hazardInBody(c, h, f)) return false;
        for (Element g : c.hazards) if (g != h && physical(g) && otherRevolution(c, CourseGenerator.hzS(h), CourseGenerator.hzS(g)) && hazardsMeet(c, h, g)) return false;
        for (Element g : otherHaz) if (g != h && physical(g) && otherRevolution(c, CourseGenerator.hzS(h), CourseGenerator.hzS(g)) && hazardsMeet(c, h, g)) return false;
        return true;
    }

    /** DC5: element {@code e} lies in no hazard's lethal box of another revolution (I4 seen from the element: a deck placed after the hazard). */
    static boolean elementClear(Course c, Element e, List<Element> hz, List<Element> otherHaz) {
        for (Element h : hz) if (physical(h) && otherRevolution(c, CourseGenerator.hzS(h), e.s) && hazardInBody(c, h, e)) return false;
        for (Element h : otherHaz) if (physical(h) && otherRevolution(c, CourseGenerator.hzS(h), e.s) && hazardInBody(c, h, e)) return false;
        return true;
    }

    /** Diagnostics only (T4): the hazard whose lethal box of another revolution holds element {@code e}, or null ({@link #elementClear}'s test). */
    static Element elementBlocker(Course c, Element e, List<Element> hz, List<Element> otherHaz) {
        for (Element h : hz) if (physical(h) && otherRevolution(c, CourseGenerator.hzS(h), e.s) && hazardInBody(c, h, e)) return h;
        for (Element h : otherHaz) if (physical(h) && otherRevolution(c, CourseGenerator.hzS(h), e.s) && hazardInBody(c, h, e)) return h;
        return null;
    }

    /** The violation between two elements, or null. */
    static String pair(Course c, Element e, Element f) {
        if (otherRevolution(c, e.s, f.s)) return CourseGenerator.layersBodiesClash(c, e, f) ? "I1 bodies of two revolutions intersect" : null;
        if (e.isPlatform() && f.isPlatform() && solid(e) && solid(f)) {
            float arc = Math.abs(c.dsWrap(e.s, f.s));
            if (arc < CourseGenerator.arcHalf(e) + CourseGenerator.arcHalf(f) - 0.05f && slabsMeet(e, f)) return "I2 decks interpenetrate";
        }
        Element rope = e.type == Element.Type.ROPE ? e : f.type == Element.Type.ROPE ? f : null, deck = rope == e ? f : e;
        if (rope != null && deck != rope && deck.isPlatform() && solid(deck)) {
            float arc = Math.abs(c.dsWrap(rope.s, deck.s));
            if (arc < deck.halfW() - 0.1f && rope.y > deck.y + 0.3f && rope.y - rope.len < deck.y - DECK - 0.3f) return "I3 rope through a deck";
        }
        return null;
    }

    static boolean otherRevolution(Course c, float s1, float s2) { return Math.abs(s1 - s2) >= c.circumference * 0.5f; }

    /** A deck that is solid where it is drawn (a depth slider leaves the lane and holed planks are still a deck). */
    private static boolean solid(Element e) { return e.type != Element.Type.MOVE_Z; }

    /** The slabs of two same-revolution platforms share height, over their vertical travel. */
    private static boolean slabsMeet(Element e, Element f) { return e.y - DECK < top(f) - 0.02f && top(e) > f.y - DECK + 0.02f; }
    /** The highest a deck's surface gets: an elevator's top stop, a swing's ends (it hangs lowest, at y, in the middle of its arc: Element.yAt). */
    private static float top(Element e) {
        switch (e.type) {
            case MOVE_V: return e.y + e.amp;
            case SWING: return e.y + e.len * (1f - (float) StrictMath.cos(e.amp));
            default: return e.y;
        }
    }

    /** Hazards that are physical objects in the lane (keys, gates and clubs are pickups and doors). */
    private static boolean physical(Element h) { return h.type != Element.Type.KEY && h.type != Element.Type.GATE && h.type != Element.Type.CLUB; }

    private static boolean hazardInBody(Course c, Element h, Element f) {
        float hs = CourseGenerator.hzS(h), hh = CourseGenerator.hzHalf(h);
        return Math.abs(c.dsWrap(hs, f.s)) < hh + CourseGenerator.arcHalf(f) && CourseGenerator.hzLo(h) < CourseGenerator.vHi(f) && CourseGenerator.hzHi(h) > CourseGenerator.vLo(f);
    }

    private static boolean hazardsMeet(Course c, Element h, Element g) {
        return Math.abs(c.dsWrap(CourseGenerator.hzS(h), CourseGenerator.hzS(g))) < CourseGenerator.hzHalf(h) + CourseGenerator.hzHalf(g)
            && CourseGenerator.hzLo(h) < CourseGenerator.hzHi(g) && CourseGenerator.hzHi(h) > CourseGenerator.hzLo(g);
    }

    private static String describe(Element e) { return String.format(java.util.Locale.ROOT, "%s s=%.1f y=%.1f w=%.1f%s", e.type, e.s, e.y, e.w, e.anchor >= 0 ? " side" : ""); }
}

package com.hotatticgames.climbup.sim;

import java.util.ArrayList;
import java.util.List;

/** A generated climb: elements in route order (index 0 = start, last = goal). */
public final class Course {
    public final long seed;
    public final float circumference;
    public final List<Element> elements = new ArrayList<>();
    /** Environmental hazards (saws, cannons, spike traps...). Kept apart from the route so the route stays a simple ascending chain; each is anchored to a route element. */
    public final List<Element> hazards = new ArrayList<>();

    public Course(long seed, float circumference) { this.seed = seed; this.circumference = circumference; }

    /** Elements [0, routeCount) are the solvable route; anything after is decoy scenery (dead ends, lures), sorted by anchor. */
    public int routeCount = -1;
    private int[] decoyFrom = new int[0], decoyTo = new int[0];

    public int routeSize() { return routeCount < 0 ? elements.size() : routeCount; }
    public void finishRoute() { routeCount = elements.size(); indexDecoys(); }

    public void indexDecoys() {
        int rs = routeSize();
        decoyFrom = new int[rs]; decoyTo = new int[rs];
        java.util.Arrays.fill(decoyFrom, -1);
        for (int i = rs; i < elements.size(); i++) {
            int a = elements.get(i).anchor;
            if (a < 0 || a >= rs) continue;
            if (decoyFrom[a] < 0) decoyFrom[a] = i;
            decoyTo[a] = i + 1;
        }
    }

    /** Contiguous [from,to) range of decoy indices anchored to route elements aLo..aHi (empty if none). */
    public int[] decoyRange(int aLo, int aHi) {
        int from = Integer.MAX_VALUE, to = -1;
        for (int a = Math.max(0, aLo); a <= Math.min(decoyFrom.length - 1, aHi); a++) {
            if (decoyFrom[a] < 0) continue;
            from = Math.min(from, decoyFrom[a]); to = Math.max(to, decoyTo[a]);
        }
        return to < 0 ? new int[]{0, 0} : new int[]{from, to};
    }

    /**
     * Key rooms: {anchor route platform, first decoy index, decoys on the way out (ending at the key platform), 1 if a return pad follows them, key hazard-list index, gate hazard-list index}.
     * The solver uses them to fetch the key before the matching castle gate.
     */
    public final List<int[]> keyRooms = new ArrayList<>();

    /** Indices of hazards anchored to route/decoy elements aLo..aHi (inclusive). */
    public int[] hazardsFor(int aLo, int aHi) {
        int n = 0;
        for (Element h : hazards) if (h.anchor >= aLo && h.anchor <= aHi) n++;
        int[] r = new int[n]; int k = 0;
        for (int i = 0; i < hazards.size(); i++) { int a = hazards.get(i).anchor; if (a >= aLo && a <= aHi) r[k++] = i; }
        return r;
    }

    public int add(Element e) { elements.add(e); return elements.size() - 1; }
    public Element get(int i) { return elements.get(i); }
    public int size() { return elements.size(); }
    public int goalIndex() { return routeSize() - 1; }

    /** Signed shortest arc difference a-b on the ring. */
    public float dsWrap(float a, float b) {
        float d = (a - b) % circumference;
        if (d > circumference * 0.5f) d -= circumference;
        else if (d < -circumference * 0.5f) d += circumference;
        return d;
    }

    public float wrap(float s) {
        s %= circumference;
        return s < 0 ? s + circumference : s;
    }

    public int lastCheckpointAtOrBefore(int idx) {
        for (int i = Math.min(idx, elements.size() - 1); i >= 0; i--) if (elements.get(i).checkpoint) return i;
        return 0;
    }
}

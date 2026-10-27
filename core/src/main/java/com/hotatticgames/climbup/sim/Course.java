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

    /** Endless climb: only the gem platforms (STATIC skin 3, halfway between castles) are checkpoints; the other 'checkpoint' flags merely mark rest platforms for the generator. */
    public boolean gemCheckpoints;
    public boolean isCheckpoint(Element e) { return e.checkpoint && (!gemCheckpoints || (e.type == Element.Type.STATIC && e.skin == 3)); }

    public Course(long seed, float circumference) { this.seed = seed; this.circumference = circumference; }

    /** Elements [0, routeCount) are the solvable route; anything after is decoy scenery (dead ends, lures), sorted by anchor. */
    public int routeCount = -1;
    private int[] decoyFrom = new int[0], decoyTo = new int[0];

    public int routeSize() { return routeCount < 0 ? elements.size() : routeCount; }
    public void finishRoute() { routeCount = elements.size(); indexDecoys(); }
    /** False for a streaming tower's world, whose decoys sit among the route elements (each slice's route, then its decoys) rather than after them. */
    public boolean routeFinished() { return routeCount >= 0; }
    /** The route element after route element i: i + 1, or in a streaming tower's world the next one past any decoys in between (each slice's decoys follow its route). size() if none. */
    public int nextRoute(int i) { if (routeFinished()) return i + 1; int j = i + 1; while (j < elements.size() && elements.get(j).anchor >= 0) j++; return j; }

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
    /** Owner directive 2026-10-24: alternative routes. Each fork is {start element, first branch element, branch length, route element it rejoins}. */
    public final List<int[]> forks = new ArrayList<>();

    /** Indices of hazards anchored to route/decoy elements aLo..aHi (inclusive). */
    /** The hazards anchored to elements aLo..aHi, plus any side cannon further back whose balls fly on into that stretch (Build 63: a ball flies CANNON_BEYOND past its lane and hits there, so the cannon stays live while the player is within its reach). */
    public int[] hazardsFor(int aLo, int aHi) {
        boolean[] in = new boolean[hazards.size()]; int n = 0;
        for (int i = 0; i < hazards.size(); i++) {
            Element h = hazards.get(i);
            boolean take = h.anchor >= aLo && h.anchor <= aHi;
            if (!take && h.type == Element.Type.CANNON && h.anchor < aLo) {
                float reach = h.cannonReach() + 2f;
                for (int j = Math.max(0, aLo); j <= aHi && j < elements.size() && !take; j++) { float ahead = h.dir * dsWrap(elements.get(j).s, h.s); if (ahead >= -2f && ahead <= reach) take = true; }
            }
            if (!take && h.type == Element.Type.ENFORCER && h.anchor - 1 <= aHi && h.anchor + Math.round(h.len) >= aLo) take = true;      // it chases across its decks: in view for every link of its encounter
            if (take) { in[i] = true; n++; }
        }
        int[] r = new int[n]; int k = 0;
        for (int i = 0; i < hazards.size(); i++) if (in[i]) r[k++] = i;
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

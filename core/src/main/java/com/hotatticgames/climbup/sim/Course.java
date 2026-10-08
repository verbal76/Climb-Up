package com.hotatticgames.climbup.sim;

import java.util.ArrayList;
import java.util.List;

/** A generated climb: elements in route order (index 0 = start, last = goal). */
public final class Course {
    public final long seed;
    public final float circumference;
    public final List<Element> elements = new ArrayList<>();

    public Course(long seed, float circumference) { this.seed = seed; this.circumference = circumference; }

    public int add(Element e) { elements.add(e); return elements.size() - 1; }
    public Element get(int i) { return elements.get(i); }
    public int size() { return elements.size(); }
    public int goalIndex() { return elements.size() - 1; }

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

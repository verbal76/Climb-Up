package com.hotatticgames.climbup;

import com.hotatticgames.climbup.sim.*;
import java.nio.file.*;

final class TestUtil {
    static Tuning tuning() throws Exception {
        return Tuning.parse(new String(Files.readAllBytes(Paths.get("../assets/data/tuning.json"))));
    }
    private static final java.util.Map<Integer, Course> TOWERS = new java.util.HashMap<>();
    /** A finite, validated test tower (seed 300 + i) built by the same generator that feeds the endless climb. */
    static synchronized Course tower(int i) throws Exception {
        Course c = TOWERS.get(i);
        if (c == null) { c = CourseGenerator.generate(300 + i + (i >= 5 ? 1 : 0), tuning()); TOWERS.put(i, c); }
        return c;
    }
    private static final java.util.Map<String, java.util.List<Course>> SLICES = new java.util.HashMap<>();
    /** The slices of an endless run of this seed up to the one whose route passes {@code top} metres (generated once and cached for the whole test run). */
    static synchronized java.util.List<Course> slices(long seed, float top) throws Exception {
        java.util.List<Course> l = SLICES.computeIfAbsent(seed + "", k -> new java.util.ArrayList<>());
        Tuning t = tuning();
        while (l.isEmpty() || l.get(l.size() - 1).get(l.get(l.size() - 1).routeSize() - 1).y < top) {
            Course prev = l.isEmpty() ? null : l.get(l.size() - 1);
            l.add(CourseGenerator.chunk(seed, l.size(), prev, t));
        }
        // only the slices up to top, even when another test has grown the shared cache higher (Build 77: a check meant for the first 1,100 m was measuring
        // whole 5,300 m runs whenever a 5,300 m test happened to run first in the same JVM)
        int n = 0; while (n < l.size() && l.get(n).get(l.get(n).routeSize() - 1).y < top) n++;
        return java.util.Collections.unmodifiableList(l.subList(0, Math.min(l.size(), n + 1)));
    }
    /** Flat test world: a long platform plus helpers. */
    static Course flat(Tuning t, Element... extra) {
        Course c = new Course(0, t.circumference());
        Element start = new Element(Element.Type.STATIC, 10f, 0f, 8f); start.checkpoint = true; c.add(start);
        for (Element e : extra) c.add(e);
        return c;
    }
    static void run(Sim s, int steps, float mx, boolean jumpHeld, int jumpAtStep) {
        InputState in = new InputState();
        for (int i = 0; i < steps; i++) {
            in.clear(); in.moveX = mx; in.jumpHeld = jumpHeld; in.jumpPressed = i == jumpAtStep;
            s.step(in);
        }
    }
}

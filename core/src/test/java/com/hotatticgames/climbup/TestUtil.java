package com.hotatticgames.climbup;

import com.hotatticgames.climbup.sim.*;
import java.nio.file.*;

final class TestUtil {
    static Tuning tuning() throws Exception {
        return Tuning.parse(new String(Files.readAllBytes(Paths.get("../assets/data/tuning.json"))));
    }
    static Course tower(int i) throws Exception {
        return CourseIO.fromJson(new String(Files.readAllBytes(Paths.get(String.format("../assets/courses/tower%02d.json", i)))));
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

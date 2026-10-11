package com.hotatticgames.climbup.desktop;

import com.hotatticgames.climbup.desktop.input.Ctl;
import com.hotatticgames.climbup.desktop.input.KeyCodes;
import com.hotatticgames.climbup.desktop.input.Pad;
import com.hotatticgames.climbup.desktop.input.Sources;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Deterministic stand-ins for the keyboard/mouse and controllers, so input logic is tested without a window or hardware. */
final class Fakes {
    private Fakes() { }

    static final class FakePad implements Pad {
        final String id, name; boolean connected = true;
        final EnumMap<Ctl, Float> v = new EnumMap<>(Ctl.class); final Set<Ctl> latch = new HashSet<>();
        FakePad(String id, String name) { this.id = id; this.name = name; }
        void set(Ctl c, float x) { v.put(c, x); }
        void tap(Ctl c) { latch.add(c); }
        @Override public String id() { return id; }
        @Override public String name() { return name; }
        @Override public boolean connected() { return connected; }
        @Override public float value(Ctl c) { return v.getOrDefault(c, 0f); }
        @Override public boolean latched(Ctl c) { return latch.remove(c); }
    }

    static final class FakeSources implements Sources {
        final Set<Integer> down = new HashSet<>(), just = new HashSet<>();
        final List<FakePad> pads = new ArrayList<>();
        int mx, my; float scroll;
        void press(int code) { down.add(code); just.add(code); }
        void release(int code) { down.remove(code); }
        void tapKey(int code) { just.add(code); }                // pressed and released inside one frame
        /** Call after InputManager.update: edges last one frame. */
        void endFrame() { just.clear(); }
        @Override public boolean down(int code) { return down.contains(code); }
        @Override public boolean justPressed(int code) { return just.contains(code); }
        @Override public int anyJustPressed() { for (int k : just) return k; return -1; }
        @Override public int mouseX() { return mx; }
        @Override public int mouseY() { return my; }
        @Override public float takeScroll() { float s = scroll; scroll = 0; return s; }
        @Override public List<FakePad> pads() { return pads; }
    }

    static int mouse(int b) { return KeyCodes.mouse(b); }
}

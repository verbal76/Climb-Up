package com.hotatticgames.climbup.desktop.input;

import com.badlogic.gdx.controllers.Controller;
import com.badlogic.gdx.controllers.ControllerMapping;

/** A gdx-controllers device seen through the standard mapping, so no code anywhere depends on a driver's raw button numbers. */
public final class GdxPad implements Pad {
    private static final int TRIGGER_LEFT_AXIS = 4, TRIGGER_RIGHT_AXIS = 5;       // SDL game-controller layout (jamepad): after the two sticks
    public final Controller c;
    private final boolean[] latch = new boolean[Ctl.values().length];

    GdxPad(Controller c) { this.c = c; }

    @Override public String id() { String u = c.getUniqueId(); return u == null || u.isEmpty() ? "pad-" + c.getName() : u; }
    @Override public String name() { String n = c.getName(); return n == null ? "CONTROLLER" : n; }
    @Override public boolean connected() { return c.isConnected(); }

    private boolean b(int code) { return code >= 0 && c.getButton(code); }
    private float axis(int idx) { if (idx < 0) return 0f; try { return c.getAxis(idx); } catch (RuntimeException e) { return 0f; } }
    private static float pos(float v) { return v > 0f ? v : 0f; }

    @Override public float value(Ctl k) {
        ControllerMapping m = c.getMapping();
        if (m == null) return 0f;
        switch (k) {
            case A: return b(m.buttonA) ? 1f : 0f;
            case B: return b(m.buttonB) ? 1f : 0f;
            case X: return b(m.buttonX) ? 1f : 0f;
            case Y: return b(m.buttonY) ? 1f : 0f;
            case BACK: return b(m.buttonBack) ? 1f : 0f;
            case START: return b(m.buttonStart) ? 1f : 0f;
            case L1: return b(m.buttonL1) ? 1f : 0f;
            case R1: return b(m.buttonR1) ? 1f : 0f;
            case L2: return m.buttonL2 >= 0 ? (b(m.buttonL2) ? 1f : 0f) : axis(TRIGGER_LEFT_AXIS);
            case R2: return m.buttonR2 >= 0 ? (b(m.buttonR2) ? 1f : 0f) : axis(TRIGGER_RIGHT_AXIS);
            case L3: return b(m.buttonLeftStick) ? 1f : 0f;
            case R3: return b(m.buttonRightStick) ? 1f : 0f;
            case DPAD_UP: return b(m.buttonDpadUp) ? 1f : 0f;
            case DPAD_DOWN: return b(m.buttonDpadDown) ? 1f : 0f;
            case DPAD_LEFT: return b(m.buttonDpadLeft) ? 1f : 0f;
            case DPAD_RIGHT: return b(m.buttonDpadRight) ? 1f : 0f;
            case LS_UP: return pos(-axis(m.axisLeftY));          // SDL convention: pushing a stick up reads negative
            case LS_DOWN: return pos(axis(m.axisLeftY));
            case LS_LEFT: return pos(-axis(m.axisLeftX));
            case LS_RIGHT: return pos(axis(m.axisLeftX));
            case RS_UP: return pos(-axis(m.axisRightY));
            case RS_DOWN: return pos(axis(m.axisRightY));
            case RS_LEFT: return pos(-axis(m.axisRightX));
            default: return pos(axis(m.axisRightX));
        }
    }

    @Override public boolean latched(Ctl k) { boolean v = latch[k.ordinal()]; latch[k.ordinal()] = false; return v; }

    /** Event hook: remember a button press that may be shorter than a frame. */
    void buttonDown(int code) {
        ControllerMapping m = c.getMapping();
        if (m == null || code < 0) return;
        if (code == m.buttonA) latch[Ctl.A.ordinal()] = true; else if (code == m.buttonB) latch[Ctl.B.ordinal()] = true;
        else if (code == m.buttonX) latch[Ctl.X.ordinal()] = true; else if (code == m.buttonY) latch[Ctl.Y.ordinal()] = true;
        else if (code == m.buttonStart) latch[Ctl.START.ordinal()] = true; else if (code == m.buttonBack) latch[Ctl.BACK.ordinal()] = true;
        else if (code == m.buttonL1) latch[Ctl.L1.ordinal()] = true; else if (code == m.buttonR1) latch[Ctl.R1.ordinal()] = true;
        else if (code == m.buttonDpadUp) latch[Ctl.DPAD_UP.ordinal()] = true; else if (code == m.buttonDpadDown) latch[Ctl.DPAD_DOWN.ordinal()] = true;
        else if (code == m.buttonDpadLeft) latch[Ctl.DPAD_LEFT.ordinal()] = true; else if (code == m.buttonDpadRight) latch[Ctl.DPAD_RIGHT.ordinal()] = true;
        else if (code == m.buttonLeftStick) latch[Ctl.L3.ordinal()] = true; else if (code == m.buttonRightStick) latch[Ctl.R3.ordinal()] = true;
    }
}

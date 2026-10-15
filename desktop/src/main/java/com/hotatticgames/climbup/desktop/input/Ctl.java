package com.hotatticgames.climbup.desktop.input;

/** A physical control on a standard gamepad, independent of the driver's button numbers. Analog entries are a stick/trigger half-axis with a polarity. */
public enum Ctl {
    A, B, X, Y, BACK, START, L1, R1, L2, R2, L3, R3, DPAD_UP, DPAD_DOWN, DPAD_LEFT, DPAD_RIGHT,
    LS_UP, LS_DOWN, LS_LEFT, LS_RIGHT, RS_UP, RS_DOWN, RS_LEFT, RS_RIGHT;

    public boolean analog() { return ordinal() >= LS_UP.ordinal(); }
}

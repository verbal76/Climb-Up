package com.hotatticgames.climbup.desktop.input;

/**
 * Two controls pressed together fire once (Build 66: LB + RB takes a screenshot). The pair re-arms only when both are up again,
 * so a bumper held while the other is tapped over and over never fires twice, and a pad unplugged mid-press simply re-arms. Pure.
 */
public final class ChordLatch {
    private boolean armed = true;

    /** Call once per frame with both controls' states; true exactly once per joint press. */
    public boolean update(boolean a, boolean b) {
        if (a && b) { if (!armed) return false; armed = false; return true; }
        if (!a && !b) armed = true;
        return false;
    }

    public boolean armed() { return armed; }
}

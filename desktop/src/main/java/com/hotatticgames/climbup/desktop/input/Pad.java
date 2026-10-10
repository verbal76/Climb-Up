package com.hotatticgames.climbup.desktop.input;

/** One connected controller as the game sees it (a thin view so the input logic can be tested with fakes and the real gdx-controllers adapter stays tiny). */
public interface Pad {
    /** Stable identifier of the physical device where the driver provides one. */
    String id();
    String name();
    boolean connected();
    /** 0..1 strength of a control: 0/1 for buttons, the deflection in that direction for a stick half-axis. */
    float value(Ctl c);
    /** True once if the control went down since the last call (catches taps shorter than a frame). Consumes the latch. */
    boolean latched(Ctl c);
}

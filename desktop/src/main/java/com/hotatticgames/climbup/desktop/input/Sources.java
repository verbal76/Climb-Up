package com.hotatticgames.climbup.desktop.input;

import java.util.List;

/** Keyboard and mouse as polled once per frame. Codes are {@link Keys} codes (mouse buttons from Keys.MOUSE). */
public interface Sources {
    boolean down(int code);
    boolean justPressed(int code);
    /** The first input pressed this frame (any key or mouse button except left click), or -1. Used by rebinding and device detection. */
    int anyJustPressed();
    int mouseX();
    int mouseY();
    /** Mouse wheel movement since the last call (positive = down), consumed. */
    float takeScroll();
    /** Controllers currently connected, in connection order. */
    List<? extends Pad> pads();
}

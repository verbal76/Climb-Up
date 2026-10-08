package com.hotatticgames.climbup.sim;

/** One frame of player intent. moveX/moveY in [-1,1]; jumpPressed is an edge flag for this step. */
public final class InputState {
    public float moveX, moveY;
    public boolean jumpHeld, jumpPressed;

    public void clear() { moveX = moveY = 0; jumpHeld = jumpPressed = false; }
}

package com.hotatticgames.climbup.platform;

import com.hotatticgames.climbup.sim.InputState;

/**
 * Desktop gameplay input. This is the only way a desktop device reaches the simulation: it fills the same {@link InputState} the touch overlay fills
 * (moveX/moveY, jumpHeld, jumpPressed, swingPressed). It never changes how the simulation interprets those values.
 */
public interface GameInput {
    /** Fills moveX/moveY/jumpHeld/jumpPressed/swingPressed for the next simulation step. */
    void read(InputState in);
    /** Called after each simulation step: one-shot presses (jump, swing) are consumed. */
    void stepDone();
    /** The pause action was pressed this frame. */
    boolean pausePressed();
    /** The back/cancel action was pressed this frame. */
    boolean backPressed();
    /** Drops latched presses (pausing, resuming, finishing). */
    void clear();
    /** True once when the controller the player was using has just been unplugged (the game pauses so the run is not lost); false on platforms without controllers. */
    default boolean controllerLost() { return false; }
    /** True once when the "close the tip" control was pressed (a control bound to no gameplay action; never fed into the InputState). */
    default boolean tipDismissPressed() { return false; }
    /** The short name of that control for the tip banner ("TAB", "B", ...), or null to show none. */
    default String tipDismissLabel() { return null; }
}

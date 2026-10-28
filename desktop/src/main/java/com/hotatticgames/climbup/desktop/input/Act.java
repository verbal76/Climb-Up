package com.hotatticgames.climbup.desktop.input;

/** Every action a player can bind. GAME actions fill the simulation's InputState; MENU actions drive menu focus. Two bindings may share an input only across contexts. */
public enum Act {
    LEFT("MOVE LEFT", true), RIGHT("MOVE RIGHT", true), CLIMB_UP("CLIMB UP", true), CLIMB_DOWN("CLIMB DOWN", true), JUMP("JUMP", true), SWING("SWING CLUB", true), PAUSE("PAUSE", true),
    MENU_UP("MENU UP", false), MENU_DOWN("MENU DOWN", false), MENU_LEFT("MENU LEFT", false), MENU_RIGHT("MENU RIGHT", false), CONFIRM("CONFIRM", false), BACK("BACK", false);

    public final String label; public final boolean game;
    Act(String label, boolean game) { this.label = label; this.game = game; }
    /** True when both actions can be active at the same time, so one input must not drive both. */
    public boolean sameContext(Act o) { return game == o.game; }
}

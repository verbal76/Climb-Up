package com.hotatticgames.climbup.desktop.input;

import com.badlogic.gdx.Input.Keys;
import java.util.Arrays;

/** The default bindings (the owner's specification). */
public final class Defaults {
    private Defaults() { }

    public static Bindings<Integer> keyboard() {
        Bindings<Integer> b = new Bindings<>();
        b.set(Act.LEFT, Arrays.asList(Keys.A, Keys.LEFT));
        b.set(Act.RIGHT, Arrays.asList(Keys.D, Keys.RIGHT));
        b.set(Act.CLIMB_UP, Arrays.asList(Keys.W, Keys.UP));
        b.set(Act.CLIMB_DOWN, Arrays.asList(Keys.S, Keys.DOWN));
        b.set(Act.JUMP, Arrays.asList(Keys.SPACE));
        b.set(Act.SWING, Arrays.asList(Keys.X, Keys.J));
        b.set(Act.PAUSE, Arrays.asList(Keys.ESCAPE, Keys.P));
        b.set(Act.MENU_UP, Arrays.asList(Keys.W, Keys.UP));
        b.set(Act.MENU_DOWN, Arrays.asList(Keys.S, Keys.DOWN));
        b.set(Act.MENU_LEFT, Arrays.asList(Keys.A, Keys.LEFT));
        b.set(Act.MENU_RIGHT, Arrays.asList(Keys.D, Keys.RIGHT));
        b.set(Act.CONFIRM, Arrays.asList(Keys.ENTER, Keys.SPACE));
        b.set(Act.BACK, Arrays.asList(Keys.ESCAPE));
        return b;
    }

    public static Bindings<Ctl> pad() {
        Bindings<Ctl> b = new Bindings<>();
        b.set(Act.LEFT, Arrays.asList(Ctl.LS_LEFT, Ctl.DPAD_LEFT));
        b.set(Act.RIGHT, Arrays.asList(Ctl.LS_RIGHT, Ctl.DPAD_RIGHT));
        b.set(Act.CLIMB_UP, Arrays.asList(Ctl.LS_UP, Ctl.DPAD_UP));
        b.set(Act.CLIMB_DOWN, Arrays.asList(Ctl.LS_DOWN, Ctl.DPAD_DOWN));
        b.set(Act.JUMP, Arrays.asList(Ctl.A));
        b.set(Act.SWING, Arrays.asList(Ctl.X));
        b.set(Act.PAUSE, Arrays.asList(Ctl.START));
        b.set(Act.MENU_UP, Arrays.asList(Ctl.DPAD_UP, Ctl.LS_UP));
        b.set(Act.MENU_DOWN, Arrays.asList(Ctl.DPAD_DOWN, Ctl.LS_DOWN));
        b.set(Act.MENU_LEFT, Arrays.asList(Ctl.DPAD_LEFT, Ctl.LS_LEFT));
        b.set(Act.MENU_RIGHT, Arrays.asList(Ctl.DPAD_RIGHT, Ctl.LS_RIGHT));
        b.set(Act.CONFIRM, Arrays.asList(Ctl.A));
        b.set(Act.BACK, Arrays.asList(Ctl.B));
        return b;
    }
}

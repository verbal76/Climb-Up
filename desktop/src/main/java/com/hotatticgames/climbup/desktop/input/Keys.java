package com.hotatticgames.climbup.desktop.input;

import com.badlogic.gdx.Input;

/** Keyboard and mouse inputs share one int space: key codes as libGDX defines them, mouse buttons from {@link #MOUSE} up. */
public final class Keys {
    public static final int MOUSE = 1000;
    private Keys() { }

    public static int mouse(int button) { return MOUSE + button; }
    public static boolean isMouse(int code) { return code >= MOUSE; }

    /** Reserved: the left mouse button always points at menu items, F11 always toggles fullscreen. */
    public static boolean reserved(int code) { return code == mouse(Input.Buttons.LEFT) || code == Input.Keys.F11 || code == Input.Keys.UNKNOWN; }

    public static String name(int code) {
        if (isMouse(code)) {
            switch (code - MOUSE) {
                case Input.Buttons.LEFT: return "MOUSE LEFT"; case Input.Buttons.RIGHT: return "MOUSE RIGHT"; case Input.Buttons.MIDDLE: return "MOUSE MIDDLE";
                case Input.Buttons.BACK: return "MOUSE BACK"; case Input.Buttons.FORWARD: return "MOUSE FORWARD"; default: return "MOUSE " + (code - MOUSE);
            }
        }
        String n = Input.Keys.toString(code);
        return n == null ? "KEY " + code : n.toUpperCase(java.util.Locale.ROOT);
    }

    /** Name as stored on disk (stable across versions); {@code null} if unknown. */
    public static String token(int code) {
        if (isMouse(code)) return "MOUSE" + (code - MOUSE);
        String n = Input.Keys.toString(code);
        return n == null ? null : n.replace(' ', '_');
    }

    public static int parse(String token) {
        if (token == null || token.isEmpty()) return Input.Keys.UNKNOWN;
        if (token.startsWith("MOUSE")) { try { return mouse(Integer.parseInt(token.substring(5))); } catch (NumberFormatException e) { return Input.Keys.UNKNOWN; } }
        int k = Input.Keys.valueOf(token.replace('_', ' '));
        return k;
    }
}

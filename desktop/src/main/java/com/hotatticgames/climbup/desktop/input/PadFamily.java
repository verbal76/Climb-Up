package com.hotatticgames.climbup.desktop.input;

import java.util.Locale;

/** Controller family, used only to choose button names for prompts. Gameplay never branches on it. */
public enum PadFamily {
    XBOX, PLAYSTATION, SWITCH, GENERIC;

    public static PadFamily of(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (n.contains("xbox") || n.contains("xinput") || n.contains("microsoft")) return XBOX;
        if (n.contains("playstation") || n.contains("dualshock") || n.contains("dualsense") || n.contains("sony") || n.contains("ps4") || n.contains("ps5") || n.contains("ps3") || n.equals("wireless controller")) return PLAYSTATION;
        if (n.contains("nintendo") || n.contains("switch") || n.contains("pro controller") || n.contains("joy-con")) return SWITCH;
        return GENERIC;
    }

    /** The name printed on the controller for a logical control. Switch pads print their face buttons in swapped positions. */
    public String label(Ctl c) {
        boolean ps = this == PLAYSTATION, sw = this == SWITCH;
        switch (c) {
            case A: return ps ? "CROSS" : sw ? "B" : "A";
            case B: return ps ? "CIRCLE" : sw ? "A" : "B";
            case X: return ps ? "SQUARE" : sw ? "Y" : "X";
            case Y: return ps ? "TRIANGLE" : sw ? "X" : "Y";
            case BACK: return ps ? "SHARE" : sw ? "MINUS" : "VIEW";
            case START: return ps ? "OPTIONS" : sw ? "PLUS" : "MENU";
            case L1: return ps ? "L1" : sw ? "L" : "LB";
            case R1: return ps ? "R1" : sw ? "R" : "RB";
            case L2: return ps ? "L2" : sw ? "ZL" : "LT";
            case R2: return ps ? "R2" : sw ? "ZR" : "RT";
            case L3: return "L STICK CLICK";
            case R3: return "R STICK CLICK";
            case DPAD_UP: return "D-PAD UP";
            case DPAD_DOWN: return "D-PAD DOWN";
            case DPAD_LEFT: return "D-PAD LEFT";
            case DPAD_RIGHT: return "D-PAD RIGHT";
            case LS_UP: return "L STICK UP";
            case LS_DOWN: return "L STICK DOWN";
            case LS_LEFT: return "L STICK LEFT";
            case LS_RIGHT: return "L STICK RIGHT";
            case RS_UP: return "R STICK UP";
            case RS_DOWN: return "R STICK DOWN";
            case RS_LEFT: return "R STICK LEFT";
            default: return "R STICK RIGHT";
        }
    }
}

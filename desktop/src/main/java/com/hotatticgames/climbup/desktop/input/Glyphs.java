package com.hotatticgames.climbup.desktop.input;

import com.badlogic.gdx.Input;
import java.util.Locale;

/**
 * Which glyph image shows a control or key: the asset path under {@code assets/glyphs} (JulioCacko's CC0 "Input Prompts" pack, see
 * assets/licenses/JulioCacko_InputPrompts_License.txt), or {@code null} when the pack has no image for it (the text name is shown then).
 * Xbox, PlayStation and Switch pads each get their own family (a Switch pad's bottom button is its B, like {@link PadFamily#label});
 * a generic pad is drawn with the Xbox layout. Pure functions, so {@code GlyphsTest} can check every file exists without a screen.
 */
public final class Glyphs {
    private Glyphs() { }

    static String family(PadFamily f) { return f == PadFamily.PLAYSTATION ? "ps" : f == PadFamily.SWITCH ? "switch" : "xbox"; }

    /** The glyph for a physical control on this family of pad. The Switch set has no stick-click image. */
    public static String pad(PadFamily f, Ctl c) {
        if (f == PadFamily.SWITCH && (c == Ctl.L3 || c == Ctl.R3)) return null;
        return "glyphs/" + family(f) + "/" + c.name().toLowerCase(Locale.ROOT) + ".png";
    }

    /** The whole left stick (any direction) or the whole d-pad, for the MOVE prompt. */
    public static String stick(PadFamily f) { return "glyphs/" + family(f) + "/ls.png"; }
    public static String dpad(PadFamily f) { return "glyphs/" + family(f) + "/dpad.png"; }

    /** The glyph for a keyboard key or mouse button ({@link KeyCodes} code space); null for keys the pack does not draw (they show as text). */
    public static String key(int code) {
        if (KeyCodes.isMouse(code)) {
            switch (code - KeyCodes.MOUSE) {
                case Input.Buttons.LEFT: return "glyphs/key/mouse_left.png";
                case Input.Buttons.RIGHT: return "glyphs/key/mouse_right.png";
                case Input.Buttons.MIDDLE: return "glyphs/key/mouse_middle.png";
                default: return null;
            }
        }
        if (code >= Input.Keys.A && code <= Input.Keys.Z) return "glyphs/key/" + (char) ('a' + code - Input.Keys.A) + ".png";
        if (code >= Input.Keys.NUM_0 && code <= Input.Keys.NUM_9) { int d = code - Input.Keys.NUM_0; return d == 4 ? null : "glyphs/key/" + d + ".png"; }   // the pack ships no "4" key
        if (code >= Input.Keys.F1 && code <= Input.Keys.F12) return "glyphs/key/f" + (code - Input.Keys.F1 + 1) + ".png";
        switch (code) {
            case Input.Keys.SPACE: return "glyphs/key/space.png";
            case Input.Keys.ENTER: return "glyphs/key/enter.png";
            case Input.Keys.ESCAPE: return "glyphs/key/escape.png";
            case Input.Keys.SHIFT_LEFT: case Input.Keys.SHIFT_RIGHT: return "glyphs/key/shift.png";
            case Input.Keys.TAB: return "glyphs/key/tab.png";
            case Input.Keys.ALT_LEFT: case Input.Keys.ALT_RIGHT: return "glyphs/key/alt.png";
            case Input.Keys.CONTROL_LEFT: case Input.Keys.CONTROL_RIGHT: return "glyphs/key/ctrl.png";
            case Input.Keys.UP: return "glyphs/key/up.png";
            case Input.Keys.DOWN: return "glyphs/key/down.png";
            case Input.Keys.LEFT: return "glyphs/key/left.png";
            case Input.Keys.RIGHT: return "glyphs/key/right.png";
            case Input.Keys.BACKSPACE: return "glyphs/key/backspace.png";
            case Input.Keys.FORWARD_DEL: return "glyphs/key/delete.png";
            case Input.Keys.HOME: return "glyphs/key/home.png";
            case Input.Keys.END: return "glyphs/key/end.png";
            case Input.Keys.PAGE_UP: return "glyphs/key/pageup.png";
            case Input.Keys.PAGE_DOWN: return "glyphs/key/pagedown.png";
            default: return null;
        }
    }
}

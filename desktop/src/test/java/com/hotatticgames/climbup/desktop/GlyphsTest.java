package com.hotatticgames.climbup.desktop;

import static org.junit.Assert.*;

import com.badlogic.gdx.Input;
import com.hotatticgames.climbup.desktop.input.*;
import java.io.File;
import java.util.List;
import org.junit.Test;

/** Every control of every pad family and every default key has a glyph image in the assets (or is a known, documented gap), so prompts never point at a missing file. */
public class GlyphsTest {
    private static File root() { String p = System.getProperty("climb.assets"); File f = new File(p != null ? p : "../assets"); assertTrue(f.getAbsolutePath(), f.isDirectory()); return f; }

    @Test public void everyPadControlHasAGlyphExceptTheSwitchStickClicks() {
        for (PadFamily f : PadFamily.values()) for (Ctl c : Ctl.values()) {
            String path = Glyphs.pad(f, c);
            if (f == PadFamily.SWITCH && (c == Ctl.L3 || c == Ctl.R3)) { assertNull(f + " " + c, path); continue; }
            assertNotNull(f + " " + c, path);
            assertTrue(f + " " + c + " -> " + path, new File(root(), path).isFile());
        }
        for (PadFamily f : PadFamily.values()) { assertTrue(new File(root(), Glyphs.stick(f)).isFile()); assertTrue(new File(root(), Glyphs.dpad(f)).isFile()); }
        assertEquals("a generic pad is drawn with the Xbox layout", Glyphs.pad(PadFamily.XBOX, Ctl.A), Glyphs.pad(PadFamily.GENERIC, Ctl.A));
        assertTrue("a Switch pad's bottom button is its B", Glyphs.pad(PadFamily.SWITCH, Ctl.A).endsWith("/a.png"));     // the file named a.png holds the B artwork for the Switch family
    }

    @Test public void everyDefaultKeyboardBindingHasAGlyph() {
        Bindings<Integer> b = Defaults.keyboard();
        for (Act a : Act.values()) for (int k : b.get(a)) {
            String path = Glyphs.key(k);
            assertNotNull(a + " key " + KeyCodes.name(k), path);
            assertTrue(path, new File(root(), path).isFile());
        }
    }

    @Test public void lettersDigitsFunctionKeysAndMouseButtonsMapAndUnknownKeysFallBackToText() {
        for (int k = Input.Keys.A; k <= Input.Keys.Z; k++) assertTrue(new File(root(), Glyphs.key(k)).isFile());
        for (int k = Input.Keys.NUM_0; k <= Input.Keys.NUM_9; k++) { String p = Glyphs.key(k); if (k == Input.Keys.NUM_4) assertNull(p); else assertTrue(new File(root(), p).isFile()); }
        for (int k = Input.Keys.F1; k <= Input.Keys.F12; k++) assertTrue(new File(root(), Glyphs.key(k)).isFile());
        assertTrue(new File(root(), Glyphs.key(KeyCodes.mouse(Input.Buttons.LEFT))).isFile());
        assertNull(Glyphs.key(KeyCodes.mouse(Input.Buttons.FORWARD)));
        assertNull(Glyphs.key(Input.Keys.INSERT)); assertNull(Glyphs.key(Input.Keys.UNKNOWN));
        List<String> all = new java.util.ArrayList<>();
        for (String s : new String[]{"space", "enter", "escape", "shift", "tab", "alt", "ctrl", "up", "down", "left", "right", "backspace", "delete", "home", "end", "pageup", "pagedown"}) all.add("glyphs/key/" + s + ".png");
        for (String p : all) assertTrue(p, new File(root(), p).isFile());
    }
}

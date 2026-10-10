package com.hotatticgames.climbup.desktop;

import static org.junit.Assert.*;

import com.hotatticgames.climbup.desktop.input.*;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class PlatformMiscTest {
    @Test public void savesGoToAppDataOnWindowsNeverTheWorkingDirectory() {
        File f = DataDirs.resolve("Windows 11", "C:\\Users\\Kev\\AppData\\Roaming", null, "C:\\Users\\Kev");
        assertTrue(f.getPath().replace('\\', '/').endsWith("HotAtticGames/Upwardly"));
        assertTrue(f.getPath().startsWith("C:\\Users\\Kev\\AppData\\Roaming"));
        File g = DataDirs.resolve("Windows 10", null, null, "/home/x");
        assertTrue(g.getPath().replace('\\', '/').contains("AppData/Roaming/HotAtticGames/Upwardly"));
        assertTrue(DataDirs.resolve("Linux", null, null, "/home/x").getPath().endsWith("upwardly"));
        assertTrue(DataDirs.resolve("Linux", null, "/xdg", "/home/x").getPath().startsWith("/xdg"));
    }

    @Test public void padFamiliesAreRecognisedByName() {
        assertEquals(PadFamily.XBOX, PadFamily.of("Xbox 360 Controller"));
        assertEquals(PadFamily.XBOX, PadFamily.of("Xbox Wireless Controller"));
        assertEquals(PadFamily.PLAYSTATION, PadFamily.of("PS4 Controller"));
        assertEquals(PadFamily.PLAYSTATION, PadFamily.of("DualSense Wireless Controller"));
        assertEquals(PadFamily.PLAYSTATION, PadFamily.of("Wireless Controller"));
        assertEquals(PadFamily.SWITCH, PadFamily.of("Nintendo Switch Pro Controller"));
        assertEquals(PadFamily.GENERIC, PadFamily.of("Generic USB Joystick"));
        assertEquals(PadFamily.GENERIC, PadFamily.of(null));
    }

    @Test public void buttonNamesMatchEachFamily() {
        assertEquals("A", PadFamily.XBOX.label(Ctl.A)); assertEquals("CROSS", PadFamily.PLAYSTATION.label(Ctl.A));
        assertEquals("CIRCLE", PadFamily.PLAYSTATION.label(Ctl.B)); assertEquals("OPTIONS", PadFamily.PLAYSTATION.label(Ctl.START));
        assertEquals("B", PadFamily.SWITCH.label(Ctl.A));          // the Nintendo layout prints its labels in swapped positions
        for (PadFamily f : PadFamily.values()) for (Ctl c : Ctl.values()) assertFalse(f + " " + c, f.label(c).isEmpty());
    }

    @Test public void touchWordingBecomesDesktopPrompts() {
        String t = Prompts.rewrite("HOLD JUMP FOR A HIGHER LEAP. THE STICK STEERS IN THE AIR.", "SPACE", "X", "A/D", "W", "S", "A", "D");
        assertEquals("HOLD SPACE FOR A HIGHER LEAP. A/D STEERS IN THE AIR.", t);
        assertEquals("W/S CLIMBS. SPACE TO LEAP OFF.".length(), Prompts.rewrite("GRAB A ROPE. STICK UP/DOWN CLIMBS. JUMP TO LEAP OFF.", "SPACE", "X", "A/D", "W", "S", "A", "D").length() - "GRAB A ROPE. ".length() - 0);
        assertTrue(Prompts.rewrite("SPIKED CLUB! TAP SWING TO KNOCK", "SPACE", "CIRCLE", "m", "u", "d", "l", "r").contains("PRESS CIRCLE"));
        assertEquals("CONFIRM AGAIN: NEW CLIMB", Prompts.rewrite("TAP AGAIN: NEW CLIMB", "J", "S", "m", "u", "d", "l", "r"));
        assertEquals("HERO: SELECT TO CHANGE", Prompts.rewrite("HERO: TAP TO CHANGE", "J", "S", "m", "u", "d", "l", "r"));
        assertEquals("RED GEMS ARE CHECKPOINTS.", Prompts.rewrite("RED GEMS ARE CHECKPOINTS.", "J", "S", "m", "u", "d", "l", "r"));
    }
}

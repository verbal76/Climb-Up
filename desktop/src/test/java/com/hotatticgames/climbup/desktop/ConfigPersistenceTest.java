package com.hotatticgames.climbup.desktop;

import static org.junit.Assert.*;

import com.badlogic.gdx.Input.Keys;
import com.hotatticgames.climbup.desktop.input.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ConfigPersistenceTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private File file() { return new File(tmp.getRoot(), "upwardly-desktop.cfg"); }

    @Test public void customisedSettingsSurviveARestart() {
        DesktopConfig c = new DesktopConfig();
        c.keyboard.assign(Act.JUMP, 1, Keys.Z, false);
        c.keyboard.assign(Act.SWING, 2, Fakes.mouse(com.badlogic.gdx.Input.Buttons.RIGHT), false);
        Bindings<Ctl> p = c.editablePad("03000000-abc/def");
        p.assign(Act.JUMP, 0, Ctl.R1, true);
        c.setPadProfile("03000000-abc/def", p);
        c.padDefault.assign(Act.SWING, 0, Ctl.Y, true);
        c.inputMode = DesktopConfig.InputMode.CONTROLLER; c.setDeadzone(0.4f);
        c.display = DesktopConfig.DisplayMode.FULLSCREEN; c.width = 1920; c.height = 1080;
        assertTrue(c.save(file()));
        DesktopConfig r = DesktopConfig.load(file());
        assertEquals(c.keyboard, r.keyboard);
        assertEquals(Arrays.asList(Keys.SPACE, Keys.Z), r.keyboard.get(Act.JUMP));
        assertEquals(c.padDefault, r.padDefault);
        assertEquals(Arrays.asList(Ctl.R1), r.padFor("03000000-abc/def").get(Act.JUMP));
        assertEquals(Arrays.asList(Ctl.A), r.padFor("some-other-controller").get(Act.JUMP));     // an unknown controller falls back to the defaults
        assertEquals(DesktopConfig.InputMode.CONTROLLER, r.inputMode);
        assertEquals(0.4f, r.deadzone, 1e-4f);
        assertEquals(DesktopConfig.DisplayMode.FULLSCREEN, r.display);
        assertEquals(1920, r.width); assertEquals(1080, r.height);
    }

    @Test public void missingFileGivesDefaults() {
        DesktopConfig r = DesktopConfig.load(file());
        assertEquals(Defaults.keyboard(), r.keyboard);
        assertEquals(DesktopConfig.InputMode.AUTO, r.inputMode);
        assertEquals(DesktopConfig.DEADZONE_DEFAULT, r.deadzone, 1e-6f);
    }

    @Test public void aCorruptFileIsKeptAsEvidenceAndDefaultsAreUsed() throws Exception {
        Files.write(file().toPath(), new byte[]{0, 1, 2, (byte) 0xff, 0x7f});
        DesktopConfig r = DesktopConfig.load(file());
        assertEquals(Defaults.keyboard(), r.keyboard);
        assertTrue(new File(file().getPath() + ".corrupt").exists());
    }

    @Test public void aCorruptFileFallsBackToTheLastGoodBackup() throws Exception {
        DesktopConfig c = new DesktopConfig(); c.keyboard.assign(Act.JUMP, 1, Keys.Z, false);
        assertTrue(c.save(file()));
        c.setDeadzone(0.3f); assertTrue(c.save(file()));                        // the first save is now the .bak
        Files.write(file().toPath(), "garbage without the marker".getBytes(StandardCharsets.UTF_8));
        DesktopConfig r = DesktopConfig.load(file());
        assertEquals(Arrays.asList(Keys.SPACE, Keys.Z), r.keyboard.get(Act.JUMP));
    }

    @Test public void aFailedWriteLeavesThePreviousBindingsIntact() throws Exception {
        DesktopConfig c = new DesktopConfig(); c.keyboard.assign(Act.JUMP, 1, Keys.Z, false);
        assertTrue(c.save(file()));
        byte[] good = Files.readAllBytes(file().toPath());
        assertTrue(new File(file().getPath() + ".tmp").mkdir());                // the temp file cannot be created: the write must fail
        c.keyboard.assign(Act.JUMP, 2, Keys.K, false);
        assertFalse(c.save(file()));
        assertArrayEquals(good, Files.readAllBytes(file().toPath()));
        assertEquals(Arrays.asList(Keys.SPACE, Keys.Z), DesktopConfig.load(file()).keyboard.get(Act.JUMP));
    }

    @Test public void badLinesAndUnknownTokensNeverSpoilTheRest() {
        String text = "version=1\nkb.JUMP=NOT_A_KEY,SPACE\nkb.NOPE=A\nkb.LEFT=\ndeadzone=banana\nresolution=12x4\npad.default.JUMP=NOT_A_CTL\nrandom line\n";
        DesktopConfig r = DesktopConfig.parse(text);
        assertEquals(Arrays.asList(Keys.SPACE), r.keyboard.get(Act.JUMP));
        assertEquals(Defaults.keyboard().get(Act.LEFT), r.keyboard.get(Act.LEFT));      // an emptied action keeps its default
        assertEquals(DesktopConfig.DEADZONE_DEFAULT, r.deadzone, 1e-6f);
        assertEquals(1280, r.width);
        assertEquals(Defaults.pad().get(Act.JUMP), r.padDefault.get(Act.JUMP));
        assertTrue(r.keyboard.valid());
    }

    @Test public void aHandEditedFileWithTheSameKeyOnTwoActionsStillBehaves() {
        DesktopConfig r = DesktopConfig.parse("version=1\nkb.LEFT=A\nkb.RIGHT=A,D\n");
        assertTrue(r.keyboard.conflicts(Act.RIGHT, Keys.A).isEmpty() || r.keyboard.get(Act.LEFT).contains(Keys.A));
        assertFalse(r.keyboard.get(Act.RIGHT).contains(Keys.A) && r.keyboard.get(Act.LEFT).contains(Keys.A));
        assertTrue(r.keyboard.valid());
    }

    @Test public void reservedInputsInAFileAreIgnored() {
        DesktopConfig r = DesktopConfig.parse("version=1\nkb.JUMP=MOUSE0,F11\n");
        assertEquals(Defaults.keyboard().get(Act.JUMP), r.keyboard.get(Act.JUMP));
    }

    @Test public void deadZoneIsClampedToTheSupportedRange() {
        DesktopConfig c = new DesktopConfig();
        c.setDeadzone(5f); assertEquals(DesktopConfig.DEADZONE_MAX, c.deadzone, 1e-6f);
        c.setDeadzone(-1f); assertEquals(DesktopConfig.DEADZONE_MIN, c.deadzone, 1e-6f);
    }

    @Test public void restoreDefaultsRestoresOnlyTheChosenDevice() {
        DesktopConfig c = new DesktopConfig();
        c.keyboard.assign(Act.JUMP, 1, Keys.Z, false);
        Bindings<Ctl> p = c.editablePad("p1"); p.assign(Act.JUMP, 0, Ctl.R1, true); c.setPadProfile("p1", p);
        c.restoreKeyboard();
        assertEquals(Defaults.keyboard(), c.keyboard);
        assertEquals(Arrays.asList(Ctl.R1), c.padFor("p1").get(Act.JUMP));
        c.restorePad("p1");
        assertEquals(Defaults.pad(), c.padFor("p1"));
    }

    @Test public void padKeysAreSafeAsFileKeys() {
        assertEquals("a_b_c", DesktopConfig.padKey("a b/c"));
        assertTrue(DesktopConfig.padKey(new String(new char[300]).replace('\0', 'x')).length() <= 64);
    }
}

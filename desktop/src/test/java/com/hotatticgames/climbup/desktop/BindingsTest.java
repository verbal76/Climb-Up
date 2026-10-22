package com.hotatticgames.climbup.desktop;

import static org.junit.Assert.*;

import com.badlogic.gdx.Input.Keys;
import com.hotatticgames.climbup.desktop.input.*;
import java.util.Arrays;
import org.junit.Test;

public class BindingsTest {
    @Test public void defaultsFollowTheOwnersSpecification() {
        Bindings<Integer> k = Defaults.keyboard();
        assertEquals(Arrays.asList(Keys.A, Keys.LEFT), k.get(Act.LEFT));
        assertEquals(Arrays.asList(Keys.D, Keys.RIGHT), k.get(Act.RIGHT));
        assertEquals(Arrays.asList(Keys.SPACE), k.get(Act.JUMP));
        assertTrue(k.get(Act.PAUSE).contains(Keys.ESCAPE));
        assertEquals(Arrays.asList(Keys.W, Keys.UP), k.get(Act.MENU_UP));
        assertEquals(Arrays.asList(Keys.S, Keys.DOWN), k.get(Act.MENU_DOWN));
        assertEquals(Arrays.asList(Keys.ENTER, Keys.SPACE), k.get(Act.CONFIRM));
        assertEquals(Arrays.asList(Keys.ESCAPE), k.get(Act.BACK));
        assertTrue(k.valid());
        Bindings<Ctl> p = Defaults.pad();
        assertTrue(p.get(Act.JUMP).contains(Ctl.A));
        assertTrue(p.get(Act.CONFIRM).contains(Ctl.A));
        assertTrue(p.get(Act.BACK).contains(Ctl.B));
        assertTrue(p.get(Act.PAUSE).contains(Ctl.START));
        assertTrue(p.get(Act.LEFT).containsAll(Arrays.asList(Ctl.LS_LEFT, Ctl.DPAD_LEFT)));
        assertTrue(p.valid());
    }

    @Test public void defaultsHaveNoConflictInsideAContext() {
        for (Bindings<Integer> b : Arrays.asList(Defaults.keyboard()))
            for (Act a : Act.values()) for (int key : b.get(a)) assertTrue(a + " " + key, b.conflicts(a, key).isEmpty());
        Bindings<Ctl> p = Defaults.pad();
        for (Act a : Act.values()) for (Ctl c : p.get(a)) assertTrue(a + " " + c, p.conflicts(a, c).isEmpty());
    }

    @Test public void sameInputOnGameAndMenuIsNotAConflictButWithinAContextIs() {
        Bindings<Integer> k = Defaults.keyboard();
        assertTrue(k.conflicts(Act.JUMP, Keys.ENTER).isEmpty());                    // ENTER confirms in menus, jump is gameplay
        assertEquals(Arrays.asList(Act.LEFT), k.conflicts(Act.JUMP, Keys.A));          // A moves left
        assertEquals(Arrays.asList(Act.CONFIRM), k.conflicts(Act.BACK, Keys.ENTER));
    }

    @Test public void conflictIsReportedAndNothingChangesUnlessTheTakeoverIsAllowed() {
        Bindings<Integer> k = Defaults.keyboard();
        Bindings<Integer> before = k.copy();
        assertEquals(Bindings.Result.CONFLICT, k.assign(Act.JUMP, 1, Keys.A, false));
        assertEquals(before, k);
        assertEquals(Bindings.Result.OK, k.assign(Act.JUMP, 1, Keys.A, true));
        assertEquals(Arrays.asList(Keys.SPACE, Keys.A), k.get(Act.JUMP));
        assertEquals(Arrays.asList(Keys.LEFT), k.get(Act.LEFT));                      // taken from LEFT, which keeps its other key
        assertTrue(k.valid());
    }

    @Test public void aTakeoverThatWouldLeaveAnotherActionWithNothingIsRefused() {
        Bindings<Integer> k = Defaults.keyboard();
        Bindings<Integer> before = k.copy();
        assertEquals(Bindings.Result.WOULD_ORPHAN, k.assign(Act.LEFT, 0, Keys.SPACE, true));       // SPACE is JUMP's only key
        assertEquals(before, k);
    }

    @Test public void secondaryBindingsAreNeverSilentlyErased() {
        Bindings<Integer> k = Defaults.keyboard();
        assertEquals(Bindings.Result.OK, k.assign(Act.LEFT, 0, Keys.Q, false));       // replaces only slot 0
        assertEquals(Arrays.asList(Keys.Q, Keys.LEFT), k.get(Act.LEFT));
        assertEquals(Bindings.Result.OK, k.assign(Act.LEFT, 2, Keys.Z, false));       // appends a third
        assertEquals(3, k.get(Act.LEFT).size());
        assertEquals(Bindings.Result.BAD_SLOT, k.assign(Act.LEFT, 3, Keys.C, false));
    }

    @Test public void theLastBindingOfAnActionCannotBeRemoved() {
        Bindings<Integer> k = Defaults.keyboard();
        assertFalse(k.clearSlot(Act.JUMP, 0));
        assertTrue(k.clearSlot(Act.LEFT, 1));
        assertFalse(k.clearSlot(Act.LEFT, 0));
        assertTrue(k.valid());
    }

    @Test public void mouseButtonsAreBindableExceptLeftClickWhichStaysForPointing() {
        Bindings<Integer> k = Defaults.keyboard();
        assertFalse(KeyCodes.reserved(Fakes.mouse(com.badlogic.gdx.Input.Buttons.RIGHT)));
        assertTrue(KeyCodes.reserved(Fakes.mouse(com.badlogic.gdx.Input.Buttons.LEFT)));
        assertEquals(Bindings.Result.OK, k.assign(Act.SWING, 2, Fakes.mouse(com.badlogic.gdx.Input.Buttons.RIGHT), false));
        assertEquals("MOUSE RIGHT", KeyCodes.name(Fakes.mouse(com.badlogic.gdx.Input.Buttons.RIGHT)));
    }

    @Test public void keyTokensRoundTripForEveryKeyLibGdxNames() {
        int n = 0;
        for (int code = 1; code < 256; code++) {
            String t = KeyCodes.token(code);
            if (t == null) continue;
            assertEquals("code " + code + " token " + t, code, KeyCodes.parse(t)); n++;
        }
        assertTrue(n > 100);
        for (int b = 0; b < 5; b++) assertEquals(Fakes.mouse(b), KeyCodes.parse(KeyCodes.token(Fakes.mouse(b))));
    }
}

package com.hotatticgames.climbup.desktop;

import static org.junit.Assert.*;

import com.badlogic.gdx.Input.Keys;
import com.hotatticgames.climbup.desktop.Fakes.FakeSources;
import com.hotatticgames.climbup.desktop.input.*;
import java.util.Arrays;
import org.junit.Test;

public class FocusAndRemapTest {
    // ---------------------------------------------------------------- focus model (spatial navigation)

    private FocusModel column() {
        FocusModel m = new FocusModel();
        m.beginFrame(); m.add("PLAY", 40, 400, 400, 76, true); m.add("SETTINGS", 40, 312, 400, 76, false); m.add("CREDITS", 40, 224, 400, 76, false); m.add("HERO", 800, 400, 400, 76, false); m.endFrame();
        return m;
    }

    @Test public void theAccentButtonStartsFocused() { assertEquals(0, column().focus); }

    @Test public void downAndUpWalkTheColumnAndStopAtTheEnds() {
        FocusModel m = column();
        assertTrue(m.move(1)); assertEquals(1, m.focus);
        assertTrue(m.move(1)); assertEquals(2, m.focus);
        assertFalse(m.move(1)); assertEquals(2, m.focus);
        assertTrue(m.move(0)); assertEquals(1, m.focus);
    }

    @Test public void rightReachesTheButtonOnTheOtherSideOfTheScreenAndLeftComesBack() {
        FocusModel m = column();
        assertTrue(m.move(3)); assertEquals(3, m.focus);
        assertTrue(m.move(2)); assertEquals(0, m.focus);
    }

    @Test public void focusIsClampedWhenButtonsDisappear() {
        FocusModel m = column(); m.move(1); m.move(1);
        m.beginFrame(); m.add("ONLY", 40, 400, 400, 76, false); m.endFrame();
        assertEquals(0, m.focus);
    }

    @Test public void hoveringFocusesTheButtonUnderThePointer() {
        FocusModel m = column(); m.focusAt(100, 340); assertEquals(1, m.focus);
    }

    // ---------------------------------------------------------------- menu navigator over the input manager

    private static final class Rig {
        final DesktopConfig cfg = new DesktopConfig(); final FakeSources src = new FakeSources(); final InputManager im = new InputManager(cfg, src); final MenuNav nav = new MenuNav(im);
        void frame(float dt) { im.update(dt); nav.frame(dt, src.takeScroll()); }
        int[] draw() { int[] f = new int[4]; String[] l = {"PLAY", "SETTINGS", "CREDITS", "BACK"}; float[] y = {400, 312, 224, 20};
            for (int i = 0; i < 4; i++) f[i] = nav.button(l[i], 40, y[i], 400, 76, i == 0); nav.endFrame(); src.endFrame(); return f; }
    }

    @Test public void keyboardOnlyNavigationReachesAndActivatesAButton() {
        Rig r = new Rig(); r.nav.reset(); r.frame(0.5f); r.draw(); r.frame(0.5f); r.draw();
        r.src.tapKey(Keys.S); r.frame(0.016f); r.draw();                 // down
        r.src.tapKey(Keys.ENTER); r.frame(0.016f);
        int[] f = r.draw();
        assertEquals("SETTINGS is focused and activated", 3, f[1]);
        assertEquals(0, f[0] & 2);
    }

    @Test public void aButtonAppearingUnderAHeldJumpKeyIsNotPressedByIt() {
        Rig r = new Rig();
        r.frame(0.016f); r.nav.endFrame();                               // a frame with no buttons (playing)
        r.src.tapKey(Keys.SPACE); r.frame(0.016f);                       // the menu appears while SPACE (a confirm key) is pressed
        int[] f = r.draw();
        assertEquals("not activated", 0, f[0] & 2);
    }

    @Test public void escapeActivatesTheBackButton() {
        Rig r = new Rig(); r.frame(0.5f); r.draw(); r.frame(0.5f); r.draw();
        r.src.tapKey(Keys.ESCAPE); r.frame(0.016f);
        assertEquals(2, r.draw()[3] & 2);
    }

    @Test public void controllerOnlyNavigation() {
        Rig r = new Rig(); Fakes.FakePad p = new Fakes.FakePad("p", "Xbox Controller"); r.src.pads.add(p);
        r.frame(0.5f); r.draw(); r.frame(0.5f); r.draw();
        p.set(Ctl.DPAD_DOWN, 1f); r.frame(0.016f); r.draw(); p.set(Ctl.DPAD_DOWN, 0f); r.frame(0.016f); r.draw();
        p.set(Ctl.A, 1f); r.frame(0.016f);
        assertEquals(3, r.draw()[1]);
    }

    @Test public void volumeStyleRowsAdjustWithLeftAndRight() {
        Rig r = new Rig(); r.nav.reset(); r.frame(0.5f);
        String[] l = {"-", "+"}; float[] x = {600, 900};
        for (int i = 0; i < 2; i++) r.nav.button(l[i], x[i], 300, 86, 64, false); r.nav.endFrame(); r.src.endFrame();
        r.src.tapKey(Keys.D); r.frame(0.016f);
        int a = r.nav.button("-", 600, 300, 86, 64, false), b = r.nav.button("+", 900, 300, 86, 64, false);
        assertEquals(0, a & 2); assertEquals(2, b & 2);
    }

    @Test public void mouseHoverFocusesAndTheRingShows() {
        Rig r = new Rig(); r.frame(0.5f); r.draw();
        r.nav.pointer(100, 340, true); r.frame(0.016f);
        int[] f = r.draw();
        assertEquals(1, f[1] & 1);
        assertTrue(r.nav.visible);
    }

    @Test public void theMouseWheelMovesThroughMenus() {
        Rig r = new Rig(); r.frame(0.5f); r.draw();
        r.src.scroll = 1f; r.frame(0.016f); int[] f = r.draw();
        assertEquals(0, f[0] & 1);
    }

    // ---------------------------------------------------------------- rebinding dialog

    @Test public void rebindingFlowWaitsProposesDetectsAConflictAndSaves() {
        Bindings<Integer> k = Defaults.keyboard(); Remapper<Integer> r = new Remapper<>(k);
        r.begin(Act.JUMP, 1);
        assertEquals(Remapper.State.WAITING, r.state);
        assertTrue(r.capture(Keys.A, false));
        assertEquals(Remapper.State.PROPOSED, r.state);
        assertTrue(r.hasConflict()); assertEquals(Arrays.asList(Act.LEFT), r.conflicts);
        assertEquals("nothing changes until the player confirms", Arrays.asList(Keys.SPACE), k.get(Act.JUMP));
        assertEquals(Bindings.Result.OK, r.confirm());
        assertEquals(Arrays.asList(Keys.SPACE, Keys.A), k.get(Act.JUMP));
        assertEquals(Remapper.State.IDLE, r.state);
    }

    @Test public void cancellingLeavesTheBindingsExactlyAsTheyWere() {
        Bindings<Integer> k = Defaults.keyboard(); Bindings<Integer> before = k.copy(); Remapper<Integer> r = new Remapper<>(k);
        r.begin(Act.JUMP, 0); r.capture(Keys.K, false); r.cancel();
        assertEquals(before, k); assertEquals(Remapper.State.IDLE, r.state);
        r.begin(Act.JUMP, 0); r.cancel(); assertEquals(before, k);
    }

    @Test public void reservedInputsAreRefusedAndTheDialogKeepsWaiting() {
        Remapper<Integer> r = new Remapper<>(Defaults.keyboard()); r.begin(Act.JUMP, 0);
        assertFalse(r.capture(Keys.F11, Keysreserved(Keys.F11)));
        assertEquals(Remapper.State.WAITING, r.state); assertFalse(r.message.isEmpty());
    }
    private static boolean Keysreserved(int k) { return com.hotatticgames.climbup.desktop.input.KeyCodes.reserved(k); }

    @Test public void aConfirmThatWouldOrphanAnotherActionStaysOpenWithAMessage() {
        Bindings<Integer> k = Defaults.keyboard(); Remapper<Integer> r = new Remapper<>(k);
        r.begin(Act.LEFT, 0); r.capture(Keys.SPACE, false);
        assertEquals(Bindings.Result.WOULD_ORPHAN, r.confirm());
        assertEquals(Remapper.State.PROPOSED, r.state); assertTrue(r.message.contains("JUMP"));
        assertEquals(Defaults.keyboard(), k);
    }

    @Test public void controllerAnalogDirectionsAreBindableWithTheirPolarity() {
        Bindings<Ctl> b = Defaults.pad(); Remapper<Ctl> r = new Remapper<>(b);
        r.begin(Act.RIGHT, 2); assertTrue(r.capture(Ctl.RS_RIGHT, false)); assertEquals(Bindings.Result.OK, r.confirm());
        assertTrue(b.get(Act.RIGHT).contains(Ctl.RS_RIGHT)); assertFalse(b.get(Act.RIGHT).contains(Ctl.RS_LEFT));
        assertTrue(Ctl.RS_RIGHT.analog()); assertFalse(Ctl.A.analog());
    }

    @Test public void retryReturnsToWaiting() {
        Remapper<Integer> r = new Remapper<>(Defaults.keyboard()); r.begin(Act.JUMP, 0); r.capture(Keys.K, false); r.retry();
        assertEquals(Remapper.State.WAITING, r.state); assertNull(r.proposed);
    }
}

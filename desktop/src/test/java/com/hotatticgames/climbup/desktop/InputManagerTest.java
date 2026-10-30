package com.hotatticgames.climbup.desktop;

import static org.junit.Assert.*;

import com.badlogic.gdx.Input.Keys;
import com.hotatticgames.climbup.desktop.Fakes.FakePad;
import com.hotatticgames.climbup.desktop.Fakes.FakeSources;
import com.hotatticgames.climbup.desktop.input.*;
import com.hotatticgames.climbup.sim.InputState;
import org.junit.Before;
import org.junit.Test;

public class InputManagerTest {
    private DesktopConfig cfg; private FakeSources src; private InputManager im; private final InputState in = new InputState();
    private static final float DT = 1f / 60f;

    @Before public void setUp() { cfg = new DesktopConfig(); src = new FakeSources(); im = new InputManager(cfg, src); }

    private void frame() { im.update(DT); src.endFrame(); }
    private void frames(int n) { for (int i = 0; i < n; i++) frame(); }
    private InputState read() { in.moveX = in.moveY = 0; in.jumpHeld = in.jumpPressed = in.swingPressed = false; im.read(in); return in; }

    // ---------------------------------------------------------------- keyboard

    @Test public void keyboardMovesLeftAndRightWithAandDandTheArrows() {
        src.press(Keys.D); im.update(DT); assertEquals(1f, read().moveX, 0); src.release(Keys.D); src.endFrame();
        src.press(Keys.A); im.update(DT); assertEquals(-1f, read().moveX, 0); src.release(Keys.A); src.endFrame();
        src.press(Keys.RIGHT); im.update(DT); assertEquals(1f, read().moveX, 0); src.release(Keys.RIGHT); src.endFrame();
        src.press(Keys.LEFT); im.update(DT); assertEquals(-1f, read().moveX, 0);
        src.press(Keys.RIGHT); im.update(DT); assertEquals("both directions cancel", 0f, read().moveX, 0);
    }

    @Test public void keyboardClimbsWithWandSAndUpDown() {
        src.press(Keys.W); im.update(DT); assertEquals(1f, read().moveY, 0); src.release(Keys.W); src.endFrame();
        src.press(Keys.DOWN); im.update(DT); assertEquals(-1f, read().moveY, 0);
    }

    @Test public void spaceJumpsAndHoldingSpaceKeepsJumpHeldWithOneJumpPress() {
        src.press(Keys.SPACE); im.update(DT); src.endFrame();
        InputState s = read(); assertTrue(s.jumpPressed); assertTrue(s.jumpHeld);
        im.stepDone();
        frames(5);
        s = read(); assertFalse("one press only", s.jumpPressed); assertTrue(s.jumpHeld);
        src.release(Keys.SPACE); frame();
        assertFalse(read().jumpHeld);
    }

    @Test public void aJumpTappedBetweenTwoSimulationStepsIsNotLost() {
        src.tapKey(Keys.SPACE); frame();                       // pressed and released within one frame
        frames(3);                                             // several frames pass with no simulation step (accumulator not ready)
        assertTrue("the latched press waits for the next step", read().jumpPressed);
        im.stepDone();
        assertFalse(read().jumpPressed);
    }

    @Test public void clearDropsALatchedPressAcrossPauseAndResume() {
        src.tapKey(Keys.SPACE); frame(); im.clear();
        assertFalse(read().jumpPressed);
    }

    @Test public void escapePausesAndBacks() {
        src.tapKey(Keys.ESCAPE); im.update(DT);
        assertTrue(im.pausePressed()); assertTrue(im.backPressed());
        src.endFrame(); im.update(DT);
        assertFalse(im.pausePressed());
    }

    @Test public void swingKeyLatchesLikeJump() {
        src.tapKey(Keys.X); frame();
        assertTrue(read().swingPressed); im.stepDone(); assertFalse(read().swingPressed);
    }

    @Test public void mouseMovementAndClicksNeverSteerTheHero() {
        src.mx = 400; src.my = 300; frame(); src.mx = 900; src.my = 500; frame();
        src.press(Fakes.mouse(com.badlogic.gdx.Input.Buttons.LEFT)); im.update(DT);
        InputState s = read();
        assertEquals(0f, s.moveX, 0); assertEquals(0f, s.moveY, 0); assertFalse(s.jumpHeld); assertFalse(s.jumpPressed);
    }

    @Test public void aRemappedMouseButtonCanJump() {
        cfg.keyboard.assign(Act.JUMP, 1, Fakes.mouse(com.badlogic.gdx.Input.Buttons.RIGHT), false);
        src.press(Fakes.mouse(com.badlogic.gdx.Input.Buttons.RIGHT)); im.update(DT);
        assertTrue(read().jumpPressed);
    }

    // ---------------------------------------------------------------- custom bindings drive the game

    @Test public void aRemappedKeyDrivesGameplayImmediatelyAndTheOldOneStillWorksIfKept() {
        cfg.keyboard.assign(Act.JUMP, 0, Keys.K, false);
        src.press(Keys.SPACE); im.update(DT); assertFalse("SPACE no longer jumps", read().jumpHeld); src.release(Keys.SPACE); src.endFrame();
        src.press(Keys.K); im.update(DT); assertTrue(read().jumpHeld);
    }

    @Test public void menuNavigationSurvivesEveryMenuKeyBeingRemapped() {
        for (Act a : new Act[]{Act.MENU_UP, Act.MENU_DOWN, Act.MENU_LEFT, Act.MENU_RIGHT, Act.CONFIRM, Act.BACK}) cfg.keyboard.set(a, java.util.Arrays.asList(Keys.F1));   // worst case: one useless key each
        src.tapKey(Keys.DOWN); im.update(DT); assertTrue("arrow keys always navigate", im.menuPressed(Act.MENU_DOWN)); src.endFrame();
        src.tapKey(Keys.ENTER); im.update(DT); assertTrue("enter always confirms", im.justPressed(Act.CONFIRM)); src.endFrame();
        src.tapKey(Keys.ESCAPE); im.update(DT); assertTrue("escape always goes back", im.justPressed(Act.BACK));
    }

    // ---------------------------------------------------------------- controller

    private FakePad connect(String id, String name) { FakePad p = new FakePad(id, name); src.pads.add(p); return p; }

    @Test public void xboxLeftStickMovesProportionally() {
        FakePad p = connect("x1", "Xbox Wireless Controller"); frame();
        float dz = cfg.deadzone;
        p.set(Ctl.LS_RIGHT, 0.1f); im.update(DT); assertEquals("inside the dead zone", 0f, read().moveX, 0);
        p.set(Ctl.LS_RIGHT, 0.4f); im.update(DT); assertEquals("a small tilt is a small move (no 0.35 floor jump)", (0.4f - dz) / (1f - dz), read().moveX, 1e-5f);
        p.set(Ctl.LS_RIGHT, 0.625f); im.update(DT); assertEquals("a firm tilt scales straight toward full", (0.625f - dz) / (1f - dz), read().moveX, 1e-5f);
        p.set(Ctl.LS_RIGHT, 1f); im.update(DT); assertEquals("a full push is full speed", 1f, read().moveX, 1e-6f);
        p.set(Ctl.LS_RIGHT, 0f); p.set(Ctl.LS_LEFT, 1f); im.update(DT); assertEquals(-1f, read().moveX, 1e-6f);
    }

    @Test public void dpadMovesAtFullSpeedAndAJumps() {
        FakePad p = connect("x1", "Xbox Controller"); frame();
        p.set(Ctl.DPAD_RIGHT, 1f); im.update(DT); assertEquals(1f, read().moveX, 0);
        p.set(Ctl.DPAD_RIGHT, 0f); p.set(Ctl.A, 1f); im.update(DT);
        InputState s = read(); assertTrue(s.jumpPressed); assertTrue(s.jumpHeld);
    }

    @Test public void aShortControllerTapBetweenFramesStillJumps() {
        FakePad p = connect("x1", "Xbox Controller"); frame();
        p.tap(Ctl.A); frame();                                // pressed and released between two polls: only the event latch saw it
        assertTrue(read().jumpPressed);
    }

    @Test public void startPausesAndBBacks() {
        FakePad p = connect("x1", "Xbox Controller"); frame();
        p.set(Ctl.START, 1f); im.update(DT); assertTrue(im.pausePressed());
        p.set(Ctl.START, 0f); src.endFrame(); im.update(DT);
        p.set(Ctl.B, 1f); im.update(DT); assertTrue(im.backPressed());
    }

    @Test public void stickUpClimbsProportionallyAndDownDrops() {
        FakePad p = connect("x1", "Pad"); frame();
        float dz = cfg.deadzone;
        p.set(Ctl.LS_UP, 0.5f); im.update(DT); assertEquals("a gentle up-tilt climbs slowly", (0.5f - dz) / (1f - dz), read().moveY, 1e-5f);
        p.set(Ctl.LS_UP, 1f); im.update(DT); assertEquals("a full up-tilt climbs at full speed", 1f, read().moveY, 1e-6f);
        p.set(Ctl.LS_UP, 0f); p.set(Ctl.LS_DOWN, 0.9f); im.update(DT); assertEquals("down is negative (descend/drop)", -(0.9f - dz) / (1f - dz), read().moveY, 1e-5f);
    }

    @Test public void theAdjustableDeadZoneIgnoresDrift() {
        FakePad p = connect("x1", "Pad"); cfg.setDeadzone(0.3f); frame();
        p.set(Ctl.LS_RIGHT, 0.28f); im.update(DT); assertEquals(0f, read().moveX, 0);
        cfg.setDeadzone(0.1f); im.update(DT); assertTrue(read().moveX > 0f);
    }

    @Test public void keyboardWinsWhenBothDevicesPushAtOnceAndTheJumpFiresOnce() {
        FakePad p = connect("x1", "Pad"); frame();
        src.press(Keys.A); p.set(Ctl.LS_RIGHT, 1f); src.press(Keys.SPACE); p.set(Ctl.A, 1f); im.update(DT);
        InputState s = read();
        assertEquals(-1f, s.moveX, 0); assertTrue(s.jumpPressed);
        im.stepDone(); src.endFrame(); im.update(DT);
        assertFalse("holding both does not jump twice", read().jumpPressed);
    }

    @Test public void aRemappedControllerButtonDrivesGameplayForThatControllerOnly() {
        FakePad a = connect("pad-a", "Pad A"), b = connect("pad-b", "Pad B");
        Bindings<Ctl> mine = cfg.editablePad("pad-a"); mine.assign(Act.JUMP, 0, Ctl.R1, true); cfg.setPadProfile("pad-a", mine);
        frame();
        a.set(Ctl.A, 1f); im.update(DT); assertFalse("A no longer jumps on pad A", read().jumpHeld); a.set(Ctl.A, 0f); frame();
        a.set(Ctl.R1, 1f); im.update(DT); assertTrue(read().jumpHeld); a.set(Ctl.R1, 0f); frame();
        b.set(Ctl.A, 1f); im.update(DT); assertTrue("pad B still has the defaults", read().jumpHeld);
    }

    @Test public void aRemappedAnalogDirectionIsHonoured() {
        FakePad p = connect("x1", "Pad");
        Bindings<Ctl> b = cfg.editablePad("x1"); b.assign(Act.RIGHT, 0, Ctl.RS_RIGHT, true); cfg.setPadProfile("x1", b); frame();
        p.set(Ctl.RS_RIGHT, 1f); im.update(DT); assertEquals(1f, read().moveX, 0);
        p.set(Ctl.RS_RIGHT, 0f); p.set(Ctl.RS_LEFT, 1f); im.update(DT); assertEquals("polarity matters", 0f, read().moveX, 0);
    }

    // ---------------------------------------------------------------- discovery, switching, prompts

    @Test public void noControllerMeansKeyboardPromptsAndNoSetup() {
        frame();
        assertNull(im.activePad());
        assertEquals(InputManager.Device.KEYBOARD, im.promptDevice());
        assertEquals("SPACE", im.prompt(Act.JUMP));
        assertEquals("A/D", im.promptMove());
        assertEquals("ESCAPE", im.prompt(Act.PAUSE));
    }

    @Test public void aControllerPluggedInMidGameIsPickedUpWithoutDisturbingTheRun() {
        src.press(Keys.D); im.update(DT);
        assertEquals(1f, read().moveX, 0);
        FakePad p = connect("x1", "Xbox Controller"); im.update(DT);
        assertEquals("the held key still drives movement", 1f, read().moveX, 0);
        assertSame(p, im.activePad());
        assertTrue(im.controllerEvent);
        assertEquals("plugging in alone does not change the prompts", InputManager.Device.KEYBOARD, im.promptDevice());
    }

    @Test public void usingTheControllerSwitchesPromptsToItsNamesAndBack() {
        FakePad p = connect("x1", "Xbox Controller"); frame();
        p.set(Ctl.A, 1f); frames(2);
        assertEquals(InputManager.Device.CONTROLLER, im.promptDevice());
        assertEquals("A", im.prompt(Act.JUMP));
        assertEquals("L STICK", im.promptMove());
        p.set(Ctl.A, 0f);
        for (int i = 0; i < 30; i++) frame();                  // past the debounce
        src.press(Keys.SPACE); frames(2);
        assertEquals(InputManager.Device.KEYBOARD, im.promptDevice());
        assertEquals("SPACE", im.prompt(Act.JUMP));
    }

    @Test public void playStationControllersShowCrossAndCircleAndSharePause() {
        FakePad p = connect("ps1", "PS5 Controller"); frame();
        p.set(Ctl.A, 1f); frames(2);
        assertEquals(PadFamily.PLAYSTATION, im.family());
        assertEquals("CROSS", im.prompt(Act.JUMP));
        assertEquals("SHARE", im.prompt(Act.PAUSE));   // pause is the View/Share button now (Options/Menu still works as a secondary)
        assertEquals("CIRCLE", im.prompt(Act.BACK));
    }

    @Test public void thePromptFollowsARemappedBinding() {
        cfg.keyboard.assign(Act.JUMP, 0, Keys.K, false);
        frame(); assertEquals("K", im.prompt(Act.JUMP));
        FakePad p = connect("x1", "Xbox Controller");
        Bindings<Ctl> b = cfg.editablePad("x1"); b.assign(Act.JUMP, 0, Ctl.Y, true); cfg.setPadProfile("x1", b);
        frame(); p.set(Ctl.Y, 1f); frames(2);
        assertEquals("Y", im.prompt(Act.JUMP));
    }

    @Test public void stickDriftNeverFlipsThePromptsBackAndForth() {
        FakePad p = connect("x1", "Xbox Controller"); frame();
        p.set(Ctl.LS_LEFT, 0.33f);                             // a worn stick resting off centre
        for (int i = 0; i < 600; i++) frame();
        assertEquals(InputManager.Device.KEYBOARD, im.promptDevice());
        p.set(Ctl.LS_LEFT, 0.05f + 0.1f * (float) Math.sin(1)); frames(60);
        assertEquals(InputManager.Device.KEYBOARD, im.promptDevice());
        p.set(Ctl.LS_LEFT, 0.95f); frames(2);                  // a deliberate push does switch
        assertEquals(InputManager.Device.CONTROLLER, im.promptDevice());
    }

    @Test public void tinyMouseJitterDoesNotSwitchBackToKeyboardPrompts() {
        src.mx = 500; src.my = 300; frame();
        FakePad p = connect("x1", "Xbox Controller"); frame();
        p.set(Ctl.A, 1f); frames(2); p.set(Ctl.A, 0f);
        for (int i = 0; i < 30; i++) frame();
        for (int i = 0; i < 100; i++) { src.mx = 500 + (i % 2); frame(); }
        assertEquals(InputManager.Device.CONTROLLER, im.promptDevice());
        src.mx = 900; frames(2);
        assertEquals("a real mouse movement is meaningful", InputManager.Device.KEYBOARD, im.promptDevice());
    }

    @Test public void disconnectingTheControllerKeepsKeyboardAndMouseUsableAndThePromptsFollow() {
        FakePad p = connect("x1", "Xbox Controller"); frame();
        p.set(Ctl.A, 1f); frames(2); p.set(Ctl.A, 0f); frame();
        assertEquals(InputManager.Device.CONTROLLER, im.promptDevice());
        p.connected = false; src.pads.clear(); im.controllerEvent = false; frame();
        assertNull(im.activePad());
        assertTrue(im.controllerEvent);
        assertEquals(InputManager.Device.KEYBOARD, im.promptDevice());
        src.press(Keys.D); frame();
        assertEquals(1f, read().moveX, 0);
    }

    @Test public void unpluggingTheControllerInUseRaisesControllerLostOnce() {
        FakePad p = connect("x1", "Xbox Controller"); frame();
        p.set(Ctl.A, 1f); frames(2); p.set(Ctl.A, 0f); frame();
        assertEquals(InputManager.Device.CONTROLLER, im.promptDevice());
        assertFalse("nothing lost while it is plugged in", im.controllerLost());
        p.connected = false; src.pads.clear(); frame();
        assertTrue("the pad the player was using is gone: the play screen pauses on this", im.controllerLost());
        assertFalse("reported once, not every frame", im.controllerLost());
        frame();
        assertFalse(im.controllerLost());
    }

    @Test public void removingAControllerNobodyWasUsingRaisesNothing() {
        FakePad p = connect("x1", "Xbox Controller"); frame();
        src.press(Keys.D); frame(); src.release(Keys.D); frame();        // the keyboard is the device in use
        assertEquals(InputManager.Device.KEYBOARD, im.promptDevice());
        p.connected = false; src.pads.clear(); frame();
        assertFalse("a spare pad unplugged while the keyboard is in use is not an interruption", im.controllerLost());
    }

    @Test public void aReconnectedControllerIsPickedUpAgainAndItsPromptsReturn() {
        FakePad p = connect("x1", "Xbox Controller"); frame();
        p.set(Ctl.A, 1f); frames(2); p.set(Ctl.A, 0f); frame();
        p.connected = false; src.pads.clear(); frame(); im.controllerLost();
        assertNull(im.activePad());
        frames(20);
        FakePad again = connect("x1", "Xbox Controller"); frame();
        assertSame("the same pad plugged back in is active again", again, im.activePad());
        again.set(Ctl.A, 1f); frames(2); again.set(Ctl.A, 0f); frame();
        assertEquals("prompts are back on the controller", InputManager.Device.CONTROLLER, im.promptDevice());
        assertEquals("A", im.prompt(Act.JUMP));
    }

    @Test public void theSteamDeckAndSteamInputPadsUseTheXboxLayout() {
        assertEquals(PadFamily.XBOX, PadFamily.of("Steam Deck Controller"));
        assertEquals(PadFamily.XBOX, PadFamily.of("Steam Virtual Gamepad"));
        assertEquals(PadFamily.XBOX, PadFamily.of("Valve Software Steam Controller"));
        assertEquals(PadFamily.PLAYSTATION, PadFamily.of("Sony DualSense Wireless Controller"));
        assertEquals(PadFamily.SWITCH, PadFamily.of("Nintendo Switch Pro Controller"));
        assertEquals(PadFamily.GENERIC, PadFamily.of("USB Gamepad"));
        assertEquals("a generic pad falls back to the Xbox labels", "A", PadFamily.GENERIC.label(Ctl.A));
    }

    @Test public void withTwoControllersTheLastOneUsedWinsAndTheirInputsAreNeverCombined() {
        FakePad a = connect("a", "Pad A"), b = connect("b", "Pad B"); frame();
        assertSame("the first connected is the default", a, im.activePad());
        b.set(Ctl.LS_RIGHT, 1f); im.update(DT);
        assertSame("a deliberate push on B makes B the active controller", b, im.activePad());
        a.set(Ctl.LS_LEFT, 0.8f);                              // A drifts hard but B stays the one in control until A does something new and deliberate
        im.update(DT);
        // Only the active pad steers: moveX is A's solo proportional value, never A summed with B's opposite push (which would land near +0.27).
        assertEquals("only one controller can steer: no summing of opposite stick pushes", -(0.8f - cfg.deadzone) / (1f - cfg.deadzone), read().moveX, 1e-5f);
    }

    @Test public void ifTheActiveControllerVanishesTheNextOneTakesOver() {
        FakePad a = connect("a", "Pad A"), b = connect("b", "Pad B"); frame();
        a.set(Ctl.A, 1f); frames(2); a.set(Ctl.A, 0f); frame();
        assertSame(a, im.activePad());
        a.connected = false; src.pads.remove(a); frame();
        assertSame(b, im.activePad());
    }

    @Test public void theInputDevicePreferenceFixesThePromptsButNeverDisablesADevice() {
        FakePad p = connect("x1", "Xbox Controller"); frame();
        cfg.inputMode = DesktopConfig.InputMode.CONTROLLER; frame();
        assertEquals(InputManager.Device.CONTROLLER, im.promptDevice());
        assertEquals("A", im.prompt(Act.JUMP));
        src.press(Keys.SPACE); im.update(DT);
        assertTrue("the keyboard still works", read().jumpPressed);
        assertEquals("prompts stay on the controller", InputManager.Device.CONTROLLER, im.promptDevice());
        cfg.inputMode = DesktopConfig.InputMode.KEYBOARD; frame();
        assertEquals(InputManager.Device.KEYBOARD, im.promptDevice());
        p.connected = false; src.pads.clear(); cfg.inputMode = DesktopConfig.InputMode.CONTROLLER; frame();
        assertEquals("no controller: keyboard prompts", InputManager.Device.KEYBOARD, im.promptDevice());
    }

    @Test public void heldMenuDirectionsRepeat() {
        src.press(Keys.DOWN); im.update(DT); assertTrue(im.menuPressed(Act.MENU_DOWN)); src.endFrame();
        int n = 0;
        for (int i = 0; i < 120; i++) { im.update(DT); if (im.menuPressed(Act.MENU_DOWN)) n++; src.endFrame(); }
        assertTrue("repeats after the initial delay, not every frame: " + n, n >= 8 && n <= 20);
    }

    // ---------------------------------------------------------------- tip banner dismiss (owner pop-up UX fix)

    @Test public void tabClosesTheTipAndIsNothingElse() {
        src.press(Keys.TAB); im.update(DT); src.endFrame();
        InputState s = read();
        assertTrue(im.tipDismissPressed());
        assertFalse("consumed", im.tipDismissPressed());
        assertFalse(s.jumpPressed); assertFalse(s.jumpHeld); assertFalse(s.swingPressed); assertEquals(0f, s.moveX, 0f); assertEquals(0f, s.moveY, 0f);
        assertFalse("not pause", im.pausePressed());
        assertEquals("TAB", im.tipDismissLabel());
    }

    @Test public void tabBoundToAGameActionNoLongerClosesTips() {
        cfg.keyboard.set(Act.JUMP, java.util.Arrays.asList(Keys.TAB));
        src.press(Keys.TAB); im.update(DT); src.endFrame();
        assertFalse("a gameplay key never doubles as the dismiss", im.tipDismissPressed());
        assertNull(im.tipDismissLabel());
    }

    @Test public void theControllersBackButtonClosesTheTipButNotWhenItJumps() {
        FakePad p = connect("pad1", "Xbox Controller"); frame();
        p.set(Ctl.B, 1f); im.update(DT);
        assertTrue(im.tipDismissPressed());
        assertFalse(read().jumpPressed);
        p.set(Ctl.B, 0f); im.update(DT);
        cfg.padFor("pad1").set(Act.JUMP, java.util.Arrays.asList(Ctl.B));
        p.set(Ctl.B, 1f); im.update(DT);
        assertFalse("B now jumps: it does not also dismiss", im.tipDismissPressed());
        assertTrue("jump stays jump", read().jumpPressed);
    }

    @Test public void jumpAndMoveNeverCloseTheTip() {
        src.press(Keys.SPACE); src.press(Keys.D); src.press(Keys.ESCAPE); im.update(DT); src.endFrame();
        assertFalse(im.tipDismissPressed());
    }
}

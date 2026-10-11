package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import com.hotatticgames.climbup.platform.Platform;
import org.junit.Test;

/** The Android launcher uses Platform.MOBILE: every answer must be the behaviour the game had before the desktop seam existed. */
public class PlatformSeamTest {
    @Test public void mobileReproducesTheOriginalBehaviour() {
        Platform p = Platform.MOBILE;
        assertFalse(p.desktop());
        assertTrue("touch controls are drawn and steer the hero", p.touchControls());
        assertTrue("the signed OTA check is allowed", p.networkAllowed());
        assertEquals("CLIMB UP", p.title());
        assertNull("no desktop input source: the original touch/keyboard path is used", p.gameInput());
        assertTrue(p.settingsTabs().isEmpty());
        assertFalse("the shared touch pause icon is drawn", p.drawPauseButton(null, 0, 0, 0));
        String tip = "TAP AGAIN: NEW CLIMB. HOLD JUMP, STICK UP/DOWN CLIMBS";
        assertSame("tip text is untouched", tip, p.tip(tip));
    }

    @Test public void theDefaultGameConstructorUsesMobile() {
        assertSame(Platform.MOBILE, new ClimbGame(new java.io.File("x")).platform);
    }
}

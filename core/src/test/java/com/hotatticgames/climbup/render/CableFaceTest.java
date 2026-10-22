package com.hotatticgames.climbup.render;

import static org.junit.Assert.*;

import java.lang.reflect.Method;
import org.junit.Test;

/**
 * Build 71 (owner): under a cable the bunny's eyes must stay ON its face. Build 67 drew the eye parts through the arms (no
 * depth test), which read as eyes floating over the arms and was rejected. The fix is a pose instead: a wider, higher grip
 * so the arms rise beside the head, the head tipped back and turned a little toward the camera, and less travel yaw. This
 * pins that the see-through hack stays gone and that the pose stays a subtle, sane adjustment (verified visually with
 * xvfb-run ./gradlew :desktop:creatureShot -Dclimb.shotCable=true, both directions; docs/VERIFICATION.md, Build 71).
 */
public class CableFaceTest {

    @Test public void theEyesAreNeverDrawnThroughTheArms() {
        for (Method m : HeroRig.class.getDeclaredMethods())
            assertFalse("the Build 67 eyes-through-arms hack is back: " + m.getName(), m.getName().toLowerCase().contains("eyesthrough"));
    }

    @Test public void theCablePoseIsASubtleAdjustment() {
        assertTrue("head tips back, but only slightly", HeroRig.CABLE_HEAD_BACK > 0.1f && HeroRig.CABLE_HEAD_BACK <= 0.6f);
        assertTrue("the head turns at most part of the way back toward the camera", HeroRig.CABLE_HEAD_TURN >= 0f && HeroRig.CABLE_HEAD_TURN <= 1f);
        assertTrue("the chest turns less than the head", HeroRig.CABLE_CHEST_TURN >= 0f && HeroRig.CABLE_CHEST_TURN <= HeroRig.CABLE_HEAD_TURN);
        assertTrue("the bunny faces the camera more than the old 24 degree cable yaw", HeroRig.CABLE_YAW >= 0f && HeroRig.CABLE_YAW < 24f);
        assertTrue("arms spread wide enough to clear the face", HeroRig.CABLE_SPREAD >= 0.6f && HeroRig.CABLE_SPREAD <= 1f);
        assertTrue("hands reach up to the line, not across the face", HeroRig.CABLE_UP >= 0.9f && HeroRig.CABLE_FWD < 0.4f);
        assertTrue("the arm stretch stays cartoon-subtle", HeroRig.CABLE_STRETCH > 0f && HeroRig.CABLE_STRETCH <= 0.6f);
    }
}

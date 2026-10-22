package com.hotatticgames.climbup.desktop;

import static org.junit.Assert.*;

import com.hotatticgames.climbup.desktop.input.ChordLatch;
import org.junit.Test;

/** Build 66: LB + RB together takes a screenshot - once per joint press, locked until both bumpers are up again. */
public class ChordLatchTest {
    @Test public void bothBumpersTogetherFireOnceUntilBothAreReleased() {
        ChordLatch l = new ChordLatch();
        assertFalse(l.update(false, false)); assertFalse("one bumper alone does nothing", l.update(true, false));
        assertTrue("both down: fires", l.update(true, true));
        for (int i = 0; i < 30; i++) assertFalse("held: never again", l.update(true, true));
        assertFalse(l.update(true, false)); assertFalse("the other bumper tapped again while one stays held: still locked", l.update(true, true));
        assertFalse(l.update(false, true)); assertFalse(l.update(true, true));
        assertFalse(l.update(false, false)); assertTrue("both up re-arms", l.armed());
        assertTrue("the next joint press fires again", l.update(true, true));
    }

    @Test public void aPadUnpluggedMidPressReArms() {
        ChordLatch l = new ChordLatch();
        assertTrue(l.update(true, true));
        assertFalse("no pad: both read as up", l.update(false, false));
        assertTrue(l.update(true, true));
    }

    @Test public void pressingTheSecondBumperAFrameLaterStillCounts() {
        ChordLatch l = new ChordLatch();
        assertFalse(l.update(true, false)); assertFalse(l.update(true, false));
        assertTrue(l.update(true, true));
    }
}

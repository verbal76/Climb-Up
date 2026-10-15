package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import org.junit.Test;

/** The accessibility TEXT SIZE stepper: steps below and above 100%, monotonic, clamped, and still readable by old saves. */
public class SettingsTextScaleTest {

    @Test public void defaultIsOneHundredPercent() {
        assertEquals(1.0f, new Settings().textMul(), 1e-6f);
        assertEquals("100%", new Settings().textPercent());
    }

    @Test public void stepsRunBelowAndAboveOneHundredAndAreMonotonic() {
        Settings s = new Settings();
        float prev = -1f; boolean sawBelow100 = false, saw100 = false;
        assertTrue("there are at least five steps", Settings.textStepCount() >= 5);
        for (int i = 0; i < Settings.textStepCount(); i++) {
            s.textScale = Settings.textScaleForStep(i);
            float m = s.textMul();
            assertTrue("sizes increase step by step", m > prev); prev = m;
            if (m < 1.0f) sawBelow100 = true;
            if (Math.abs(m - 1.0f) < 1e-6f) saw100 = true;
            assertEquals("the step index round-trips", i, s.textStep());
        }
        assertTrue("there is at least one size below 100%", sawBelow100);
        assertTrue("100% is one of the steps", saw100);
    }

    @Test public void legacyStoredValuesKeepTheirOldMeaning() {   // saves written before this change used 0/1/2 = 100/130/160
        Settings s = new Settings();
        s.textScale = 0; assertEquals(1.0f, s.textMul(), 1e-6f);
        s.textScale = 1; assertEquals(1.3f, s.textMul(), 1e-6f);
        s.textScale = 2; assertEquals(1.6f, s.textMul(), 1e-6f);
    }

    @Test public void theStepperClampsAtBothEnds() {
        Settings s = new Settings();
        for (int i = 0; i < 20; i++) s.setTextStep(s.textStep() - 1);   // hold minus
        float min = s.textMul();
        assertTrue("the smallest step is below 100%", min < 1.0f);
        s.setTextStep(s.textStep() - 1);
        assertEquals("minus does not go past the smallest step", min, s.textMul(), 1e-6f);
        for (int i = 0; i < 20; i++) s.setTextStep(s.textStep() + 1);   // hold plus
        float max = s.textMul();
        assertTrue("the largest step is above 100%", max > 1.0f);
        s.setTextStep(s.textStep() + 1);
        assertEquals("plus does not go past the largest step", max, s.textMul(), 1e-6f);
    }

    @Test public void anUnknownStoredValueReadsAsOneHundred() {
        Settings s = new Settings(); s.textScale = 99;
        assertEquals(1.0f, s.textMul(), 1e-6f);
        assertEquals("100%", s.textPercent());
    }
}

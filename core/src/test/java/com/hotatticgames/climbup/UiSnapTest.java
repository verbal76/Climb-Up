package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import com.hotatticgames.climbup.ui.Ui;
import org.junit.Test;

public class UiSnapTest {
    @Test public void textSizesLandOnWholeDevicePixels() {
        for (float scale : new float[]{1f, 1.5f, 2f, 3f, 2.6667f}) for (float px = 2f; px < 8f; px += 0.35f) {
            float s = Ui.snapSize(px, scale) * scale;
            assertEquals(Math.round(s), s, 1e-3f);
            assertTrue(s >= 1f - 1e-4f);
        }
    }

    @Test public void atFourKThePixelFontStaysCloseToItsDesignedSize() {
        assertEquals(3.3333f, Ui.snapSize(3.2f, 3f), 1e-3f);
        assertEquals(4f, Ui.snapSize(4f, 3f), 1e-6f);
    }

    @Test public void positionsLandOnDevicePixels() {
        assertEquals(10f / 1.5f, Ui.snapPos(6.5f, 1.5f), 1e-4f);
        assertEquals(7f, Ui.snapPos(7f, 1f), 1e-6f);
    }

    @Test public void aBrokenScaleLeavesValuesAlone() {
        assertEquals(3.2f, Ui.snapSize(3.2f, 0f), 0f);
        assertEquals(5.5f, Ui.snapPos(5.5f, 0f), 0f);
    }
}

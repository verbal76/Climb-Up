package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import com.hotatticgames.climbup.sim.*;
import org.junit.Test;

/** The retained tower costs a small, constant amount per metre climbed (the whole tower stays resident so any fall can land on it); measured, not assumed. */
public class MemoryBoundsTest {
    private static long used() { for (int i = 0; i < 3; i++) System.gc(); Runtime r = Runtime.getRuntime(); return r.totalMemory() - r.freeMemory(); }

    @Test public void retainedWorldGrowsLinearlyWithASmallConstantCostPerElement() throws Exception {
        Tuning t = TestUtil.tuning();
        Tower tw = new Tower(77, t); Sim sim = Sim.startOn(tw.world, t, 0); long base = used();
        double[] perElem = new double[3]; int n = 0;
        for (int k = 1; k <= 150; k++) {
            tw.extend(); sim.ensureCapacity();
            if (k % 50 == 0) { perElem[n++] = (used() - base) / (double) tw.world.size(); }
        }
        System.out.printf("world bytes per element after 50/100/150 slices: %.0f / %.0f / %.0f (top %.0f m, %d elements)%n", perElem[0], perElem[1], perElem[2], tw.topY(), tw.world.size());
        for (double b : perElem) assertTrue("each element of the retained tower costs under 700 bytes including the simulation arrays (" + b + ")", b < 700);
        assertTrue("and the cost per element does not grow as the tower does (" + perElem[2] + " vs " + perElem[0] + ")", perElem[2] <= perElem[0] * 1.15);
        assertSame(tw.world, sim.course);
    }
}

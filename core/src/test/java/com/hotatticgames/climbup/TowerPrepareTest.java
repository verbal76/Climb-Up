package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import com.hotatticgames.climbup.sim.*;
import org.junit.Test;

/**
 * Build 71 (owner: no freeze when a climb begins): the first slice of the next climb is made in the background ahead of time. It must be exactly the slice the
 * climb would have made itself, it is only used for its own seed and tuning, and once it is ready starting the climb no longer waits for generation.
 */
public class TowerPrepareTest {
    @Test public void aPreparedFirstSliceIsTheSameClimbByteForByte() throws Exception {
        Tuning t = TestUtil.tuning();
        byte[] made = new Tower(31, t).blob(0);
        Tower.prepare(31, t);
        Tower prepared = new Tower(31, t);
        assertArrayEquals("the prepared slice 0 is the one the climb makes itself", made, prepared.blob(0));
        assertEquals(new Tower(31, t).world.size(), prepared.world.size());
    }

    @Test public void aSliceForAnotherSeedOrTuningIsNeverUsed() throws Exception {
        Tuning t = TestUtil.tuning(), other = TestUtil.tuning();
        byte[] made = new Tower(32, t).blob(0);
        Tower.prepare(33, t);
        assertArrayEquals("another seed's slice is not taken", made, new Tower(32, t).blob(0));
        Tower.prepare(32, other);
        assertArrayEquals("nor one made with other tuning", made, new Tower(32, t).blob(0));
    }

    @Test public void onceReadyStartingTheClimbDoesNotGenerate() throws Exception {
        Tuning t = TestUtil.tuning();
        long t0 = System.nanoTime(); new Tower(34, t); long cold = System.nanoTime() - t0;
        Tower.prepare(35, t);
        Thread.sleep(Math.max(500, 3 * cold / 1_000_000));                    // the title screen is up for a moment: plenty of time to make it
        long t1 = System.nanoTime(); Tower tw = new Tower(35, t); long warm = System.nanoTime() - t1;
        assertNotNull(tw.blob(0));
        System.out.printf("PREPARE making slice 0 on the spot %.0f ms, prepared %.1f ms%n", cold / 1e6, warm / 1e6);
        assertTrue("prepared start " + warm / 1e6 + " ms vs " + cold / 1e6 + " ms", warm * 5 < cold);
    }
}

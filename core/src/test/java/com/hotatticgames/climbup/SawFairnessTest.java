package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import com.hotatticgames.climbup.sim.*;
import org.junit.Test;

/** Horizontal saws must leave comfortable room to time the jump, at every height, not merely be possible. */
public class SawFairnessTest {
    @Test public void everySawLeavesAWideTimingWindow() throws Exception {
        Tuning t = TestUtil.tuning();
        int gap = 0, mid = 0; float worstGap = 9f, worstMid = 9f;
        for (long seed : new long[]{1, 2, 3, 4, 5}) {
            Course prev = null;
            for (int k = 0; k < 26; k++) {
                Course c = CourseGenerator.chunk(seed, k, prev, t); prev = c;
                for (Element h : c.hazards) {
                    if (h.type != Element.Type.SAW_H || h.anchor < 0 || h.anchor >= c.routeSize() - 1) continue;
                    Element p = c.get(h.anchor), n = c.get(h.anchor + 1);
                    boolean onPlatform = p.w >= 7f && Math.abs(h.y - p.y - 0.62f) < 0.01f;
                    float window = Autopilot.plan(Sim.startOn(c, t, h.anchor), h.anchor, true).window;
                    if (onPlatform) {
                        mid++; worstMid = Math.min(worstMid, window);
                        assertTrue("a floor saw sweeps at most 1.1 m each way, leaving room at both ends (amp " + h.amp + ")", h.amp <= 1.1f + 1e-4f);
                    } else {
                        gap++; worstGap = Math.min(worstGap, window);
                        float g = c.dsWrap(n.s, p.s) - p.w / 2f - n.w / 2f;
                        assertTrue("a saw over a gap never guards an uphill jump (dy " + (n.y - p.y) + ")", n.y - p.y <= 0.1f + 1e-3f);
                        assertTrue("and never a near-full-length gap (" + g + ")", g <= 2.6f + 1e-3f);
                        assertTrue("blade top stays within a normal jump arc: " + (h.y - Math.max(p.y, n.y)), h.y - Math.max(p.y, n.y) <= 0.85f);
                    }
                }
            }
        }
        System.out.println("saws: gap " + gap + " (worst window " + worstGap + "), floor " + mid + " (worst window " + worstMid + ")");
        assertTrue("saws actually appear", gap + mid >= 10);
        assertTrue("every saw can be passed during at least half of its cycle (gap saws " + worstGap + ")", worstGap >= 0.5f);
        assertTrue("floor saws: " + worstMid, worstMid >= 0.5f);
    }
}

package com.hotatticgames.climbup.sim;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * The validation gate's independent geometry check places slices in a common frame (LaneReport.integrity). Rules 14 Round 2 work: an absolute float
 * frame at ~25.8 km of arc rounds positions to ~2 mm, and NGH seed 1 slice 49 (capacity-control generation check) reported two decks of different
 * revolutions as intersecting: in the generator's frame they are 6.001286 m apart against the 6.0 m limit (arc halves 1.5 + 2.0 + 2.5 m clearance),
 * the float frame made it 5.999821 m. The frame is now anchored at the slice's own base with double offsets.
 */
public class IntegrityFrameTest {
    /** The two decks exactly as generated (local slice coordinates) and the slice base they were generated at. */
    private static Course slice49() {
        Course c = new Course(1, (float) (Math.PI * 20.0));
        c.add(new Element(Element.Type.STATIC, 803.72491f, 370.112f, 3f));
        c.add(new Element(Element.Type.STATIC, 860.55548f, 374.678f, 4f));
        return c;
    }
    static final double BASE_S = 25823.89115524292, BASE_Y = 3500.0;

    @Test public void theGeneratorsOwnFrameKeepsTheDecksClear() {
        Course c = slice49();
        assertFalse(CourseGenerator.layersBodiesClash(c, c.get(0), c.get(1)));
    }

    @Test public void aPureAbsoluteFloatFrameInventsTheClashAndTheAnchoredFrameDoesNot() {
        Course c = slice49();
        CourseGenerator.Framed f = new CourseGenerator.Framed(c, BASE_Y, BASE_S);
        Course absFrame = new Course(1, c.circumference), anchored = new Course(1, c.circumference);
        for (int i = 0; i < 2; i++) { absFrame.add(LaneReport.abs(c.get(i), f)); anchored.add(LaneReport.abs(c.get(i), f, f.sBase, f.yBase)); }
        assertTrue("the old frame's rounding (documents the defect)", CourseGenerator.layersBodiesClash(absFrame, absFrame.get(0), absFrame.get(1)));
        assertFalse("the anchored frame keeps the generator's geometry", CourseGenerator.layersBodiesClash(anchored, anchored.get(0), anchored.get(1)));
        assertEquals(c.dsWrap(c.get(0).s, c.get(1).s), anchored.dsWrap(anchored.get(0).s, anchored.get(1).s), 0f);
    }

    @Test public void thePreviousSliceKeepsItsExactOffsetInTheAnchoredFrame() {
        Course prev = new Course(1, (float) (Math.PI * 20.0)), cur = new Course(1, (float) (Math.PI * 20.0));
        prev.add(new Element(Element.Type.STATIC, 812.25f, 370.0f, 3f)); cur.add(new Element(Element.Type.STATIC, 1.5f, 0.25f, 3f));
        CourseGenerator.Framed pf = new CourseGenerator.Framed(prev, 3400.0, 25010.640625), f = new CourseGenerator.Framed(cur, 3770.0, 25822.890625);
        Element a = LaneReport.abs(prev.get(0), pf, f.sBase, f.yBase), b = LaneReport.abs(cur.get(0), f, f.sBase, f.yBase);
        assertEquals((pf.sBase + 812.25) - (f.sBase + 1.5), a.s - b.s, 1e-5);
        assertEquals((pf.yBase + 370.0) - (f.yBase + 0.25), a.y - b.y, 1e-5);
    }
}

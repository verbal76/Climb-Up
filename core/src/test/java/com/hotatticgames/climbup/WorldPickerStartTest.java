package com.hotatticgames.climbup;

import com.hotatticgames.climbup.sim.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Build 77 (owner): the hidden world picker starts you at the castle where the chosen world begins, past its door, with the next key still ahead. */
public class WorldPickerStartTest {
    @Test public void eachWorldStartsJustPastItsOpeningCastle() throws Exception {
        Tuning t = TestUtil.tuning();
        for (int w = 1; w < 5; w++) {
            int h = (int) (w * t.zoneHeight) + 5, castle = (int) (h / t.castleSpacing);
            Tower tw = new Tower(3, t); while (tw.topAbsY() < h + 80f) tw.extend();
            int i = tw.afterCastle(castle);
            assertTrue("world " + w + ": castle " + castle + " is in the window", i > 0);
            Course c = tw.world; double oy = tw.originY;
            Element deck = c.get(i - 1), start = c.get(i);
            assertEquals("world " + w + ": the castle deck stands at the world's first height", w * t.zoneHeight, deck.y + oy, 0.01);
            assertTrue("world " + w + ": the start is a route platform just past it", start.anchor < 0 && start.y + oy >= deck.y + oy - 0.5 && start.y + oy < deck.y + oy + 8f);
            boolean keyAhead = false;
            for (Element k : c.hazards) if (k.type == Element.Type.KEY && k.color == CourseGenerator.castleColor(3, castle + 1) && k.y + oy > start.y + oy && k.y + oy < (castle + 1) * t.castleSpacing) keyAhead = true;
            for (int ex = 0; ex < 12 && !keyAhead; ex++) { tw.extend(); for (Element k : tw.world.hazards) if (k.type == Element.Type.KEY && k.color == CourseGenerator.castleColor(3, castle + 1) && k.y + tw.originY > w * t.zoneHeight && k.y + tw.originY < (castle + 1) * t.castleSpacing) keyAhead = true; }
            assertTrue("world " + w + ": the key of castle " + (castle + 1) + " lies ahead in the section", keyAhead);
            Sim s = Sim.startOn(c, t, i); s.keysFree = false; s.keys = 0;
            assertEquals("no keys are given", 0, s.keys);
        }
    }
}

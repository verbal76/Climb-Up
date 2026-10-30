package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import com.hotatticgames.climbup.ui.TipBanner;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.junit.Test;

/** Owner playtest fix: the mechanic-introduction banner is compact, brief, out of the climb, one at a time, dismissable, and respects the seen list. */
public class TipBannerTest {
    /** PixelFont.width without a GL context: 6 units per glyph, minus the trailing gap. */
    private static final TipBanner.Measure FONT = (s, px) -> s.length() * 6 * px - px;
    private static final float[][] SCREENS = {{1280, 720}, {1600, 720}, {1280, 960}};          // 16:9 desktop/phone, 20:9 phone (2400x1080 is this too), 4:3 tablet (ExtendViewport 1280x720 minimum)
    private static final String[] SCREEN_NAMES = {"16:9", "20:9", "4:3"};
    private static final float[] TEXT = {0.70f, 0.85f, 1.00f, 1.30f, 1.60f};                   // every TEXT SIZE step (Settings.TEXT_STEP_MUL)
    private static final String[] MARKS = {"X", "TAB", "B", "CIRCLE", null};

    private static boolean overlap(float ax, float ay, float aw, float ah, float bx, float by, float bw, float bh) {
        return ax < bx + bw && bx < ax + aw && ay < by + bh && by < ay + ah;
    }

    @Test public void theBannerStaysInTheTopBandClearOfThePlayerTheRouteAndTheHud() {
        TipBanner.Box b = new TipBanner.Box();
        for (int si = 0; si < SCREENS.length; si++) for (float tm : TEXT) for (String mark : MARKS) for (int mode = 0; mode < 3; mode++) for (TipCatalog.Tip tip : TipCatalog.ALL) {
            float W = SCREENS[si][0], H = SCREENS[si][1];
            boolean touch = mode > 0, left = mode == 2;
            String what = SCREEN_NAMES[si] + " text " + tm + " mark " + mark + (touch ? left ? " touch left-handed" : " touch" : " desktop") + " tip " + tip.key;
            TipBanner.layout(tip.brief, touch ? "X" : mark, W, H, tm, touch, left, FONT, b);
            // the player and the climb around him: the hero is anchored at the centre of the screen; this box takes the middle half of the width
            // and everything from 15 % up to 76 % of the height (his jumps, the next platforms, the hazards he is about to meet)
            assertFalse("covers the player / route: " + what, overlap(b.x, b.y, b.w, b.h, W * 0.25f, H * 0.15f, W * 0.5f, H * 0.61f));
            assertTrue("in the top band: " + what, b.y >= H * 0.76f);
            assertTrue("on screen: " + what, b.x >= 0 && b.x + b.w <= W && b.y + b.h <= H - TipBanner.MARGIN + 0.01f);
            assertFalse("clear of the height plate: " + what, b.x < TipBanner.plateRight(tm));
            if (touch) {
                float px = left ? TipBanner.MARGIN : W - TipBanner.MARGIN - TipBanner.PAUSE;
                assertFalse("clear of the pause button: " + what, overlap(b.x, b.y, b.w, b.h, px - 10, H - TipBanner.MARGIN - TipBanner.PAUSE - 10, TipBanner.PAUSE + 20, TipBanner.PAUSE + 20));
            }
            assertTrue("at most two lines: " + what, b.lines.length >= 1 && b.lines.length <= TipBanner.MAX_LINES);
            assertFalse("never cut short: " + what, b.truncated);
            assertTrue("the text keeps at least 75 % of its TEXT SIZE: " + what, b.px >= TipBanner.PX * tm * TipBanner.MIN_PX - 1e-3f);
            assertTrue("compact: " + what, b.h <= 2 * (7 * TipBanner.PX * 1.6f + 8) + 2 * TipBanner.PAD + 1f);
        }
    }

    @Test public void atNormalTextSizeEveryTipIsOneLineAtFullSize() {
        TipBanner.Box b = new TipBanner.Box();
        for (TipCatalog.Tip tip : TipCatalog.ALL) {
            TipBanner.layout(tip.brief, "X", 1280, 720, 1f, true, false, FONT, b);
            assertEquals(tip.key, TipBanner.PX, b.px, 1e-4f);
            assertTrue(tip.key + " fits on one line on a 16:9 phone at 100 % (two for the one longer tip)", b.lines.length == 1 || (tip.hold > TipBanner.HOLD && b.lines.length == 2));
        }
    }

    @Test public void anOverlongTextIsShrunkThenCutNeverSpilled() {
        TipBanner.Box b = new TipBanner.Box();
        String longText = "THIS IS A DELIBERATELY LONG TIP THAT CANNOT POSSIBLY FIT IN TWO LINES OF THE TOP BAND EVEN AFTER THE TEXT HAS BEEN MADE SMALLER";
        TipBanner.layout(longText, "X", 1280, 720, 1.6f, true, false, FONT, b);
        assertEquals(2, b.lines.length);
        assertTrue(b.truncated && b.lines[1].endsWith("..."));
        assertTrue(b.y >= 720 * 0.76f);
    }

    @Test public void ordinaryTipsLastAboutTwoAndAHalfSecondsThenFade() {
        TipBanner t = new TipBanner();
        assertTrue(t.show("ramp", "RAMPS: JUST RUN UP", "FULL", TipBanner.HOLD));
        float on = 0f, dt = 1f / 60f; int frames = 0;
        while (t.active() && frames < 10000) { t.update(dt, false); if (t.visible()) on += dt; frames++; }
        assertFalse(t.active());
        assertTrue("on screen about 2.5 s plus a short fade: " + on, on > 2.5f && on < 2.5f + TipBanner.FADE_OUT + 0.05f);
        for (TipCatalog.Tip tip : TipCatalog.ALL) assertTrue(tip.key + " is short-lived", tip.hold <= TipBanner.HOLD_LONG);
        int longOnes = 0; for (TipCatalog.Tip tip : TipCatalog.ALL) if (tip.hold > TipBanner.HOLD) longOnes++;
        assertTrue("longer only where genuinely needed", longOnes <= 2);
    }

    @Test public void fadesInAndOutRatherThanPopping() {
        TipBanner t = new TipBanner();
        t.show("pad", "BOUNCE", "F", TipBanner.HOLD);
        t.update(0.05f, false);
        float a1 = t.alpha();
        assertTrue("fading in", a1 > 0f && a1 < 1f);
        t.update(0.2f, false);
        assertEquals(1f, t.alpha(), 1e-4f);
        t.update(TipBanner.HOLD, false);                     // past the hold: leaving
        t.update(TipBanner.FADE_OUT * 0.5f, false);
        assertTrue("fading out", t.alpha() > 0f && t.alpha() < 1f);
    }

    @Test public void aDismissClosesItAtOnce() {
        TipBanner t = new TipBanner();
        t.show("rope", "TOUCH A ROPE", "F", TipBanner.HOLD);
        t.update(0.5f, false);
        assertTrue(t.visible());
        t.dismiss();
        t.update(TipBanner.DISMISS_FADE + 0.01f, false);
        assertFalse("gone after the short dismiss fade", t.active());
        assertEquals(0f, t.alpha(), 0f);
        t.dismiss();                                          // nothing showing: harmless
    }

    @Test public void tipsNeverStackOrChainBackToBack() {
        TipBanner t = new TipBanner();
        assertTrue(t.show("a", "A", "A", TipBanner.HOLD));
        assertFalse("a second tip cannot start while one shows", t.show("b", "B", "B", TipBanner.HOLD));
        assertEquals("a", t.key());
        t.dismiss(); t.update(0.2f, false);
        assertFalse(t.active());
        assertFalse("and not straight after either", t.ready());
        t.update(TipBanner.GAP + 0.01f, false);
        assertTrue(t.ready());
        assertTrue(t.show("b", "B", "B", TipBanner.HOLD));
    }

    @Test public void itWaitsWhileTheToastOrWorldBannerHoldsTheTopBand() {
        TipBanner t = new TipBanner();
        t.show("saw", "SAWS", "F", TipBanner.HOLD);
        for (int i = 0; i < 300; i++) t.update(1f / 60f, true);           // five seconds of a toast / world banner
        assertTrue("still waiting, not used up", t.active());
        assertEquals("hidden meanwhile", 0f, t.alpha(), 0f);
        assertEquals("its clock stood still", 0f, t.shownFor(), 0f);
        t.update(0.3f, false);
        assertTrue("then shows", t.visible());
    }

    @Test public void theSeenListIsRespectedAndOnlyAShownTipIsMarkedSeen() {
        TipBanner t = new TipBanner();
        List<String> seen = new ArrayList<>(); seen.add("ramp");
        assertFalse("already introduced: never again", TipCatalog.offer(t, seen, "ramp", s -> s));
        assertFalse(t.active());
        assertTrue(TipCatalog.offer(t, seen, "rope", s -> s));
        assertEquals("rope", t.key());
        assertEquals("TOUCH A ROPE TO GRAB IT", t.text());
        assertTrue(t.full().startsWith("GRAB A ROPE BY TOUCHING IT"));
        assertFalse("busy: the next one waits and is NOT marked seen", TipCatalog.offer(t, seen, "pad", s -> s));
        assertFalse(seen.contains("pad"));
        assertTrue(seen.contains("rope"));
        assertTrue("the platform's control words are applied", TipCatalog.offer(new TipBanner(), new ArrayList<>(), "jump", s -> s.replace("HOLD JUMP", "HOLD SPACE")));
    }

    @Test public void catalogIsCompleteAndItsKeysAreTheSavedOnes() {
        HashSet<String> keys = new HashSet<>();
        for (TipCatalog.Tip tip : TipCatalog.ALL) {
            assertTrue("unique key " + tip.key, keys.add(tip.key));
            assertTrue(tip.brief.length() > 0 && tip.brief.length() <= 42);
            assertTrue("the brief line is shorter than the full text: " + tip.key, tip.brief.length() < tip.full.length());
            for (char c : (tip.brief + tip.full).toCharArray()) assertTrue("printable in the pixel font: '" + c + "' in " + tip.key, c == ' ' || "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789.,:!?-+/()%'=<>_&".indexOf(c) >= 0);
        }
        // the keys players already have in their saves (Build 83 and earlier) are all still here, so nobody sees an old tip again
        for (String k : new String[]{"jump", "ledge", "pad", "rope", "crumble", "move", "cable", "swing", "checkpoint", "saw", "cannon", "trap", "block", "crab", "bee", "club", "drop", "spring",
                "ramp", "rampcrumble", "rampshake", "rampspin", "rampbelt", "beltbridge", "seesaw", "gate"}) assertNotNull(k, TipCatalog.get(k));
    }
}

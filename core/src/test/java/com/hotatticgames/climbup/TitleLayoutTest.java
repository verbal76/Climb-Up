package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import com.hotatticgames.climbup.TitleLayout.Rect;
import java.util.List;
import org.junit.Test;

/**
 * The title menu holds up to 7 big buttons (PLAY/CONTINUE, NEW RUN, PLAYERS, SETTINGS, CREDITS, EXIT and a later TIMES). These checks use plain numbers (no screen needed): nothing overlaps, nothing runs into the
 * title art, the hero's space or the status bar, every button is at least 48dp tall on phone-size screens, and the labels fit their buttons, on 16:9, 21:9 and other shapes and on small phones up to a 4K TV.
 */
public class TitleLayoutTest {
    /** {width, height} in virtual units: 16:9, 21:9, 20:9, 32:9, 16:10, 4:3 */
    private static final float[][] SHAPES = {{1280, 720}, {1680, 720}, {1600, 720}, {2560, 720}, {1280, 800}, {1280, 960}};
    /** virtual units per dp: unknown, 4K desktop at 100%, 4K TV at density 2, 1080p, 412dp-high phone, 360dp-high phone */
    private static final float[] DENSITIES = {0f, 0.33f, 0.67f, 1f, 1.75f, 2f};

    private static float textPx(String label, float w) { return Math.min(5f, (w - 24f) / Math.max(1, label.length() * 6 - 1)); }   // what Ui.button draws at 100% text size

    @Test public void theMenuHasEveryButtonAndExitIsLast() {
        TitleScreen.Item[] with = TitleScreen.menu(true), without = TitleScreen.menu(false);
        assertEquals(6, with.length); assertEquals(5, without.length);
        assertEquals(TitleScreen.Item.PLAY, with[0]); assertEquals(TitleScreen.Item.PLAY, without[0]);
        assertEquals("EXIT is the last button", TitleScreen.Item.EXIT, with[with.length - 1]); assertEquals(TitleScreen.Item.EXIT, without[without.length - 1]);
        assertEquals("EXIT", TitleScreen.Item.EXIT.label(true));
        java.util.Set<String> labels = new java.util.HashSet<>();
        for (TitleScreen.Item it : with) assertTrue("label is unique: " + it.label(true), labels.add(it.label(true)));
        assertTrue(labels.contains("PLAYERS") && labels.contains("SETTINGS") && labels.contains("CREDITS") && labels.contains("NEW RUN") && labels.contains("CONTINUE"));
        assertEquals("PLAY", TitleScreen.Item.PLAY.label(false));
        assertFalse("NEW RUN only exists when there is a climb to replace", java.util.Arrays.asList(without).contains(TitleScreen.Item.NEW_RUN));
    }

    @Test public void fiveSixAndSevenButtonsNeverOverlapAnythingOnAnyScreenShapeOrDensity() {
        for (float[] sh : SHAPES) for (float upd : DENSITIES) for (int n = 1; n <= 8; n++) for (int lg = 0; lg < 2; lg++) {
            float W = sh[0], H = sh[1];
            String where = W + "x" + H + " upd=" + upd + " n=" + n + " legacy=" + lg;
            TitleLayout l = TitleLayout.of(W, H, n, lg == 1, upd, TitleLayout.typicalHeadY(H));
            assertEquals(where, n, l.menu.length); assertEquals(where, lg == 1, l.legacy != null);
            List<Rect> all = l.all();
            for (int i = 0; i < all.size(); i++) {
                Rect r = all.get(i);
                assertTrue(where + " inside the safe sides " + r, r.x >= TitleLayout.EDGE - 0.01f && r.right() <= W - TitleLayout.EDGE + 0.01f);
                assertTrue(where + " above the status bar " + r, r.y >= TitleLayout.FLOOR - 0.01f);
                assertTrue(where + " below the title art " + r, r.top() <= H - TitleLayout.ART_BOTTOM - TitleLayout.ART_TO_MENU + 0.01f);
                assertTrue(where + " has size " + r, r.w > 0 && r.h > 0);
                assertFalse(where + " touches the title art " + r, r.overlaps(l.art));
                assertFalse(where + " covers the hero " + r, r.overlaps(l.heroZone));
                assertFalse(where + " covers the status bar " + r, r.overlaps(l.statusBar));
                assertFalse(where + " is in the hero's speech bubble " + r + " / " + l.bubble, r.overlaps(l.bubble));
                for (int j = i + 1; j < all.size(); j++) assertFalse(where + " overlap " + r + " / " + all.get(j), r.overlaps(all.get(j)));
            }
        }
    }

    @Test public void everyButtonIsAtLeast48dpTallAndThePrimaryOneAtLeast56dpWhereThereIsRoom() {
        for (float[] sh : SHAPES) for (float upd : DENSITIES) for (int n = 5; n <= 7; n++) for (int lg = 0; lg < 2; lg++) {
            String where = sh[0] + "x" + sh[1] + " upd=" + upd + " n=" + n + " legacy=" + lg;
            TitleLayout l = TitleLayout.of(sh[0], sh[1], n, lg == 1, upd, TitleLayout.typicalHeadY(sh[1]));
            for (Rect r : l.all()) {
                if (r == l.playing) continue;                                  // a caption, not a button
                assertTrue(where + " 48dp tall: " + r.h + " units at " + upd + " per dp", r.h >= 48f * upd - 0.01f);
                assertTrue(where + " 48dp wide: " + r.w, r.w >= 48f * upd - 0.01f);
            }
            assertTrue(where + " primary button 56dp: " + l.menu[0].h, l.menu[0].h >= 56f * Math.min(upd, 1.75f) - 0.01f);
        }
    }

    @Test public void theLabelsFitTheirButtonsAndStayReadable() {
        for (float[] sh : SHAPES) for (float upd : DENSITIES) for (int lg = 0; lg < 2; lg++) for (boolean has : new boolean[]{false, true}) {
            TitleScreen.Item[] items = TitleScreen.menu(has);
            TitleLayout l = TitleLayout.of(sh[0], sh[1], items.length, lg == 1, upd, TitleLayout.typicalHeadY(sh[1]));
            for (int i = 0; i < items.length; i++) {
                float px = textPx(items[i].label(has), l.menu[i].w);
                assertTrue(sh[0] + "x" + sh[1] + " " + items[i].label(has) + " text size " + px, px >= 4f);       // 4 units a dot = 28 units tall, 14dp on a 360dp phone
            }
            assertTrue("HERO: HAMSTER fits", textPx("HERO: HAMSTER", l.hero.w) >= 4f);
            if (l.legacy != null) assertTrue("LEGACY RUNS (99) fits", textPx("LEGACY RUNS (99)", l.legacy.w) >= 3.5f);
        }
    }

    @Test public void theMenuStaysOnTheLeftTheOtherButtonsOnTheRightAndTheHeroInTheMiddle() {
        for (float[] sh : SHAPES) {
            TitleLayout l = TitleLayout.of(sh[0], sh[1], 7, true, 2f, TitleLayout.typicalHeadY(sh[1]));
            float mid = sh[0] / 2f;
            for (Rect r : l.menu) assertTrue("menu is left of the hero " + r, r.right() <= mid - TitleLayout.HERO_HALF_W);
            assertTrue(l.hero.x >= mid + TitleLayout.HERO_HALF_W); assertTrue(l.legacy.x >= mid + TitleLayout.HERO_HALF_W); assertTrue(l.playing.x >= mid + TitleLayout.HERO_HALF_W);
        }
    }

    @Test public void theLayoutIsTheSameShapeOnAWiderScreenJustCentred() {
        TitleLayout a = TitleLayout.of(1280, 720, 7, true, 1f, 379f), b = TitleLayout.of(1680, 720, 7, true, 1f, 379f);
        for (int i = 0; i < 7; i++) { assertEquals(a.menu[i].y, b.menu[i].y, 0.01f); assertEquals(a.menu[i].w, b.menu[i].w, 0.01f); assertEquals(a.menu[i].x + 200f, b.menu[i].x, 0.01f); }
        assertEquals(a.hero.x + 200f, b.hero.x, 0.01f);
    }

    @Test public void tooManyButtonsOrATinyScreenShrinkTheRowsInsteadOfOverlapping() {
        TitleLayout l = TitleLayout.of(1280, 720, 8, true, 3f, 379f);        // more than the layout is sized for, on a very small screen
        List<Rect> all = l.all();
        for (int i = 0; i < all.size(); i++) for (int j = i + 1; j < all.size(); j++) assertFalse(all.get(i) + " / " + all.get(j), all.get(i).overlaps(all.get(j)));
        for (Rect r : all) assertTrue(r.y >= TitleLayout.FLOOR - 0.01f);
    }

    @Test public void thePlayerAndNameScreensKeepEveryButtonAtLeast48dpAndInsideTheScreen() {
        for (float[] sh : SHAPES) for (float upd : DENSITIES) {
            float H = sh[1], kh = NameScreen.keyHeight(H, upd), rh = PlayersScreen.rowHeight(H, upd);
            String where = sh[0] + "x" + H + " upd=" + upd;
            float keysBottom = (H - 174f) - 5 * kh - 4 * 8f;                                   // the SPACE / DELETE / OK / CANCEL row
            assertTrue(where + " picker fits: bottom " + keysBottom, keysBottom >= 19.99f);
            float lastRowBottom = (H - 140f) - rh - 2 * (rh + 40f), lastStatBottom = lastRowBottom - 28f;     // the third player row and its numbers line
            assertTrue(where + " players list fits: " + lastStatBottom, lastStatBottom >= 30f + rh);
            if (upd <= 2f) { assertTrue(where + " keys 48dp: " + kh, kh >= 48f * upd - 0.01f); assertTrue(where + " rows 48dp: " + rh, rh >= 48f * upd - 0.01f); }
        }
    }

    /** every line the hero can say (read from HeroRig so a new line is checked too) */
    private static String[] heroLines() throws Exception {
        java.lang.reflect.Field f = com.hotatticgames.climbup.render.HeroRig.class.getDeclaredField("LINES"); f.setAccessible(true);
        return (String[]) f.get(null);
    }

    @Test public void theHeroSpeechBubbleIsNeverHiddenBehindTheMenuOrTheRightColumn() throws Exception {
        String[] lines = heroLines(); assertTrue(lines.length >= 5);
        for (float[] sh : SHAPES) for (int n = 5; n <= 7; n++) for (int lg = 0; lg < 2; lg++) {
            float W = sh[0], H = sh[1], headX = W / 2f, headY = TitleLayout.typicalHeadY(H);
            TitleLayout l = TitleLayout.of(W, H, n, lg == 1, 1.75f, headY);
            for (String s : lines) for (float tm : new float[]{1f, 1.3f, 1.6f}) {
                float px = 3.2f * tm, w = (s.length() * 6 - 1) * px + 36f, h = 7 * px + 28f;
                float x = TitleLayout.bubbleX(W, w, headX, l.bubbleMinX);
                String where = W + "x" + H + " n=" + n + " '" + s + "' text " + tm;
                assertTrue(where + " on screen", x >= 16f - 0.01f && x + w <= W - 16f + 0.01f);
                assertTrue(where + " tail inside the body", x <= headX - 8f && x + w >= headX + 8f);
                if (tm > 1f) continue;          // at 130% and 160% text the longest lines are wider than the free space; they stay on screen and keep the tail, which is all that can be asked
                Rect body = new Rect(x - 4f, headY + 6f - 4f, w + 8f, h + 8f), tail = new Rect(headX - 8f, headY + 6f - 14f, 16f, 14f);
                for (Rect r : l.all()) { assertFalse(where + " hides " + r, body.overlaps(r)); assertFalse(where + " tail on " + r, tail.overlaps(r)); }
                assertTrue(where + " inside the reserved zone", body.x >= l.bubble.x - 4.01f && body.right() <= l.bubble.right() && body.y >= l.bubble.y && body.top() <= l.bubble.top());
                assertFalse(where + " touches the title art", body.overlaps(l.art));
            }
        }
    }

    @Test public void theRightColumnStaysClearOfTheBubbleWhereverTheHeroStands() {
        for (float f : new float[]{0.48f, 0.526f, 0.56f}) for (float[] sh : SHAPES) {
            TitleLayout l = TitleLayout.of(sh[0], sh[1], 7, true, 1.75f, sh[1] * f);
            for (Rect r : l.all()) assertFalse(sh[0] + "x" + sh[1] + " head " + f + " " + r + " / " + l.bubble, r.overlaps(l.bubble));
            assertTrue("fits above the status bar", l.legacy.y >= TitleLayout.FLOOR - 0.01f);
        }
    }
}

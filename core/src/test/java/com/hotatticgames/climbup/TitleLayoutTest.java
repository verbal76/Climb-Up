package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import com.hotatticgames.climbup.TitleLayout.Rect;
import java.util.List;
import org.junit.Test;

/**
 * The title menu is one column of up to 4 big buttons (PLAY/CONTINUE, NEW RUN, SETTINGS, MORE); everything else sits in the MORE panel (PLAYERS, HERO, TIMES, LEGACY RUNS, CREDITS, EXIT). These
 * checks use plain numbers (no screen needed): nothing overlaps, nothing runs into the title art, the hero's space or the status bar, every button is at least 48dp tall on phone-size screens, the
 * labels fit their buttons, and the MORE panel fits inside every screen, on 16:9, 21:9 and other shapes and on small phones up to a 4K TV.
 */
public class TitleLayoutTest {
    /** {width, height} in virtual units: 16:9, 21:9, 20:9, 32:9, 16:10, 4:3 */
    private static final float[][] SHAPES = {{1280, 720}, {1680, 720}, {1600, 720}, {2560, 720}, {1280, 800}, {1280, 960}};
    /** virtual units per dp: unknown, 4K desktop at 100%, 4K TV at density 2, 1080p, 412dp-high phone, 360dp-high phone */
    private static final float[] DENSITIES = {0f, 0.33f, 0.67f, 1f, 1.75f, 2f};

    private static float textPx(String label, float w) { return Math.min(5f, (w - 24f) / Math.max(1, label.length() * 6 - 1)); }   // what Ui.button draws at 100% text size

    @Test public void theMenuIsOneShortColumnAndEverythingElseIsBehindMore() {
        TitleScreen.Item[] with = TitleScreen.menu(true), without = TitleScreen.menu(false);
        assertEquals(4, with.length); assertEquals(4, without.length);          // PLAY/CONTINUE, NEW RUN, SETTINGS, MORE (Build 64: NEW RUN is always there, it is where a course code is typed in)
        assertEquals(TitleScreen.Item.PLAY, with[0]); assertEquals(TitleScreen.Item.PLAY, without[0]);
        assertEquals("MORE is the last button", TitleScreen.Item.MORE, with[with.length - 1]); assertEquals(TitleScreen.Item.MORE, without[without.length - 1]);
        assertEquals("CONTINUE", TitleScreen.Item.PLAY.label(true)); assertEquals("PLAY", TitleScreen.Item.PLAY.label(false)); assertEquals("MORE", TitleScreen.Item.MORE.label(true));
        assertTrue("NEW RUN (random or seeded) is offered with or without a climb", java.util.Arrays.asList(without).contains(TitleScreen.Item.NEW_RUN));
        assertEquals(TitleScreen.TAGLINES[0], TitleScreen.taglineFor(0)); assertEquals(TitleScreen.TAGLINES[1], TitleScreen.taglineFor(1)); assertEquals(TitleScreen.TAGLINES[0], TitleScreen.taglineFor(2));   // the two taglines alternate per visit
        for (String tl : TitleScreen.TAGLINES) assertTrue("fits a phone screen at the title's size: " + tl, tl.length() <= 48);
        TitleScreen.More[] all = TitleScreen.more(true), noLegacy = TitleScreen.more(false);
        assertEquals(6, all.length); assertEquals(5, noLegacy.length);
        assertEquals("EXIT is the last button of the panel", TitleScreen.More.EXIT, all[all.length - 1]); assertEquals(TitleScreen.More.EXIT, noLegacy[noLegacy.length - 1]);
        assertFalse("LEGACY RUNS only exists when there are old climbs", java.util.Arrays.asList(noLegacy).contains(TitleScreen.More.LEGACY));
        java.util.Set<String> labels = new java.util.HashSet<>();
        for (TitleScreen.More it : all) assertTrue("label is unique: " + it.label(1, 3), labels.add(it.label(1, 3)));
        assertTrue(labels.contains("PLAYERS") && labels.contains("HERO: HAMSTER") && labels.contains("TIMES") && labels.contains("LEGACY RUNS (3)") && labels.contains("CREDITS") && labels.contains("EXIT"));
    }

    @Test public void theColumnNeverOverlapsAnythingOnAnyScreenShapeOrDensity() {
        for (float[] sh : SHAPES) for (float upd : DENSITIES) for (int n = 1; n <= 5; n++) {
            float W = sh[0], H = sh[1];
            String where = W + "x" + H + " upd=" + upd + " n=" + n;
            TitleLayout l = TitleLayout.of(W, H, n, upd, TitleLayout.typicalHeadY(H));
            assertEquals(where, n, l.menu.length);
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
                assertEquals(where + " one column: same x", l.menu[0].x, r.x, 0.01f); assertEquals(where + " one column: same width", l.menu[0].w, r.w, 0.01f);
                for (int j = i + 1; j < all.size(); j++) assertFalse(where + " overlap " + r + " / " + all.get(j), r.overlaps(all.get(j)));
            }
        }
    }

    @Test public void everyButtonIsAtLeast48dpTallAndThePrimaryOneAtLeast56dpWhereThereIsRoom() {
        for (float[] sh : SHAPES) for (float upd : DENSITIES) for (int n = 3; n <= 4; n++) {
            String where = sh[0] + "x" + sh[1] + " upd=" + upd + " n=" + n;
            TitleLayout l = TitleLayout.of(sh[0], sh[1], n, upd, TitleLayout.typicalHeadY(sh[1]));
            for (Rect r : l.all()) {
                assertTrue(where + " 48dp tall: " + r.h + " units at " + upd + " per dp", r.h >= 48f * upd - 0.01f);
                assertTrue(where + " 48dp wide: " + r.w, r.w >= 48f * upd - 0.01f);
            }
            assertTrue(where + " primary button 56dp: " + l.menu[0].h, l.menu[0].h >= 56f * Math.min(upd, 1.75f) - 0.01f);
        }
    }

    @Test public void theLabelsFitTheirButtonsAndStayReadable() {
        for (float[] sh : SHAPES) for (float upd : DENSITIES) for (boolean has : new boolean[]{false, true}) {
            TitleScreen.Item[] items = TitleScreen.menu(has);
            TitleLayout l = TitleLayout.of(sh[0], sh[1], items.length, upd, TitleLayout.typicalHeadY(sh[1]));
            for (int i = 0; i < items.length; i++) {
                float px = textPx(items[i].label(has), l.menu[i].w);
                assertTrue(sh[0] + "x" + sh[1] + " " + items[i].label(has) + " text size " + px, px >= 4f);       // 4 units a dot = 28 units tall, 14dp on a 360dp phone
            }
            for (boolean lg : new boolean[]{false, true}) {
                TitleScreen.More[] more = TitleScreen.more(lg);
                TitleLayout.More m = TitleLayout.more(sh[0], sh[1], more.length, upd);
                for (int i = 0; i < more.length; i++) {
                    String label = more[i].label(5, 99);                                   // the longest hero name and a two-digit legacy count
                    assertTrue(sh[0] + "x" + sh[1] + " " + label + " fits " + m.buttons[i], textPx(label, m.buttons[i].w) >= 3.5f);
                }
                assertTrue("BACK fits", textPx("BACK", m.back.w) >= 4f);
            }
        }
    }

    @Test public void theMenuStaysOnTheLeftAndTheHeroInTheMiddle() {
        for (float[] sh : SHAPES) {
            TitleLayout l = TitleLayout.of(sh[0], sh[1], 4, 2f, TitleLayout.typicalHeadY(sh[1]));
            float mid = sh[0] / 2f;
            for (Rect r : l.menu) assertTrue("menu is left of the hero " + r, r.right() <= mid - TitleLayout.HERO_HALF_W);
            assertTrue("nothing is placed right of the hero (the owner's declutter)", l.all().size() == l.menu.length);
        }
    }

    @Test public void theLayoutIsTheSameShapeOnAWiderScreenJustCentred() {
        TitleLayout a = TitleLayout.of(1280, 720, 4, 1f, 379f), b = TitleLayout.of(1680, 720, 4, 1f, 379f);
        for (int i = 0; i < 4; i++) { assertEquals(a.menu[i].y, b.menu[i].y, 0.01f); assertEquals(a.menu[i].w, b.menu[i].w, 0.01f); assertEquals(a.menu[i].x + 200f, b.menu[i].x, 0.01f); }
        TitleLayout.More ma = TitleLayout.more(1280, 720, 6, 1f), mb = TitleLayout.more(1680, 720, 6, 1f);
        assertEquals(ma.panel.w, mb.panel.w, 0.01f); assertEquals(ma.panel.x + 200f, mb.panel.x, 0.01f); assertEquals(ma.panel.y, mb.panel.y, 0.01f);
    }

    @Test public void tooManyButtonsOrATinyScreenShrinkTheRowsInsteadOfOverlapping() {
        TitleLayout l = TitleLayout.of(1280, 720, 8, 3f, 379f);        // more than the layout is sized for, on a very small screen
        List<Rect> all = l.all();
        for (int i = 0; i < all.size(); i++) for (int j = i + 1; j < all.size(); j++) assertFalse(all.get(i) + " / " + all.get(j), all.get(i).overlaps(all.get(j)));
        for (Rect r : all) assertTrue(r.y >= TitleLayout.FLOOR - 0.01f);
        TitleLayout.More m = TitleLayout.more(1280, 720, 12, 3f);
        for (Rect r : m.all()) assertTrue("inside the screen " + r, r.y >= 20f - 0.01f && r.top() <= 700f + 0.01f);
    }

    @Test public void theMorePanelFitsEveryScreenWithBigButtonsAndBackUnderneath() {
        for (float[] sh : SHAPES) for (float upd : DENSITIES) for (int n = 5; n <= 6; n++) {
            float W = sh[0], H = sh[1];
            String where = W + "x" + H + " upd=" + upd + " n=" + n;
            TitleLayout.More m = TitleLayout.more(W, H, n, upd);
            assertEquals(where, n, m.buttons.length);
            assertTrue(where + " panel inside the screen " + m.panel, m.panel.x >= TitleLayout.EDGE - 0.01f && m.panel.right() <= W - TitleLayout.EDGE + 0.01f && m.panel.y >= 20f - 0.01f && m.panel.top() <= H - 20f + 0.01f);
            assertTrue(where + " panel centred", Math.abs((m.panel.x + m.panel.w / 2f) - W / 2f) < 0.01f);
            List<Rect> all = m.all();
            for (int i = 0; i < all.size(); i++) {
                Rect r = all.get(i);
                assertTrue(where + " button inside the panel " + r + " / " + m.panel, r.x >= m.panel.x && r.right() <= m.panel.right() + 0.01f && r.y >= m.panel.y && r.top() <= m.panel.top() - TitleLayout.MORE_PAD - TitleLayout.MORE_TITLE_H + 0.01f);
                if (upd <= 2f) { assertTrue(where + " 48dp tall: " + r.h, r.h >= 48f * upd - 0.01f); assertTrue(where + " 48dp wide: " + r.w, r.w >= 48f * upd - 0.01f); }
                for (int j = i + 1; j < all.size(); j++) assertFalse(where + " overlap " + r + " / " + all.get(j), r.overlaps(all.get(j)));
            }
            for (Rect r : m.buttons) assertTrue(where + " BACK is under the grid " + r + " / " + m.back, m.back.top() <= r.y + 0.01f);
            assertEquals(where + " two columns: first two buttons on one row", m.buttons[0].y, m.buttons[1].y, 0.01f);
            assertTrue(where + " reading order: second column to the right", m.buttons[1].x > m.buttons[0].right());
            if (n % 2 == 1) assertEquals(where + " an odd last button takes the whole row", 2 * m.buttons[0].w + m.gap, m.buttons[n - 1].w, 0.01f);
        }
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

    @Test public void theHeroSpeechBubbleIsNeverHiddenBehindTheMenu() throws Exception {
        String[] lines = heroLines(); assertTrue(lines.length >= 5);
        for (float[] sh : SHAPES) for (int n = 3; n <= 4; n++) {
            float W = sh[0], H = sh[1], headX = W / 2f, headY = TitleLayout.typicalHeadY(H);
            TitleLayout l = TitleLayout.of(W, H, n, 1.75f, headY);
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

    @Test public void theMenuStaysClearOfTheBubbleWhereverTheHeroStands() {
        for (float f : new float[]{0.48f, 0.526f, 0.56f}) for (float[] sh : SHAPES) {
            TitleLayout l = TitleLayout.of(sh[0], sh[1], 4, 1.75f, sh[1] * f);
            for (Rect r : l.all()) assertFalse(sh[0] + "x" + sh[1] + " head " + f + " " + r + " / " + l.bubble, r.overlaps(l.bubble));
        }
    }
}

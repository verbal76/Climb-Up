package com.hotatticgames.climbup.ui;

import java.util.ArrayList;

/**
 * Owner playtest fix (mechanic-introduction pop-ups): the contextual tip as a compact, short-lived banner at the TOP EDGE of the screen, in the
 * band between the height plate and the pause button, never in the middle of the climb. Pure logic and layout (no GL), so the timing and the
 * placement are unit-tested; {@code PlayScreen} decides when a tip is due and draws the box this class lays out.
 *
 * <ul>
 * <li>Timing: a short fade-in, {@link #HOLD} seconds (a few genuinely harder mechanics {@link #HOLD_LONG}), then a {@link #FADE_OUT} fade.</li>
 * <li>One at a time: while a tip shows, and for {@link #GAP} seconds after, no other tip starts (nothing stacks, nothing chains back to back).</li>
 * <li>It yields to the event toast and the world banner, which use the same top band: while one is up the tip hides and its clock stops, so the
 *     two never overlap and the tip is not cut short.</li>
 * <li>{@link #dismiss()} (a tap on the box, or the desktop / controller dismiss control) ends it at once with a very short fade.</li>
 * <li>It never touches the simulation or the input state: it only reads time.</li>
 * </ul>
 */
public final class TipBanner {
    public static final float HOLD = 2.5f, HOLD_LONG = 4.0f, FADE_IN = 0.15f, FADE_OUT = 0.35f, DISMISS_FADE = 0.12f, GAP = 1.2f, YIELD_FADE = 0.12f;

    private String key, text, full;
    private float hold, t, outT = -1f, outLen = FADE_OUT, gapT, vis;
    private String lastKey, lastFull;

    /** True when a new tip may start now (none showing, the gap after the last one over). */
    public boolean ready() { return key == null && gapT <= 0f; }
    /** True while a tip is on (showing, waiting for the band, or fading). */
    public boolean active() { return key != null; }
    public String key() { return key; }
    public String text() { return text; }
    public String full() { return full; }
    /** The most recently started tip (for the pause menu), or null. */
    public String lastKey() { return lastKey; }
    public String lastFull() { return lastFull; }
    /** Seconds the current tip has been on screen (its clock stops while it yields). */
    public float shownFor() { return t; }

    /** Starts a tip if {@link #ready()}; returns false (and changes nothing) otherwise. */
    public boolean show(String key, String text, String full, float hold) {
        if (!ready() || key == null || text == null) return false;
        this.key = key; this.text = text; this.full = full != null ? full : text; this.hold = Math.max(0.5f, hold);
        t = 0f; outT = -1f; vis = 0f;
        lastKey = key; lastFull = this.full;
        return true;
    }

    /** Ends the tip now (a short fade); no effect when none is showing or it is already leaving. */
    public void dismiss() {
        if (key == null) return;
        if (outT < 0f) { outT = 0f; outLen = DISMISS_FADE; }
        else if (outLen > DISMISS_FADE) { float k = outT / outLen; outLen = DISMISS_FADE; outT = k * DISMISS_FADE; }
    }

    /** Drops any tip at once (the climb reset or the screen closed). */
    public void clear() { key = null; text = full = null; outT = -1f; vis = 0f; gapT = 0f; }

    /**
     * @param yield true while the toast or the world banner occupies the top band: the tip hides (fast fade) and its clock stops
     */
    public void update(float dt, boolean yield) {
        if (dt <= 0f) return;
        if (key == null) { if (gapT > 0f) gapT = Math.max(0f, gapT - dt); return; }
        if (outT >= 0f) { outT += dt; if (outT >= outLen) end(); return; }      // leaving: finishes whatever else is up
        if (yield) { vis = Math.max(0f, vis - dt / YIELD_FADE); return; }
        vis = Math.min(1f, vis + dt / (t < FADE_IN ? FADE_IN : YIELD_FADE));
        t += dt;
        if (t >= hold) { outT = 0f; outLen = FADE_OUT; }
    }

    private void end() { key = null; text = full = null; outT = -1f; vis = 0f; gapT = GAP; }

    /** Opacity to draw the banner with now (0 = nothing to draw). */
    public float alpha() {
        if (key == null) return 0f;
        float a = vis;
        if (outT >= 0f) a *= Math.max(0f, 1f - outT / outLen);
        return Math.max(0f, Math.min(1f, a));
    }
    /** True when the banner is drawn now (and so can be tapped away). */
    public boolean visible() { return alpha() > 0.05f; }

    // ------------------------------------------------------------------ layout

    /** Text width at a font size (the game's PixelFont: {@code width(s, px)}). */
    public interface Measure { float width(String s, float px); }

    /** The banner's rectangle in UI units (y up), its text size and lines. */
    public static final class Box {
        public float x, y, w, h, px, lineH, pad;
        public String[] lines = new String[0];
        /** Width kept at the right end for the dismiss mark (0 = none). */
        public float markW;
        public boolean truncated;
        public boolean contains(float px0, float py0, float slop) { return px0 >= x - slop && px0 <= x + w + slop && py0 >= y - slop && py0 <= y + h + slop; }
    }

    /** HUD geometry this layout keeps clear of (mirrors PlayScreen.drawHud). */
    public static final float MARGIN = 28f, PLATE_W = 380f, PAUSE = 96f, GAP_X = 18f, PX = 3.0f, MIN_PX = 0.75f, PAD = 14f, GLYPH_H = 7f, LINE_GAP = 8f;
    public static final int MAX_LINES = 2;

    /** Size of the dismiss mark relative to the text: a one-letter "X" at full size (it is the touch target's cue), a key or button name a little smaller. */
    public static float markScale(String mark) { return mark != null && mark.length() == 1 ? 1f : 0.8f; }

    /** Right edge of the height plate (or of the REDUCED HUD's tower bar, which is narrower) at text multiplier {@code tm}. */
    public static float plateRight(float tm) { float zk = Math.min(tm, 1.3f); return MARGIN - 6f + PLATE_W * zk + 12f; }

    /**
     * Lays the banner out in the top band: between the height plate and the pause button (touch, right-handed) or the right margin, centred in
     * that span, top edge {@link #MARGIN} below the screen's top. At most {@link #MAX_LINES} lines; a text that needs more first shrinks (down to
     * {@link #MIN_PX} of its size) and only then is cut with "...". {@code mark} is the dismiss hint drawn small at the right end ("X", "TAB", ...) or null.
     */
    public static Box layout(String text, String mark, float W, float H, float tm, boolean touch, boolean leftHanded, Measure m, Box out) {
        if (out == null) out = new Box();
        float left = plateRight(tm) + GAP_X;
        if (touch && leftHanded) left = Math.max(left, MARGIN + PAUSE + GAP_X);
        float right = W - MARGIN - (touch && !leftHanded ? PAUSE + GAP_X : 0f);
        float base = PX * tm, px = base;
        out.pad = PAD;
        String[] lines = null;
        float markW = 0f, avail = 0f;
        for (int step = 0; step < 8; step++) {
            markW = mark == null || mark.isEmpty() ? 0f : m.width(mark, px * markScale(mark)) + PAD;
            avail = right - left - 2 * PAD - markW;
            lines = wrap(text, px, avail, m);
            if (lines.length <= MAX_LINES || px <= base * MIN_PX + 1e-4f) break;
            px = Math.max(base * MIN_PX, px * 0.92f);
        }
        out.truncated = false;
        if (lines.length > MAX_LINES) {
            String[] cut = new String[MAX_LINES];
            System.arraycopy(lines, 0, cut, 0, MAX_LINES);
            String last = cut[MAX_LINES - 1] + "...";
            while (m.width(last, px) > avail && last.length() > 4) last = last.substring(0, last.length() - 4).trim() + "...";
            cut[MAX_LINES - 1] = last; lines = cut; out.truncated = true;
        }
        float tw = 0f; for (String l : lines) tw = Math.max(tw, m.width(l, px));
        out.px = px; out.lineH = GLYPH_H * px + LINE_GAP; out.lines = lines; out.markW = markW;
        out.w = tw + 2 * PAD + markW;
        out.h = lines.length * out.lineH - LINE_GAP + 2 * PAD;
        float cx = (left + right) / 2f;
        out.x = Math.max(left, Math.min(right - out.w, cx - out.w / 2f));
        out.y = H - MARGIN - out.h;
        return out;
    }

    /** Greedy word wrap at {@code maxW}; a single word wider than the line stays on its own line. */
    public static String[] wrap(String s, float px, float maxW, Measure m) {
        ArrayList<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String word : s.split(" ")) {
            if (word.isEmpty()) continue;
            String t = cur.length() == 0 ? word : cur + " " + word;
            if (m.width(t, px) > maxW && cur.length() > 0) { out.add(cur.toString()); cur = new StringBuilder(word); } else { cur = new StringBuilder(t); }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out.toArray(new String[0]);
    }
}

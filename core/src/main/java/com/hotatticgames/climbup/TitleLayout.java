package com.hotatticgames.climbup;

/**
 * Where everything on the title screen goes, as plain numbers (no GL), so a test can check every screen shape. Units are the UI's virtual units: 1280 x 720, extended wider (21:9) or taller (4:3).
 *
 * <pre>
 *   [ title art, full width, at the top ]
 *   [ MENU GRID (left)      ]   [ hero stands here ]   [ PLAYING AS / HERO / LEGACY (right) ]
 *   [ status bar ]
 * </pre>
 *
 * The menu is one wide primary button (PLAY or CONTINUE) and then pairs of buttons; an odd last button (EXIT) takes the full width. Up to 7 buttons make 4 rows, so they fit under the title on a
 * small landscape phone with every button at least 48dp tall (the primary one 56dp when there is room). The button height is worked out from the screen's real density ({@code unitsPerDp}):
 * a big TV or monitor keeps the normal size, a small phone gets taller buttons, and if even that cannot fit, rows shrink to what fits rather than overlap.
 * Everything is laid out inside a 1200-unit wide area centred on the screen, so a wider screen only adds scenery at the sides.
 * The hero's speech bubble (up to about 630 units wide, just above his head) is kept clear: it is drawn starting right of the menu ({@link #bubbleMinX}), and the right column sits below it.
 */
public final class TitleLayout {
    public static final float EDGE = 40f;                    // nothing closer than this to a side (rounded phone corners)
    public static final float FLOOR = 46f;                   // lowest a button may start: the 34-unit status bar plus a button's 10-unit shadow
    public static final float ART_BOTTOM = 256f;             // the title lettering and its tagline end this far below the top
    public static final float ART_TO_MENU = 14f;             // clear space between the tagline and the first button
    public static final float HERO_HALF_W = 100f;            // half the width kept free around the hero in the middle
    public static final float ZONE_GAP = 24f;                // between a button column and the hero's space
    public static final float DEF_SECONDARY = 84f, DEF_PRIMARY = 96f, MAX_SECONDARY = 120f, MAX_PRIMARY = 132f;
    public static final float MIN_DP = 48f, PRIMARY_DP = 56f;
    public static final float PLAYING_H = 80f;               // the "PLAYING AS" block above the hero button

    /** A rectangle, bottom-left origin like the rest of the UI. */
    public static final class Rect {
        public final float x, y, w, h;
        Rect(float x, float y, float w, float h) { this.x = x; this.y = y; this.w = w; this.h = h; }
        public float right() { return x + w; }
        public float top() { return y + h; }
        public boolean overlaps(Rect o) { return x < o.x + o.w && o.x < x + w && y < o.y + o.h && o.y < y + h; }
        @Override public String toString() { return "[" + x + "," + y + " " + w + "x" + h + "]"; }
    }

    /** The menu buttons in reading order (primary first, EXIT last). */
    public final Rect[] menu;
    /** The "PLAYING AS" caption block, the hero picker button, and the LEGACY RUNS button (null when there are none). */
    public final Rect playing, hero, legacy;
    /** Places the layout must stay clear of: the title art, the hero's space, the status bar. */
    public final Rect art, heroZone, statusBar;
    /** Where the hero's speech bubble can appear (its body starts at {@link #bubbleMinX} at the earliest). Nothing else is placed here. */
    public final Rect bubble;
    public final float bubbleMinX;
    public final float primaryH, secondaryH, gap;

    private TitleLayout(Rect[] menu, Rect playing, Rect hero, Rect legacy, Rect art, Rect heroZone, Rect statusBar, Rect bubble, float primaryH, float secondaryH, float gap) {
        this.menu = menu; this.playing = playing; this.hero = hero; this.legacy = legacy; this.art = art; this.heroZone = heroZone; this.statusBar = statusBar; this.bubble = bubble; this.bubbleMinX = bubble.x;
        this.primaryH = primaryH; this.secondaryH = secondaryH; this.gap = gap;
    }

    /** All rectangles that hold something, for overlap checks. */
    public java.util.List<Rect> all() {
        java.util.List<Rect> l = new java.util.ArrayList<>(java.util.Arrays.asList(menu));
        l.add(playing); l.add(hero); if (legacy != null) l.add(legacy);
        return l;
    }

    /**
     * @param W,H         the virtual screen size (W at least 1280, H at least 720)
     * @param n           how many menu buttons (1 to 8 make sense; 5 to 7 are expected)
     * @param hasLegacy   whether the LEGACY RUNS button is shown
     * @param unitsPerDp  virtual units per Android dp (0 if unknown): the viewport's world height per screen pixel times the screen density
     * @param headY       the virtual height of the top of the hero's head on screen (the speech bubble sits just above it); {@link #typicalHeadY} is where it is on every screen shape
     */
    public static TitleLayout of(float W, float H, int n, boolean hasLegacy, float unitsPerDp, float headY) {
        n = Math.max(1, n);
        float upd = unitsPerDp > 0f && unitsPerDp < 10f ? unitsPerDp : 0f;
        float cx = W / 2f;
        float ax0 = Math.max(EDGE, cx - 600f), ax1 = Math.min(W - EDGE, cx + 600f);
        float leftX1 = cx - HERO_HALF_W - ZONE_GAP;
        float leftW = Math.max(120f, leftX1 - ax0);
        float top = H - ART_BOTTOM - ART_TO_MENU, band = top - FLOOR;
        int rows = 1 + n / 2, sec = rows - 1;

        float s = Math.min(MAX_SECONDARY, Math.max(DEF_SECONDARY, MIN_DP * upd));
        float p = Math.min(MAX_PRIMARY, Math.max(Math.max(DEF_PRIMARY, PRIMARY_DP * upd), s));
        float gap = 12f;
        if (p + sec * (s + gap) > band) gap = 8f;
        if (p + sec * (s + gap) > band) gap = 6f;
        float over = p + sec * (s + gap) - band;
        if (over > 0f) p = Math.max(s, p - over);                    // the primary button gives way first
        over = p + sec * (s + gap) - band;
        if (over > 0f) { s = p = Math.max(40f, (band - sec * gap) / rows); }   // then every row shrinks equally: never overlap

        Rect[] menu = new Rect[n];
        float y = top;
        menu[0] = new Rect(ax0, y - p, leftW, p); y -= p + gap;
        float cw = (leftW - gap) / 2f;
        for (int i = 1; i < n; ) {
            if (i + 1 < n) { menu[i] = new Rect(ax0, y - s, cw, s); menu[i + 1] = new Rect(ax0 + cw + gap, y - s, cw, s); i += 2; }
            else { menu[i] = new Rect(ax0, y - s, leftW, s); i++; }     // an odd last button takes the whole width
            y -= s + gap;
        }

        float bubbleBottom = headY - 10f;                              // the bubble's tail reaches 14 below its body, which starts 6 above the head
        float rightTop = Math.min(top, bubbleBottom - 12f);            // the right column starts under the bubble's space
        float rw = Math.min(400f, ax1 - (cx + HERO_HALF_W + ZONE_GAP)), rx = ax1 - rw;
        Rect playing = new Rect(rx, rightTop - PLAYING_H, rw, PLAYING_H);
        float hy = rightTop - PLAYING_H - 14f - s;
        Rect hero = new Rect(rx, hy, rw, s);
        Rect legacy = hasLegacy ? new Rect(rx, hy - gap - s, rw, s) : null;

        Rect art = new Rect(cx - 490f, H - ART_BOTTOM, 980f, ART_BOTTOM);
        Rect heroZone = new Rect(cx - HERO_HALF_W, H * 0.18f, 2 * HERO_HALF_W, H * 0.37f);
        Rect statusBar = new Rect(0f, 0f, W, 34f);
        Rect bubble = new Rect(leftX1 + 16f, bubbleBottom, BUBBLE_W, BUBBLE_H);
        return new TitleLayout(menu, playing, hero, legacy, art, heroZone, statusBar, bubble, p, s, gap);
    }

    public static final float BUBBLE_W = 660f, BUBBLE_H = 84f;
    /** Where the top of the hero's head is on the title, as a fraction of the screen height (measured on 16:9 and 4:3; the title only uses it when the real position cannot be read). */
    public static final float HEAD_FRACTION = 0.526f;
    public static float typicalHeadY(float H) { return H * HEAD_FRACTION; }

    /** The left edge of the speech bubble's body: centred on the hero's head, but not left of {@code minX} (the menu's right edge) and always on the screen. */
    public static float bubbleX(float W, float bodyW, float headX, float minX) { return Math.max(16f, Math.min(W - bodyW - 16f, Math.max(minX, headX - bodyW / 2f))); }
}

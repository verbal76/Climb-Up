package com.hotatticgames.climbup;

/**
 * Where everything on the title screen goes, as plain numbers (no GL), so a test can check every screen shape. Units are the UI's virtual units: 1280 x 720, extended wider (21:9) or taller (4:3).
 *
 * <pre>
 *   [ title art, full width, at the top ]
 *   [ MENU COLUMN (left) ]   [ hero stands here ]   [ open sky and tower ]
 *   [ status bar: player name (hero), best height, best finish        version ]
 * </pre>
 *
 * The menu is ONE column on the left (the owner's declutter): a wide primary button (PLAY or CONTINUE), then NEW RUN, SETTINGS, and MORE beside EXIT on the last row (Build 67: the owner wants
 * EXIT on the title itself). Everything else (PLAYERS, the hero picker, TIMES, LEGACY RUNS, CREDITS) lives behind MORE, in a centred panel ({@link #more}), so the tower and the hero on the right
 * are never walled off by buttons.
 * Up to 4 rows fit under the title on a small landscape phone with every button at least 48dp tall (the primary one 56dp when there is room). The button height is worked out from the
 * screen's real density ({@code unitsPerDp}): a big TV or monitor keeps the normal size, a small phone gets taller buttons, and if even that cannot fit, rows shrink to what fits rather than overlap.
 * Everything is laid out inside a 1200-unit wide area centred on the screen, so a wider screen only adds scenery at the sides.
 * The hero's speech bubble (up to about 630 units wide, just above his head) is kept clear: it is drawn starting right of the menu ({@link #bubbleMinX}).
 */
public final class TitleLayout {
    public static final float EDGE = 40f;                    // nothing closer than this to a side (rounded phone corners)
    public static final float FLOOR = 46f;                   // lowest a button may start: the 34-unit status bar plus a button's 10-unit shadow
    public static final float ART_BOTTOM = 256f;             // the title lettering and its tagline end this far below the top
    public static final float ART_TO_MENU = 14f;             // clear space between the tagline and the first button
    public static final float HERO_HALF_W = 100f;            // half the width kept free around the hero in the middle
    public static final float ZONE_GAP = 24f;                // between the button column and the hero's space
    public static final float DEF_SECONDARY = 84f, DEF_PRIMARY = 96f, MAX_SECONDARY = 120f, MAX_PRIMARY = 132f;
    public static final float MIN_DP = 48f, PRIMARY_DP = 56f;
    public static final float COLUMN_W = 420f;               // the menu column is this wide at most (a single column of slabs reads better narrower than the old two-column block)

    /** A rectangle, bottom-left origin like the rest of the UI. */
    public static final class Rect {
        public final float x, y, w, h;
        Rect(float x, float y, float w, float h) { this.x = x; this.y = y; this.w = w; this.h = h; }
        public float right() { return x + w; }
        public float top() { return y + h; }
        public boolean overlaps(Rect o) { return x < o.x + o.w && o.x < x + w && y < o.y + o.h && o.y < y + h; }
        @Override public String toString() { return "[" + x + "," + y + " " + w + "x" + h + "]"; }
    }

    /** The menu buttons in reading order (primary first, MORE last). */
    public final Rect[] menu;
    /** Places the layout must stay clear of: the title art, the hero's space, the status bar. */
    public final Rect art, heroZone, statusBar;
    /** Where the hero's speech bubble can appear (its body starts at {@link #bubbleMinX} at the earliest). Nothing else is placed here. */
    public final Rect bubble;
    public final float bubbleMinX;
    public final float primaryH, secondaryH, gap;

    private TitleLayout(Rect[] menu, Rect art, Rect heroZone, Rect statusBar, Rect bubble, float primaryH, float secondaryH, float gap) {
        this.menu = menu; this.art = art; this.heroZone = heroZone; this.statusBar = statusBar; this.bubble = bubble; this.bubbleMinX = bubble.x;
        this.primaryH = primaryH; this.secondaryH = secondaryH; this.gap = gap;
    }

    /** All rectangles that hold something, for overlap checks. */
    public java.util.List<Rect> all() { return new java.util.ArrayList<>(java.util.Arrays.asList(menu)); }

    /** The secondary button height for a density: the normal size, raised to 48dp on a small screen, capped. */
    static float secondaryFor(float upd) { return Math.min(MAX_SECONDARY, Math.max(DEF_SECONDARY, MIN_DP * upd)); }

    /**
     * @param W,H         the virtual screen size (W at least 1280, H at least 720)
     * @param n           how many menu buttons (PLAY/CONTINUE, NEW RUN, SETTINGS, MORE, and EXIT since Build 67)
     * @param unitsPerDp  virtual units per Android dp (0 if unknown): the viewport's world height per screen pixel times the screen density
     * @param headY       the virtual height of the top of the hero's head on screen (the speech bubble sits just above it); {@link #typicalHeadY} is where it is on every screen shape
     */
    public static TitleLayout of(float W, float H, int n, float unitsPerDp, float headY) { return of(W, H, n, unitsPerDp, headY, false); }

    /**
     * @param pairLast    Build 67 (owner: EXIT on the title, not under MORE): the last two buttons share the bottom row, side by side, so the column keeps its four rows and every
     *                    button its 48dp; needs at least three buttons (the primary one is never paired)
     */
    public static TitleLayout of(float W, float H, int n, float unitsPerDp, float headY, boolean pairLast) {
        n = Math.max(1, n);
        boolean pair = pairLast && n >= 3;
        float upd = unitsPerDp > 0f && unitsPerDp < 10f ? unitsPerDp : 0f;
        float cx = W / 2f;
        float ax0 = Math.max(EDGE, cx - 600f);
        float leftX1 = cx - HERO_HALF_W - ZONE_GAP;
        float leftW = Math.min(COLUMN_W, Math.max(120f, leftX1 - ax0));
        float top = H - ART_BOTTOM - ART_TO_MENU, band = top - FLOOR;
        int rows = pair ? n - 1 : n, sec = rows - 1;

        float s = secondaryFor(upd);
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
        for (int i = 1; i < (pair ? n - 2 : n); i++) { menu[i] = new Rect(ax0, y - s, leftW, s); y -= s + gap; }
        if (pair) { float hw = (leftW - gap) / 2f; menu[n - 2] = new Rect(ax0, y - s, hw, s); menu[n - 1] = new Rect(ax0 + hw + gap, y - s, hw, s); }

        float bubbleBottom = headY - 10f;                              // the bubble's tail reaches 14 below its body, which starts 6 above the head
        Rect art = new Rect(cx - 490f, H - ART_BOTTOM, 980f, ART_BOTTOM);
        Rect heroZone = new Rect(cx - HERO_HALF_W, H * 0.18f, 2 * HERO_HALF_W, H * 0.37f);
        Rect statusBar = new Rect(0f, 0f, W, 34f);
        Rect bubble = new Rect(ax0 + leftW + 16f, bubbleBottom, BUBBLE_W, BUBBLE_H);
        return new TitleLayout(menu, art, heroZone, statusBar, bubble, p, s, gap);
    }

    public static final float BUBBLE_W = 660f, BUBBLE_H = 84f;
    /** Where the top of the hero's head is on the title, as a fraction of the screen height (measured on 16:9 and 4:3; the title only uses it when the real position cannot be read). */
    public static final float HEAD_FRACTION = 0.526f;
    public static float typicalHeadY(float H) { return H * HEAD_FRACTION; }

    /** The left edge of the speech bubble's body: centred on the hero's head, but not left of {@code minX} (the menu's right edge) and always on the screen. */
    public static float bubbleX(float W, float bodyW, float headX, float minX) { return Math.max(16f, Math.min(W - bodyW - 16f, Math.max(minX, headX - bodyW / 2f))); }

    // ---- the MORE panel: everything that left the title screen, in a centred panel over the dimmed title
    public static final float MORE_PAD = 36f, MORE_TITLE_H = 44f, MORE_MAX_W = 920f;     // wide enough for "HERO: BARBARA BEE" in a half-width button

    /** The MORE panel: its frame, its buttons in reading order (two columns, left to right then down) and the BACK button under them. */
    public static final class More {
        public final Rect panel, back;
        public final Rect[] buttons;
        public final float buttonH, gap;
        More(Rect panel, Rect[] buttons, Rect back, float buttonH, float gap) { this.panel = panel; this.buttons = buttons; this.back = back; this.buttonH = buttonH; this.gap = gap; }
        public java.util.List<Rect> all() { java.util.List<Rect> l = new java.util.ArrayList<>(java.util.Arrays.asList(buttons)); l.add(back); return l; }
    }

    /**
     * @param n           how many buttons the panel holds (4 or 5: PLAYERS, HERO, TIMES, [LEGACY RUNS], CREDITS)
     * @param unitsPerDp  as for {@link #of}
     */
    public static More more(float W, float H, int n, float unitsPerDp) {
        n = Math.max(1, n);
        float upd = unitsPerDp > 0f && unitsPerDp < 10f ? unitsPerDp : 0f;
        int rows = (n + 1) / 2;
        float s = secondaryFor(upd), gap = 12f;
        float pw = Math.min(W - 2 * EDGE, MORE_MAX_W);
        float need = 2 * MORE_PAD + MORE_TITLE_H + rows * (s + gap) + s;          // title, the grid, BACK
        float maxH = H - 2 * 20f;
        if (need > maxH) { gap = 6f; need = 2 * MORE_PAD + MORE_TITLE_H + rows * (s + gap) + s; }
        if (need > maxH) { s = Math.max(40f, (maxH - 2 * MORE_PAD - MORE_TITLE_H - rows * gap) / (rows + 1)); need = 2 * MORE_PAD + MORE_TITLE_H + rows * (s + gap) + s; }
        float ph = need, px = W / 2f - pw / 2f, py = Math.max(20f, H / 2f - ph / 2f);
        float cw = (pw - 2 * MORE_PAD - gap) / 2f, gridTop = py + ph - MORE_PAD - MORE_TITLE_H;
        Rect[] b = new Rect[n];
        for (int i = 0; i < n; i++) {
            int r = i / 2, c = i % 2;
            boolean lastOdd = i == n - 1 && c == 0;                                  // an odd last button takes the whole row
            float x = px + MORE_PAD + c * (cw + gap), w = lastOdd ? 2 * cw + gap : cw;
            b[i] = new Rect(x, gridTop - (r + 1) * s - r * gap, w, s);
        }
        Rect back = new Rect(px + MORE_PAD, py + MORE_PAD, pw - 2 * MORE_PAD, s);
        return new More(new Rect(px, py, pw, ph), b, back, s, gap);
    }
}

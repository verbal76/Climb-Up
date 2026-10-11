package com.hotatticgames.climbup.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.viewport.ExtendViewport;
import com.hotatticgames.climbup.Settings;

/** Tiny immediate-mode UI: voxel-style panels/buttons, taps resolved on release. Virtual space is 1280x720 (extends wider). */
public final class Ui extends InputAdapter implements Disposable {
    public static final Color INK = new Color(0.07f, 0.09f, 0.16f, 1f), PANEL = new Color(0.13f, 0.17f, 0.30f, 0.94f), EDGE = new Color(0.95f, 0.78f, 0.25f, 1f),
            ACCENT = new Color(1f, 0.62f, 0.12f, 1f), TEXT = new Color(1f, 0.98f, 0.92f, 1f), DIM = new Color(0.7f, 0.76f, 0.9f, 1f), SHADOW = new Color(0f, 0f, 0f, 0.55f),
            GOOD = new Color(0.45f, 0.95f, 0.55f, 1f);

    public final SpriteBatch batch = new SpriteBatch();
    public final PixelFont font = new PixelFont();
    public final ExtendViewport viewport = new ExtendViewport(1280, 720);
    private final Settings settings;
    private final Vector2 v = new Vector2();
    private boolean down, tapped; private float downX, downY, tapX, tapY, curX, curY;
    /** Desktop only: snap text size and position to whole device pixels so the pixel font stays sharp at any window size (off = the original behaviour). */
    public boolean crisp;
    public boolean captured;     // set by screens that handle raw touch themselves

    /** Keyboard/controller/mouse-hover menu navigation (desktop only; null on mobile, where every code path below is skipped). */
    public interface Navigator {
        void beginFrame(Ui ui);
        /** Called for every button drawn this frame. Bit 0 of the result: this button has the focus (draw the ring); bit 2: it is the focused one (draw it orange); bit 1: it was activated without a pointer (confirm, or back for a BACK button). */
        int button(String label, float x, float y, float w, float h, boolean accent);
        void endFrame();
        /** Any confirm/back style press this frame (skips the studio splash). */
        boolean anyPress();
    }
    public Navigator nav;

    public Ui(Settings s) { settings = s; }

    public float w() { return viewport.getWorldWidth(); }
    public float h() { return viewport.getWorldHeight(); }
    public float tm() { return settings.textMul(); }

    public void resize(int w, int h) { viewport.update(w, h, true); }
    // Test hook: -Dclimb.taps="x,y@seconds;x,y@seconds" injects taps (virtual coords) through the normal button path, timed from startup.
    private final String[] scriptedTaps = System.getProperty("climb.taps", "").isEmpty() ? new String[0] : System.getProperty("climb.taps").split(";");
    private int nextTap; private float clock;

    public void begin() {
        clock += Gdx.graphics.getDeltaTime();
        if (nav != null) nav.beginFrame(this);
        if (nextTap < scriptedTaps.length) {
            String[] a = scriptedTaps[nextTap].split("[,@]");
            if (clock >= Float.parseFloat(a[2])) { tapX = Float.parseFloat(a[0]); tapY = Float.parseFloat(a[1]); tapped = true; nextTap++; }
        }
        viewport.apply(); batch.setProjectionMatrix(viewport.getCamera().combined); batch.begin(); }
    public void end() { batch.end(); tapped = false; if (nav != null) nav.endFrame(); }

    // ---- input
    @Override public boolean touchDown(int sx, int sy, int p, int b) { if (p != 0 || (nav != null && b != com.badlogic.gdx.Input.Buttons.LEFT)) return false; unproject(sx, sy); down = true; downX = curX = v.x; downY = curY = v.y; return false; }
    @Override public boolean touchDragged(int sx, int sy, int p) { if (p != 0) return false; unproject(sx, sy); curX = v.x; curY = v.y; return false; }
    @Override public boolean touchUp(int sx, int sy, int p, int b) {
        if (p != 0 || (nav != null && b != com.badlogic.gdx.Input.Buttons.LEFT)) return false; unproject(sx, sy); down = false;
        if (Math.hypot(v.x - downX, v.y - downY) < 60) { tapped = true; tapX = v.x; tapY = v.y; }
        return false;
    }
    private void unproject(int sx, int sy) { v.set(sx, sy); viewport.unproject(v); }

    // ---- drawing helpers
    private static boolean in(float px, float py, float x, float y, float w, float h) { return px >= x && px <= x + w && py >= y && py <= y + h; }

    public void rect(float x, float y, float w, float h, Color c) { batch.setColor(c); font.rect(batch, x, y, w, h); batch.setColor(Color.WHITE); }

    public void panel(float x, float y, float w, float h) {
        rect(x - 6, y - 6, w + 12, h + 12, SHADOW); rect(x - 4, y - 4, w + 8, h + 8, EDGE); rect(x, y, w, h, PANEL);
    }

    /** Device pixels per virtual unit right now. */
    public float deviceScale() { float ww = viewport.getWorldWidth(); return ww > 0 ? viewport.getScreenWidth() / ww : 1f; }
    /** Font-pixel size rounded to a whole number of device pixels (at least 1); pure. */
    public static float snapSize(float px, float scale) { return scale <= 0 ? px : Math.max(1, Math.round(px * scale)) / scale; }
    /** A position rounded to a whole device pixel; pure. */
    public static float snapPos(float v, float scale) { return scale <= 0 ? v : Math.round(v * scale) / scale; }

    public void text(String s, float x, float y, float px, Color c) {
        if (crisp) { float k = deviceScale(); px = snapSize(px, k); x = snapPos(x, k); y = snapPos(y, k); }
        font.drawShadow(batch, s, x, y, px, c, SHADOW);
    }
    /** Comic speech bubble centred above (cx, y). */
    public void bubble(String s, float cx, float y, float alpha) {
        float px = 3.2f * tm(); float w = font.width(s, px) + 36, h = font.height(px) + 28;
        float x = Math.max(16, Math.min(w() - w - 16, cx - w / 2));
        Color edge = new Color(0.08f, 0.08f, 0.14f, alpha), fill = new Color(1f, 1f, 1f, alpha);
        rect(x - 4, y - 4, w + 8, h + 8, edge); rect(x, y, w, h, fill);
        rect(cx - 8, y - 14, 16, 14, edge); rect(cx - 4, y - 8, 8, 10, fill);     // tail pointing down at him
        font.draw2(batch, s, x + 18, y + 14, px, new Color(0.08f, 0.08f, 0.14f, alpha));
    }

    public void textC(String s, float cx, float y, float px, Color c) { if (crisp) px = snapSize(px, deviceScale()); text(s, cx - font.width(s, px) / 2f, y, px, c); }
    public void textBody(String s, float cx, float y, float px, Color c) { textC(s, cx, y, px * tm(), c); }

    /** A big tappable button; returns true on the frame the tap is released inside it. */
    public boolean button(String label, float x, float y, float w, float h) { return button(label, x, y, w, h, false); }

    public boolean button(String label, float x, float y, float w, float h, boolean accent) {
        boolean pressed = down && in(downX, downY, x, y, w, h) && in(curX, curY, x, y, w, h);
        float o = pressed ? 4 : 0;
        int f = nav != null ? nav.button(label, x, y, w, h, accent) : 0;
        if (nav != null) accent = (f & 4) != 0;                // with a keyboard, mouse or pad only the highlighted button is orange
        rect(x - 4, y - 4 - 6 + o, w + 8, h + 8, SHADOW);
        rect(x - 4, y - 4 + o, w + 8, h + 8, accent ? EDGE : DIM);
        rect(x, y + o, w, h, accent ? ACCENT : PANEL);
        rect(x, y + o + h - 6, w, 6, new Color(1, 1, 1, 0.14f));
        float px = Math.min(5f * tm(), (w - 24) / Math.max(1, label.length() * PixelFont.ADV - 1));
        px = Math.max(2f, px);
        text(label, x + w / 2 - font.width(label, px) / 2, y + o + h / 2 - font.height(px) / 2, px, TEXT);
        if (nav != null) {
            if ((f & 1) != 0) focusRing(x, y + o, w, h);
            if ((f & 2) != 0) return true;
        }
        if (tapped && in(tapX, tapY, x, y, w, h)) { tapped = false; return true; }
        return false;
    }

    private void focusRing(float x, float y, float w, float h) {
        float t = 4f, g = 10f;
        rect(x - g, y - g, w + 2 * g, t, TEXT); rect(x - g, y + h + g - t, w + 2 * g, t, TEXT);
        rect(x - g, y - g, t, h + 2 * g, TEXT); rect(x + w + g - t, y - g, t, h + 2 * g, TEXT);
    }

    public boolean tappedIn(float x, float y, float w, float h) {
        if (tapped && in(tapX, tapY, x, y, w, h)) { tapped = false; return true; }
        return false;
    }
    public boolean anyTap() { boolean t = tapped || (nav != null && nav.anyPress()); tapped = false; return t; }

    @Override public void dispose() { batch.dispose(); font.dispose(); }
}

package com.hotatticgames.climbup.ui;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.utils.Disposable;

/** Original 5x7 voxel-style bitmap font generated from code (no external font asset needed). */
public final class PixelFont implements Disposable {
    private static final String CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789.,:!?-+/()%'=<>_";
    private static final String[] ROWS = {
        "01110,10001,10001,11111,10001,10001,10001", "11110,10001,10001,11110,10001,10001,11110", "01110,10001,10000,10000,10000,10001,01110",
        "11110,10001,10001,10001,10001,10001,11110", "11111,10000,10000,11110,10000,10000,11111", "11111,10000,10000,11110,10000,10000,10000",
        "01110,10001,10000,10111,10001,10001,01111", "10001,10001,10001,11111,10001,10001,10001", "01110,00100,00100,00100,00100,00100,01110",
        "00111,00010,00010,00010,00010,10010,01100", "10001,10010,10100,11000,10100,10010,10001", "10000,10000,10000,10000,10000,10000,11111",
        "10001,11011,10101,10101,10001,10001,10001", "10001,11001,10101,10011,10001,10001,10001", "01110,10001,10001,10001,10001,10001,01110",
        "11110,10001,10001,11110,10000,10000,10000", "01110,10001,10001,10001,10101,10010,01101", "11110,10001,10001,11110,10100,10010,10001",
        "01111,10000,10000,01110,00001,00001,11110", "11111,00100,00100,00100,00100,00100,00100", "10001,10001,10001,10001,10001,10001,01110",
        "10001,10001,10001,10001,10001,01010,00100", "10001,10001,10001,10101,10101,10101,01010", "10001,10001,01010,00100,01010,10001,10001",
        "10001,10001,01010,00100,00100,00100,00100", "11111,00001,00010,00100,01000,10000,11111",
        "01110,10001,10011,10101,11001,10001,01110", "00100,01100,00100,00100,00100,00100,01110", "01110,10001,00001,00010,00100,01000,11111",
        "11110,00001,00001,01110,00001,00001,11110", "00010,00110,01010,10010,11111,00010,00010", "11111,10000,11110,00001,00001,10001,01110",
        "00110,01000,10000,11110,10001,10001,01110", "11111,00001,00010,00100,01000,01000,01000", "01110,10001,10001,01110,10001,10001,01110",
        "01110,10001,10001,01111,00001,00010,01100",
        "00000,00000,00000,00000,00000,01100,01100", "00000,00000,00000,00000,01100,00100,01000", "00000,01100,01100,00000,01100,01100,00000",
        "00100,00100,00100,00100,00100,00000,00100", "01110,10001,00001,00010,00100,00000,00100", "00000,00000,00000,11111,00000,00000,00000",
        "00000,00100,00100,11111,00100,00100,00000", "00001,00010,00010,00100,01000,01000,10000", "00010,00100,01000,01000,01000,00100,00010",
        "01000,00100,00010,00010,00010,00100,01000", "11001,11010,00010,00100,01000,01011,10011", "00100,00100,00000,00000,00000,00000,00000",
        "00000,00000,11111,00000,11111,00000,00000", "10000,01000,00100,00010,00100,01000,10000", "00001,00010,00100,01000,00100,00010,00001",
        "00000,00000,00000,00000,00000,00000,11111"
    };
    public static final int GW = 5, GH = 7, ADV = 6;
    private final Texture tex;
    private final TextureRegion[] regions = new TextureRegion[CHARS.length()];
    private final TextureRegion px;

    public PixelFont() {
        int cw = 8, ch = 9, cols = 16, rows = (CHARS.length() + cols - 1) / cols + 1;
        Pixmap pm = new Pixmap(cols * cw, rows * ch, Pixmap.Format.RGBA8888);
        pm.setColor(0, 0, 0, 0); pm.fill(); pm.setColor(Color.WHITE);
        for (int i = 0; i < CHARS.length(); i++) {
            String[] r = ROWS[i].split(",");
            int ox = (i % cols) * cw + 1, oy = (i / cols) * ch + 1;
            for (int y = 0; y < GH; y++) for (int x = 0; x < GW; x++) if (r[y].charAt(x) == '1') pm.drawPixel(ox + x, oy + y);
        }
        pm.fillRectangle(0, (rows - 1) * ch, 4, 4); // solid block for rects
        tex = new Texture(pm);
        tex.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        for (int i = 0; i < CHARS.length(); i++) regions[i] = new TextureRegion(tex, (i % cols) * cw + 1, (i / cols) * ch + 1, GW, GH);
        px = new TextureRegion(tex, 1, (rows - 1) * ch + 1, 2, 2);
        pm.dispose();
    }

    public float width(String s, float scale) { return s.length() * ADV * scale - scale; }
    public float height(float scale) { return GH * scale; }

    public void draw(SpriteBatch b, String s, float x, float y, float scale) {
        float cx = x;
        for (int i = 0; i < s.length(); i++) {
            char c = Character.toUpperCase(s.charAt(i));
            int k = CHARS.indexOf(c);
            if (k >= 0) b.draw(regions[k], cx, y, GW * scale, GH * scale);
            cx += ADV * scale;
        }
    }

    public void draw2(SpriteBatch b, String s, float x, float y, float scale, Color c) { b.setColor(c); draw(b, s, x, y, scale); b.setColor(Color.WHITE); }

    /** Draws with a 1-voxel drop shadow for readability on busy backgrounds. */
    public void drawShadow(SpriteBatch b, String s, float x, float y, float scale, Color c, Color shadow) {
        b.setColor(shadow); draw(b, s, x + scale, y - scale, scale);
        b.setColor(c); draw(b, s, x, y, scale);
    }

    public void rect(SpriteBatch b, float x, float y, float w, float h) { b.draw(px, x, y, w, h); }

    @Override public void dispose() { tex.dispose(); }
}

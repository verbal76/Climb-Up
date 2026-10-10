package com.hotatticgames.climbup.render;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.Disposable;
import java.util.Random;

/** Sky gradient plus parallax layers (voxel clouds, stars). Generated procedurally; scrolls with the tower's rotation. */
public final class Background implements Disposable {
    private final Texture[] clouds = new Texture[2];
    private final Texture stars;
    private int w = 1280, h = 720;

    public Background() {
        Random r = new Random(7);
        for (int k = 0; k < 2; k++) {
            Pixmap pm = new Pixmap(1024, 256, Pixmap.Format.RGBA8888);
            pm.setColor(0, 0, 0, 0); pm.fill();
            int blobs = 7 + k * 3;
            for (int b = 0; b < blobs; b++) {
                int cx = r.nextInt(1024), cy = 150 + r.nextInt(90), cw = 90 + r.nextInt(140);
                int step = 14 + k * 6;
                for (int x = 0; x < cw; x += step) {
                    int hgt = (int) (step * (1.2f + 2.2f * (float) Math.sin(Math.PI * x / cw)));
                    pm.setColor(1f, 1f, 1f, 0.95f);
                    pm.fillRectangle((cx + x) % 1024, cy - hgt, step - 2, hgt);
                    pm.setColor(0.80f, 0.87f, 1f, 0.95f);
                    pm.fillRectangle((cx + x) % 1024, cy - Math.max(3, step / 3), step - 2, Math.max(3, step / 3));
                }
            }
            clouds[k] = new Texture(pm); clouds[k].setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
            clouds[k].setWrap(Texture.TextureWrap.Repeat, Texture.TextureWrap.ClampToEdge);
            pm.dispose();
        }
        Pixmap pm = new Pixmap(512, 512, Pixmap.Format.RGBA8888);
        pm.setColor(0, 0, 0, 0); pm.fill();
        for (int i = 0; i < 160; i++) {
            float a = 0.5f + 0.5f * r.nextFloat();
            pm.setColor(1f, 1f, 0.92f, a);
            int s = r.nextInt(5) == 0 ? 3 : 2;
            pm.fillRectangle(r.nextInt(512), r.nextInt(512), s, s);
        }
        stars = new Texture(pm); stars.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        stars.setWrap(Texture.TextureWrap.Repeat, Texture.TextureWrap.Repeat);
        pm.dispose();
    }

    public void resize(int w, int h) { this.w = w; this.h = h; }

    public void render(SpriteBatch sb, ShapeRenderer sr, Color top, Color bot, float camS, float camY, float zoneF, float circumference, boolean reduced, float time) {
        sr.getProjectionMatrix().setToOrtho2D(0, 0, w, h);
        sr.begin(ShapeRenderer.ShapeType.Filled);
        sr.rect(0, 0, w, h, bot, bot, top, top);
        sr.end();
        sb.getProjectionMatrix().setToOrtho2D(0, 0, w, h);
        sb.enableBlending();
        sb.begin();
        // stars fade in toward the night zone
        float starA = Math.min(MathUtils.clamp((zoneF - 2.35f) / 0.7f, 0f, 1f), 1f - MathUtils.clamp((zoneF - Palette.ZONES + 0.25f) / 0.25f, 0f, 1f));   // from dusk to the end of deep space, fading out as the meadows return
        float deep = MathUtils.clamp((zoneF - 3.5f) / 0.6f, 0f, 1f) * (1f - MathUtils.clamp((zoneF - Palette.ZONES + 0.25f) / 0.25f, 0f, 1f));   // deep space: brighter, denser stars
        if (starA > 0.01f) {
            sb.setColor(1f, 1f, 1f, starA);
            float sx = camS * 6f * (reduced ? 0f : 1f), sy = camY * 2f;
            sb.draw(stars, 0, 0, w, h, (int) sx % 512, -(int) sy % 512, w, h, false, false);
            if (deep > 0.01f) {          // a second, slower star layer shifted over the first
                sb.setColor(0.85f, 0.9f, 1f, deep);
                sb.draw(stars, 0, 0, w, h, (int) (sx * 0.5f) % 512 + 200, -(int) (sy * 0.5f) % 512 + 90, w, h, false, false);
            }
        }
        float wrap = MathUtils.clamp((zoneF - Palette.ZONES + 0.25f) / 0.25f, 0f, 1f);       // the last quarter-zone blends back to the meadows
        float cloudA = (1f - 0.8f * MathUtils.clamp((zoneF - 2.4f) / 0.8f, 0f, 1f) * (1f - wrap)) * (1f - MathUtils.clamp((zoneF - 3.6f) / 0.5f, 0f, 1f) * (1f - wrap));      // fewer clouds at night, none in deep space
        float scale = h / 720f;
        for (int k = 0; k < FLAT_CLOUD_LAYERS; k++) {       // the flat, bar-like cloud strips are retired: the 3D cloud models (Clouds) are the clouds now
            float par = k == 0 ? 7f : 13f;                   // pixels per arc unit: far layer slower
            float off = (reduced ? 0 : camS * par * scale);
            float drift = reduced ? 0f : time * (k == 0 ? 2f : 4f);
            float vy = camY * (k == 0 ? 6f : 11f) * scale;
            float tw = 1024 * scale * (k == 0 ? 1.0f : 1.4f), th = 256 * scale * (k == 0 ? 1.0f : 1.4f);
            float baseY = h * (k == 0 ? 0.55f : 0.18f);
            float y = ((baseY - vy) % (h * 1.6f) + h * 1.6f) % (h * 1.6f) - th * 0.4f;
            float c = k == 0 ? 0.9f : 1f;
            sb.setColor(c, c, c, cloudA * (k == 0 ? 0.55f : 0.9f));
            int u0 = (int) (((off + drift) / (tw / 1024f)) % 1024);
            sb.draw(clouds[k], 0, y, w, th, u0, 0, (int) (w / (tw / 1024f)), 256, false, false);
        }
        sb.setColor(Color.WHITE);
        sb.end();
    }

    private static final int FLAT_CLOUD_LAYERS = 0;

    @Override public void dispose() { for (Texture t : clouds) t.dispose(); stars.dispose(); }
}

package com.hotatticgames.climbup;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.InputMultiplexer;
import com.badlogic.gdx.ScreenAdapter;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.hotatticgames.climbup.ui.Ui;

/**
 * The end of the demo (docs/DEMO.md): the recipe's lines ("UPWARDLY", "WISHLIST NOW ON STEAM", "AVAILABLE NOVEMBER 2026"), the official Steam logo
 * when the owner has supplied one (recipe promo.steamLogo; the demo never draws a lookalike), the store button only when an approved store page is
 * configured (recipe promo.storeUrl; never invented), PLAY DEMO AGAIN (back to NORMAL) and MAIN MENU. Sized from the screen, so it reads on a
 * phone, a Steam Deck (1280x800) and a desktop monitor alike.
 */
public final class DemoPromoScreen extends ScreenAdapter {
    private final ClimbGame g; private final DemoFlow flow;
    private final DemoBackdrop backdrop = new DemoBackdrop();
    private Texture logo; private TextureRegion logoR;
    private com.badlogic.gdx.Screen next;
    private float t;

    DemoPromoScreen(ClimbGame g, DemoFlow flow) { this.g = g; this.flow = flow; }

    @Override public void show() {
        backdrop.open(g, flow);
        String lp = flow.recipe.steamLogo;
        if (!lp.isEmpty() && Gdx.files.internal(lp).exists()) {
            try { logo = new Texture(Gdx.files.internal(lp), true); logo.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear); logoR = new TextureRegion(logo); }
            catch (RuntimeException e) { logo = null; logoR = null; }
        }
        Gdx.input.setInputProcessor(new InputMultiplexer(g.ui));
        g.audio.music("music_menu");
    }
    @Override public void resize(int w, int h) { g.ui.resize(w, h); backdrop.resize(w, h); }

    @Override public void render(float dt) {
        t += Math.min(dt, 0.1f);
        backdrop.render(dt);
        Ui ui = g.ui; ui.begin();
        float W = ui.w(), H = ui.h();
        ui.rect(0, 0, W, H, new Color(0.02f, 0.01f, 0.06f, 0.55f));
        DemoRecipe r = flow.recipe;
        float big = Math.min(16f, (W - 100f) / Math.max(1, r.promoTitle.length() * 6f)), ty = H - 110f - ui.font.height(big);
        float tx = W / 2 - ui.font.width(r.promoTitle, big) / 2;
        ui.font.drawShadow(ui.batch, r.promoTitle, tx + 6, ty - 8, big, new Color(0.35f, 0.12f, 0.02f, 1f), new Color(0, 0, 0, 0.6f));
        ui.font.drawShadow(ui.batch, r.promoTitle, tx, ty, big, new Color(1f, 0.74f, 0.16f, 1f), new Color(0.2f, 0.07f, 0.0f, 0.9f));
        float y = ty - 40f;
        if (logoR != null) {          // the owner-supplied official logo, at its own aspect ratio
            float lh = Math.min(96f, H * 0.12f), lw = lh * logo.getWidth() / (float) logo.getHeight();
            if (lw > W - 160f) { lw = W - 160f; lh = lw * logo.getHeight() / (float) logo.getWidth(); }
            y -= lh; ui.image(logoR, W / 2 - lw / 2, y, lw, lh, 1f); y -= 24f;
        }
        float l1 = Math.min(6.4f, (W - 80f) / Math.max(1, r.promoLine1.length() * 6f)), l2 = Math.min(4.6f, (W - 80f) / Math.max(1, r.promoLine2.length() * 6f));
        y -= ui.font.height(l1); ui.font.drawShadow(ui.batch, r.promoLine1, W / 2 - ui.font.width(r.promoLine1, l1) / 2, y, l1, Ui.TEXT, new Color(0, 0, 0, 0.8f));
        y -= 30f + ui.font.height(l2); ui.font.drawShadow(ui.batch, r.promoLine2, W / 2 - ui.font.width(r.promoLine2, l2) / 2, y, l2, Ui.ACCENT, new Color(0, 0, 0, 0.8f));
        // buttons stack up from the bottom edge (and the best-run line under them), never over the lines above: on a short screen they get shorter, not off-screen
        DemoRecords rec = flow.records;
        String best = rec.bestRunTicks > 0 && rec.bestRunFingerprint.equals(flow.fingerprint()) ? "YOUR BEST DEMO RUN: " + ClockText.time(rec.bestRunTicks / (float) RunRecord.STEPS_PER_SECOND) : null;
        int nb = r.hasStoreUrl() ? 3 : 2;
        float gap = 14f, bottom = best != null ? 64f : 26f, room = y - 34f - bottom;
        float bh = Math.max(64f, Math.min(Dp.touchHeight(84f, 96f, 96f, Dp.unitsPerDp(ui)), (room - gap * (nb - 1)) / nb));
        float bw = Math.min(560f, W - 120f), x = W / 2 - bw / 2, by = bottom + (nb - 1) * (bh + gap);
        if (r.hasStoreUrl()) {
            if (ui.button(r.storeButton, x, by, bw, bh, true)) { g.audio.play("click"); try { Gdx.net.openURI(r.storeUrl); } catch (RuntimeException ignored) { } }
            by -= bh + gap;
        }
        if (ui.button(r.againButton, x, by, bw, bh, !r.hasStoreUrl())) { g.audio.play("click"); next = flow.playAgain(); }
        by -= bh + gap;
        if (ui.button("MAIN MENU", x, by, bw, bh)) { g.audio.play("click"); next = new DemoTitleScreen(g); }
        if (best != null) ui.textC(best, W / 2, 22, Math.min(3f, (W - 80f) / (best.length() * 6f)), Ui.DIM);
        ui.end();
        g.autoShot("promo", dt);
        if (next != null) { com.badlogic.gdx.Screen n = next; next = null; g.setScreen(n); }
    }

    @Override public void hide() { backdrop.dispose(); if (logo != null) { logo.dispose(); logo = null; logoR = null; } }
    @Override public void dispose() { hide(); }
}

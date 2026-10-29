package com.hotatticgames.climbup;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.InputMultiplexer;
import com.badlogic.gdx.ScreenAdapter;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.hotatticgames.climbup.ui.Ui;

/**
 * A demo stage's title card (docs/DEMO.md): black, the stage's title fades in at the centre, holds, fades out, then the stage starts (fading in from
 * black). Nothing moves or is timed meanwhile: there is no simulation on this screen, and taps and keys do nothing. The stage's tower is built in
 * the background while the card shows; if it is not ready when the card ends, the screen stays black (saying LOADING) until it is.
 */
public final class DemoCardScreen extends ScreenAdapter {
    private final ClimbGame g; private final DemoFlow flow; private final int stage;
    private float t;

    DemoCardScreen(ClimbGame g, DemoFlow flow, int stage) { this.g = g; this.flow = flow; this.stage = stage; }

    @Override public void show() { Gdx.input.setInputProcessor(new InputMultiplexer(g.ui)); flow.prepare(stage); g.audio.stopLoops(); g.audio.fallStop(); }
    @Override public void resize(int w, int h) { g.ui.resize(w, h); }

    /** Text opacity at {@code t} seconds into a card (fade in, hold, fade out); 0 once it is over. */
    static float alpha(DemoRecipe r, float t) {
        if (t < r.cardFadeIn) return r.cardFadeIn <= 0f ? 1f : t / r.cardFadeIn;
        if (t < r.cardFadeIn + r.cardHold) return 1f;
        if (t < r.cardFadeIn + r.cardHold + r.cardFadeOut) return 1f - (t - r.cardFadeIn - r.cardHold) / r.cardFadeOut;
        return 0f;
    }
    static float length(DemoRecipe r) { return r.cardFadeIn + r.cardHold + r.cardFadeOut; }

    @Override public void render(float dt) {
        t += Math.min(dt, 0.1f);
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f); Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        Ui ui = g.ui; ui.begin();
        float W = ui.w(), H = ui.h();
        String title = flow.recipe.stages.get(stage).title;
        float a = alpha(flow.recipe, t);
        if (a > 0f) {
            float px = Math.min(9f, (W - 120f) / Math.max(1, title.length() * 6f));
            ui.textC(title, W / 2, H / 2 - ui.font.height(px) / 2, px, new Color(1f, 0.86f, 0.42f, a));
        } else if (t > length(flow.recipe) + 0.4f) ui.textC("LOADING...", W / 2, 60, 3f, new Color(1f, 1f, 1f, 0.6f));
        ui.end();
        g.autoShot("card", dt);
        if (t >= length(flow.recipe) && flow.ready(stage)) start();
    }

    private void start() {
        ClimbGame.Run run;
        try { run = flow.take(stage); }
        catch (RuntimeException e) { g.setScreen(new DemoTitleScreen(g, "THE DEMO COULD NOT START THIS STAGE: " + e.getMessage())); return; }
        flow.beginStage(stage);
        g.preparedRun = run; g.runFresh = false;
        g.setScreen(new PlayScreen(g, false, flow.stage(stage)));
    }
}

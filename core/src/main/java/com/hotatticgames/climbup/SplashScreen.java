package com.hotatticgames.climbup;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.InputMultiplexer;
import com.badlogic.gdx.ScreenAdapter;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;

/** Hot Attic Games studio splash: the owner-supplied logo, shown untouched, then the title screen. */
public final class SplashScreen extends ScreenAdapter {
    private final ClimbGame g;
    private Texture logo;
    private float t;
    private static final float DUR = 2.8f;

    public SplashScreen(ClimbGame g) { this.g = g; }

    @Override public void show() {
        logo = new Texture(Gdx.files.internal("branding/studio_splash.png"));
        logo.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        Gdx.input.setInputProcessor(new InputMultiplexer(g.ui));
    }

    @Override public void resize(int w, int h) { g.ui.resize(w, h); }

    @Override public void render(float dt) {
        t += dt;
        Gdx.gl.glClearColor(0.045f, 0.035f, 0.06f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        float a = Math.min(1f, Math.min(t / 0.5f, (DUR - t) / 0.5f));
        g.ui.begin();
        float h = g.ui.h() * 0.86f, w = h * logo.getWidth() / logo.getHeight();
        if (w > g.ui.w() * 0.94f) { w = g.ui.w() * 0.94f; h = w * logo.getHeight() / logo.getWidth(); }
        g.ui.batch.setColor(1f, 1f, 1f, Math.max(0f, a));
        g.ui.batch.draw(logo, (g.ui.w() - w) / 2f, (g.ui.h() - h) / 2f, w, h);
        g.ui.batch.setColor(Color.WHITE);
        boolean tap = g.ui.anyTap();
        g.ui.end();
        g.autoShot("splash", dt);
        if (t >= DUR || (tap && t > 0.3f)) g.setScreen(new TitleScreen(g));
    }

    @Override public void hide() { dispose(); }
    @Override public void dispose() { if (logo != null) { logo.dispose(); logo = null; } }
}

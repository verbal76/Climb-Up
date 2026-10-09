package com.hotatticgames.climbup.otalab;

import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;

/** The host's own minimal recovery screen: shown only when no verified module can run. It depends on nothing downloaded. */
final class RecoveryGame implements ApplicationListener {
    private final String why; private SpriteBatch batch; private BitmapFont font;
    RecoveryGame(String why) { this.why = why; }
    @Override public void create() { batch = new SpriteBatch(); font = new BitmapFont(); font.getData().setScale(2f); font.setColor(Color.WHITE); android.util.Log.i(LabLauncher.TAG, "recovery screen created"); }
    @Override public void resize(int w, int h) { batch.getProjectionMatrix().setToOrtho2D(0, 0, w, h); }
    @Override public void render() {
        Gdx.gl.glClearColor(0.25f, 0.05f, 0.05f, 1f); Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        batch.begin(); font.draw(batch, "RECOVERY - no verified game module can run", 30, Gdx.graphics.getHeight() - 40); font.draw(batch, why == null ? "" : why, 30, Gdx.graphics.getHeight() - 100); batch.end();
    }
    @Override public void pause() { } @Override public void resume() { }
    @Override public void dispose() { if (batch != null) batch.dispose(); if (font != null) font.dispose(); }
}

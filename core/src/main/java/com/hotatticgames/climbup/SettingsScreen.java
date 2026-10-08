package com.hotatticgames.climbup;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.InputMultiplexer;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.ScreenAdapter;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.hotatticgames.climbup.ui.Ui;

/** Volume, display, accessibility, controls and data reset. Every change applies immediately. */
public final class SettingsScreen extends ScreenAdapter {
    private final ClimbGame g;
    private final Screen back;
    private int page = Integer.getInteger("climb.page", 0);
    private boolean confirmReset;

    public SettingsScreen(ClimbGame g, Screen back) { this.g = g; this.back = back; }

    @Override public void show() { Gdx.input.setInputProcessor(new InputMultiplexer(g.ui)); }
    @Override public void resize(int w, int h) { g.ui.resize(w, h); }

    private float rowY;
    private static final String[] Q = {"LOW", "MEDIUM", "HIGH"}, TXT = {"100%", "130%", "160%"}, HAP = {"OFF", "LOW", "HIGH"};

    private void row(String label, String value, Runnable minus, Runnable plus, Runnable tap) {
        Ui ui = g.ui; float W = ui.w();
        float cw = Math.min(980, W - 100), x = W / 2 - cw / 2, h = 64;
        float px = Math.max(3.2f, Math.min(4f * ui.tm(), (cw * 0.5f) / (label.length() * 6f)));
        ui.text(label, x, rowY + h / 2 - 3.5f * px, px, Ui.TEXT);
        float bx = x + cw * 0.56f, bw = cw * 0.44f;
        if (minus != null) {
            if (ui.button("-", bx, rowY, 86, h)) { minus.run(); g.audio.play("click"); g.audio.applyVolume(); }
            ui.textC(value, bx + 86 + (bw - 172) / 2, rowY + h / 2 - 14, 4f, Ui.ACCENT);
            if (ui.button("+", bx + bw - 86, rowY, 86, h)) { plus.run(); g.audio.play("click"); g.audio.applyVolume(); }
        } else if (ui.button(value, bx, rowY, bw, h)) { tap.run(); g.audio.play("click"); }
        rowY -= h + 10;
    }

    private void toggle(String label, boolean v, Runnable flip) { row(label, v ? "ON" : "OFF", null, null, flip); }

    @Override public void render(float dt) {
        Settings s = g.settings;
        Gdx.gl.glClearColor(0.06f, 0.08f, 0.16f, 1f); Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        Ui ui = g.ui; ui.begin();
        float W = ui.w(), H = ui.h();
        ui.textC("SETTINGS", W / 2, H - 78, 7f, Ui.TEXT);
        if (ui.button("BACK", 40, H - 100, 200, 64, true)) { g.audio.play("click"); g.persist(); if (back instanceof PlayScreen) ((PlayScreen) back).resumeFromSettings(); g.setScreen(back); ui.end(); return; }
        float tw = 300;
        if (ui.button("AUDIO + DISPLAY", W / 2 - tw - 12, H - 168, tw, 60, page == 0)) page = 0;
        if (ui.button("CONTROLS + ACCESS", W / 2 + 12, H - 168, tw + 40, 60, page == 1)) page = 1;
        rowY = H - 176 - 64;
        if (page == 0) {
            row("MUSIC VOLUME", String.valueOf(s.music), () -> s.music = Math.max(0, s.music - 1), () -> s.music = Math.min(10, s.music + 1), null);
            row("EFFECTS VOLUME", String.valueOf(s.sfx), () -> s.sfx = Math.max(0, s.sfx - 1), () -> s.sfx = Math.min(10, s.sfx + 1), null);
            row("GRAPHICS QUALITY", Q[s.quality], null, null, () -> s.quality = (s.quality + 1) % 3);
            row("TEXT SIZE", TXT[s.textScale], null, null, () -> s.textScale = (s.textScale + 1) % 3);
            toggle("REDUCED MOTION", s.reducedMotion, () -> s.reducedMotion = !s.reducedMotion);
            toggle("CAPTIONS FOR SOUNDS", s.captions, () -> s.captions = !s.captions);
        } else {
            row("HAPTICS", HAP[s.haptics], null, null, () -> s.haptics = (s.haptics + 1) % 3);
            toggle("LEFT-HANDED LAYOUT", s.leftHanded, () -> s.leftHanded = !s.leftHanded);
            toggle("ASSIST: SLOWER SPEED", s.assistSlow, () -> s.assistSlow = !s.assistSlow);
            toggle("ASSIST: JUMP FORGIVENESS", s.assistForgive, () -> s.assistForgive = !s.assistForgive);
            toggle("CONTEXTUAL TIPS", s.tips, () -> s.tips = !s.tips);
            toggle("HIGH-CONTRAST CONTROLS", s.highContrast, () -> s.highContrast = !s.highContrast);
            row("ERASE SAVE DATA", confirmReset ? "TAP TO CONFIRM" : "ERASE", null, null, () -> {
                if (confirmReset) { g.store.eraseAll(); g.save = new SaveData(); g.settings = new Settings(); confirmReset = false; }
                else confirmReset = true;
            });
        }
        ui.end();
        g.autoShot("settings", dt);
    }
}

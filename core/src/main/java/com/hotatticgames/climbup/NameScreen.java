package com.hotatticgames.climbup;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.InputMultiplexer;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.ScreenAdapter;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.hotatticgames.climbup.ui.Ui;

/**
 * Name entry for a new player: an on-screen picker (A-Z, 0-9, SPACE, DELETE, OK, CANCEL) drawn with ui.button, so it needs no system keyboard and works with touch, the mouse and the Windows keyboard / pad menu navigation.
 * At most {@value Profiles#MAX_NAME} characters. Raw typed keys are not read on purpose: the Windows menu navigation already uses W/A/S/D, the arrows, Enter, Space and Esc, so typing would press buttons as well as add letters.
 * With that navigation WASD or the arrows walk the picker and Enter presses a key; the CANCEL button answers Esc and the pad's back button. The phone's Back button also cancels.
 */
public final class NameScreen extends ScreenAdapter {
    private static final String KEYS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int COLS = 9;

    private final ClimbGame g; private final Screen back, home;
    private final StringBuilder name = new StringBuilder();
    private String message = "";
    private float time;
    private Screen next;
    private boolean okRequested, cancelRequested;

    /** @param back where CANCEL goes (the players list); @param home where a successful OK goes (the title screen, now showing the new player) */
    public NameScreen(ClimbGame g, Screen back, Screen home) { this.g = g; this.back = back; this.home = home; }

    private boolean backWasCaught;
    @Override public void show() {
        backWasCaught = Gdx.input.isCatchKey(Input.Keys.BACK); Gdx.input.setCatchKey(Input.Keys.BACK, true);       // the phone's Back button means CANCEL here, not "close the game"
        Gdx.input.setInputProcessor(new InputMultiplexer(new InputAdapter() {
            @Override public boolean keyDown(int k) { if (k == Input.Keys.BACK) cancelRequested = true; return false; }
        }, g.ui));
    }
    @Override public void hide() { Gdx.input.setCatchKey(Input.Keys.BACK, backWasCaught); }
    @Override public void resize(int w, int h) { g.ui.resize(w, h); }

    // ---- the entry itself (also what the tests drive)

    /** Adds one character if it is allowed and there is room. Letters are made capital. Returns whether it was added. */
    boolean type(char c) {
        char u = Character.toUpperCase(c);
        if (name.length() >= Profiles.MAX_NAME || Profiles.LETTERS.indexOf(u) < 0) return false;
        if (u == ' ' && (name.length() == 0 || name.charAt(name.length() - 1) == ' ')) return false;     // no leading or doubled spaces
        name.append(u); message = ""; return true;
    }
    void backspace() { if (name.length() > 0) name.setLength(name.length() - 1); message = ""; }
    String current() { return name.toString(); }

    /** OK: makes the player when the name is fine, otherwise says why not. Returns true when the player was made. */
    boolean confirm() {
        String n = Profiles.cleanName(name.toString());
        if (n.isEmpty()) { message = "TYPE A NAME FIRST"; return false; }
        if (g.profiles.nameTaken(n, -1)) { message = "THAT NAME IS TAKEN"; return false; }
        if (!g.addPlayer(n)) { message = "COULD NOT ADD PLAYER"; return false; }
        return true;
    }

    private static final float KEYS_TOP_BELOW_H = 174f, KEYS_GAP = 8f, KEYS_BOTTOM = 20f;
    /** The key height: 88 normally, raised to 48dp on a small phone, and never so tall that the five rows of keys do not fit under the name box. */
    static float keyHeight(float H, float unitsPerDp) { return Dp.touchHeight(88f, 110f, (H - KEYS_TOP_BELOW_H - KEYS_BOTTOM - 4 * KEYS_GAP) / 5f, unitsPerDp); }

    @Override public void render(float dt) {
        time += Math.min(dt, 0.1f);
        Gdx.gl.glClearColor(0.06f, 0.08f, 0.16f, 1f); Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        Ui ui = g.ui; ui.begin();
        float W = ui.w(), H = ui.h();
        ui.textC("NEW PLAYER", W / 2, H - 54, 5f, Ui.TEXT);

        // the name so far, with a blinking cursor and a count
        float bw = 560, bh = 64, bx = W / 2 - bw / 2, by = H - 132;
        ui.panel(bx, by, bw, bh);
        String shown = name.toString() + (((int) (time * 2)) % 2 == 0 && name.length() < Profiles.MAX_NAME ? "_" : "");
        float px = 6f;
        ui.text(shown, bx + 22, by + bh / 2 - ui.font.height(px) / 2, px, Ui.TEXT);
        String cnt = name.length() + "/" + Profiles.MAX_NAME;
        ui.text(cnt, bx + bw - 18 - ui.font.width(cnt, 3f), by + bh / 2 - ui.font.height(3f) / 2, 3f, Ui.DIM);
        if (!message.isEmpty()) ui.textC(message, W / 2, by - 32, 3.4f, Ui.ACCENT);

        // letters and digits
        float gw = Math.min(W - 144, 1100), gap = KEYS_GAP, kh = keyHeight(H, Dp.unitsPerDp(ui)), kw = (gw - gap * (COLS - 1)) / COLS, gx = W / 2 - gw / 2;
        float top = H - KEYS_TOP_BELOW_H;
        for (int i = 0; i < KEYS.length(); i++) {
            int r = i / COLS, c = i % COLS;
            float x = gx + c * (kw + gap), y = top - (r + 1) * kh - r * gap;
            if (ui.button(String.valueOf(KEYS.charAt(i)), x, y, kw, kh) && type(KEYS.charAt(i))) g.audio.play("click");
        }
        // SPACE / DELETE / OK / CANCEL
        float ay = top - 5 * kh - 4 * gap, aw = (gw - 3 * gap) / 4;
        if (ui.button("SPACE", gx, ay, aw, kh) && type(' ')) g.audio.play("click");
        if (ui.button("DELETE", gx + (aw + gap), ay, aw, kh)) { backspace(); g.audio.play("click"); }
        if (ui.button("OK", gx + 2 * (aw + gap), ay, aw, kh, true)) okRequested = true;
        if (ui.button("CANCEL", gx + 3 * (aw + gap), ay, aw, kh)) cancelRequested = true;
        ui.end();
        g.autoShot("name", dt);

        if (okRequested) { okRequested = false; if (confirm()) { g.audio.play("click"); next = home; } else g.audio.play("click", 0.5f, 0.7f); }
        if (cancelRequested) { cancelRequested = false; g.audio.play("click"); next = back; }
        if (next != null) { Screen n = next; next = null; g.setScreen(n); }
    }
}

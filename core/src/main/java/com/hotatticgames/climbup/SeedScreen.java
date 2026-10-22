package com.hotatticgames.climbup;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.InputMultiplexer;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.ScreenAdapter;
import com.badlogic.gdx.graphics.GL20;
import com.hotatticgames.climbup.ui.Ui;

/**
 * SEEDED RUN (Build 64): a friend's course code typed on an on-screen picker (the 31 code symbols, DELETE, OK, CANCEL) drawn with ui.button like the name
 * picker, so it needs no system keyboard and works with touch, the mouse and the keyboard / pad menu navigation. OK decodes the code ({@link SeedCode}) and
 * starts the fresh climb on that seed through the same generator and play screen as a random run; a code that is not a course says why and starts nothing.
 */
public final class SeedScreen extends ScreenAdapter {
    private static final int COLS = 8;
    private final ClimbGame g; private final Screen back;
    private final StringBuilder code = new StringBuilder();
    private String message = "";
    private float time;
    private Screen next;
    private boolean okRequested, cancelRequested;
    public SeedScreen(ClimbGame g, Screen back) { this.g = g; this.back = back; }

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
    /** Adds one symbol if it is one of the code's and there is room. Returns whether it was added. */
    boolean type(char c) {
        char u = Character.toUpperCase(c);
        if (code.length() >= SeedCode.LENGTH || SeedCode.ALPHABET.indexOf(u) < 0) return false;
        code.append(u); message = "";
        if (code.length() == SeedCode.LENGTH && SeedCode.problem(code.toString()) == null) com.hotatticgames.climbup.sim.Tower.prepare(SeedCode.decode(code.toString()), g.tuning);     // a whole valid code: make its first slice while OK is pressed (Build 71)
        return true;
    }
    void backspace() { if (code.length() > 0) code.setLength(code.length() - 1); message = ""; }
    String current() { return code.toString(); }
    /** OK: the seed behind the code, or null with the reason shown. */
    Long confirm() {
        String why = SeedCode.problem(code.toString());
        if (why != null) { message = why; return null; }
        return SeedCode.decode(code.toString());
    }

    private static final float KEYS_TOP_BELOW_H = 190f, KEYS_GAP = 8f, KEYS_BOTTOM = 20f;
    /** The key height: 88 normally, raised to 48dp on a small phone, and never so tall that the five rows of keys do not fit under the code box. */
    static float keyHeight(float H, float unitsPerDp) { return Dp.touchHeight(88f, 110f, (H - KEYS_TOP_BELOW_H - KEYS_BOTTOM - 4 * KEYS_GAP) / 5f, unitsPerDp); }

    @Override public void render(float dt) {
        time += Math.min(dt, 0.1f);
        Gdx.gl.glClearColor(0.06f, 0.08f, 0.16f, 1f); Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        Ui ui = g.ui; ui.begin();
        float W = ui.w(), H = ui.h();
        ui.textC("SEEDED RUN", W / 2, H - 54, 5f, Ui.TEXT);
        String hint = "TYPE A FRIEND'S COURSE CODE: " + SeedCode.LENGTH + " CHARACTERS, FROM THEIR TIMES TABLE";
        ui.textC(hint, W / 2, H - 92, Math.min(2.8f, (W - 60f) / (hint.length() * 6f)), Ui.DIM);
        // the code so far in its groups, with a blinking cursor; the box holds the 15 characters of a grouped code, the cursor and the counter without touching
        float bw = Math.min(780, W - 80), bh = 64, bx = W / 2 - bw / 2, by = H - 164;
        ui.panel(bx, by, bw, bh);
        StringBuilder shown = new StringBuilder(SeedCode.group(code.toString()));
        boolean blink = ((int) (time * 2)) % 2 == 0;
        if (code.length() < SeedCode.LENGTH) { if (code.length() == 5 || code.length() == 9) shown.append('-'); shown.append(blink ? '_' : ' '); }
        String cnt = code.length() + "/" + SeedCode.LENGTH;
        float cw = ui.font.width(cnt, 3f), px = Math.min(6f, (bw - 22 - 24 - cw - 18) / (16 * 6f));
        ui.text(shown.toString(), bx + 22, by + bh / 2 - ui.font.height(px) / 2, px, Ui.TEXT);
        ui.text(cnt, bx + bw - 18 - cw, by + bh / 2 - ui.font.height(3f) / 2, 3f, Ui.DIM);
        if (!message.isEmpty()) ui.textC(message, W / 2, by - 30, Math.min(3.2f, (W - 60f) / (message.length() * 6f)), Ui.ACCENT);
        // the symbols
        float gw = Math.min(W - 144, 1100), gap = KEYS_GAP, kh = keyHeight(H, Dp.unitsPerDp(ui)), kw = (gw - gap * (COLS - 1)) / COLS, gx = W / 2 - gw / 2;
        float top = H - KEYS_TOP_BELOW_H;
        String keys = SeedCode.ALPHABET;
        for (int i = 0; i < keys.length(); i++) {
            int r = i / COLS, c = i % COLS;
            float x = gx + c * (kw + gap), y = top - (r + 1) * kh - r * gap;
            if (ui.button(String.valueOf(keys.charAt(i)), x, y, kw, kh) && type(keys.charAt(i))) g.audio.play("click");
        }
        // DELETE / OK / CANCEL on the last row, after the 31st symbol
        int rows = (keys.length() + COLS - 1) / COLS;
        float ay = top - (rows + 1) * kh - rows * gap, aw = (gw - 2 * gap) / 3;
        if (ui.button("DELETE", gx, ay, aw, kh)) { backspace(); g.audio.play("click"); }
        if (ui.button("OK", gx + (aw + gap), ay, aw, kh, true)) okRequested = true;
        if (ui.button("CANCEL", gx + 2 * (aw + gap), ay, aw, kh)) cancelRequested = true;
        ui.end();
        g.autoShot("seed", dt);
        if (okRequested) {
            okRequested = false;
            Long seed = confirm();
            if (seed != null) { g.beginFreshRun(seed); g.audio.play("click"); next = new PlayScreen(g, false); }
            else g.audio.play("click", 0.5f, 0.7f);
        }
        if (cancelRequested) { cancelRequested = false; g.audio.play("click"); next = back; }
        if (next != null) { Screen n = next; next = null; g.setScreen(n); }
    }
}

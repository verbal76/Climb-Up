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
import java.util.ArrayList;
import java.util.List;

/** Pick, add or delete a player. Every player keeps their own climb, checkpoint, best height and stats; settings are shared. */
public final class PlayersScreen extends ScreenAdapter {
    private final ClimbGame g; private final Screen home;
    private final List<String> stats = new ArrayList<>();
    private int confirmDelete = -1;           // player id awaiting the DELETE confirmation, or -1
    private Screen next;

    public PlayersScreen(ClimbGame g, Screen home) { this.g = g; this.home = home; }

    private boolean backWasCaught;
    @Override public void show() {
        backWasCaught = Gdx.input.isCatchKey(Input.Keys.BACK); Gdx.input.setCatchKey(Input.Keys.BACK, true);       // the phone's Back button goes back one step, it does not close the game
        Gdx.input.setInputProcessor(new InputMultiplexer(new InputAdapter() {
            @Override public boolean keyDown(int k) {
                if (k == Input.Keys.BACK) { if (confirmDelete >= 0) confirmDelete = -1; else next = home; }       // the phone's Back button; Esc and the pad's back button press the BACK / CANCEL buttons
                return false;
            }
        }, g.ui));
        g.persist();                                     // the list shows saved numbers, the current player's included
        confirmDelete = -1; refresh();
    }
    @Override public void hide() { Gdx.input.setCatchKey(Input.Keys.BACK, backWasCaught); }
    @Override public void resize(int w, int h) { g.ui.resize(w, h); }

    private void refresh() {
        stats.clear();
        for (Profiles.Entry e : g.profiles.players()) {
            SaveData d = e.id == g.currentPlayerId() ? g.save : new SaveStore(g.profiles.dirOf(e.id)).loadGame();
            stats.add(summary(d));
        }
    }

    private static final float TOP = 140f, STAT_LINE = 40f, BOTTOM_ROW = 30f;
    /** The button height: 88 normally, raised to 48dp on a small phone, and never so tall that three player rows plus the bottom buttons do not fit. */
    static float rowHeight(float H, float unitsPerDp) { return Dp.touchHeight(88f, 110f, (H - TOP - 3 * STAT_LINE - BOTTOM_ROW - 20f) / 4f, unitsPerDp); }

    /** One short line of plain numbers for the list. */
    static String summary(SaveData d) {
        String best = "BEST " + (int) d.bestHeight + " M";
        return d.seed != 0 ? best + "  CLIMB " + (int) d.climbHeight + " M" : best + "  NO CLIMB";
    }

    @Override public void render(float dt) {
        Gdx.gl.glClearColor(0.06f, 0.08f, 0.16f, 1f); Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        Ui ui = g.ui; ui.begin();
        float W = ui.w(), H = ui.h();
        boolean prompt = confirmDelete >= 0;
        ui.textC("PLAYERS", W / 2, H - 62, 6f, Ui.TEXT);
        ui.textC("EACH PLAYER HAS THEIR OWN CLIMB AND RECORDS", W / 2, H - 100, 2.8f, Ui.DIM);

        float nw = 380, dw = 150, gap = 8, colGap = 30, bh = rowHeight(H, Dp.unitsPerDp(ui)), pitch = bh + STAT_LINE;
        float rowW = nw + gap + dw, total = 2 * rowW + colGap, x0 = W / 2 - total / 2, top = H - TOP;
        List<Profiles.Entry> list = g.profiles.players();
        for (int i = 0; i < list.size(); i++) {
            Profiles.Entry e = list.get(i);
            int col = i % 2, row = i / 2;
            float x = x0 + col * (rowW + colGap), y = top - bh - row * pitch;
            boolean cur = e.id == g.currentPlayerId();
            if (!prompt) {
                if (ui.button(e.name, x, y, nw, bh, cur)) { g.audio.play("click"); if (!cur) g.switchPlayer(e.id); next = home; }
                if (ui.button("DELETE", x + nw + gap, y, dw, bh)) { g.audio.play("click"); confirmDelete = e.id; }
            }
            if (prompt) continue;                         // the dialog owns the screen (and every tap)
            String line = (cur ? "PLAYING  " : "") + (i < stats.size() ? stats.get(i) : "");
            float lpx = Math.max(2.2f, Math.min(2.8f, (rowW - 8f) / Math.max(1, line.length() * 6 - 1)));      // shrinks a little rather than run into the next player's line
            ui.text(line, x + 4, y - 8 - 7 * lpx, lpx, cur ? Ui.GOOD : Ui.DIM);
        }

        float by = 30, newW = 380, backW = 300, bgap = 24, bx = W / 2 - (newW + bgap + backW) / 2;
        if (!prompt) {
            if (g.profiles.canAdd()) { if (ui.button("NEW PLAYER", bx, by, newW, bh, true)) { g.audio.play("click"); next = new NameScreen(g, this, home); } }
            else ui.textC(Profiles.MAX_PLAYERS + " PLAYERS IS THE MOST", bx + newW / 2, by + bh / 2 - 10, 3f, Ui.DIM);
            if (ui.button("BACK", bx + newW + bgap, by, backW, bh)) { g.audio.play("click"); next = home; }
        }
        if (prompt) deletePrompt(ui, W, H);
        ui.end();
        g.autoShot("players", dt);
        if (next != null) { Screen n = next; next = null; g.setScreen(n); }
    }

    /** DELETE asks first; CANCEL is the highlighted button and leaves everything as it was. */
    private void deletePrompt(Ui ui, float W, float H) {
        Profiles.Entry e = g.profiles.get(confirmDelete);
        if (e == null) { confirmDelete = -1; return; }
        ui.rect(0, 0, W, H, new Color(0f, 0f, 0.05f, 0.78f));
        float pw = Math.min(W - 60, 880), ph = 360, bw = Math.min(380, (pw - 90) / 2), bh = Dp.touchHeight(88f, 110f, 110f, Dp.unitsPerDp(ui));
        float px = W / 2 - pw / 2, py = Math.max(20, H / 2 - ph / 2);
        ui.panel(px, py, pw, ph);
        ui.textC("DELETE " + e.name + "?", W / 2, py + ph - 40 - ui.font.height(5f), 5f, Ui.ACCENT);
        ui.textC("THEIR CLIMB AND RECORDS ARE GONE FOR GOOD.", W / 2, py + ph - 120, 3f, Ui.TEXT);
        if (g.profiles.count() == 1) ui.textC("A NEW EMPTY PLAYER TAKES THEIR PLACE.", W / 2, py + ph - 156, 3f, Ui.DIM);
        if (ui.button("CANCEL", W / 2 - bw - 14, py + 36, bw, bh, true)) { g.audio.play("click"); confirmDelete = -1; }
        if (ui.button("DELETE", W / 2 + 14, py + 36, bw, bh)) {
            int id = confirmDelete; confirmDelete = -1;
            g.audio.play("click"); g.deletePlayer(id); refresh();
        }
    }
}

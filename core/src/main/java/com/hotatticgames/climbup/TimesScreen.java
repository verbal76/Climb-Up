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
 * The times table: the ten castle-to-castle legs, the total for all ten, and the best distance in the endless climb beyond them. Best times next to the last run.
 * Reachable from the title screen, the pause menu and the finish screen; BACK (or Escape / Backspace / the Android back key) returns to where it was opened from.
 */
public final class TimesScreen extends ScreenAdapter {
    private final ClimbGame g; private final Screen back;
    private boolean leave;

    public TimesScreen(ClimbGame g, Screen back) { this.g = g; this.back = back; }

    @Override public void show() {
        leave = false;
        Gdx.input.setInputProcessor(new InputMultiplexer(g.ui, new InputAdapter() {
            @Override public boolean keyDown(int k) { if (k == Input.Keys.ESCAPE || k == Input.Keys.BACK || k == Input.Keys.BACKSPACE) { leave = true; return true; } return false; }
        }));
    }
    @Override public void resize(int w, int h) { g.ui.resize(w, h); }

    /** m:ss.t or a dash for "no time yet". */
    static String time(float s) { return s > 0f ? PlayScreen.fmtTime(s) : "--:--.-"; }
    static String metres(float m) { return m > 0f ? (long) m + " M" : "--"; }

    @Override public void render(float dt) {
        Gdx.gl.glClearColor(0.06f, 0.08f, 0.16f, 1f); Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        Ui ui = g.ui; ui.begin();
        float W = ui.w(), H = ui.h();
        RecordsData rec = g.save.records(); RecordsData.Run lr = rec.shown(g.save.seed);
        int legs = Math.max(1, Math.min(RecordsData.LEGS, (int) g.tuning.finishCastle));
        ui.textC("TIMES TABLE", W / 2, H - 64, 6f, Ui.TEXT);
        // the ten towers, the total and the infinity row share the height left between the title and the BACK button; two rules of 22 px (plus a text height) separate the header, the towers and the total
        float top = H - 114, bottom = 144f, avail = top - bottom;
        float step = Math.min(58f, (avail - 44f) / (legs + 2 * 0.77f));
        float px = Math.max(2.4f, Math.min(Math.min(4.4f * Math.max(1f, ui.tm()), step * 0.11f), (W - 100f) / (34 * 6f)));
        float tw = 34 * 6 * px, x0 = W / 2 - tw / 2, xBest = x0 + 11 * 6 * px, xLast = x0 + 24 * 6 * px, th = ui.font.height(px);
        Color gold = new Color(1f, 0.82f, 0.3f, 1f);
        ui.text("TOWER", x0, top, px, Ui.DIM); ui.text("BEST", xBest, top, px, Ui.DIM); ui.text(lr != null && lr == rec.current ? "THIS RUN" : "LAST RUN", xLast, top, px, Ui.DIM);
        ui.rect(x0 - 10, top - 12, tw + 20, 3, Ui.EDGE);
        float y = top - 22 - th;
        for (int i = 0; i < legs; i++) {
            boolean pb = lr != null && lr.legPb[i];
            ui.text("TOWER " + (i + 1), x0, y, px, Ui.TEXT);
            ui.text(time(rec.bestLegs[i]), xBest, y, px, gold);
            ui.text(lr != null && lr.legs[i] > 0f ? time(lr.legs[i]) + (pb ? " PB" : "") : "--:--.-", xLast, y, px, pb ? Ui.GOOD : Ui.DIM);
            if (i < legs - 1) y -= step;
        }
        ui.rect(x0 - 10, y - 12, tw + 20, 3, Ui.EDGE);
        y -= 22 + th;
        boolean fin = lr != null && lr.finished;
        ui.text("TOTAL", x0, y, px, Ui.TEXT);
        ui.text(time(rec.bestTotal), xBest, y, px, gold);
        ui.text(fin ? time(lr.total) + (lr.totalPb ? " PB" : "") : "--:--.-", xLast, y, px, fin && lr.totalPb ? Ui.GOOD : Ui.DIM);
        y -= step;
        ui.text("INFINITY", x0, y, px, Ui.TEXT);
        ui.text(metres(rec.bestInfinity), xBest, y, px, gold);
        boolean inf = lr != null && lr.infinity > 0f;
        ui.text(inf ? metres(lr.infinity) + (lr.infinityPb ? " PB" : "") : "--", xLast, y, px, inf && lr.infinityPb ? Ui.GOOD : Ui.DIM);
        ui.textC("PB MARKS A NEW PERSONAL BEST. INFINITY IS METRES CLIMBED BEYOND TOWER " + legs + ".", W / 2, 108f, Math.max(2.2f, Math.min(2.8f, (W - 100f) / (74 * 6f))), Ui.DIM);
        if (ui.button("BACK", W / 2 - 150, 22, 300, 78, true) || leave) { g.audio.play("click"); g.setScreen(back); }
        ui.end();
        g.autoShot("times", dt);
    }
}

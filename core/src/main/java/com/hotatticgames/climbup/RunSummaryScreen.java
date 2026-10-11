package com.hotatticgames.climbup;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.InputMultiplexer;
import com.badlogic.gdx.ScreenAdapter;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.hotatticgames.climbup.ui.Ui;

/** End of a run (finished, or ended from the pause / finish menu): total time, each tower's time with PB where it is a personal best, the infinity distance and whether that is a new record. */
public final class RunSummaryScreen extends ScreenAdapter {
    private final ClimbGame g; private final RecordsData.Run run;
    private boolean leave;

    public RunSummaryScreen(ClimbGame g, RecordsData.Run run) { this.g = g; this.run = run == null ? new RecordsData.Run() : run; }

    @Override public void show() {
        leave = false;
        Gdx.input.setInputProcessor(new InputMultiplexer(g.ui, new InputAdapter() {
            @Override public boolean keyDown(int k) { if (k == Input.Keys.ESCAPE || k == Input.Keys.BACK || k == Input.Keys.BACKSPACE || k == Input.Keys.ENTER) { leave = true; return true; } return false; }
        }));
    }
    @Override public void resize(int w, int h) { g.ui.resize(w, h); }

    @Override public void render(float dt) {
        Gdx.gl.glClearColor(0.06f, 0.08f, 0.16f, 1f); Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        Ui ui = g.ui; ui.begin();
        float W = ui.w(), H = ui.h();
        RecordsData rec = g.save.records();
        Color gold = new Color(1f, 0.82f, 0.3f, 1f), green = new Color(0.45f, 1f, 0.55f, 1f);
        int legs = Math.max(1, Math.min(RecordsData.LEGS, (int) g.tuning.finishCastle));
        ui.textC(run.finished ? "RUN COMPLETE" : "RUN ENDED", W / 2, H - 76, 6.4f, Ui.TEXT);
        // total
        ui.textC("TOTAL TIME", W / 2, H - 124, 3.2f, Ui.DIM);
        ui.textC(run.finished ? TimesScreen.time(run.total) : "NOT FINISHED", W / 2, H - 196, run.finished ? 7.5f : 5f, run.finished ? green : Ui.DIM);
        ui.textC(!run.finished ? "BEST " + TimesScreen.time(rec.bestTotal) : run.totalPb ? "NEW BEST TOTAL!" : "BEST " + TimesScreen.time(rec.bestTotal), W / 2, H - 238, 3.6f, run.totalPb ? gold : Ui.DIM);
        // the towers in two columns
        int perCol = (legs + 1) / 2; float step = Math.min(46f, (H - 284 - 250) / perCol);
        float px = Math.max(2.4f, Math.min(4.4f, (W / 2 - 60f) / (23 * 6f)));
        float colW = 23 * 6 * px, gap = 40f, left = W / 2 - colW - gap / 2, right = W / 2 + gap / 2, top = H - 284f;
        for (int i = 0; i < legs; i++) {
            float x = i < perCol ? left : right, y = top - (i % perCol) * step;
            boolean done = run.legs[i] > 0f, pb = run.legPb[i];
            ui.text("TOWER " + (i + 1), x, y, px, Ui.TEXT);
            String t = done ? PlayScreen.fmtTime(run.legs[i]) : "--:--.-";
            ui.text(t, x + 9 * 6 * px, y, px, done ? (pb ? green : Ui.TEXT) : Ui.DIM);
            if (pb) ui.text("PB", x + (9 + t.length() + 1) * 6 * px, y, px, gold);
        }
        // infinity
        float iy = top - perCol * step - 14f;
        ui.rect(left, iy + step * 0.62f, right + colW - left, 3, Ui.EDGE);
        String inf = run.infinity > 0f ? "INFINITY " + (long) run.infinity + " M" : "INFINITY: NOT REACHED";
        ui.textC(inf, W / 2, iy - 22f, 4.4f, run.infinity > 0f ? Ui.TEXT : Ui.DIM);
        ui.textC(run.infinityPb ? "NEW INFINITY RECORD!" : "BEST INFINITY " + TimesScreen.metres(rec.bestInfinity), W / 2, iy - 68f, 3.6f, run.infinityPb ? gold : Ui.DIM);
        float bw = Math.min(420f, W / 2 - 50f), bh = 78f;
        if (ui.button("TIMES TABLE", W / 2 - bw - 14, 22, bw, bh)) { g.audio.play("click"); g.setScreen(new TimesScreen(g, this)); }
        if (ui.button("MAIN MENU", W / 2 + 14, 22, bw, bh, true) || leave) { g.audio.play("click"); g.setScreen(new TitleScreen(g)); }
        ui.end();
        g.autoShot("summary", dt);
    }
}

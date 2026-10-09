package com.hotatticgames.climbup;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.InputMultiplexer;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.ScreenAdapter;
import com.badlogic.gdx.graphics.GL20;
import com.hotatticgames.climbup.ui.Ui;
import java.util.List;

/** Runs that were in progress when an update changed the rules, kept with the build they started on (newest first). Read-only: they cannot be resumed. */
public final class LegacyScreen extends ScreenAdapter {
    private final ClimbGame g; private final Screen back;
    public LegacyScreen(ClimbGame g, Screen back) { this.g = g; this.back = back; }
    @Override public void show() { Gdx.input.setInputProcessor(new InputMultiplexer(g.ui)); }
    @Override public void resize(int w, int h) { g.ui.resize(w, h); }
    @Override public void render(float dt) {
        Gdx.gl.glClearColor(0.06f, 0.08f, 0.16f, 1f); Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        Ui ui = g.ui; ui.begin();
        float W = ui.w(), H = ui.h();
        ui.textC("LEGACY RUNS", W / 2, H - 80, 7f, Ui.TEXT);
        ui.textC("SET ASIDE WHEN AN UPDATE CHANGED THE RULES. THEY CANNOT BE CONTINUED.", W / 2, H - 126, 2.8f * Math.min(ui.tm(), 1.3f), Ui.DIM);
        List<SaveData.LegacyRun> l = g.save.legacy;
        int shown = Math.min(l.size(), 6);
        float y = H - 200, step = Math.min(92f, (H - 200 - 130) / Math.max(1, shown));
        for (int i = 0; i < shown; i++) {
            SaveData.LegacyRun r = l.get(l.size() - 1 - i);
            ui.textC(Legacy.stampLabel(r.version, r.build, ClimbGame.VERSION) + (r.date.isEmpty() ? "" : "   STARTED " + r.date), W / 2, y, 3.4f, Ui.ACCENT);
            ui.textC((int) r.height + " M   " + r.towers + " CASTLE" + (r.towers == 1 ? "" : "S") + "   " + PlayScreen.fmtTime(r.runClock) + (r.finished ? "   FINISHED " + PlayScreen.fmtTime(r.finishTime) : "") + (r.savedDate.isEmpty() ? "" : "   SAVED " + r.savedDate), W / 2, y - 36, 3f, Ui.TEXT);
            y -= step;
        }
        if (l.size() > shown) ui.textC("+ " + (l.size() - shown) + " OLDER", W / 2, 118, 3f, Ui.DIM);
        if (ui.button("BACK", W / 2 - 150, 22, 300, 78, true)) { g.audio.play("click"); g.setScreen(back); }
        ui.end();
        g.autoShot("legacy", dt);
    }
}

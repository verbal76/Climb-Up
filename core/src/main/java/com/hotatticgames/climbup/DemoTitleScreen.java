package com.hotatticgames.climbup;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.InputMultiplexer;
import com.badlogic.gdx.ScreenAdapter;
import com.badlogic.gdx.graphics.Color;
import com.hotatticgames.climbup.ui.Ui;

/**
 * The demo's main menu (docs/DEMO.md): PLAY goes on with the one sequence (NORMAL, PUNISHER, NOT GONNA HAPPEN, then the promotional screen) at the
 * stage it has reached, never anywhere else. SETTINGS, BEST TIMES, CREDITS and EXIT are the only other ways out. No course codes, no difficulty
 * choice, no players, no legacy runs, no hidden menus.
 */
public final class DemoTitleScreen extends ScreenAdapter {
    private final ClimbGame g;
    private String problem;
    private final DemoBackdrop backdrop = new DemoBackdrop();
    private boolean showTimes, exitRequested, fading; private float fadeT;
    private com.badlogic.gdx.Screen next;

    public DemoTitleScreen(ClimbGame g) { this(g, null); }
    /** @param problem a reason the demo could not go on (shown, never hidden), or null */
    DemoTitleScreen(ClimbGame g, String problem) { this.g = g; this.problem = problem; }

    @Override public void show() {
        if (g.demoFlow == null && problem == null) problem = g.demoProblem;
        if (g.demoFlow != null && problem == null) problem = g.demoFlow.verifyAll();
        if (g.demoFlow != null && problem == null) backdrop.open(g, g.demoFlow);
        Gdx.input.setInputProcessor(new InputMultiplexer(g.ui));
        g.audio.music("music_menu");
    }
    @Override public void resize(int w, int h) { g.ui.resize(w, h); backdrop.resize(w, h); }

    /** PLAY at the start (and once the sequence is finished: the promotional screen offers PLAY DEMO AGAIN), else CONTINUE at the stage reached. */
    static String playLabel(DemoFlow f) {
        int s = f.records.stage;
        if (s <= 0 || s >= 3) return "PLAY";
        return "CONTINUE: " + f.recipe.stages.get(s).title.replace(" DIFFICULTY", "");
    }

    @Override public void render(float dt) {
        g.models.preload(4f);
        backdrop.render(dt);
        Ui ui = g.ui; ui.begin();
        float W = ui.w(), H = ui.h(), time = backdrop.time();
        String t1 = g.demoFlow != null ? g.demoFlow.recipe.promoTitle : "UPWARDLY";
        float px = Math.min(15f, (W - 80f) / Math.max(1, t1.length() * 6f));
        float tx = W / 2 - ui.font.width(t1, px) / 2, ty = H - 190 + (float) Math.sin(time * 1.6f) * 4f;
        ui.font.drawShadow(ui.batch, t1, tx + 6, ty - 8, px, new Color(0.35f, 0.12f, 0.02f, 1f), new Color(0, 0, 0, 0.6f));
        ui.font.drawShadow(ui.batch, t1, tx, ty, px, new Color(1f, 0.74f, 0.16f, 1f), new Color(0.2f, 0.07f, 0.0f, 0.9f));
        String sub = g.demoFlow != null ? g.demoFlow.recipe.demoName : "UPWARDLY DEMO";
        ui.textC(sub, W / 2, ty - 56, Math.min(4.2f, (W - 40f) / (sub.length() * 6f)), Ui.TEXT);
        float bw = Math.min(520f, W - 120f), bh = Dp.touchHeight(84f, 96f, 96f, Dp.unitsPerDp(ui)), gap = 16f, x = W / 2 - bw / 2;
        float y = Math.min(ty - 140f, H * 0.52f);
        if (problem != null) {
            java.util.List<String> lines = com.hotatticgames.climbup.ui.TextWrap.wrap(problem.toUpperCase(java.util.Locale.ROOT), s -> ui.font.width(s, 3f), W - 160f);
            float ph = 60 + lines.size() * 34f; ui.panel(80, y - ph + 40, W - 160, ph);
            for (int i = 0; i < lines.size(); i++) ui.textC(lines.get(i), W / 2, y - i * 34f, 3f, Ui.ACCENT);
            y -= ph + gap;
        } else if (!showTimes && !fading) {
            if (ui.button(playLabel(g.demoFlow), x, y - bh, bw, bh, true)) { g.audio.play("click"); fading = true; }
            y -= bh + gap;
        }
        if (!showTimes && !fading) {
            float hw = (bw - gap) / 2;
            if (ui.button("SETTINGS", x, y - bh, hw, bh)) { g.audio.play("click"); next = new SettingsScreen(g, this, 0); }
            if (problem == null && ui.button("BEST TIMES", x + hw + gap, y - bh, hw, bh)) { g.audio.play("click"); showTimes = true; }
            y -= bh + gap;
            if (ui.button("CREDITS", x, y - bh, hw, bh)) { g.audio.play("click"); next = new CreditsScreen(g, this); }
            if (ui.button("EXIT", x + hw + gap, y - bh, hw, bh)) { g.audio.play("click"); exitRequested = true; }
        }
        if (showTimes) timesPanel(ui, W, H);
        String ver = g.versionLabel();
        ui.rect(0, 0, W, 34, new Color(0.05f, 0.07f, 0.14f, 0.7f));
        ui.text(ver, W - 72 - ui.font.width(ver, 2.8f), 9, 2.8f, Ui.ACCENT);
        if (fading) { fadeT += Math.min(dt, 0.1f); ui.rect(0, 0, W, H, new Color(0, 0, 0, Math.min(1f, fadeT / 0.5f))); }
        ui.end();
        g.autoShot("demotitle", dt);
        if (exitRequested) { exitRequested = false; g.persist(); Gdx.app.exit(); return; }
        if (fading && fadeT >= 0.5f) { fading = false; fadeT = 0f; next = g.demoFlow.resume(); }
        if (next != null) { com.badlogic.gdx.Screen n = next; next = null; g.setScreen(n); }
    }

    /** The demo's best times: one per difficulty (on this demo's course), and the best whole run of all three. */
    private void timesPanel(Ui ui, float W, float H) {
        ui.rect(0, 0, W, H, new Color(0f, 0f, 0.05f, 0.72f));
        float pw = Math.min(W - 80, 900), ph = Math.min(H - 60, 470), px = W / 2 - pw / 2, py = H / 2 - ph / 2;
        ui.panel(px, py, pw, ph);
        ui.textC("BEST TIMES", W / 2, py + ph - 70, 5f, Ui.ACCENT);
        DemoFlow f = g.demoFlow; float y = py + ph - 140;
        for (int d = 0; d < 3; d++) {
            DemoFlow.Stage st = f.stage(d);
            long b = f.records.bestFor(d, st.seed, st.fingerprint);
            String name = f.recipe.stages.get(d).title.replace(" DIFFICULTY", "");
            ui.text(name, px + 50, y, 3.4f, Ui.TEXT);
            String v = b > 0 ? ClockText.time(b / (float) RunRecord.STEPS_PER_SECOND) : "-";
            ui.text(v, px + pw - 50 - ui.font.width(v, 3.4f), y, 3.4f, b > 0 ? new Color(0.45f, 1f, 0.55f, 1f) : Ui.DIM);
            y -= 54;
        }
        String all = f.records.bestRunTicks > 0 && f.records.bestRunFingerprint.equals(f.fingerprint()) ? ClockText.time(f.records.bestRunTicks / (float) RunRecord.STEPS_PER_SECOND) : "-";
        ui.text("ALL THREE", px + 50, y, 3.4f, Ui.ACCENT); ui.text(all, px + pw - 50 - ui.font.width(all, 3.4f), y, 3.4f, Ui.ACCENT);
        String foot = "CASTLE 2 TO CASTLE 3. ASSISTED RUNS ARE NOT RECORDED.";
        ui.textC(foot, W / 2, py + 120, Math.min(2.6f, (pw - 60f) / (foot.length() * 6f)), Ui.DIM);          // always inside the panel
        if (ui.button("BACK", W / 2 - 150, py + 26, 300, 78, true)) { g.audio.play("click"); showTimes = false; }
    }

    @Override public void hide() { backdrop.dispose(); }
    @Override public void dispose() { backdrop.dispose(); }
}

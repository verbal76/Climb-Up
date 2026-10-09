package com.hotatticgames.climbup;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.InputMultiplexer;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.ScreenAdapter;
import com.badlogic.gdx.graphics.Color;
import com.hotatticgames.climbup.render.WorldRenderer;
import com.hotatticgames.climbup.sim.Course;
import com.hotatticgames.climbup.sim.InputState;
import com.hotatticgames.climbup.sim.Sim;
import com.hotatticgames.climbup.sim.CourseIO;
import com.hotatticgames.climbup.sim.Tower;
import com.hotatticgames.climbup.ui.Ui;

/** Title screen: the live tower with the hero waiting at the continue point, plus original voxel title lettering. */
public final class TitleScreen extends ScreenAdapter {
    private final ClimbGame g;
    private Course course;
    private Sim sim;
    private WorldRenderer world;
    private float time;
    private boolean confirmNew, confirmDiscard;
    private String lastBub;
    private com.badlogic.gdx.Screen next;   // screen change is applied after ui.end() so the sprite batch is never left open

    public TitleScreen(ClimbGame g) { this.g = g; }

    @Override public void show() {
        if (Legacy.needsPrompt(g.save, ClimbGame.VERSION) && Legacy.tryContinue(g.save, g.tuning)) g.persist();      // an older climb that can safely carry on does: nobody loses a run that still works
        Tower tw = null; int idx = 0;
        if (g.save.seed != 0 && g.save.sliceJson != null) {        // backdrop: where the climb in progress stands
            try {
                Course d = CourseIO.fromJson(g.save.sliceJson);
                tw = new Tower(g.save.seed, g.tuning, g.save.slice, d);
                idx = tw.slices.get(0).toWorld(Math.max(0, Math.min(g.save.sliceCheckpoint, d.routeSize() - 1)));
            } catch (Exception e) { tw = null; idx = 0; }
        }
        if (tw == null) tw = new Tower(2024L, g.tuning);
        course = tw.world;
        sim = Sim.startOn(course, g.tuning, idx);
        sim.setRange(idx - 60, idx + 120);
        for (int i = 0; i < 30; i++) sim.step(new InputState());
        world = new WorldRenderer(g.tuning, course, g.models, g.settings.quality, g.settings.character);
        world.reducedMotion = g.settings.reducedMotion;
        world.snapCamera(sim);
        Gdx.input.setInputProcessor(new InputMultiplexer(g.ui));
        g.audio.music("music_menu");
    }

    @Override public void resize(int w, int h) { g.ui.resize(w, h); if (world != null) world.resize(w, h); }

    @Override public void render(float dt) {
        time += Math.min(dt, 0.1f);
        world.render(sim, 1f, dt, time, true);
        Ui ui = g.ui; ui.begin();
        float W = ui.w(), H = ui.h();
        // title lettering: chunky two-layer voxel look
        float px = 15f;
        String t1 = "CLIMB UP";
        float tw = ui.font.width(t1, px);
        float tx = W / 2 - tw / 2, ty = H - 190 + (float) Math.sin(time * 1.6f) * 4f;
        ui.font.drawShadow(ui.batch, t1, tx + 6, ty - 8, px, new Color(0.35f, 0.12f, 0.02f, 1f), new Color(0, 0, 0, 0.6f));
        ui.font.drawShadow(ui.batch, t1, tx, ty, px, new Color(1f, 0.74f, 0.16f, 1f), new Color(0.2f, 0.07f, 0.0f, 0.9f));
        ui.textC("A TOWER THAT DOESN'T EXIST. A CLIMB THAT DOES.", W / 2, ty - 56, 3.4f, Ui.TEXT);
        String bub = world.heroBubble();
        if (bub != null && !bub.equals(lastBub)) g.audio.play("talk", 0.4f, com.hotatticgames.climbup.render.Characters.voice(g.settings.character));
        lastBub = bub;
        if (bub != null) { float[] hp = new float[2]; world.heroHeadScreen(W, H, hp); ui.bubble(bub, hp[0], hp[1] + 6, world.heroBubbleAlpha()); }
        float bw = 400, bh = 76, bx = Math.max(40f, W / 2 - 640f + 40f);
        boolean has = g.save.seed != 0 && g.save.sliceJson != null;
        boolean prompt = Legacy.needsPrompt(g.save, ClimbGame.VERSION);        // a run from an older version is waiting for a decision before anything else
        float y0 = H * 0.46f;
        if (!prompt) {
            if (ui.button(has ? "CONTINUE" : "PLAY", bx, y0, bw, bh, true)) { g.audio.play("click"); next = new PlayScreen(g, false); }
            float yy = y0 - 88;
            if (has) {
                if (ui.button(confirmNew ? "TAP AGAIN: NEW CLIMB" : "NEW CLIMB", bx, yy, bw, bh)) {
                    if (confirmNew) { g.forgetRun(); g.save.falls = 0; g.runFresh = true; g.persist(); g.audio.play("click"); next = new PlayScreen(g, false); }
                    confirmNew = true;
                }
                yy -= 88;
            }
            if (!g.save.legacy.isEmpty()) {
                if (ui.button("LEGACY RUNS (" + g.save.legacy.size() + ")", bx, yy, bw, bh)) { g.audio.play("click"); next = new LegacyScreen(g, this); }
                yy -= 88;
            }
            if (ui.button("SETTINGS", bx, yy, bw, bh)) { g.audio.play("click"); next = new SettingsScreen(g, this); }
            yy -= 88;
            if (ui.button("CREDITS", bx, yy, bw, bh)) { g.audio.play("click"); next = new CreditsScreen(g, this); }
        }
        float cx = Math.min(W - bw - 40f, W / 2 + 240f);
        if (!prompt) ui.textC("HERO: TAP TO CHANGE", cx + bw / 2, y0 + bh + 14, 3.2f, Ui.TEXT);
        if (!prompt && ui.button(com.hotatticgames.climbup.render.Characters.name(g.settings.character), cx, y0, bw, bh)) {
            g.settings.character = com.hotatticgames.climbup.render.Characters.next(g.settings.character);
            world.setCharacter(g.settings.character); g.persist(); g.audio.play("click");
        }
        String stat = "BEST " + (int) g.save.bestHeight + " M" + (g.save.bestFinish > 0f ? "   BEST FINISH " + PlayScreen.fmtTime(g.save.bestFinish) : "");
        ui.rect(0, 0, W, 54, new Color(0.05f, 0.07f, 0.14f, 0.7f)); ui.text(stat, 24, 18, 3f, Ui.TEXT);
        String ver = Legacy.versionLine(ClimbGame.VERSION, ClimbGame.appBuild);          // always visible: which build is this?
        ui.text(ver, W - 24 - ui.font.width(ver, 3.6f), 17, 3.6f, Ui.ACCENT);
        if (prompt) legacyPrompt(ui, W, H);
        ui.end();
        g.autoShot("title", dt);
        if (next != null) { Screen n = next; next = null; g.setScreen(n); }
    }

    /** A run from an older version is in progress: it cannot continue under the new rules, so offer to keep it as a stamped record. */
    private void legacyPrompt(Ui ui, float W, float H) {
        SaveData sd = g.save;
        ui.rect(0, 0, W, H, new Color(0f, 0f, 0.05f, 0.72f));
        float pw = Math.min(W - 60, 900), ph = 470, px = W / 2 - pw / 2, py = H / 2 - ph / 2;
        ui.panel(px, py, pw, ph);
        float cx = W / 2;
        ui.textC("A RUN FROM AN OLDER VERSION", cx, py + ph - 56, 4.6f, Ui.ACCENT);
        ui.textC("STARTED ON  " + Legacy.stampLabel(sd.climbVersion, sd.climbBuild, ClimbGame.VERSION) + (sd.climbDate.isEmpty() ? "" : "   " + sd.climbDate), cx, py + ph - 112, 3.4f, Ui.TEXT);
        ui.textC((int) sd.climbHeight + " M   " + sd.towers + " CASTLE" + (sd.towers == 1 ? "" : "S") + "   " + PlayScreen.fmtTime(sd.runClock) + (sd.finished ? "   FINISHED" : ""), cx, py + ph - 156, 3.4f, Ui.TEXT);
        ui.textC("THE RULES CHANGED IN THIS VERSION, SO IT CANNOT BE CONTINUED.", cx, py + ph - 214, 3f, Ui.DIM);
        ui.textC("SAVE IT AS A LEGACY RUN, STAMPED WITH THE BUILD IT STARTED ON?", cx, py + ph - 246, 3f, Ui.DIM);
        float bw = Math.min(400, pw / 2 - 40), bh = 80;
        if (ui.button("SAVE AS LEGACY RUN", cx - bw - 14, py + 40, bw, bh, true)) { g.audio.play("click"); Legacy.archive(sd, Legacy.today()); g.persist(); confirmDiscard = false; }
        if (ui.button(confirmDiscard ? "TAP AGAIN: DISCARD" : "DISCARD IT", cx + 14, py + 40, bw, bh)) {
            if (confirmDiscard) { g.forgetRun(); g.persist(); g.audio.play("click"); confirmDiscard = false; } else confirmDiscard = true;
        }
    }

    @Override public void hide() { dispose(); }
    @Override public void dispose() { if (world != null) { world.dispose(); world = null; } }
}

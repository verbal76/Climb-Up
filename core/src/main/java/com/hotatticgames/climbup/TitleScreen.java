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
import com.hotatticgames.climbup.ui.Ui;

/** Title screen: the live tower with the hero waiting at the continue point, plus original voxel title lettering. */
public final class TitleScreen extends ScreenAdapter {
    private final ClimbGame g;
    private Course course;
    private Sim sim;
    private WorldRenderer world;
    private float time;
    private boolean confirmNew;
    private com.badlogic.gdx.Screen next;   // screen change is applied after ui.end() so the sprite batch is never left open

    public TitleScreen(ClimbGame g) { this.g = g; }

    @Override public void show() {
        course = g.loadCourse(g.save.courseIndex);
        int cp = Math.min(g.save.checkpoint, course.goalIndex());
        sim = Sim.startOn(course, g.tuning, cp);
        for (int i = 0; i < 30; i++) sim.step(new InputState());
        world = new WorldRenderer(g.tuning, course, g.models, g.settings.quality);
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
        float bw = 400, bh = 76, bx = Math.max(40f, W / 2 - 640f + 40f);
        boolean has = g.save.bestHeight > 1f || g.save.checkpoint > 0;
        float y0 = H * 0.46f;
        if (ui.button(has ? "CONTINUE" : "PLAY", bx, y0, bw, bh, true)) { g.audio.play("click"); next = new PlayScreen(g, false); }
        float yy = y0 - 88;
        if (has) {
            if (ui.button(confirmNew ? "TAP AGAIN: NEW TOWER" : "NEW TOWER", bx, yy, bw, bh)) {
                if (confirmNew) { g.save.courseIndex++; g.save.checkpoint = 0; g.save.falls = 0; g.persist(); g.audio.play("click"); next = new PlayScreen(g, false); }
                confirmNew = true;
            }
            yy -= 88;
        }
        if (ui.button("SETTINGS", bx, yy, bw, bh)) { g.audio.play("click"); next = new SettingsScreen(g, this); }
        yy -= 88;
        if (ui.button("CREDITS", bx, yy, bw, bh)) { g.audio.play("click"); next = new CreditsScreen(g, this); }
        String stat = "BEST " + (int) g.save.bestHeight + " M   TOWER " + (g.save.courseIndex % ClimbGame.TOWERS + 1) + "   V" + ClimbGame.VERSION;
        ui.rect(0, 0, W, 54, new Color(0.05f, 0.07f, 0.14f, 0.7f)); ui.textC(stat, W / 2, 18, 3f, Ui.TEXT);
        ui.end();
        g.autoShot("title", dt);
        if (next != null) { Screen n = next; next = null; g.setScreen(n); }
    }

    @Override public void hide() { dispose(); }
    @Override public void dispose() { if (world != null) { world.dispose(); world = null; } }
}

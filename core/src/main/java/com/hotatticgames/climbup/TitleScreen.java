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
import com.hotatticgames.climbup.sim.Tower;
import com.hotatticgames.climbup.ui.TextWrap;
import com.hotatticgames.climbup.ui.Ui;

/** Title screen: the live tower with the hero waiting at the continue point, plus original voxel title lettering. */
public final class TitleScreen extends ScreenAdapter {
    private final ClimbGame g;
    private Course course;
    private Sim sim;
    private WorldRenderer world;
    private float time;
    private boolean confirmNew;
    private String lastBub;
    private com.badlogic.gdx.Screen next;   // screen change is applied after ui.end() so the sprite batch is never left open

    public TitleScreen(ClimbGame g) { this.g = g; }

    private boolean has;      // a climb in progress that can really be resumed

    @Override public void show() {
        Tower tw = null; int idx = 0;
        has = g.climbValid();
        if (has) {        // backdrop: where the climb in progress stands
            try {
                tw = new Tower(g.save.seed, g.tuning, g.history.read(g.save.seed), g.save.cpSlice);
                idx = tw.worldIndex(new Tower.Ref(g.save.cpSlice, g.save.cpLocal));
                if (idx < 0) { tw = null; idx = 0; }
            } catch (Exception e) { tw = null; idx = 0; }
        }
        if (tw == null) { has = false; tw = new Tower(2024L, g.tuning); }
        course = tw.world;
        sim = Sim.startOn(course, g.tuning, idx);
        sim.setRange(0, course.size() - 1);
        for (int i = 0; i < 30; i++) sim.step(new InputState());
        world = new WorldRenderer(g.tuning, course, g.models, g.settings.quality, g.settings.character);
        world.setOrigin(tw.originY, tw.originS);
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
        String t1 = "UPWARDLY";
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
        boolean notice = g.save.noticeOldClimb;        // the one-time notice that an old climb could not carry over comes first
        boolean prompt = notice || confirmNew;         // either dialog hides the menu behind it
        float y0 = H * 0.46f;
        if (!prompt) {
            if (ui.button(has ? "CONTINUE" : "PLAY", bx, y0, bw, bh, true)) { g.audio.play("click"); next = new PlayScreen(g, false); }
            float yy = y0 - 88;
            if (has) {
                if (ui.button("NEW RUN", bx, yy, bw, bh)) { g.audio.play("click"); confirmNew = true; }       // replaces the saved climb: asks first
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
        // old climbs live on the right, under the hero picker: the left column keeps its four full-size buttons and never runs into the status bar at the bottom
        if (!prompt && !g.save.legacy.isEmpty() && ui.button("LEGACY RUNS (" + g.save.legacy.size() + ")", cx, y0 - 88, bw, bh)) { g.audio.play("click"); next = new LegacyScreen(g, this); }
        String stat = "BEST " + (int) g.save.bestHeight + " M" + (g.save.bestFinish > 0f ? "   BEST FINISH " + PlayScreen.fmtTime(g.save.bestFinish) : "");
        ui.rect(0, 0, W, 34, new Color(0.05f, 0.07f, 0.14f, 0.7f)); ui.text(stat, 72, 9, 2.8f, Ui.TEXT);          // thin bar; text kept 72 px from the edges (rounded phone corners clip anything closer)
        String ver = Legacy.versionLine(ClimbGame.VERSION, ClimbGame.appBuild);          // always visible: which build is this?
        ui.text(ver, W - 72 - ui.font.width(ver, 2.8f), 9, 2.8f, Ui.ACCENT);
        if (notice) legacyPrompt(ui, W, H); else if (confirmNew) newRunPrompt(ui, W, H);
        ui.end();
        g.autoShot("title", dt);
        if (next != null) { Screen n = next; next = null; g.setScreen(n); }
    }

    /** NEW RUN over a saved climb: nothing is lost unless the player confirms; CANCEL (the highlighted button) leaves the saved climb exactly as it was. */
    private void newRunPrompt(Ui ui, float W, float H) {
        ui.rect(0, 0, W, H, new Color(0f, 0f, 0.05f, 0.72f));
        float scale = Math.min(ui.tm(), 1.3f), pw = Math.min(W - 60, 880), ph = 440 * Math.max(1f, scale * 0.9f), bw = Math.min(380, (pw - 90) / 2), bh = 84;
        float px = W / 2 - pw / 2, py = Math.max(20, H / 2 - ph / 2);
        ui.panel(px, py, pw, ph);
        float t = 4.6f * scale, b = 3.2f * scale;
        String l1 = "YOUR SAVED CLIMB (" + (int) g.save.climbHeight + " M) WILL BE REPLACED.";
        java.util.List<String> body = TextWrap.wrap(l1, str -> ui.font.width(str, b), pw - 80);
        ui.textC("START A NEW RUN?", W / 2, py + ph - 40 - ui.font.height(t), t, Ui.ACCENT);
        float y = py + ph - 40 - ui.font.height(t) - 40;
        for (String line : body) { y -= ui.font.height(b); ui.textC(line, W / 2, y, b, Ui.TEXT); y -= 14; }
        if (ui.button("CANCEL", W / 2 - bw - 14, py + 40, bw, bh, true)) { g.audio.play("click"); confirmNew = false; }
        if (ui.button("NEW RUN", W / 2 + 14, py + 40, bw, bh)) {
            confirmNew = false; g.forgetRun(); g.save.falls = 0; g.runFresh = true; g.persist(); g.audio.play("click"); next = new PlayScreen(g, false);
        }
    }

    private static final String[] NOTICE_A = {"THIS UPDATE CHANGED HOW A CLIMB IS STORED, SO YOUR", "UNFINISHED CLIMB COULD NOT CARRY OVER."};
    private static final String[] NOTICE_B = {"IT IS KEPT AS A LEGACY RUN. YOUR RECORDS AND SETTINGS ARE SAFE.", "YOUR NEXT CLIMB STARTS FRESH."};

    private static java.util.List<String> wrapAll(String[] src, java.util.function.ToDoubleFunction<String> width, float maxW) {
        java.util.List<String> out = new java.util.ArrayList<>(); for (String s : src) out.addAll(TextWrap.wrap(s, width, maxW)); return out;
    }

    /**
     * One-time notice after the save format change: the old climb could not carry over. The panel is as wide as the notice's own lines need (the wide layout), up to the screen;
     * a line is only wrapped when it still does not fit (large text sizes, narrow screens), and the text shrinks a step at a time if the panel would not fit the screen height.
     */
    private void legacyPrompt(Ui ui, float W, float H) {
        ui.rect(0, 0, W, H, new Color(0f, 0f, 0.05f, 0.72f));
        float pad = 44, bw = 360, bh = 80, scale = ui.tm(), titlePx = 4.6f, body1 = 3.2f, body2 = 3f;
        float pw, ph; java.util.List<String> a, b;
        while (true) {
            float t = titlePx * Math.min(scale, 1.3f), p1 = body1 * scale, p2 = body2 * scale;
            float need = ui.font.width("NEW VERSION, NEW TOWER", t);
            for (String l : NOTICE_A) need = Math.max(need, ui.font.width(l, p1));
            for (String l : NOTICE_B) need = Math.max(need, ui.font.width(l, p2));
            pw = Math.max(Math.min(W - 60, 900), Math.min(W - 60, need + 2 * pad)); float maxW = pw - 2 * pad;
            a = wrapAll(NOTICE_A, str -> ui.font.width(str, p1), maxW); b = wrapAll(NOTICE_B, str -> ui.font.width(str, p2), maxW);
            ph = 40 + ui.font.height(t) + 34 + a.size() * (ui.font.height(p1) + 14) + 22 + b.size() * (ui.font.height(p2) + 14) + 30 + bh + 40;
            if (ph <= H - 40 || scale <= 0.7f) break;
            scale -= 0.1f;
        }
        float t = titlePx * Math.min(scale, 1.3f), p1 = body1 * scale, p2 = body2 * scale;
        if (ui.font.width("NEW VERSION, NEW TOWER", t) > pw - 2 * pad) t = Math.max(2f, t * (pw - 2 * pad) / ui.font.width("NEW VERSION, NEW TOWER", t));
        float px = W / 2 - pw / 2, py = Math.max(20, H / 2 - ph / 2);
        ui.panel(px, py, pw, ph);
        float cx = W / 2, top = py + ph - 40, y = top - ui.font.height(t);
        ui.textC("NEW VERSION, NEW TOWER", cx, y, t, Ui.ACCENT);
        top = y - 34;
        for (String line : a) { y = top - ui.font.height(p1); ui.textC(line, cx, y, p1, Ui.TEXT); top = y - 14; }
        top -= 22;
        for (String line : b) { y = top - ui.font.height(p2); ui.textC(line, cx, y, p2, Ui.DIM); top = y - 14; }
        if (ui.button("OK", cx - bw / 2, py + 40, bw, bh, true)) { g.audio.play("click"); g.save.noticeOldClimb = false; g.persist(); }
    }

    @Override public void hide() { dispose(); }
    @Override public void dispose() { if (world != null) { world.dispose(); world = null; } }
}

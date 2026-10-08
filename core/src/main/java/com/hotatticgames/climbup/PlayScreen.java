package com.hotatticgames.climbup;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.InputMultiplexer;
import com.badlogic.gdx.ScreenAdapter;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.utils.ScreenUtils;
import com.hotatticgames.climbup.render.Palette;
import com.hotatticgames.climbup.render.WorldRenderer;
import com.hotatticgames.climbup.sim.*;
import com.hotatticgames.climbup.ui.Ui;
import java.io.File;

/** The climb itself: fixed-step simulation, touch controls, HUD, pause/summit overlays, contextual tips. */
public final class PlayScreen extends ScreenAdapter {
    private enum State { PLAYING, PAUSED, WON }

    private final ClimbGame g;
    private final boolean demo;
    private Course course;
    private Sim sim;
    private WorldRenderer world;
    private final InputState in = new InputState();
    private final ShapeRenderer shapes = new ShapeRenderer();
    private final Autopilot.Driver[] driver = new Autopilot.Driver[1];
    private State state = State.PLAYING;
    private float acc, time, runTime, fade, toastT, zoneT, stepT, ropeT, autosaveT, tipT, shotT;
    private String toast = "", tip = "", caption = ""; private float captionT;
    private int lastZone = -1, shots;
    private boolean confirmRestart;
    private static final class Pop { final String t; final Color c; float age; Pop(String t, Color c) { this.t = t; this.c = c; } }
    private final java.util.ArrayList<Pop> pops = new java.util.ArrayList<>();
    private float hitstop, confettiT; private int milestone;
    private static final String[] CATCH = {"NICE CATCH!", "FINGERTIPS!", "CLUTCH!", "HANG ON!"}, CLOSE = {"JUST MADE IT!", "CLOSE ONE!", "WHEW!", "THAT WAS TIGHT!"};

    private void pop(String t, Color c) { if (pops.size() > 3) pops.remove(0); pops.add(new Pop(t, c)); }
    private void freeze(float s) { if (!g.settings.reducedMotion) hitstop = Math.max(hitstop, s); }
    private com.badlogic.gdx.Screen next; private boolean disposeOnLeave;   // applied at the end of render(), after the batch is closed

    // ---- touch state
    private int stickPtr = -1, jumpPtr = -1;
    private final Vector2 stickBase = new Vector2(), stickKnob = new Vector2();
    private boolean jumpLatch, jumpHeldTouch;
    private float kx, ky; private boolean kJump, kJumpHeld;
    private static final float STICK_R = 120f;
    private static final String OVERLAY = System.getProperty("climb.overlay");   // test hook: pause | win

    public PlayScreen(ClimbGame g, boolean demo) { this.g = g; this.demo = demo; }

    @Override public void show() {
        if (world != null) { // returning from the settings screen: keep the run exactly as it was
            applySettings();
            Gdx.input.setInputProcessor(new InputMultiplexer(g.ui, new Touch()));
            g.audio.music("music_game");
            return;
        }
        course = g.loadCourse(g.save.courseIndex);
        sim = Sim.startOn(course, g.tuning, Math.min(g.save.checkpoint, course.goalIndex()));
        sim.checkpoint = Math.min(g.save.checkpoint, course.goalIndex());
        world = new WorldRenderer(g.tuning, course, g.models, g.settings.quality);
        applySettings();
        world.snapCamera(sim);
        if (demo) driver[0] = new Autopilot.Driver(sim);
        InputMultiplexer mux = new InputMultiplexer(g.ui, new Touch());
        Gdx.input.setInputProcessor(mux);
        Gdx.input.setCatchKey(Input.Keys.BACK, true);
        g.audio.music("music_game");
        runTime = 0; milestone = (int) (sim.maxHeight / 50f);
    }

    private void applySettings() {
        world.reducedMotion = g.settings.reducedMotion;
        world.quality = g.settings.quality;
        world.particles.density = g.settings.quality == 0 ? 0.4f : g.settings.quality == 1 ? 0.7f : 1f;
        sim.assistForgive = g.settings.assistForgive ? g.tuning.assistJumpForgiveness : 0f;
    }

    @Override public void resize(int w, int h) { g.ui.resize(w, h); world.resize(w, h); }

    // ------------------------------------------------------------------ input

    private final class Touch extends InputAdapter {
        private boolean stickSide(float x) { return g.settings.leftHanded ? x > g.ui.w() * 0.5f : x < g.ui.w() * 0.5f; }
        private final Vector2 v = new Vector2();
        private Vector2 un(int sx, int sy) { v.set(sx, sy); g.ui.viewport.unproject(v); return v; }

        @Override public boolean touchDown(int sx, int sy, int p, int b) {
            if (state != State.PLAYING) return false;
            Vector2 u = un(sx, sy);
            if (u.x > g.ui.w() - 150 && u.y > g.ui.h() - 150) return false; // pause button (handled via tap)
            if (u.x < 150 && u.y > g.ui.h() - 150 && g.settings.leftHanded) return false;
            if (stickSide(u.x)) {
                if (stickPtr < 0 && u.y < g.ui.h() * 0.85f) { stickPtr = p; stickBase.set(u); stickKnob.set(u); }
            } else if (jumpPtr < 0) { jumpPtr = p; jumpLatch = true; jumpHeldTouch = true; }
            return false;
        }
        @Override public boolean touchDragged(int sx, int sy, int p) {
            if (p == stickPtr) { stickKnob.set(un(sx, sy)); }
            return false;
        }
        @Override public boolean touchUp(int sx, int sy, int p, int b) {
            if (p == stickPtr) stickPtr = -1;
            if (p == jumpPtr) { jumpPtr = -1; jumpHeldTouch = false; }
            return false;
        }
        @Override public boolean keyDown(int k) {
            if (k == Input.Keys.SPACE || k == Input.Keys.Z || k == Input.Keys.K || k == Input.Keys.UP && false) { kJump = true; kJumpHeld = true; }
            if (k == Input.Keys.ESCAPE || k == Input.Keys.BACK || k == Input.Keys.P) { if (state == State.PLAYING) pauseGame(); else if (state == State.PAUSED) resumePlay(); }
            return false;
        }
        @Override public boolean keyUp(int k) {
            if (k == Input.Keys.SPACE || k == Input.Keys.Z || k == Input.Keys.K) kJumpHeld = false;
            return false;
        }
    }

    private void readInput() {
        in.moveX = in.moveY = 0;
        if (stickPtr >= 0) {
            float dx = stickKnob.x - stickBase.x, dy = stickKnob.y - stickBase.y;
            float len = (float) Math.hypot(dx, dy);
            if (len > STICK_R) { dx *= STICK_R / len; dy *= STICK_R / len; stickKnob.set(stickBase.x + dx, stickBase.y + dy); }
            float nx = dx / STICK_R, ny = dy / STICK_R;
            in.moveX = Math.abs(nx) < 0.18f ? 0f : nx;
            in.moveY = Math.abs(ny) < 0.35f ? 0f : (ny > 0 ? 1f : -1f);
            if (in.moveX != 0) in.moveX = Math.signum(in.moveX) * Math.min(1f, (Math.abs(in.moveX) - 0.18f) / 0.45f + 0.35f);
        }
        float kxv = (Gdx.input.isKeyPressed(Input.Keys.RIGHT) || Gdx.input.isKeyPressed(Input.Keys.D) ? 1 : 0) - (Gdx.input.isKeyPressed(Input.Keys.LEFT) || Gdx.input.isKeyPressed(Input.Keys.A) ? 1 : 0);
        float kyv = (Gdx.input.isKeyPressed(Input.Keys.UP) || Gdx.input.isKeyPressed(Input.Keys.W) ? 1 : 0) - (Gdx.input.isKeyPressed(Input.Keys.DOWN) || Gdx.input.isKeyPressed(Input.Keys.S) ? 1 : 0);
        if (kxv != 0) in.moveX = kxv;
        if (kyv != 0) in.moveY = kyv;
        in.jumpHeld = jumpHeldTouch || kJumpHeld;
        in.jumpPressed = jumpLatch || kJump;
    }

    // ------------------------------------------------------------------ frame

    @Override public void render(float dt) {
        dt = Math.min(dt, 0.1f);
        time += dt;
        if (OVERLAY != null && time > 0.8f && state == State.PLAYING) { if (OVERLAY.equals("pause")) pauseGame(); else if (OVERLAY.equals("win")) { runTime = 734f; sim.falls = 5; state = State.WON; } }
        boolean play = state == State.PLAYING;
        if (play) {
            float speed = g.settings.assistSlow ? g.tuning.assistSlowFactor : 1f;
            if (hitstop > 0) hitstop -= dt; else acc += dt * speed;
            int steps = 0;
            while (acc >= Sim.DT && steps < 6) {
                if (demo) { driver[0].drive(sim, in); } else readInput();
                sim.step(in);
                in.jumpPressed = false; jumpLatch = false; kJump = false;
                runTime += Sim.DT; handleEvents(sim.consumeEvents());
                acc -= Sim.DT; steps++;
                if (state != State.PLAYING) break;
            }
            if (steps == 6) acc = 0;
            g.save.playSeconds += dt;
            if (sim.maxHeight > g.save.bestHeight) g.save.bestHeight = sim.maxHeight;
            int ms = (int) (sim.maxHeight / 50f);
            if (ms > milestone) { milestone = ms; toast = ms * 50 + " M!"; toastT = 1.6f; g.audio.play("checkpoint", 0.55f, 1.35f); world.particles.burst(sim.s, sim.y + 1f, 12, gold, 2.6f, 3.2f, 0.1f, -0.5f, 1f); }
            for (int pi = pops.size() - 1; pi >= 0; pi--) { Pop pp = pops.get(pi); pp.age += dt; if (pp.age > 1.1f) pops.remove(pi); }
            autosaveT += dt; if (autosaveT > 8f) { autosaveT = 0; g.persist(); }
            ambientSounds(dt);
            updateTips(dt);
        }
        if (fade > 0) fade = Math.max(0, fade - dt * 2.2f);
        if (state == State.WON) {
            confettiT -= dt;
            if (confettiT <= 0) {
                confettiT = 0.16f;
                Color[] pal = {gold, cyan, new Color(1f, 0.45f, 0.7f, 1f), new Color(0.5f, 1f, 0.5f, 1f)};
                world.particles.burst(sim.s + MathUtils.random(-3f, 3f), sim.y + MathUtils.random(1.5f, 4.5f), 6, pal[MathUtils.random(3)], 3.5f, 4f, 0.14f, 6f, 1.4f);
            }
            world.particles.update(dt);
        }
        world.particles.update(play ? dt : 0f);
        world.render(sim, play ? acc / Sim.DT : 1f, dt, time, true);
        drawHud();
        g.autoShot("play", dt);
        if (next != null) { com.badlogic.gdx.Screen n = next; next = null; boolean d = disposeOnLeave; g.setScreen(n); if (d) dispose(); }
    }

    private void ambientSounds(float dt) {
        if (sim.mode == Sim.Mode.GROUND && Math.abs(sim.vx) > 2f) { stepT -= dt; if (stepT <= 0) { stepT = 0.27f; g.audio.play("step", 0.35f, 0.9f + MathUtils.random(0.2f)); world.particles.burst(sim.s - sim.facing * 0.3f, sim.y + 0.05f, 2, dust, 0.5f, 0.7f, 0.09f, 3f, 0.35f); } }
        if (sim.mode == Sim.Mode.ROPE && Math.abs(in.moveY) > 0.3f) { ropeT -= dt; if (ropeT <= 0) { ropeT = 0.32f; g.audio.play("rope", 0.4f, 0.9f + MathUtils.random(0.2f)); } }
    }

    private void vibrate(int ms, int minLevel) {
        if (g.settings.haptics >= minLevel && g.settings.haptics > 0) Gdx.input.vibrate(g.settings.haptics == 1 ? Math.max(5, ms / 2) : ms);
    }

    private void say(String s) { if (g.settings.captions) { caption = s; captionT = 1.4f; } }

    private final Color dust = new Color(0.9f, 0.88f, 0.8f, 1f), gold = new Color(1f, 0.85f, 0.25f, 1f), brown = new Color(0.55f, 0.4f, 0.28f, 1f), cyan = new Color(0.4f, 0.9f, 1f, 1f);

    private void handleEvents(int ev) {
        if (ev == 0) return;
        float s = sim.s, y = sim.y;
        world.heroEvents(ev, sim.landSpeed);
        if ((ev & Sim.EV_LAND) != 0 && sim.onElem >= 0) {
            world.platformLanded(sim.onElem, sim.landSpeed);
            Element le = course.get(sim.onElem);
            float edge = Math.abs(course.dsWrap(sim.s, sim.es1[sim.onElem])) - le.halfW();
            if (le.isPlatform() && edge > 0.06f && sim.landSpeed > 5f) { pop(CLOSE[MathUtils.random(CLOSE.length - 1)], gold); freeze(0.07f); world.shake(0.5f); vibrate(25, 1); }
        }
        if ((ev & Sim.EV_JUMP) != 0) world.kick(0.8f);
        if ((ev & Sim.EV_BOUNCE) != 0) { world.kick(4f); pop("BOING!", cyan); }
        if ((ev & Sim.EV_GRAB) != 0 && sim.mode == Sim.Mode.LEDGE) { pop(CATCH[MathUtils.random(CATCH.length - 1)], gold); freeze(0.08f); world.shake(0.4f); }
        if ((ev & Sim.EV_JUMP) != 0) { g.audio.play("jump", 0.7f, 0.95f + MathUtils.random(0.1f)); world.particles.burst(s, y, 4, dust, 1.4f, 1.2f, 0.12f, 6f, 0.4f); vibrate(8, 2); }
        if ((ev & Sim.EV_LAND) != 0) {
            float k = MathUtils.clamp(sim.landSpeed / 16f, 0.3f, 1f);
            g.audio.play("land", 0.4f + 0.5f * k, 0.9f + MathUtils.random(0.15f)); world.particles.burst(s, y, (int) (6 * k) + 2, dust, 2f, 1.5f, 0.14f, 7f, 0.45f);
            if (sim.landSpeed > 15f) { world.shake(0.4f); vibrate(18, 1); }
        }
        if ((ev & Sim.EV_BOUNCE) != 0) { g.audio.play("bounce", 0.9f, 1f); world.particles.burst(s, y, 10, cyan, 3.2f, 3.2f, 0.12f, 4f, 0.6f); world.shake(0.5f); vibrate(30, 1); say("[BOING]"); }
        if ((ev & Sim.EV_GRAB) != 0) { g.audio.play("grab", 0.8f, 1f); vibrate(22, 1); say("[GRAB]"); }
        if ((ev & Sim.EV_PULL) != 0) { g.audio.play("pull", 0.7f, 1f); }
        if ((ev & Sim.EV_CRUMBLE) != 0) { g.audio.play("crumble", 0.7f, 1f); world.particles.burst(s, y - 0.2f, 12, brown, 2.2f, 1f, 0.16f, 12f, 0.8f); say("[CRUMBLE]"); }
        if ((ev & Sim.EV_CHECKPOINT) != 0) {
            g.audio.play("checkpoint", 0.8f, 1f); world.particles.burst(s, y + 0.8f, 14, gold, 2.5f, 3.5f, 0.12f, -1f, 1.1f);
            toast = "CHECKPOINT"; toastT = 2f; g.save.checkpoint = sim.checkpoint; g.persist(); say("[CHECKPOINT]");
        }
        if ((ev & Sim.EV_RESPAWN) != 0) {
            world.particles.burst(sim.s, sim.y + 0.6f, 18, cyan, 3f, 3.2f, 0.1f, 2f, 0.7f);
            g.audio.play("respawn", 0.8f, 1f); fade = 1f; g.save.falls++; vibrate(40, 1); world.shake(0.5f);
            toast = "BACK TO CHECKPOINT"; toastT = 1.6f; g.persist();
        }
        if ((ev & Sim.EV_WIN) != 0) { g.audio.play("win", 1f, 1f); state = State.WON; g.save.completions++; if (g.save.bestTime <= 0 || runTime < g.save.bestTime) g.save.bestTime = runTime; world.particles.burst(s, y + 1f, 24, gold, 4f, 5f, 0.16f, 3f, 1.6f); g.persist(); }
    }

    private static final String[][] TIPS = {
        {"jump", "HOLD JUMP FOR A HIGHER LEAP. THE STICK STEERS IN THE AIR."},
        {"ledge", "SHORT BY A HAIR? PUSH TOWARD THE LEDGE TO GRAB, THEN STICK UP OR JUMP TO PULL UP."},
        {"pad", "LAND ON A BOUNCE PAD TO LAUNCH. HOLD JUMP FOR EXTRA HEIGHT."},
        {"rope", "GRAB A ROPE BY TOUCHING IT. STICK UP/DOWN CLIMBS. JUMP TO LEAP OFF."},
        {"crumble", "CRUMBLING TILES DROP FAST. KEEP MOVING."},
        {"move", "RIDE MOVING PLATFORMS, OR WAIT FOR THE RIGHT MOMENT TO JUMP."},
        {"cable", "JUMP UP TO CABLES AND HANG. STICK LEFT/RIGHT SHIMMIES. DOWN OR JUMP DROPS."},
        {"swing", "TIME YOUR LEAP ONTO THE SWINGING PLATFORM."},
        {"checkpoint", "FLAGS ARE CHECKPOINTS. FALL FAR AND YOU RESTART FROM THE LAST ONE."},
    };

    private void updateTips(float dt) {
        if (toastT > 0) toastT -= dt;
        if (captionT > 0) captionT -= dt;
        if (tipT > 0) { tipT -= dt; if (tipT <= 0) tip = ""; return; }
        if (!g.settings.tips || demo) return;
        // look for the next element of an unseen kind within 8 units ahead
        for (int i = Math.max(0, sim.bestElem - 1); i < Math.min(course.routeSize(), sim.bestElem + 5); i++) {
            Element e = course.get(i);
            String key = null;
            switch (e.type) {
                case PAD: key = "pad"; break; case ROPE: key = "rope"; break; case CRUMBLE: key = "crumble"; break;
                case MOVE_H: case MOVE_V: key = "move"; break; case CABLE: key = "cable"; break; case SWING: key = "swing"; break;
                default: if (e.checkpoint && i > 0) key = "checkpoint";
            }
            if (key == null && i == 1) key = "jump";
            if (key == null) continue;
            if (Math.abs(course.dsWrap(e.s, sim.s)) > 9f) continue;
            if (g.save.shownTips.contains(key)) continue;
            g.save.shownTips.add(key);
            for (String[] t : TIPS) if (t[0].equals(key)) { tip = t[1]; tipT = 5f; }
            return;
        }
    }

    // ------------------------------------------------------------------ HUD & overlays

    private void drawHud() {
        Ui ui = g.ui;
        ui.begin();
        float W = ui.w(), H = ui.h(), m = 28f;
        float tm = ui.tm();
        // progress meter (top-left): height and zone
        int zone = Math.min(3, (int) (sim.y / g.tuning.courseHeight * 4f));
        String hs = (int) Math.max(0, sim.y) + " / " + (int) g.tuning.courseHeight + " M";
        ui.rect(m - 6, H - m - 62 * Math.min(tm, 1.3f) - 18, 360 * Math.min(tm, 1.3f) + 12, 62 * Math.min(tm, 1.3f) + 18 + 6, new Color(0.05f, 0.07f, 0.14f, 0.55f));
        ui.text(hs, m, H - m - 30 * Math.min(tm, 1.3f), 4f * Math.min(tm, 1.3f), Ui.TEXT);
        float barW = 340 * Math.min(tm, 1.3f), barY = H - m - 56 * Math.min(tm, 1.3f) - 6;
        ui.rect(m, barY, barW, 12, new Color(0.2f, 0.22f, 0.32f, 1f));
        for (int z = 0; z < 4; z++) { Color c = Palette.SKY_BOT[z]; ui.rect(m + z * barW / 4f + 1, barY + 1, barW / 4f - 2, 10, new Color(c.r, c.g, c.b, 0.9f)); }
        float prog = MathUtils.clamp(sim.y / g.tuning.courseHeight, 0, 1);
        ui.rect(m + prog * barW - 4, barY - 6, 8, 24, Ui.ACCENT);
        // pause button
        float pb = 96f; float px = g.settings.leftHanded ? m : W - m - pb, py = H - m - pb;
        ui.rect(px - 3, py - 3, pb + 6, pb + 6, Ui.EDGE); ui.rect(px, py, pb, pb, Ui.PANEL);
        ui.rect(px + 28, py + 24, 14, 48, Ui.TEXT); ui.rect(px + 54, py + 24, 14, 48, Ui.TEXT);
        if (state == State.PLAYING && ui.tappedIn(px - 10, py - 10, pb + 20, pb + 20)) pauseGame();
        // zone banner
        if (zone != lastZone) { lastZone = zone; zoneT = 3f; }
        if (zoneT > 0) {
            zoneT -= Gdx.graphics.getDeltaTime();
            float a = Math.min(1f, zoneT);
            ui.textC(Palette.NAMES[zone], W / 2, H - 150, 7f, new Color(1f, 0.95f, 0.8f, a));
        }
        if (toastT > 0) ui.textC(toast, W / 2, H * 0.62f, 6f, new Color(1f, 0.9f, 0.4f, Math.min(1f, toastT)));
        if (captionT > 0 && g.settings.captions) { float cw = ui.font.width(caption, 4f * tm); ui.rect(W / 2 - cw / 2 - 14, 28, cw + 28, 44 * tm, new Color(0, 0, 0, 0.6f)); ui.text(caption, W / 2 - cw / 2, 40, 4f * tm, Ui.TEXT); }
        if (!tip.isEmpty() && state == State.PLAYING) {
            float px2 = 3.6f * tm; float maxW = W * 0.62f;
            String[] lines = wrap(tip, px2, maxW);
            float bh = lines.length * (PixelFont_H * px2 + 10) + 24;
            float by = H * 0.45f;
            ui.panel(W / 2 - maxW / 2 - 20, by, maxW + 40, bh);
            for (int i = 0; i < lines.length; i++) ui.textC(lines[i], W / 2, by + bh - 24 - (i + 1) * (PixelFont_H * px2 + 10) + 10, px2, Ui.TEXT);
        }
        drawHeroBubble();
        drawPops();
        if (state == State.PLAYING) drawControls();
        if (fade > 0) ui.rect(0, 0, W, H, new Color(0, 0, 0, fade));
        if (state == State.PAUSED) pauseMenu();
        if (state == State.WON) winMenu();
        ui.end();
    }
    private static final float PixelFont_H = 7f;
    private final float[] headPos = new float[2];

    private void drawPops() {
        if (pops.isEmpty() || state != State.PLAYING) return;
        world.heroHeadScreen(g.ui.w(), g.ui.h(), headPos);
        for (Pop p : pops) {
            float k = p.age / 1.1f, grow = 1f + Math.max(0f, 1f - p.age * 7f) * 0.7f;
            float a = Math.min(1f, (1f - k) * 3f);
            g.ui.textC(p.t, headPos[0], headPos[1] + 70f + p.age * 90f, 5.5f * grow, new Color(p.c.r, p.c.g, p.c.b, a));
        }
    }

    private void drawHeroBubble() {
        String b = world.heroBubble();
        if (b == null || state != State.PLAYING) return;
        world.heroHeadScreen(g.ui.w(), g.ui.h(), headPos);
        g.ui.bubble(b, headPos[0], headPos[1] + 6, world.heroBubbleAlpha());
    }

    private String[] wrap(String s, float px, float maxW) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String word : s.split(" ")) {
            String t = cur.length() == 0 ? word : cur + " " + word;
            if (g.ui.font.width(t, px) > maxW && cur.length() > 0) { out.add(cur.toString()); cur = new StringBuilder(word); } else { cur = new StringBuilder(t); }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out.toArray(new String[0]);
    }

    private void drawControls() {
        Ui ui = g.ui;
        ui.batch.end();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        shapes.setProjectionMatrix(ui.viewport.getCamera().combined);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        float W = ui.w();
        boolean hc = g.settings.highContrast; float ab = hc ? 0.55f : 0.28f;
        // stick
        float bx, by;
        if (stickPtr >= 0) { bx = stickBase.x; by = stickBase.y; } else { bx = g.settings.leftHanded ? W - 260 : 260; by = 190; }
        shapes.setColor(1f, 1f, 1f, ab * 0.6f); shapes.circle(bx, by, STICK_R + 8, 40);
        shapes.setColor(0.1f, 0.12f, 0.2f, 0.45f); shapes.circle(bx, by, STICK_R, 40);
        float kx2 = stickPtr >= 0 ? stickKnob.x : bx, ky2 = stickPtr >= 0 ? stickKnob.y : by;
        shapes.setColor(1f, 0.85f, 0.4f, stickPtr >= 0 ? 0.9f : ab); shapes.circle(kx2, ky2, 52, 30);
        // jump button
        float jx = g.settings.leftHanded ? 230 : W - 230, jy = 190;
        boolean pressed = jumpPtr >= 0 || kJumpHeld;
        shapes.setColor(1f, 1f, 1f, ab * 0.7f); shapes.circle(jx, jy, 112, 40);
        shapes.setColor(pressed ? 1f : 0.95f, pressed ? 0.75f : 0.5f, 0.12f, pressed ? 0.95f : (hc ? 0.85f : 0.55f)); shapes.circle(jx, jy, 104, 40);
        shapes.setColor(1f, 1f, 1f, 0.9f);
        shapes.triangle(jx - 46, jy - 18, jx + 46, jy - 18, jx, jy + 40);   // jump arrow
        shapes.end();
        ui.batch.begin();
        ui.textC("JUMP", jx, jy - 78, 3.2f, new Color(1, 1, 1, hc ? 1f : 0.8f));
    }

    private void pauseMenu() {
        Ui ui = g.ui; float W = ui.w(), H = ui.h();
        ui.rect(0, 0, W, H, new Color(0, 0, 0, 0.55f));
        float pw = 560, ph = 560, x = W / 2 - pw / 2, y = H / 2 - ph / 2;
        ui.panel(x, y, pw, ph);
        ui.textC("PAUSED", W / 2, y + ph - 90, 8f, Ui.TEXT);
        float bw = 440, bh = 84, bx = W / 2 - bw / 2;
        if (ui.button("RESUME", bx, y + ph - 200, bw, bh, true)) resumePlay();
        if (ui.button(confirmRestart ? "TAP AGAIN TO CONFIRM" : "RETRY CHECKPOINT", bx, y + ph - 304, bw, bh)) {
            if (confirmRestart) { sim.respawn(); sim.consumeEvents(); world.snapCamera(sim); confirmRestart = false; resumePlay(); } else confirmRestart = true;
        }
        if (ui.button("SETTINGS", bx, y + ph - 408, bw, bh)) { g.audio.play("click"); g.persist(); next = new SettingsScreen(g, this); disposeOnLeave = false; }
        if (ui.button("MAIN MENU", bx, y + ph - 512, bw, bh)) { g.audio.play("click"); g.persist(); next = new TitleScreen(g); disposeOnLeave = true; }
    }

    private void winMenu() {
        Ui ui = g.ui; float W = ui.w(), H = ui.h();
        ui.rect(0, 0, W, H, new Color(0, 0, 0, 0.5f));
        float pw = 860, ph = 560, x = W / 2 - pw / 2, y = H / 2 - ph / 2;
        ui.panel(x, y, pw, ph);
        ui.textC("SUMMIT REACHED!", W / 2, y + ph - 90, 8f, Ui.GOOD);
        int mins = (int) (runTime / 60), secs = (int) (runTime % 60);
        ui.textC("CLIMB TIME " + mins + ":" + (secs < 10 ? "0" : "") + secs, W / 2, y + ph - 170, 4.5f, Ui.TEXT);
        ui.textC("FALLS BACK TO CHECKPOINT " + sim.falls, W / 2, y + ph - 225, 4f, Ui.DIM);
        float bw = 520, bh = 84, bx = W / 2 - bw / 2;
        if (ui.button("CLIMB A NEW TOWER", bx, y + 190, bw, bh, true)) {
            g.audio.play("click"); g.save.courseIndex++; g.save.checkpoint = 0; g.persist(); next = new PlayScreen(g, false); disposeOnLeave = true;
        }
        if (ui.button("MAIN MENU", bx, y + 70, bw, bh)) { g.audio.play("click"); g.save.courseIndex++; g.save.checkpoint = 0; g.persist(); next = new TitleScreen(g); disposeOnLeave = true; }
    }

    // ------------------------------------------------------------------ lifecycle

    private void pauseGame() { if (state == State.PLAYING) { state = State.PAUSED; confirmRestart = false; stickPtr = jumpPtr = -1; jumpHeldTouch = false; g.audio.play("click"); g.persist(); } }
    private void resumePlay() { state = State.PLAYING; acc = 0; g.audio.play("click"); }
    public void resumeFromSettings() { applySettings(); }

    @Override public void hide() { g.persist(); }
    @Override public void pause() { /* app backgrounded */ if (state == State.PLAYING) { state = State.PAUSED; confirmRestart = false; stickPtr = jumpPtr = -1; jumpHeldTouch = false; } g.persist(); g.audio.pauseMusic(); }
    @Override public void resume() { g.audio.resumeMusic(); }

    @Override public void dispose() { if (world != null) { world.dispose(); world = null; } shapes.dispose(); }

}

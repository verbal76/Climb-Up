package com.hotatticgames.climbup;

import com.hotatticgames.climbup.audio.Audio;
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
    private enum State { PLAYING, PAUSED, FINISHED }
    private boolean finishNewBest;

    private final ClimbGame g;
    private final boolean demo;
    private Course course;
    private Tower tower;                 // null only in the scripted demo, which plays one fixed slice
    private int simSize, seenRebuilds;              // size of the world the sim window was last set for
    private double maxAbs;            // highest absolute height reached (the sim only knows heights relative to the floating origin)
    private double absNow() { return tower != null ? tower.absY(sim.y) : sim.y; }
    private Sim sim;
    private WorldRenderer world;
    private final InputState in = new InputState();
    private final ShapeRenderer shapes = new ShapeRenderer();
    private final Autopilot.Driver[] driver = new Autopilot.Driver[1];
    private State state = State.PLAYING;
    private boolean clockLive;           // the speed-run clock starts on the first input of a session
    private String toastSub = "";
    private float acc, time, runTime, fade, toastT, zoneT, stepT, ropeT, autosaveT, tipT, shotT;
    private String toast = "", tip = "", caption = ""; private float captionT;
    private int lastZone = -1, shots;
    private boolean confirmRestart, confirmEnd;
    private static final class Pop { final String t; final Color c; float age; Pop(String t, Color c) { this.t = t; this.c = c; } }
    private final java.util.ArrayList<Pop> pops = new java.util.ArrayList<>();
    private float hitstop, confettiT, gruntT; private int milestone, gruntN;
    private static final String[] STRAIN = {"UNGH!", "HNNG!", "AGH!", "NOPE NOPE NOPE!", "HOLD ON!", "NOT TODAY!"};
    private static final String[] CATCH = {"NICE CATCH!", "FINGERTIPS!", "CLUTCH!", "HANG ON!"}, CLOSE = {"JUST MADE IT!", "CLOSE ONE!", "WHEW!", "THAT WAS TIGHT!"};

    private static final String[] KEY_NAMES = {"RED", "BLUE", "GREEN", "GOLD"};
    private static final float[][] KEY_RGB = {{0.95f, 0.22f, 0.20f}, {0.25f, 0.55f, 1f}, {0.30f, 0.85f, 0.35f}, {1f, 0.82f, 0.18f}};
    private float lockedT;

    private void pop(String t, Color c) { if (pops.size() > 3) pops.remove(0); pops.add(new Pop(t, c)); }
    private void freeze(float s) { if (!g.settings.reducedMotion) hitstop = Math.max(hitstop, s); }
    private com.badlogic.gdx.Screen next; private boolean disposeOnLeave;   // applied at the end of render(), after the batch is closed

    // ---- touch state
    private int stickPtr = -1, jumpPtr = -1, swingPtr = -1;
    private boolean swingLatch, kSwing;
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
            g.audio.playlist(Audio.GAME_TRACKS);
            return;
        }
        if (demo) {                                       // scripted attract/screenshot run: one fixed, validated slice
            course = CourseGenerator.chunk(Long.getLong("climb.seed", 11L), Integer.getInteger("climb.slice", 4), null, g.tuning);
            sim = Sim.startOn(course, g.tuning, 0);
        } else {
            ClimbGame.Run run = g.openRun(g.runFresh); g.runFresh = false;
            tower = run.tower; course = tower.world;
            int hStart = Integer.getInteger("climb.startHeight", 0);              // test hook: begin partway up
            while (hStart > 0 && tower.topAbsY() < hStart + 80f) tower.extend();
            String hzType = System.getProperty("climb.hazard");              // test hook: start beside the first hazard of this type (e.g. CANNON)
            if (hzType != null) {
                for (int tries = 0; tries < 40; tries++) {
                    for (int q = 3; q < course.size(); q++) { Element e = course.get(q); if (e.anchor < 0 && ((e.type.name().equals(hzType) && (e.type != Element.Type.RAMP || Integer.getInteger("climb.rampSkin", -1) < 0 || e.skin == Integer.getInteger("climb.rampSkin", -1))) || (hzType.equals("BRIDGE") && e.skin == 1 && e.type == Element.Type.STATIC) || (hzType.equals("CHECKPOINT") && e.checkpoint && q > 2))) { run.startIdx = Math.max(0, q - Integer.getInteger("climb.hazardBack", 1)); break; } }
                    for (Element h : course.hazards) if (run.startIdx == 0 && h.type.name().equals(hzType) && h.anchor > 2 && course.get(h.anchor).anchor < 0) { run.startIdx = Math.max(0, h.anchor - Integer.getInteger("climb.hazardBack", 1)); break; }
                    if (run.startIdx > 0) break;
                    tower.extend();
                }
            }
            boolean fromSnapshot = false;
            if (run.resumed && run.snapshot != null) {            // CONTINUE after SAVE & EXIT: the climb exactly as it was left
                Sim ss = new Sim(course, g.tuning);
                ss.keysFree = false; ss.deferRespawn = true; ss.floorOverride = tower.floorLocal(); ss.setRange(0, course.size() - 1);
                tower.openedUpTo = g.save.openedUpTo;
                ResumeState.restore(g.save, ss, tower);
                if (run.snapshot.apply(ss, tower)) { sim = ss; fromSnapshot = true; }
                else { g.store.deleteRun(); run = g.openRun(false); tower = run.tower; course = tower.world; }       // does not fit the world on disk: resume from the checkpoint
            }
            if (!fromSnapshot) {
                sim = Sim.startOn(course, g.tuning, run.startIdx);
                sim.checkpoint = run.startIdx; sim.keysFree = false; sim.keys = 0;
                if (run.resumed) { sim.deferRespawn = true; sim.setRange(0, course.size() - 1); ResumeState.restore(g.save, sim, tower); }
            }
            if (hStart > 0) { int i = 0; while (i < course.size() - 1 && course.get(i + 1).y < hStart) i++; while (i > 0 && course.get(i).anchor >= 0) i--; sim = Sim.startOn(course, g.tuning, i); sim.checkpoint = i; sim.keysFree = false; sim.keys = 0; }
            if (tower != null) { tower.openedUpTo = g.save.openedUpTo; sim.deferRespawn = true; sim.floorOverride = tower.floorLocal(); }
            sim.setRange(0, course.size() - 1); simSize = course.size(); if (tower != null) seenRebuilds = tower.rebuilds;
        }
        world = new WorldRenderer(g.tuning, course, g.models, g.settings.quality, g.settings.character);
        if (tower != null) world.setOrigin(tower.originY, tower.originS);
        applySettings();
        world.snapCamera(sim);
        if (demo) driver[0] = new Autopilot.Driver(sim);
        InputMultiplexer mux = new InputMultiplexer(g.ui, new Touch());
        Gdx.input.setInputProcessor(mux);
        Gdx.input.setCatchKey(Input.Keys.BACK, true);
        g.audio.playlist(Audio.GAME_TRACKS);
        runTime = 0; maxAbs = absNow(); milestone = (int) (maxAbs / 50f);
        if (!demo && tower != null) g.save.records().catchUp(g.save.seed, g.save.splits, g.save.towers, g.save.finished, g.save.finishTime);          // times table: a climb begun before the records existed
        if (Boolean.getBoolean("climb.finishDemo")) { g.save.runClock = 3723.4f; g.save.finished = true; g.save.finishTime = 3723.4f; finishNewBest = true; state = State.FINISHED; if (tower != null) g.save.records().finish(g.save.seed, 3723.4f); }       // test hook: shows the finish screen
        if (Boolean.getBoolean("climb.ropeScript")) {   // test hook: start low on the first rope; readInput() then climbs it, mounts the beam and jumps (screenshots of the rope top)
            for (int e = 0; e < course.size(); e++) if (course.get(e).type == Element.Type.ROPE && course.get(e).y > 5f) {
                Element el = course.get(e); sim.mode = Sim.Mode.ROPE; sim.onElem = e; sim.s = sim.es1[e]; sim.y = el.y - el.len + 0.6f; sim.vx = sim.vy = 0; world.snapCamera(sim); break;
            }
        }
        if (Boolean.getBoolean("climb.hang")) {   // test hook: hang from the left edge of the first platform ahead
            int e = 1; Element el = course.get(e);
            sim.mode = Sim.Mode.LEDGE; sim.onElem = e; sim.ledgeSide = 1; sim.facing = 1;
            sim.s = course.wrap(sim.es1[e] - (el.halfW() + 0.24f)); sim.y = sim.ey1[e] - g.tuning.handHeight + 0.08f;
            world.snapCamera(sim);
        }
    }

    private void applySettings() {
        world.reducedMotion = g.settings.reducedMotion;
        world.quality = g.settings.quality;
        world.setCharacter(g.settings.character);
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
            if (sim.clubTime > 0f) {                                             // the swing button sits above the jump button while you carry the club
                float sbx = g.settings.leftHanded ? 230 : g.ui.w() - 230, sby = 190 + 230;
                if (Math.hypot(u.x - sbx, u.y - sby) < 105) { swingPtr = p; swingLatch = true; return false; }
            }
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
            if (p == swingPtr) swingPtr = -1;
            return false;
        }
        @Override public boolean keyDown(int k) {
            if (k == Input.Keys.SPACE || k == Input.Keys.Z || k == Input.Keys.K || k == Input.Keys.UP && false) { kJump = true; kJumpHeld = true; }
            if (k == Input.Keys.X || k == Input.Keys.J) kSwing = true;
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
        in.swingPressed = swingLatch || kSwing;
        if (Boolean.getBoolean("climb.ropeScript")) {
            scriptT += Sim.DT;
            in.moveY = sim.mode == Sim.Mode.ROPE || sim.mounting() ? 1f : 0f; in.moveX = 0f;
            if (sim.mode == Sim.Mode.BEAM && !sim.mounting()) { in.moveX = scriptT % 3f < 1.2f ? 1f : 0f; in.jumpPressed = scriptT > Float.parseFloat(System.getProperty("climb.ropeJumpAt", "99")); }
        }
    }
    private float scriptT;

    // ------------------------------------------------------------------ frame

    @Override public void render(float dt) {
        dt = Math.min(dt, 0.1f);
        time += dt;
        if (OVERLAY != null && time > 0.8f && state == State.PLAYING) { if (OVERLAY.equals("pause")) pauseGame(); }
        boolean play = state == State.PLAYING;
        if (play) {
            float speed = g.settings.assistSlow ? g.tuning.assistSlowFactor : 1f;
            if (hitstop > 0) hitstop -= dt; else acc += dt * speed;
            if (tower != null) {
                try { tower.openedUpTo = g.save.openedUpTo; tower.ensureAbove(maxAbs, 75f); tower.maintain(sim); }
                catch (RuntimeException ex) {          // the next stretch of tower could not be built (never expected): keep the run as a stamped record and go back to the title instead of crashing
                    Gdx.app.error("climb", "tower generation failed", ex);
                    if (g.save.seed != 0) Legacy.archive(g.save, Legacy.today());
                    g.history.delete(); g.persist(); next = new TitleScreen(g); disposeOnLeave = true;
                }
                if (tower.rebuilds != seenRebuilds) { seenRebuilds = tower.rebuilds; world.rebased(tower.lastRemap, tower.originY, tower.originS); simSize = course.size(); }
                if (course.size() != simSize) { sim.setRange(0, course.size() - 1); simSize = course.size(); }
                g.syncHistory(tower);
            }
            int steps = 0;
            while (acc >= Sim.DT && steps < 6) {
                if (demo) { driver[0].drive(sim, in); } else readInput();
                sim.step(in);
                in.jumpPressed = false; jumpLatch = false; kJump = false; in.swingPressed = false; swingLatch = false; kSwing = false;
                runTime += Sim.DT;
                if (runTime > 15f && g.ota != null) g.ota.confirm();          // live play with the current (possibly OTA) content: a freshly applied update is now trusted
                if (!demo) { if (!clockLive && (in.moveX != 0f || in.jumpPressed || in.moveY != 0f)) clockLive = true; RunRecord.tick(g.save, Sim.DT, clockLive); if (absNow() > maxAbs) maxAbs = absNow(); if (maxAbs > g.save.climbHeight) g.save.climbHeight = (float) maxAbs; ResumeState.capture(g.save, sim); }
                handleEvents(sim.consumeEvents());
                acc -= Sim.DT; steps++;
                if (state != State.PLAYING) break;
            }
            if (steps == 6) acc = 0;
            if (world.takeWhoosh()) g.audio.play("whoosh", 0.6f, 1f);
            g.save.playSeconds += dt;
            if (maxAbs > g.save.bestHeight) g.save.bestHeight = (float) maxAbs;
            if (g.save.finished && tower != null && !demo) g.save.records().infinity(g.save.seed, infinityMetres());
            int ms = (int) (maxAbs / 50f);
            if (ms > milestone) { milestone = ms; toast = ms * 50 + " M!"; toastT = 1.6f; g.audio.play("checkpoint", 0.55f, 1.35f); world.particles.burst(sim.s, sim.y + 1f, 12, gold, 2.6f, 3.2f, 0.1f, -0.5f, 1f); }
            for (int pi = pops.size() - 1; pi >= 0; pi--) { Pop pp = pops.get(pi); pp.age += dt; if (pp.age > 1.1f) pops.remove(pi); }
            autosaveT += dt; if (autosaveT > 8f) { autosaveT = 0; g.persist(); saveRun(); }
            ambientSounds(dt);
            juice(dt);
            updateTips(dt);
        }
        if (fade > 0) fade = Math.max(0, fade - dt * 2.2f);
        world.particles.update(play ? dt : 0f);
        world.render(sim, play ? acc / Sim.DT : 1f, dt, time, true);
        if (world.cloudsBroken > 0) { g.audio.play("poof", 0.45f, 0.9f + MathUtils.random(0.3f)); world.cloudsBroken = 0; }
        drawHud();
        g.autoShot("play", dt);
        if (next != null) { com.badlogic.gdx.Screen n = next; next = null; boolean d = disposeOnLeave; g.setScreen(n); if (d) dispose(); }
    }

    private final java.util.HashMap<Element, Integer> cycleSeen = new java.util.HashMap<>();
    private float sawT;

    /** Hazards you can hear: cannon booms, spikes snapping up, the saw's whir. Volume falls off with distance. */
    private void hazardSounds(float dt) {
        sawT -= dt;
        for (int k = 0, cnt = sim.hz == null ? course.hazards.size() : sim.hz.length; k < cnt; k++) {
            Element h = course.hazards.get(sim.hz == null ? k : sim.hz[k]);
            float d = Math.abs(course.dsWrap(h.sAt(sim.time), sim.s)) + Math.abs(h.y - sim.y) * 0.6f;
            if (d > 14f) continue;
            float vol = Math.max(0f, 1f - d / 14f);
            switch (h.type) {
                case CANNON: case SPIKE_TRAP: case MORTAR: {
                    int cyc = (int) Math.floor(h.type == Element.Type.CANNON || h.type == Element.Type.MORTAR ? sim.time / h.period + h.phase / 6.2832f : (sim.time / h.period + h.phase / 6.2832f - 0.55f));
                    Integer prev = cycleSeen.put(h, cyc);
                    if (prev != null && prev != cyc) {
                        if (h.type == Element.Type.MORTAR) { g.audio.play("cannon", 0.7f * vol + 0.05f, 1.25f); world.particles.burst(h.s, h.y + 0.5f, 6, dust, 1.2f, 1.6f, 0.12f, 0f, 0.4f); }
                        else if (h.type == Element.Type.CANNON) { g.audio.play("cannon", 0.9f * vol + 0.1f, 1f); world.particles.burst(h.s + h.dir * 0.8f, h.y, 6, dust, 1.5f, 0.6f, 0.12f, 0f, 0.4f); world.shake(0.15f * vol); say("[BOOM]"); }
                        else g.audio.play("spikes", 0.7f * vol + 0.05f, 1f);
                    }
                    break;
                }
                case BEE:
                    if (h.beePresent(sim.time) && sawT <= 0f && d < 11f) { sawT = 0.3f; g.audio.play("buzz", 0.4f * vol, 0.9f + MathUtils.random(0.2f)); }
                    break;
                case SAW_H: case SAW_V:
                    if (sawT <= 0f && d < 9f) { sawT = 0.45f; g.audio.play("saw", 0.35f * vol, 0.95f + MathUtils.random(0.1f)); }
                    break;
                default: break;
            }
        }
        if (cycleSeen.size() > 64) cycleSeen.clear();
    }

    private void ambientSounds(float dt) {
        hazardSounds(dt);
        if (sim.mode == Sim.Mode.LEDGE) {   // he is really trying not to fall
            gruntT -= dt;
            if (gruntT <= 0) {
                gruntT = 0.55f + MathUtils.random(0.4f); gruntN++;
                g.audio.play("grunt" + (1 + MathUtils.random(2)), 0.9f, (0.93f + MathUtils.random(0.16f)) * com.hotatticgames.climbup.render.Characters.voice(g.settings.character));
                if (gruntN % 2 == 0) pop(STRAIN[MathUtils.random(STRAIN.length - 1)], Color.WHITE);
                say("[STRAINING]"); vibrate(8, 2);
            }
        } else gruntT = 0.35f;
        if (sim.mode == Sim.Mode.GROUND && Math.abs(sim.vx) > 2f) { stepT -= dt; if (stepT <= 0) { stepT = 0.27f; g.audio.play("step", 0.35f, 0.9f + MathUtils.random(0.2f)); world.particles.burst(sim.s - sim.facing * 0.3f, sim.y + 0.05f, 2, dust, 0.5f, 0.7f, 0.09f, 3f, 0.35f); } }
        if (sim.mode == Sim.Mode.ROPE && Math.abs(in.moveY) > 0.3f) { ropeT -= dt; if (ropeT <= 0) { ropeT = 0.32f; g.audio.play("rope", 0.4f, 0.9f + MathUtils.random(0.2f)); } }
    }

    private void vibrate(int ms, int minLevel) {
        if (g.settings.haptics >= minLevel && g.settings.haptics > 0) Gdx.input.vibrate(g.settings.haptics == 1 ? Math.max(5, ms / 2) : ms);
    }

    private void say(String s) { if (g.settings.captions) { caption = s; captionT = 1.4f; } }

    private final Color red = new Color(1f, 0.3f, 0.25f, 1f), dust = new Color(0.9f, 0.88f, 0.8f, 1f), gold = new Color(1f, 0.85f, 0.25f, 1f), brown = new Color(0.55f, 0.4f, 0.28f, 1f), cyan = new Color(0.4f, 0.9f, 1f, 1f);

    /** A castle was opened: record the split (time since the previous unlock) and the total, keep the personal bests, start timing the next tower. */
    private void towerUnlocked() {
        SaveData sd = g.save;
        if (sd.finished) { toast = KEY_NAMES[sim.lastGateColor] + " CASTLE " + sim.lastGateNo + " OPENED!"; toastT = 3f; g.persist(); return; }      // after the official finish nothing more is timed or recorded
        float split = sd.runClock - sd.towerStartClock, total = sd.runClock;
        int n = sd.towers;
        sd.splits = java.util.Arrays.copyOf(sd.splits, n + 1); sd.splits[n] = split;
        sd.towerTotals = java.util.Arrays.copyOf(sd.towerTotals, n + 1); sd.towerTotals[n] = total;
        sd.towers = n + 1;
        if (n < RecordsData.LEGS) sd.records().legDone(sd.seed, n, split);          // times table: this leg, and whether it is a personal best
        boolean hadSplit = sd.bestSplit > 0f, pbSplit = !hadSplit || split < sd.bestSplit;
        if (pbSplit) sd.bestSplit = split;
        if (sd.bestTotals.length <= n) sd.bestTotals = java.util.Arrays.copyOf(sd.bestTotals, n + 1);
        boolean hadTotal = sd.bestTotals[n] > 0f, pbTotal = !hadTotal || total < sd.bestTotals[n];
        if (pbTotal) sd.bestTotals[n] = total;
        sd.towerStartClock = sd.runClock; sd.towerStartHeight = (float) Math.max(0.0, maxAbs);
        toast = KEY_NAMES[sim.lastGateColor] + " CASTLE OPENED!"; toastT = 3.2f;
        toastSub = "TOWER " + (n + 1) + "  " + fmtTime(split) + (pbSplit && hadSplit ? "  FASTEST TOWER!" : "") + "    TOTAL " + fmtTime(total) + (pbTotal && hadTotal ? "  PACE PB!" : "");
        g.persist();
    }

    /** Metres climbed beyond the finish castle (what the HUD shows as INFINITY +m), counting the highest point reached in this climb even across a resume. */
    private float infinityMetres() {
        return (float) Math.max(0.0, Math.max(maxAbs, g.save.climbHeight) - g.tuning.finishCastle * g.tuning.castleSpacing);
    }

    /** END RUN AND SAVE TIME: the times already stand in the records; the climb is forgotten and the end-of-run summary comes up. */
    private void endRunToSummary() {
        g.audio.play("click");
        RecordsData.Run r = tower != null && !demo ? g.save.records().endRun(g.save.seed) : null;
        g.forgetRun(); g.persist();
        next = new RunSummaryScreen(g, r); disposeOnLeave = true;
    }

    /** m:ss.t (h:mm:ss.t from an hour). */
    public static String fmtTime(float sec) {
        int t = Math.max(0, (int) (sec * 10f)), tenth = t % 10, s = (t / 10) % 60, m = (t / 600) % 60, h = t / 36000;
        return h > 0 ? String.format("%d:%02d:%02d.%d", h, m, s, tenth) : String.format("%d:%02d.%d", m, s, tenth);
    }

    private boolean fallCaptioned;
    private float windT, trailT, creakT, fallT2;

    /** Cosmetic feedback that runs every frame: wind streaks in a fast fall, a spark trail after a bounce, wood creaks on a tipping bridge. */
    private void juice(float dt) {
        if (sim == null) return;
        // the falling sound: starts when a real fall has lasted a moment (not on every hop), stops the instant it ends
        if (sim.mode == Sim.Mode.AIR && sim.vy < -9f) { fallT2 += dt; if (fallT2 > 0.22f) { g.audio.fallStart(0.85f); if (!fallCaptioned) { fallCaptioned = true; say("[FALLING]"); } } }
        else { fallT2 = 0f; fallCaptioned = false; if (g.audio.isFalling()) g.audio.fallStop(); }
        if (g.settings.reducedMotion) return;
        if (sim.mode == Sim.Mode.AIR && sim.vy < -11f) {
            windT -= dt;
            if (windT <= 0) { windT = 0.03f; world.particles.spawn(sim.s + MathUtils.random(-0.8f, 0.8f), sim.y + MathUtils.random(0f, 1.6f), 0f, 9f + MathUtils.random(4f), Color.WHITE, 0.045f, 0f, 0.3f); }
        }
        if (sim.mode == Sim.Mode.AIR && sim.vy > 8f && sim.lastPad >= 0 && sim.lastPad < course.size() && course.get(sim.lastPad).type == Element.Type.PAD) {
            trailT -= dt;
            if (trailT <= 0) { trailT = 0.04f; world.particles.spawn(sim.s + MathUtils.random(-0.2f, 0.2f), sim.y + 0.3f, MathUtils.random(-0.3f, 0.3f), -1.5f, cyan, 0.07f, 0f, 0.5f); }
        }
        if (sim.mode == Sim.Mode.GROUND && sim.onElem >= 0 && course.get(sim.onElem).type == Element.Type.SEESAW && Math.abs(sim.tilt[sim.onElem]) > 0.25f) {
            creakT -= dt;
            if (creakT <= 0) { creakT = 0.4f; g.audio.play("rope", 0.35f, 0.55f + MathUtils.random(0.15f)); world.particles.burst(sim.s, sim.y, 2, brown, 0.8f, 0.6f, 0.07f, 4f, 0.4f); }
        }
    }

    private void handleEvents(int ev) {
        if (ev == 0) return;
        float s = sim.s, y = sim.y;
        world.heroEvents(ev, sim.landSpeed);
        if ((ev & Sim.EV_LAND) != 0 && sim.onElem >= 0) {
            world.platformLanded(sim.onElem, sim.landSpeed);
            Element le = course.get(sim.onElem);
            float edge = Math.abs(course.dsWrap(sim.s, sim.es1[sim.onElem])) - le.halfW();
            if (le.type == Element.Type.SEESAW) { g.audio.play("bonk", 0.7f, 0.65f); world.particles.burst(sim.s, sim.y, 6, brown, 1.6f, 1.2f, 0.12f, 6f, 0.5f); }
            if (le.type == Element.Type.RAMP && le.skin == 3) { g.audio.play("steam", 0.5f, 1.0f); world.particles.burst(sim.s, sim.y - 0.4f, 8, cyan, 1.4f, 0.8f, 0.1f, 1f, 0.6f); }
            if (le.type == Element.Type.RAMP && le.skin == 2) { g.audio.play("bonk", 0.5f, 1.2f); }
            if (le.isPlatform() && edge > 0.06f && sim.landSpeed > 5f) { pop(CLOSE[MathUtils.random(CLOSE.length - 1)], gold); freeze(0.07f); world.shake(0.5f); vibrate(25, 1); }
        }
        if ((ev & Sim.EV_JUMP) != 0) world.kick(0.8f);
        if ((ev & Sim.EV_BOUNCE) != 0) { world.kick(4f); pop("BOING!", cyan); }
        if ((ev & Sim.EV_PULL) != 0) { g.audio.play("effort", 0.9f, com.hotatticgames.climbup.render.Characters.voice(g.settings.character)); pop("HUP!", gold); vibrate(20, 2); }
        if ((ev & Sim.EV_GRAB) != 0 && sim.mode == Sim.Mode.LEDGE) {
            gruntT = 0.35f; pop(CATCH[MathUtils.random(CATCH.length - 1)], gold); freeze(0.08f); world.shake(0.4f); }
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
            g.audio.play("checkpoint", 0.8f, 1f);
            toast = "CHECKPOINT"; toastT = 2f; if (tower != null) g.rememberCheckpoint(tower, sim.checkpoint); g.persist(); say("[CHECKPOINT]");
        }
        if ((ev & Sim.EV_RESPAWN) != 0) {
            world.particles.burst(sim.s, sim.y + 0.6f, 18, cyan, 3f, 3.2f, 0.1f, 2f, 0.7f);
            g.audio.play("respawn", 0.8f, 1f); fade = 1f; g.save.falls++; vibrate(40, 1); world.shake(0.5f);
            if ((ev & Sim.EV_HIT) == 0) { toast = "BACK TO CHECKPOINT"; toastT = 1.6f; }
            g.persist();
        }
        if ((ev & Sim.EV_CLUB) != 0) {
            g.audio.play("key", 0.9f, 0.8f); world.particles.burst(s, y + 1f, 14, gold, 3f, 3.2f, 0.12f, -1f, 0.9f); vibrate(25, 1);
            toast = "SPIKED CLUB! TAP SWING TO KNOCK CRABS AND BEES OFF"; toastT = 3f; say("[CLUB]");
        }
        if ((ev & Sim.EV_SWING) != 0) { g.audio.play("swing", 0.8f, 0.95f + MathUtils.random(0.1f)); vibrate(10, 2); }
        if ((ev & Sim.EV_SHOVE) != 0) { g.audio.play("bonk", 0.9f, 0.9f + MathUtils.random(0.2f)); world.shake(0.4f); freeze(0.05f); vibrate(25, 1); pop("OOF!", Color.WHITE); say("[BONK]"); }
        if ((ev & Sim.EV_CRAB_OFF) != 0) {
            g.audio.play("squeak", 0.9f, 0.9f + MathUtils.random(0.3f)); world.crabFlung(sim.crabS, sim.crabY, sim.facing, sim.knockedBee); freeze(0.07f); world.shake(0.4f); vibrate(30, 1);
            pop("BONK!", gold); world.particles.burst(sim.crabS, sim.crabY + 0.4f, 12, red, 3.5f, 3.5f, 0.12f, 6f, 0.6f);
        }
        if ((ev & Sim.EV_KEY) != 0) {
            float[] kc = KEY_RGB[sim.lastKeyColor];
            g.audio.play("key", 1f, 1f); world.particles.burst(s, y + 1f, 16, new Color(kc[0], kc[1], kc[2], 1f), 3f, 3.5f, 0.12f, -1f, 1.0f); vibrate(25, 1); freeze(0.06f);
            toast = KEY_NAMES[sim.lastKeyColor] + " KEY! NOW BACK TO THE " + KEY_NAMES[sim.lastKeyColor] + " CASTLE"; toastT = 3f; say("[KEY]");
        }
        if ((ev & Sim.EV_DOOR) != 0) {
            float[] kc = KEY_RGB[sim.lastGateColor];
            g.audio.play("door", 1f, 1f); world.particles.burst(s + sim.facing * 1.4f, y + 1.2f, 22, new Color(kc[0], kc[1], kc[2], 1f), 3.5f, 3.5f, 0.14f, 0f, 1.0f); world.shake(0.5f); vibrate(40, 1);
            if (ResumeState.gateOpened(g.save, sim.lastGateNo)) towerUnlocked();          // a castle counts (and is timed) once, even if the climb is resumed and its door walked through again
            say("[DOOR OPENS]");
        }
        if ((ev & Sim.EV_FINISH) != 0) {
            int r = RunRecord.complete(g.save);
            if (r >= 0) {
                finishNewBest = r == 1;
                if (tower != null) g.save.records().finish(g.save.seed, g.save.finishTime);
                g.audio.fallStop(); g.audio.play("win", 1f, 1f); world.particles.burst(s, y + 1.2f, 40, new Color(1f, 0.85f, 0.3f, 1f), 4f, 4f, 0.14f, 1f, 1.6f); vibrate(60, 1); say("[RUN COMPLETE]");
                state = State.FINISHED; stickPtr = jumpPtr = -1; jumpHeldTouch = false; g.persist();
            }
        }
        if ((ev & Sim.EV_BLOCKED) != 0 && lockedT <= 0f) {
            lockedT = 1.6f; g.audio.play("locked", 0.8f, 1f); vibrate(15, 2);
            toast = "LOCKED. FIND THE " + KEY_NAMES[sim.lastGateColor] + " KEY"; toastT = 2.6f; say("[LOCKED]");
        }
        if ((ev & Sim.EV_HIT) != 0) {
            world.particles.burst(sim.hitS, sim.hitY + 0.7f, 22, red, 4f, 4f, 0.13f, 8f, 0.7f);
            g.audio.play("hit", 1f, com.hotatticgames.climbup.render.Characters.voice(g.settings.character)); world.shake(0.9f); freeze(0.09f); vibrate(60, 1); say("[OUCH]");
            toast = "OUCH! BE CAREFUL!"; toastT = 1.8f;
        }
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
        {"checkpoint", "RED GEMS ARE CHECKPOINTS. REACH ONE AND YOU RESTART FROM IT WHEN YOU FALL."},
        {"saw", "SAWBLADES SLIDE BACK AND FORTH. WATCH THE RHYTHM, THEN GO. A HIT SENDS YOU BACK TO THE CHECKPOINT."},
        {"cannon", "CANNONS FIRE SPIKED BALLS. WAIT FOR ONE TO PASS, THEN LEAP."},
        {"trap", "SPIKES POP UP ON A BEAT. WAIT IN THE SAFE PATCH, THEN RUN ACROSS WHEN THEY ARE DOWN."},
        {"block", "STONE SPIKE BLOCKS: HOP OVER THEM, AND DON'T DROP INTO ONE."},
        {"crab", "CRABS BUMP YOU AROUND LIKE PING-PONG. TIME YOUR RUN, HOP OVER, OR KNOCK THEM OFF THE EDGE WITH THE SPIKED CLUB."},
        {"bee", "BEES BUZZ IN, BUMP YOU AND FLY OFF AGAIN. THEY ONLY NUDGE YOU: THE DANGER IS WHAT IS BELOW."},
        {"club", "A SPIKED CLUB! PRESS SWING TO KNOCK CRABS OFF THE PLATFORM. IT WEARS OFF AFTER A WHILE."},
        {"drop", "SPIKED SLABS SLAM DOWN ON A BEAT. SLIP UNDER WHILE THEY ARE RAISED."},
        {"spring", "SPRINGS LAUNCH YOU ALONG THE ARROW. STEER IN THE AIR TO LAND IT."},
        {"ramp", "RAMPS LEAD UP TO BLOCKS TOO TALL TO JUMP. JUST RUN UP."},
        {"rampcrumble", "THIS RAMP CRUMBLES WHEN YOU STAND ON IT. RUN!"},
        {"rampshake", "A SHAKY RAMP BOUNCES YOU AROUND. KEEP MOVING AND AIM FOR THE TOP."},
        {"rampsink", "A HOVER RAMP SINKS AWAY UNDER YOUR WEIGHT. GET UP BEFORE IT GOES."},
        {"seesaw", "THE BRIDGE TIPS UNDER YOU. KEEP RUNNING ACROSS BEFORE IT DUMPS YOU OFF."},
        {"gate", "A COLOURED CASTLE NEEDS THE KEY OF ITS COLOUR. THE KEY IS HIDDEN BELOW THE PATH BEFORE IT: DROP DOWN, FIND IT, CLIMB BACK."},
    };

    private void updateTips(float dt) {
        if (toastT > 0) toastT -= dt;
        if (lockedT > 0) lockedT -= dt;
        if (captionT > 0) captionT -= dt;
        if (tipT > 0) { tipT -= dt; if (tipT <= 0) tip = ""; return; }
        if (!g.settings.tips || demo) return;
        // look for the next element of an unseen kind within 9 units
        for (int i = Math.max(0, sim.bestElem - 1); i < Math.min(course.size(), sim.bestElem + 8); i++) {
            Element e = course.get(i);
            if (e.anchor >= 0) continue;                       // decoys never teach anything
            String key = null;
            switch (e.type) {
                case PAD: key = "pad"; break; case ROPE: key = "rope"; break; case CRUMBLE: key = "crumble"; break;
                case MOVE_H: case MOVE_V: key = "move"; break; case CABLE: key = "cable"; break; case SWING: key = "swing"; break; case SPRING: key = "spring"; break; case SEESAW: key = "seesaw"; break; case RAMP: key = e.skin == 1 ? "rampcrumble" : e.skin == 2 ? "rampshake" : e.skin == 3 ? "rampsink" : "ramp"; break;
                default: if (course.isCheckpoint(e) && i > 0) key = "checkpoint";
            }
            if (key == null && i == 1) key = "jump";
            if (key == null) continue;
            if (Math.abs(course.dsWrap(e.s, sim.s)) > 9f || Math.abs(e.y - sim.y) > 9f) continue;
            if (tipFor(key)) return;
        }
        for (int k = 0, cnt = sim.hz == null ? course.hazards.size() : sim.hz.length; k < cnt; k++) {
            Element h = course.hazards.get(sim.hz == null ? k : sim.hz[k]);
            String key = null;
            switch (h.type) {
                case SAW_H: case SAW_V: key = "saw"; break; case CANNON: key = "cannon"; break;
                case SPIKE_TRAP: key = "trap"; break; case SPIKE_BLOCK: key = "block"; break; case SPIKE_DROP: key = "drop"; break; case GATE: key = "gate"; break; case CRAB: key = "crab"; break; case CLUB: key = "club"; break; case BEE: key = "bee"; break; default: break;
            }
            if (key == null || Math.abs(course.dsWrap(h.s, sim.s)) > 9f || Math.abs(h.y - sim.y) > 7f) continue;
            if (tipFor(key)) return;
        }
    }

    private boolean tipFor(String key) {
        if (g.save.shownTips.contains(key)) return false;
        g.save.shownTips.add(key);
        for (String[] t : TIPS) if (t[0].equals(key)) { tip = t[1]; tipT = 5f; }
        return true;
    }

    // ------------------------------------------------------------------ HUD & overlays

    private void drawHud() {
        Ui ui = g.ui;
        ui.begin();
        float W = ui.w(), H = ui.h(), m = 28f;
        float tm = ui.tm();
        // height meter (top-left): current height, best height, and where we are in the repeating worlds
        float zk = Math.min(tm, 1.3f);
        double hAbs = absNow();
        int Z = Palette.ZONES; int zone = Math.min(Z - 1, (int) (((hAbs / g.tuning.zoneHeight) % Z + Z) % Z)), lap = (int) (hAbs / (g.tuning.zoneHeight * Z));
        String hs = (long) Math.max(0, hAbs) + " M";
        ui.rect(m - 6, H - m - 136 * zk, 380 * zk + 12, 136 * zk + 6, new Color(0.05f, 0.07f, 0.14f, 0.55f));
        ui.text(hs, m, H - m - 30 * zk, 4f * zk, Ui.TEXT);
        ui.text("BEST " + (int) Math.max(g.save.bestHeight, maxAbs), m, H - m - 56 * zk, 2.6f * zk, Ui.DIM);
        // speed-run clock: distance and time on the tower being worked towards, and the total time of the climb (always shown)
        float segM = (float) Math.max(0.0, maxAbs - g.save.towerStartHeight);
        if (g.save.finished) ui.text("INFINITY +" + (int) Math.max(0.0, maxAbs - g.tuning.finishCastle * g.tuning.castleSpacing) + " M", m, H - m - 80 * zk, 3f * zk, new Color(1f, 0.82f, 0.3f, 1f));
        else ui.text("TOWER " + (int) segM + " M  " + fmtTime(g.save.runClock - g.save.towerStartClock), m, H - m - 80 * zk, 3f * zk, new Color(1f, 0.82f, 0.3f, 1f));
        if (g.save.finished) ui.text("FINISH " + fmtTime(g.save.finishTime), m, H - m - 104 * zk, 3f * zk, new Color(0.45f, 1f, 0.55f, 1f));
        else ui.text("TOTAL " + fmtTime(g.save.runClock), m, H - m - 104 * zk, 3f * zk, Ui.TEXT);
        float barW = 360 * zk, barY = H - m - 126 * zk;
        // ten towers (castle to castle): finished towers green, the one being climbed yellow, the rest black; the orange tick is where you are right now
        int N = Math.max(1, (int) g.tuning.finishCastle); double span = g.tuning.castleSpacing;
        int cur = Math.min(N - 1, (int) (Math.max(0.0, maxAbs) / span));
        float within = (float) Math.max(0.0, Math.min(1.0, hAbs / (span * N)));
        ui.rect(m, barY, barW, 8, new Color(0.2f, 0.22f, 0.32f, 1f));
        for (int z = 0; z < N; z++) {
            boolean done = g.save.finished || z < cur, active = !g.save.finished && z == cur;
            Color c = done ? new Color(0.25f, 0.85f, 0.35f, 1f) : (active ? new Color(1f, 0.9f, 0.2f, 1f) : new Color(0.02f, 0.02f, 0.04f, 1f));
            ui.rect(m + z * barW / N + 1, barY + 1, barW / N - 2, 6, c);
        }
        ui.rect(m + within * barW - 3, barY - 5, 6, 18, Ui.ACCENT);
        // keys carried
        float kx0 = m, ky0 = barY - 44 * zk;
        for (int c = 0; c < 4; c++) {
            if ((sim.keys & (1 << c)) == 0) continue;
            Color kc = new Color(KEY_RGB[c][0], KEY_RGB[c][1], KEY_RGB[c][2], 1f);
            float ks = 30f * zk;
            ui.rect(kx0 - 3, ky0 - 3, ks * 1.9f + 6, ks + 6, new Color(0.05f, 0.07f, 0.14f, 0.7f));
            ui.rect(kx0, ky0 + ks * 0.1f, ks * 0.7f, ks * 0.8f, kc); ui.rect(kx0 + ks * 0.18f, ky0 + ks * 0.3f, ks * 0.34f, ks * 0.4f, new Color(0.05f, 0.07f, 0.14f, 1f));
            ui.rect(kx0 + ks * 0.7f, ky0 + ks * 0.4f, ks * 1.0f, ks * 0.2f, kc); ui.rect(kx0 + ks * 1.3f, ky0 + ks * 0.15f, ks * 0.18f, ks * 0.3f, kc); ui.rect(kx0 + ks * 1.6f, ky0 + ks * 0.15f, ks * 0.18f, ks * 0.3f, kc);
            kx0 += ks * 2.1f + 10;
        }
        if (sim.clubTime > 0f) {                                       // club timer
            float cw = 150 * zk, cx0 = m, cy0 = ky0 - 34 * zk;
            ui.rect(cx0 - 3, cy0 - 3, cw + 6, 24 * zk + 6, new Color(0.05f, 0.07f, 0.14f, 0.7f));
            ui.rect(cx0, cy0, cw * sim.clubTime / Sim.CLUB_SECONDS, 24 * zk, new Color(0.35f, 0.6f, 1f, 1f));
            ui.text("CLUB", cx0 + 8, cy0 + 4 * zk, 2.6f * zk, Ui.TEXT);
        }
        // pause button
        float pb = 96f; float px = g.settings.leftHanded ? m : W - m - pb, py = H - m - pb;
        ui.rect(px - 3, py - 3, pb + 6, pb + 6, Ui.EDGE); ui.rect(px, py, pb, pb, Ui.PANEL);
        ui.rect(px + 28, py + 24, 14, 48, Ui.TEXT); ui.rect(px + 54, py + 24, 14, 48, Ui.TEXT);
        if (state == State.PLAYING && ui.tappedIn(px - 10, py - 10, pb + 20, pb + 20)) pauseGame();
        // zone banner
        int zoneKey = zone + 4 * lap;
        if (zoneKey != lastZone) { lastZone = zoneKey; zoneT = 3f; }
        if (zoneT > 0) {
            zoneT -= Gdx.graphics.getDeltaTime();
            float a = Math.min(1f, zoneT);
            ui.textC(Palette.NAMES[zone] + (lap > 0 ? " " + (lap + 1) : ""), W / 2, H - 70, 7f, new Color(1f, 0.95f, 0.8f, a));
        }
        if (toastT > 0) { ui.textC(toast, W / 2, H - 125f, 6f, new Color(1f, 0.9f, 0.4f, Math.min(1f, toastT))); if (!toastSub.isEmpty() && toast.endsWith("OPENED!")) ui.textC(toastSub, W / 2, H - 168f, Math.min(3.6f * tm, (W - 60) / Math.max(1, toastSub.length() * 6f)), new Color(1f, 1f, 1f, Math.min(1f, toastT))); }
        if (captionT > 0 && g.settings.captions) { float cw = ui.font.width(caption, 4f * tm); ui.rect(W / 2 - cw / 2 - 14, 28, cw + 28, 44 * tm, new Color(0, 0, 0, 0.6f)); ui.text(caption, W / 2 - cw / 2, 40, 4f * tm, Ui.TEXT); }
        if (!tip.isEmpty() && state == State.PLAYING) {
            float px2 = 3.4f * tm; float maxW = W * 0.5f;
            String[] lines = wrap(tip, px2, maxW);
            float bh = lines.length * (PixelFont_H * px2 + 10) + 24;
            float by = H - 150f - bh;          // top of the screen, under the HUD row, clear of the action
            ui.panel(W / 2 - maxW / 2 - 20, by, maxW + 40, bh);
            for (int i = 0; i < lines.length; i++) ui.textC(lines[i], W / 2, by + bh - 24 - (i + 1) * (PixelFont_H * px2 + 10) + 10, px2, Ui.TEXT);
        }
        drawHeroBubble();
        drawPops();
        if (state == State.PLAYING) drawControls();
        if (fade > 0) ui.rect(0, 0, W, H, new Color(0, 0, 0, fade));
        if (state == State.PAUSED) pauseMenu();
        if (state == State.FINISHED) finishMenu();
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
            g.ui.textC(p.t, headPos[0], headPos[1] + 52f + p.age * 55f, 5.5f * grow, new Color(p.c.r, p.c.g, p.c.b, a));
        }
    }

    private String lastBubble;
    private void drawHeroBubble() {
        String b = world.heroBubble();
        if (b == null || state != State.PLAYING) { lastBubble = null; return; }
        if (!b.equals(lastBubble)) { lastBubble = b; g.audio.play("talk", 0.45f, com.hotatticgames.climbup.render.Characters.voice(g.settings.character)); }
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
        float sbx = jx, sby = jy + 230;
        if (sim.clubTime > 0f) {                                           // swing button (only while carrying the club)
            boolean sp = swingPtr >= 0 || kSwing;
            shapes.setColor(1f, 1f, 1f, ab * 0.7f); shapes.circle(sbx, sby, 98, 36);
            shapes.setColor(sp ? 0.5f : 0.3f, sp ? 0.75f : 0.55f, 1f, sp ? 0.95f : (hc ? 0.85f : 0.6f)); shapes.circle(sbx, sby, 90, 36);
            shapes.setColor(0.1f, 0.12f, 0.2f, 0.95f);
            shapes.rect(sbx - 8, sby - 46, 16, 70);                                        // club handle
            shapes.circle(sbx, sby + 34, 24, 16);                                          // spiked head
            for (int sp2 = 0; sp2 < 6; sp2++) { float a = sp2 * 1.047f; shapes.triangle(sbx + 20 * (float) Math.cos(a), sby + 34 + 20 * (float) Math.sin(a), sbx + 36 * (float) Math.cos(a + 0.2f), sby + 34 + 36 * (float) Math.sin(a + 0.2f), sbx + 36 * (float) Math.cos(a - 0.2f), sby + 34 + 36 * (float) Math.sin(a - 0.2f)); }
        }
        shapes.end();
        ui.batch.begin();
        ui.textC("JUMP", jx, jy - 78, 3.2f, new Color(1, 1, 1, hc ? 1f : 0.8f));
        if (sim.clubTime > 0f) ui.textC("SWING", sbx, sby - 74, 3f, new Color(1, 1, 1, hc ? 1f : 0.85f));
    }

    /** Castle 10's door has been walked through: the time is recorded; END RUN returns to the title (fresh climb next time), CONTINUE keeps climbing for ever without further timing. */
    private void finishMenu() {
        Ui ui = g.ui; float W = ui.w(), H = ui.h(); SaveData sd = g.save;
        ui.rect(0, 0, W, H, new Color(0, 0, 0, 0.62f));
        float pw = 640, ph = 650, x = W / 2 - pw / 2, y = H / 2 - ph / 2;
        ui.panel(x, y, pw, ph);
        ui.textC("CASTLE " + g.tuning.finishCastle + " REACHED!", W / 2, y + ph - 76, 4.8f, Ui.ACCENT);
        ui.textC("FINISH TIME", W / 2, y + ph - 150, 3.4f, Ui.DIM);
        ui.textC(fmtTime(sd.finishTime), W / 2, y + ph - 216, 8f, new Color(0.45f, 1f, 0.55f, 1f));
        ui.textC(finishNewBest ? "NEW RECORD!" : "BEST " + fmtTime(sd.bestFinish), W / 2, y + ph - 270, 3.6f, finishNewBest ? new Color(1f, 0.85f, 0.3f, 1f) : Ui.DIM);
        float bw = 520, bh = 90, bx = W / 2 - bw / 2;
        if (ui.button("END RUN AND SAVE TIME", bx, y + 240, bw, bh, true)) endRunToSummary();
        if (ui.button("TIMES", bx, y + 140, bw, bh)) { g.audio.play("click"); g.persist(); next = new TimesScreen(g, this); disposeOnLeave = false; }
        if (ui.button("KEEP CLIMBING FOR EVER", bx, y + 40, bw, bh)) { resumePlay(); }
    }

    private void pauseMenu() {
        Ui ui = g.ui; float W = ui.w(), H = ui.h();
        boolean canEnd = g.save.finished && tower != null && !demo;          // climbing for ever after castle 10: the run can be ended here and its times saved
        float pw = 560, bw = 440, bh = 76, bs = 90, ph = 176 + (canEnd ? 5 : 4) * bs + 36, x = W / 2 - pw / 2, y = H / 2 - ph / 2;
        ui.rect(0, 0, W, H, new Color(0, 0, 0, 0.55f));
        ui.panel(x, y, pw, ph);
        ui.textC("PAUSED", W / 2, y + ph - 90, 8f, Ui.TEXT);
        if (W >= pw + 2 * 450f) {                  // speed-run splits beside the menu
            SaveData sd = g.save; float sx = x + pw + 28, sw = 420, sh = ph;
            ui.panel(sx, y, sw, sh);
            ui.textC("TOWER SPLITS", sx + sw / 2, y + sh - 56, 4.4f, Ui.ACCENT);
            ui.text("NOW   " + fmtTime(sd.runClock - sd.towerStartClock), sx + 26, y + sh - 108, 3.4f, new Color(1f, 0.82f, 0.3f, 1f));
            ui.text("TOTAL " + fmtTime(sd.runClock), sx + 26, y + sh - 146, 3.4f, Ui.TEXT);
            int first = Math.max(0, sd.towers - 7);
            for (int i = first; i < sd.towers; i++) ui.text((i + 1) + "  " + fmtTime(sd.splits[i]) + "  " + fmtTime(sd.towerTotals[i]), sx + 26, y + sh - 196 - (i - first) * 40, 3f, Ui.TEXT);
            if (sd.towers == 0) ui.text("NO TOWER OPENED YET", sx + 26, y + sh - 196, 3f, Ui.DIM);
            if (sd.bestSplit > 0f) ui.text("FASTEST TOWER " + fmtTime(sd.bestSplit), sx + 26, y + 40, 3f, Ui.DIM);
        }
        float bx = W / 2 - bw / 2, by = y + ph - 176;
        if (ui.button("RESUME", bx, by, bw, bh, true)) resumePlay();
        by -= bs;
        if (ui.button(confirmRestart ? "TAP AGAIN TO CONFIRM" : "RETRY CHECKPOINT", bx, by, bw, bh)) {
            if (confirmRestart) { sim.respawn(); sim.consumeEvents(); world.snapCamera(sim); confirmRestart = confirmEnd = false; resumePlay(); } else confirmRestart = true;
        }
        by -= bs;
        if (ui.button("SETTINGS", bx, by, bw, bh)) { g.audio.play("click"); g.persist(); next = new SettingsScreen(g, this); disposeOnLeave = false; }
        by -= bs;
        if (ui.button("TIMES", bx, by, bw, bh)) { g.audio.play("click"); g.persist(); next = new TimesScreen(g, this); disposeOnLeave = false; }
        by -= bs;
        if (canEnd) {
            if (ui.button(confirmEnd ? "TAP AGAIN TO CONFIRM" : "END RUN AND SAVE TIME", bx, by, bw, bh)) { if (confirmEnd) endRunToSummary(); else confirmEnd = true; }
            by -= bs;
        }
        if (ui.button(tower != null ? "SAVE & EXIT" : "MAIN MENU", bx, by, bw, bh)) { g.audio.play("click"); saveRun(); g.persist(); next = new TitleScreen(g); disposeOnLeave = true; }
    }

    // ------------------------------------------------------------------ lifecycle

    private void pauseGame() { if (state == State.PLAYING) { g.audio.fallStop(); state = State.PAUSED; confirmRestart = confirmEnd = false; stickPtr = jumpPtr = -1; jumpHeldTouch = false; g.audio.play("click"); g.persist(); saveRun(); } }
    /** Writes the exact moment of the climb (not in the scripted demo, and not once the finish screen is up: that run is over). */
    private void saveRun() { if (tower != null && !demo && state != State.FINISHED) g.saveRun(tower, sim); }
    private void resumePlay() { state = State.PLAYING; acc = 0; g.audio.play("click"); }
    public void resumeFromSettings() { applySettings(); }

    @Override public void hide() { g.audio.fallStop(); g.persist(); }
    @Override public void pause() { /* app backgrounded */ g.audio.fallStop(); if (state == State.PLAYING) { state = State.PAUSED; confirmRestart = confirmEnd = false; stickPtr = jumpPtr = -1; jumpHeldTouch = false; } g.persist(); saveRun(); g.audio.pauseMusic(); }
    @Override public void resume() { g.audio.resumeMusic(); }

    @Override public void dispose() { g.audio.fallStop(); if (world != null) { world.dispose(); world = null; } shapes.dispose(); }

}

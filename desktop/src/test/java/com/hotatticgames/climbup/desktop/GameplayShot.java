package com.hotatticgames.climbup.desktop;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.model.Node;
import com.badlogic.gdx.graphics.g3d.model.NodePart;
import com.badlogic.gdx.graphics.g3d.utils.AnimationController;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.hotatticgames.climbup.render.Characters;
import com.hotatticgames.climbup.render.HeroRig;
import com.hotatticgames.climbup.render.Models;
import com.hotatticgames.climbup.render.WorldRenderer;
import com.hotatticgames.climbup.sim.Autopilot;
import com.hotatticgames.climbup.sim.Course;
import com.hotatticgames.climbup.sim.CourseGenerator;
import com.hotatticgames.climbup.sim.Element;
import com.hotatticgames.climbup.sim.InputState;
import com.hotatticgames.climbup.sim.Sim;
import com.hotatticgames.climbup.sim.Tuning;
import java.io.File;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DIAGNOSTIC VALIDATION HARNESS (2026-10-12 controlled diagnostic phase). Renders what the Android player actually sees:
 * a REAL {@link Sim} driven by the autopilot, rendered EVERY simulation frame through the REAL {@link WorldRenderer} (same
 * HeroRig, same model files, same rope geometry, same camera code) at a phone aspect ratio, mirroring PlayScreen:
 * {@code world.resize(w,h)}, {@code world.snapCamera(sim)} once at the start of play, one fixed 60 Hz step per rendered
 * frame with the sim's events fed to the rig ({@code world.heroEvents}), and the camera left to its own per-frame smoothing.
 * So when a frame is captured the rig's animation cross-fade has completed, lineMove/climbPhase are at their in-game
 * values and the camera is where the game's would be. Captures are UNCROPPED full frames, written as a short sequence per
 * state so the hand-over-hand cycle is visible.
 *
 * Why this exists: the previous harness (FullShot) rendered - and therefore updated the HeroRig - only once per capture
 * and never snapped the camera, so every frame it produced showed a one-frame-old pose from the wrong camera height.
 * {@code -Dclimb.mode=legacy} reproduces that old cadence (with snapCamera) and {@code legacy-nosnap} the pre-21:51 state,
 * for A/B comparison against the faithful mode; nothing in the game is changed by any mode.
 *
 * Every capture also prints a diagnostic block (source revision and model hashes, which class files ran, camera, hero
 * joints projected to screen pixels, ball centres, rope placement, animation-controller state, and the posed world extent
 * of the residual forearm/hand mesh vertices relative to the ball hands - computed by CPU skinning of the live bone
 * matrices, read-only). No production class is modified; private state is read by reflection only.
 *
 * Run from the assets directory, e.g.
 *   xvfb-run -a -s "-screen 0 2400x1080x24" java -Dclimb.rev=$(git rev-parse HEAD) -Dclimb.chars=1,2 -Dclimb.shotDir=/tmp/out \
 *       -cp <core classes>:<core resources>:<desktop test classes>:<desktop classes>:<gdx jars> com.hotatticgames.climbup.desktop.GameplayShot
 */
public final class GameplayShot {
    private static final int W = Integer.getInteger("climb.w", 2400), H = Integer.getInteger("climb.h", 1080);   // Build 55 device: 2404x1080
    private static final String CHARS = System.getProperty("climb.chars", "1,2");
    private static final long SEED = Long.getLong("climb.seed", 11L);
    private static final int SLICES = Integer.getInteger("climb.slices", 16), FRAMES = Integer.getInteger("climb.frames", 1600);
    private static final int SETTLE = Integer.getInteger("climb.settle", 30);     // frames in a state before the first capture (Jump_Idle blend 0.12 s = 7 frames; lineMove eases at 14/s)
    private static final int SEQ = Integer.getInteger("climb.seq", 4), SEQ_GAP = Integer.getInteger("climb.seqGap", 12);   // captures per state, frames apart
    private static final int QUALITY = Integer.getInteger("climb.quality", 2);   // Settings default (0 low, 1 medium, 2 high); the owner's device setting is unknown
    private static final String OUT = System.getProperty("climb.shotDir", "build/gameplayshot");
    private static final String MODE = System.getProperty("climb.mode", "faithful");   // faithful | legacy | legacy-nosnap
    private static final String REV = System.getProperty("climb.rev", "?");
    private static final String WANTS = System.getProperty("climb.states", "idle,rope_hang,rope_climb,cable_shimmy,ledge_hang");
    private static final int FILL_FROM = Integer.getInteger("climb.fillFrom", 8428);   // hamster: vertices appended by tools/hamster/fill_holes.py start here (audit §2.4)
    // "cable" / "ledge": the autopilot never traverses a cable or hangs from a ledge on the probed seeds, so for those states the sim is
    // placed directly on the first cable (Sim.startOn, the game's own helper) or hanging from the first platform's edge (the same fields
    // Sim's ledge grab sets), then driven by the autopilot after a short hold; everything else (rig, camera, rendering) is unchanged.
    private static final String START_ON = System.getProperty("climb.startOn", "");
    private static final int WALK_FRAMES = Integer.getInteger("climb.walkFrames", 0);     // > 0: for that many frames the stick is held half right instead of the autopilot (the slow Walk clip; the autopilot only ever runs)
    private static final float WALK_STICK = Float.parseFloat(System.getProperty("climb.walkStick", "0.5"));

    public static void main(String[] args) {
        Lwjgl3ApplicationConfiguration c = new Lwjgl3ApplicationConfiguration();
        c.setTitle("gameplayshot"); c.setWindowedMode(W, H); c.setResizable(false); c.useVsync(false); c.disableAudio(true);
        new Lwjgl3Application(new ApplicationAdapter() {
            PrintStream log;
            @Override public void create() {
                try { run(); } catch (Throwable e) { System.out.println("GAMEPLAYSHOT ERROR " + e); e.printStackTrace(); }
                Gdx.app.exit();
            }

            void run() throws Exception {
                new FileHandle(OUT).mkdirs();
                log = new PrintStream(new java.io.FileOutputStream(new File(OUT, "report.txt"), true), true, "UTF-8");
                boolean faithful = MODE.equals("faithful");
                say("GAMEPLAYSHOT mode=" + MODE + " rev=" + REV + " size=" + W + "x" + H + " aspect=" + String.format("%.3f", W / (float) H)
                        + " backbuffer=" + Gdx.graphics.getBackBufferWidth() + "x" + Gdx.graphics.getBackBufferHeight()
                        + " quality=" + QUALITY + " seed=" + SEED + " settle=" + SETTLE + " seq=" + SEQ + "x" + SEQ_GAP + " cwd=" + new File(".").getCanonicalPath());
                say("CLASSES HeroRig=" + origin(HeroRig.class) + " WorldRenderer=" + origin(WorldRenderer.class) + " Sim=" + origin(Sim.class) + " harness=" + origin(GameplayShot.class));
                Tuning t = Tuning.parse(Gdx.files.internal("data/tuning.json").readString("UTF-8"));
                Models models = new Models();
                FrameBuffer fbo = new FrameBuffer(Pixmap.Format.RGBA8888, W, H, true);
                fbo.begin();
                String[] wants = WANTS.split(",");
                Set<String> wantSet = new LinkedHashSet<>(java.util.Arrays.asList(wants));   // only the asked-for states are captured (the loop ends once each has been)
                for (String chS : CHARS.split(",")) {
                    int ch = Integer.parseInt(chS.trim());
                    say("CHARACTER " + ch + " " + Characters.name(ch) + " model=" + Characters.model(ch) + " sha256=" + sha256(Gdx.files.internal(Characters.model(ch)))
                            + (ch == 1 ? " texture=hero/hamster.png sha256=" + sha256(Gdx.files.internal("hero/hamster.png")) : "") + " scale=" + Characters.scale(ch));
                    Set<String> done = new LinkedHashSet<>();
                    Map<String, Integer> briefIdx = new LinkedHashMap<>();   // brief states (a turn, a grab, a jump...) accumulate captures across occurrences and slices
                    float time = 0f, dt = Sim.DT;
                    InputState in = new InputState();
                    long t0 = System.currentTimeMillis();
                    boolean wantApproach = wantSet.contains("rope_approach");
                    Pixmap[] ring = new Pixmap[8]; int[] ringFrame = new int[8]; int approachN = 0;   // the frames just before a rope grab, kept only when asked for
                    Course prevCourse = null;                               // cable slices: chunks are chained like the game's tower, because cables only generate above the first zone
                    outer:
                    for (int k = 0; k < SLICES; k++) {
                        Course course = CourseGenerator.chunk(SEED, k, START_ON.equals("cable") ? prevCourse : null, t);
                        if (START_ON.equals("cable")) prevCourse = course;
                        WorldRenderer wr = new WorldRenderer(t, course, models, QUALITY, ch);
                        wr.resize(W, H);                                       // PlayScreen.resize -> world.resize(w, h)
                        Sim sim = Sim.startOn(course, t, 0); sim.setRange(0, course.size() - 1);
                        int hold = 0;                                          // frames of no input after a scripted placement (lets a ledge hang settle)
                        if (START_ON.equals("cable")) {
                            int ci = -1; for (int i = 0; i < course.size(); i++) if (course.get(i).type == Element.Type.CABLE) { ci = i; break; }
                            if (ci < 0) { wr.dispose(); continue; }            // no cable in this slice: try the next one
                            sim = Sim.startOn(course, t, ci); sim.setRange(0, course.size() - 1);
                            say("  scripted start: on cable element " + ci + " of slice " + k);
                        } else if (START_ON.equals("ledge")) {
                            int pi = -1; for (int i = 1; i < course.size(); i++) if (course.get(i).isPlatform()) { pi = i; break; }
                            if (pi < 0) { wr.dispose(); continue; }
                            Element el = course.get(pi); int side = 1;         // hang on the platform's -s face, exactly as Sim's ledge grab places the body
                            sim.ledgeSide = side; sim.onElem = pi; sim.mode = Sim.Mode.LEDGE; sim.vx = sim.vy = 0; sim.facing = side;
                            sim.s = course.wrap(sim.es1[pi] - side * (el.halfW() + 0.24f)); sim.y = sim.ey1[pi] - t.handHeight + 0.08f; sim.py0 = sim.y; sim.ps0 = sim.s;
                            hold = 90;
                            say("  scripted start: hanging from the ledge of element " + pi + " of slice " + k);
                        }
                        wr.snapCamera(sim);                                    // PlayScreen:139 - once when play starts
                        Autopilot.Driver driver = new Autopilot.Driver(sim);
                        String holding = null; int held = 0; String seqLabel = null; int seqIdx = 0, sinceCap = 0; int frames = 0;
                        Sim.Mode lastMode = sim.mode, prevMode = null; int modeAge = 0;
                        for (int f = 0; f < FRAMES; f++) {
                            in.clear(); if (f < WALK_FRAMES) in.moveX = WALK_STICK; else if (f >= hold) driver.drive(sim, in); sim.step(in);
                            int ev = sim.consumeEvents(); wr.heroEvents(ev, sim.landSpeed);   // PlayScreen:292/444
                            time += dt; frames++;
                            if (sim.mode != lastMode) { prevMode = lastMode; lastMode = sim.mode; modeAge = 0; } else modeAge++;
                            if (faithful) draw(wr, sim, dt, time);              // the game renders (and so updates the rig + camera) every frame
                            if (wantApproach) {                                 // "rope_approach": the frames 6, 4 and 2 before a rope grab, taken from a ring of the last rendered frames
                                if (sim.mode == Sim.Mode.ROPE && modeAge == 0 && approachN < SEQ) {
                                    for (int back : new int[]{6, 4, 2}) for (int r = 0; r < ring.length; r++) if (ring[r] != null && ringFrame[r] == f - back && approachN < SEQ) {
                                        String name = ch + "_rope_approach_" + approachN++;
                                        PixmapIO.writePNG(new FileHandle(OUT + "/" + name + ".png"), ring[r]);
                                        say("CAPTURE " + name + " slice=" + k + " frame=" + (f - back) + " (buffered " + back + " frames before the rope grab at frame " + f + "; no per-frame diagnostics for buffered frames)");
                                    }
                                    if (approachN >= SEQ) done.add("rope_approach");
                                }
                                int slot = f % ring.length; if (ring[slot] != null) ring[slot].dispose();
                                ring[slot] = Pixmap.createFromFrameBuffer(0, 0, W, H); flipY(ring[slot]); ringFrame[slot] = f;
                            }
                            String now = label(sim, wr.heroRig(), modeAge, prevMode);
                            if (now != null && now.equals(holding)) held++; else { holding = now; held = now == null ? 0 : 1; }
                            boolean brief = isBrief(now);
                            if (seqLabel != null && !seqLabel.equals(now)) { if (!isBrief(seqLabel)) done.add(seqLabel); seqLabel = null; }   // the state ended early: keep what was captured (a brief state keeps collecting at its next occurrence); fall through so the new state can start this same frame
                            if (seqLabel != null) {
                                if (++sinceCap >= gapFor(seqLabel)) { capture(wr, sim, dt, time, ch, seqLabel, seqIdx++, k, f); sinceCap = 0; if (isBrief(seqLabel)) briefIdx.put(seqLabel, seqIdx); if (seqIdx >= SEQ) { done.add(seqLabel); seqLabel = null; } }
                            } else if (now != null && wantSet.contains(now) && !done.contains(now) && held >= settleFor(now) && !("run_front".equals(now) && f < 30)) {   // (the yaw starts at 0 when a slice begins: not a turn)
                                seqLabel = now; seqIdx = brief ? briefIdx.getOrDefault(now, 0) : 0; sinceCap = 0;
                                capture(wr, sim, dt, time, ch, seqLabel, seqIdx++, k, f);
                                if (brief) briefIdx.put(now, seqIdx);
                                if (seqIdx >= SEQ) { done.add(seqLabel); seqLabel = null; }
                            }
                            if (sim.won) break;
                            if (driver.failed) { sim.respawn(); sim.consumeEvents(); driver = new Autopilot.Driver(sim); holding = null; held = 0; seqLabel = null; }   // in-game respawn: sim.teleported makes render() snap the camera
                            if (done.size() >= wants.length) break outer;
                        }
                        say("  slice " + k + " frames=" + frames + " elapsed=" + (System.currentTimeMillis() - t0) / 1000 + "s captured=" + done);
                        wr.dispose();
                        if (done.size() >= wants.length) break;
                    }
                    for (Pixmap p : ring) if (p != null) p.dispose();
                    say("GAMEPLAYSHOT char=" + ch + " captured=" + done);
                }
                fbo.end(); fbo.dispose(); models.dispose();
                say("GAMEPLAYSHOT_DONE");
            }

            void draw(WorldRenderer wr, Sim sim, float dt, float time) {
                Gdx.gl.glClearColor(0f, 0f, 0f, 1f);
                Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
                wr.render(sim, 1f, dt, time, true);                            // PlayScreen:313 (alpha 1: one fixed step per frame)
            }

            String label(Sim s, HeroRig rig, int modeAge, Sim.Mode prev) {
                if (prev == Sim.Mode.ROPE && s.mode != Sim.Mode.ROPE && modeAge < 12) return "rope_leave";   // the first 12 frames after letting go / topping out (pull-up or straight onto the platform)
                switch (s.mode) {
                    case GROUND: {
                        float sp = Math.abs(s.vx);
                        // "run_front" (only when asked for in climb.states): running while the body yaw is within 35 deg of facing the camera - the few
                        // frames of a direction reversal (yaw eases 68 -> -68 at 18/s) or of the first steps, where both arms are seen from the front
                        if (sp >= 0.4f && WANTS.contains("run_front") && Math.abs(rig.yaw) <= 35f) return "run_front";
                        return sp < 0.4f ? "idle" : sp < 3.8f ? "walk" : "run";                       // HeroRig: RUN anim from 0.4; the Walk clip below 3.8, the Run clip above
                    }
                    case AIR:    return s.vy > 1f ? "jump" : s.vy < -3f ? "fall" : null;              // HeroRig.pick: JUMP while rising faster than 1, FALL below -3 (the apex is neither)
                    case ROPE:   if (modeAge < 12) return "rope_grab";                                // the grab itself: the Jump_Idle cross-fade (0.12 s) and lineMove easing are still running
                                 return Math.abs(s.y - s.py0) > 0.004f ? "rope_climb" : "rope_hang";
                    case PULLUP: return "ledge_pullup";
                    case CABLE:  return Math.abs(s.course.dsWrap(s.s, s.ps0)) > 0.004f ? "cable_shimmy" : null;
                    case LEDGE:  return "ledge_hang";
                    default: return null;
                }
            }
            // capture cadence per state: steady states settle for SETTLE frames and are sampled SEQ_GAP apart; brief states (a grab, a
            // take-off, a turn) are captured from their first frames, closer together, and keep collecting across later occurrences
            int settleFor(String l) { switch (l) { case "run_front": case "rope_grab": case "rope_leave": case "ledge_pullup": return 1; case "jump": case "fall": return 3; default: return SETTLE; } }
            int gapFor(String l) { switch (l) { case "run_front": return 1; case "rope_grab": return 2; case "rope_leave": case "ledge_pullup": return 3; case "jump": case "fall": return 4; default: return SEQ_GAP; } }
            boolean isBrief(String l) { return l != null && settleFor(l) != SETTLE; }

            void capture(WorldRenderer wr, Sim sim, float dt, float time, int ch, String label, int idx, int slice, int frame) throws Exception {
                if (!MODE.equals("faithful")) {                                // old FullShot cadence: the rig is updated only now
                    if (MODE.equals("legacy")) wr.snapCamera(sim);
                    draw(wr, sim, dt, time);
                }
                String name = ch + "_" + label + "_" + idx;
                Pixmap pm = Pixmap.createFromFrameBuffer(0, 0, W, H);
                flipY(pm);
                FileHandle fh = new FileHandle(OUT + "/" + name + ".png");
                PixmapIO.writePNG(fh, pm); pm.dispose();
                say("CAPTURE " + name + " slice=" + slice + " frame=" + frame + " sim.mode=" + sim.mode + " sim.y=" + f3(sim.y) + " sim.s=" + f3(sim.s) + " vy=" + f3(sim.vy) + " onElem=" + sim.onElem + " file=" + fh.path());
                diagnostics(wr, sim, ch, label);
            }

            // ---- read-only diagnostics (reflection; nothing is written back) ----
            final Vector3 tmp = new Vector3(), tmp2 = new Vector3(), scr = new Vector3();
            final Matrix4 m4 = new Matrix4();

            void diagnostics(WorldRenderer wr, Sim sim, int ch, String label) throws Exception {
                HeroRig rig = wr.heroRig();
                ModelInstance inst = (ModelInstance) get(rig, "inst");
                AnimationController ac = (AnimationController) get(rig, "ac");
                ModelInstance ballL = (ModelInstance) get(rig, "ballL"), ballR = (ModelInstance) get(rig, "ballR");
                float ballRad = (Float) get(rig, "ballRad"), lineMove = (Float) get(rig, "lineMove"), climbPhase = (Float) get(rig, "climbPhase");
                PerspectiveCamera cam = (PerspectiveCamera) get(wr, "cam");
                say("  CAMERA pos=" + v(cam.position) + " dir=" + v(cam.direction) + " up=" + v(cam.up) + " fov=" + f3(cam.fieldOfView) + " viewport=" + (int) cam.viewportWidth + "x" + (int) cam.viewportHeight + " camY=" + f3(wr.getCamY()) + " near/far=" + cam.near + "/" + cam.far);
                say("  RIG anim=" + rig.anim + " lineMove=" + f3(lineMove) + " climbPhase=" + f3(climbPhase) + " ac.current=" + (ac.current == null ? "-" : ac.current.animation.id + "@" + f3(ac.current.time))
                        + " inAction=" + ac.inAction + " transition=" + f3(ac.transitionCurrentTime) + "/" + f3(ac.transitionTargetTime) + " queued=" + (ac.queued != null) + " maxHandScale=" + f3(rig.maxHandScale()) + " limbs=" + rig.limbScalesDebug());
                // joints: world position and screen pixel (origin top-left, like the phone screenshot)
                String[] suff = {"_Head", "_Neck", "_Torso", "_Shoulder.L", "_Shoulder.R", "_UpperArm.L", "_UpperArm.R", "_LowerArm.L", "_LowerArm.R", "_Fist.L", "_Fist.R", "_Middle1.L", "_Middle1.R", "_Hips"};
                StringBuilder sb = new StringBuilder("  JOINTS");
                for (String s : suff) { Node n = find(inst, s); if (n == null) continue; m4.set(inst.transform).mul(n.globalTransform).getTranslation(tmp); sb.append(' ').append(s.substring(1)).append('=').append(v(tmp)).append("px").append(px(cam, tmp)); }
                say(sb.toString());
                // arm directions (world): upper->lower and lower->hand, with the angle from straight down and from straight out to the side
                StringBuilder ab = new StringBuilder("  ARMS");
                for (String side : new String[]{".L", ".R"}) {
                    Node up = find(inst, "_UpperArm" + side), lo = find(inst, "_LowerArm" + side), hd = find(inst, "_Fist" + side); if (hd == null) hd = find(inst, "_Middle1" + side);
                    if (up == null || lo == null || hd == null) continue;
                    Vector3 pu = new Vector3(), pl = new Vector3(), ph = new Vector3();
                    m4.set(inst.transform).mul(up.globalTransform).getTranslation(pu); m4.set(inst.transform).mul(lo.globalTransform).getTranslation(pl); m4.set(inst.transform).mul(hd.globalTransform).getTranslation(ph);
                    Vector3 d1 = new Vector3(pl).sub(pu).nor(), d2 = new Vector3(ph).sub(pl).nor(); float out = side.equals(".L") ? 1f : -1f;
                    ab.append(' ').append(side.substring(1)).append(": upper->lower=").append(v(d1)).append(" fromDown=").append(Math.round(Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, -d1.y)))))).append("deg fromOut=").append(Math.round(Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, d1.x * out)))))).append("deg")
                      .append(" lower->hand fromDown=").append(Math.round(Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, -d2.y)))))).append("deg");
                }
                say(ab.toString());
                Vector3 fl = new Vector3(), fr = new Vector3(); rig.fistWorld(fl, fr);
                Vector3 ftl = new Vector3(), ftr = new Vector3(); rig.footWorld(ftl, ftr);
                say("  HANDS fistL=" + v(fl) + "px" + px(cam, fl) + " fistR=" + v(fr) + "px" + px(cam, fr) + " feetL=" + v(ftl) + " feetR=" + v(ftr));
                if (ballL != null) {
                    ballL.transform.getTranslation(tmp); ballR.transform.getTranslation(tmp2);
                    say("  BALLS radius=" + f3(ballRad) + " L=" + v(tmp) + "px" + px(cam, tmp) + " R=" + v(tmp2) + "px" + px(cam, tmp2));
                }
                // rope the sim is on: drawn as a 0.11-wide box from e.y-len to e.y on the lane (WorldRenderer drawElement ROPE), at the arc offset from the player
                if (sim.onElem >= 0 && sim.onElem < sim.course.size()) {
                    Element e = sim.course.get(sim.onElem);
                    if (e.type == Element.Type.ROPE) {
                        float ds = sim.course.dsWrap(sim.es1[sim.onElem], sim.s), r = sim.T.radius, phi = ds / r;
                        float rx = r * (float) Math.sin(phi), rz = -r + r * (float) Math.cos(phi);
                        tmp.set(rx, e.y, rz); tmp2.set(rx, e.y - e.len, rz);
                        say("  ROPE elem=" + sim.onElem + " s=" + f3(e.s) + " top.y=" + f3(e.y) + " len=" + f3(e.len) + " world x=" + f3(rx) + " z=" + f3(rz) + " halfWidth=0.055 top px" + px(cam, tmp) + " bottom px" + px(cam, tmp2));
                        // grip numbers: each hand (ball centre, else the fist bone) against the rope's line - sideways offset, depth in front of the line (+) or behind it (-),
                        // height relative to the head centre and the shoulders. "onLine" = the ball overlaps the line sideways and is not behind it by more than its radius.
                        Node head = find(inst, "_Head"), shL = find(inst, "_Shoulder.L"), shR = find(inst, "_Shoulder.R");
                        Vector3 hp = new Vector3(Float.NaN, Float.NaN, Float.NaN), sp = new Vector3(hp);
                        if (head != null) m4.set(inst.transform).mul(head.globalTransform).getTranslation(hp);
                        if (shL != null && shR != null) { Vector3 a = new Vector3(), b = new Vector3(); m4.set(inst.transform).mul(shL.globalTransform).getTranslation(a); m4.set(inst.transform).mul(shR.globalTransform).getTranslation(b); sp.set(a).add(b).scl(0.5f); }
                        StringBuilder gb = new StringBuilder("  GRIP");
                        for (int side = 0; side < 2; side++) {
                            Vector3 h = new Vector3();
                            if (ballL != null) (side == 0 ? ballL : ballR).transform.getTranslation(h); else h.set(side == 0 ? fl : fr);
                            float dx = h.x - rx, dz = h.z - rz, rad = ballL != null ? ballRad : 0.05f;
                            boolean on = Math.abs(dx) <= rad + 0.03f && dz >= -rad;
                            gb.append(side == 0 ? " L:" : " R:").append(" dx=").append(f3(dx)).append(" dz=").append(f3(dz)).append(" dyHead=").append(f3(h.y - hp.y)).append(" dyShoulder=").append(f3(h.y - sp.y)).append(" onLine=").append(on);
                        }
                        gb.append(" (ball r=").append(f3(ballRad)).append(", line halfWidth=0.055)");
                        say(gb.toString());
                    } else say("  ROPE elem=" + sim.onElem + " type=" + e.type);
                }
                face(cam, inst, ballL, ballR, ballRad);
                residual(inst, ballL, ballR, ballRad, ch);
            }

            /** CPU-skins the referenced vertices dominated by each arm/hand bone with the LIVE bone matrices and reports where that mesh sits
             *  relative to the ball hands (world units). Measurement only. */
            void residual(ModelInstance inst, ModelInstance ballL, ModelInstance ballR, float ballRad, int ch) {
                Node meshNode = null;
                for (Node n : inst.nodes) { meshNode = findSkinned(n); if (meshNode != null) break; }
                if (meshNode == null) { say("  RESIDUAL no skinned node part"); return; }
                NodePart np = meshNode.parts.get(0);
                Mesh mesh = np.meshPart.mesh;
                int stride = mesh.getVertexSize() / 4, nv = mesh.getNumVertices();
                float[] vt = new float[nv * stride]; mesh.getVertices(vt);
                VertexAttributes va = mesh.getVertexAttributes();
                int posOff = -1; List<Integer> bw = new ArrayList<>();
                for (VertexAttribute a : va) { if (a.usage == VertexAttributes.Usage.Position) posOff = a.offset / 4; if (a.usage == VertexAttributes.Usage.BoneWeight) bw.add(a.offset / 4); }
                short[] ix = new short[mesh.getNumIndices()]; mesh.getIndices(ix);
                boolean[] referenced = new boolean[nv];
                for (int i = np.meshPart.offset; i < np.meshPart.offset + np.meshPart.size; i++) referenced[ix[i] & 0xFFFF] = true;
                String[] boneName = new String[np.bones.length];
                for (int i = 0; i < np.bones.length; i++) boneName[i] = np.invBoneBindTransforms.keys[i].id;
                Vector3 bl = new Vector3(), br = new Vector3(); if (ballL != null) { ballL.transform.getTranslation(bl); ballR.transform.getTranslation(br); }
                Map<String, Stat> stats = new LinkedHashMap<>();
                for (int vI = 0; vI < nv; vI++) {
                    if (!referenced[vI]) continue;
                    int base = vI * stride; int domB = -1; float domW = -1f;
                    for (int o : bw) { int bi = (int) vt[base + o]; float w = vt[base + o + 1]; if (w > domW) { domW = w; domB = bi; } }
                    if (domB < 0) continue;
                    String bn = boneName[domB];
                    String group = groupOf(bn);
                    if (group == null && !(ch == 1 && vI >= FILL_FROM)) continue;
                    // skinned world position: sum_i w_i * bones[i] * v, then the instance transform (libGDX: skinned renderables use inst.transform as worldTrans)
                    tmp2.setZero();
                    for (int o : bw) { int bi = (int) vt[base + o]; float w = vt[base + o + 1]; if (w <= 0f) continue; tmp.set(vt[base + posOff], vt[base + posOff + 1], vt[base + posOff + 2]).mul(np.bones[bi]).scl(w); tmp2.add(tmp); }
                    tmp2.mul(inst.transform);
                    if (group != null) stats.computeIfAbsent(group, g -> new Stat()).add(tmp2, bn.endsWith(".L") ? bl : br);
                    if (ch == 1 && vI >= FILL_FROM) stats.computeIfAbsent("fillVerts(" + bn + ")", g -> new Stat()).add(tmp2, bn.endsWith(".L") ? bl : br);
                }
                for (Map.Entry<String, Stat> e : stats.entrySet()) say("  RESIDUAL " + e.getKey() + " " + e.getValue().report(ballRad));
            }

            /** Face occlusion, measured: CPU-skins the vertices the Head bone dominates with the LIVE bone matrices, keeps the camera-facing half
             *  (world z at or above the head's mean z; the game camera looks down -z), projects them with the game camera, and counts how many land
             *  inside either ball hand's projected disc. The disc radius is the ball radius projected at the ball's own depth. Measurement only. */
            void face(PerspectiveCamera cam, ModelInstance inst, ModelInstance ballL, ModelInstance ballR, float ballRad) {
                if (ballL == null) { say("  FACE (no ball hands for this character)"); return; }
                Node meshNode = null;
                for (Node n : inst.nodes) { meshNode = findSkinned(n); if (meshNode != null) break; }
                if (meshNode == null) { say("  FACE no skinned node part"); return; }
                NodePart np = meshNode.parts.get(0);
                Mesh mesh = np.meshPart.mesh;
                int stride = mesh.getVertexSize() / 4, nv = mesh.getNumVertices();
                float[] vt = new float[nv * stride]; mesh.getVertices(vt);
                int posOff = -1; List<Integer> bw = new ArrayList<>();
                for (VertexAttribute a : mesh.getVertexAttributes()) { if (a.usage == VertexAttributes.Usage.Position) posOff = a.offset / 4; if (a.usage == VertexAttributes.Usage.BoneWeight) bw.add(a.offset / 4); }
                short[] ix = new short[mesh.getNumIndices()]; mesh.getIndices(ix);
                boolean[] referenced = new boolean[nv];
                for (int i = np.meshPart.offset; i < np.meshPart.offset + np.meshPart.size; i++) referenced[ix[i] & 0xFFFF] = true;
                String[] boneName = new String[np.bones.length];
                for (int i = 0; i < np.bones.length; i++) boneName[i] = np.invBoneBindTransforms.keys[i].id;
                List<Vector3> pts = new ArrayList<>(); float zSum = 0f;
                for (int vI = 0; vI < nv; vI++) {
                    if (!referenced[vI]) continue;
                    int base = vI * stride; int domB = -1; float domW = -1f;
                    for (int o : bw) { int bi = (int) vt[base + o]; float w = vt[base + o + 1]; if (w > domW) { domW = w; domB = bi; } }
                    if (domB < 0 || !boneName[domB].endsWith("_Head")) continue;
                    Vector3 acc = new Vector3();
                    for (int o : bw) { int bi = (int) vt[base + o]; float w = vt[base + o + 1]; if (w <= 0f) continue; tmp.set(vt[base + posOff], vt[base + posOff + 1], vt[base + posOff + 2]).mul(np.bones[bi]).scl(w); acc.add(tmp); }
                    acc.mul(inst.transform); pts.add(acc); zSum += acc.z;
                }
                if (pts.isEmpty()) { say("  FACE no Head-dominated vertices"); return; }
                float zMean = zSum / pts.size();
                Vector3 bl = new Vector3(), br = new Vector3(); ballL.transform.getTranslation(bl); ballR.transform.getTranslation(br);
                Vector3 right = new Vector3(cam.direction).crs(cam.up).nor();
                float[] cl = proj(cam, bl), cr = proj(cam, br);
                float rl = Math.abs(proj(cam, new Vector3(bl).mulAdd(right, ballRad))[0] - cl[0]), rr = Math.abs(proj(cam, new Vector3(br).mulAdd(right, ballRad))[0] - cr[0]);
                int front = 0, inL = 0, inR = 0, inAny = 0; float x0 = 1e9f, y0 = 1e9f, x1 = -1e9f, y1 = -1e9f; Vector3 centre = new Vector3();
                for (Vector3 p : pts) {
                    if (p.z < zMean) continue;
                    front++; centre.add(p);
                    float[] s = proj(cam, p); x0 = Math.min(x0, s[0]); y0 = Math.min(y0, s[1]); x1 = Math.max(x1, s[0]); y1 = Math.max(y1, s[1]);
                    boolean l = dist2(s, cl) <= rl * rl, r = dist2(s, cr) <= rr * rr;
                    if (l) inL++; if (r) inR++; if (l || r) inAny++;
                }
                centre.scl(1f / Math.max(1, front));
                say("  FACE headVerts=" + pts.size() + " front=" + front + " frontCentre=" + v(centre) + "px" + px(cam, centre) + " frontBboxPx=[" + Math.round(x0) + "," + Math.round(y0) + "," + Math.round(x1) + "," + Math.round(y1) + "]"
                        + " ballL px(" + Math.round(cl[0]) + "," + Math.round(cl[1]) + ") r=" + Math.round(rl) + "px ballR px(" + Math.round(cr[0]) + "," + Math.round(cr[1]) + ") r=" + Math.round(rr) + "px"
                        + " coveredL=" + inL + " coveredR=" + inR + " coveredAny=" + inAny + " (" + Math.round(100f * inAny / Math.max(1, front)) + "% of the camera-facing head)");
            }
            float[] proj(PerspectiveCamera cam, Vector3 world) { scr.set(world); cam.project(scr, 0, 0, W, H); return new float[]{scr.x, H - scr.y}; }
            float dist2(float[] a, float[] b) { float dx = a[0] - b[0], dy = a[1] - b[1]; return dx * dx + dy * dy; }

            String groupOf(String bn) {
                if (bn.contains("LowerArm")) return "LowerArm" + side(bn);
                if (bn.contains("Fist")) return "Fist" + side(bn);
                if (bn.contains("Pinky") || bn.contains("Middle") || bn.contains("Index") || bn.contains("Thumb")) return "Finger" + side(bn);
                return null;
            }
            String side(String bn) { return bn.endsWith(".L") ? ".L" : bn.endsWith(".R") ? ".R" : "?"; }

            final class Stat {
                int n; float minX = 1e9f, minY = 1e9f, minZ = 1e9f, maxX = -1e9f, maxY = -1e9f, maxZ = -1e9f, dMin = 1e9f, dMax = 0f, dSum = 0f; int inside = 0, near = 0; final Vector3 c = new Vector3();
                void add(Vector3 p, Vector3 ball) {
                    n++; c.add(p);
                    minX = Math.min(minX, p.x); minY = Math.min(minY, p.y); minZ = Math.min(minZ, p.z); maxX = Math.max(maxX, p.x); maxY = Math.max(maxY, p.y); maxZ = Math.max(maxZ, p.z);
                    float d = p.dst(ball); dMin = Math.min(dMin, d); dMax = Math.max(dMax, d); dSum += d;
                }
                String report(float r) {
                    if (n == 0) return "n=0";
                    return "n=" + n + " centroid=(" + f3(c.x / n) + "," + f3(c.y / n) + "," + f3(c.z / n) + ") bbox x[" + f3(minX) + "," + f3(maxX) + "] y[" + f3(minY) + "," + f3(maxY) + "] z[" + f3(minZ) + "," + f3(maxZ) + "]"
                            + " distToBall min=" + f3(dMin) + " mean=" + f3(dSum / n) + " max=" + f3(dMax) + " (ball r=" + f3(r) + ")";
                }
            }

            Node findSkinned(Node n) {
                if (n.parts.size > 0 && n.parts.get(0).bones != null) return n;
                for (Node c : n.getChildren()) { Node r = findSkinned(c); if (r != null) return r; }
                return null;
            }
            Node find(ModelInstance inst, String suffix) { for (Node n : inst.nodes) { Node r = find(n, suffix); if (r != null) return r; } return null; }
            Node find(Node n, String suffix) {
                if (n.id.endsWith(suffix)) return n;
                for (Node c : n.getChildren()) { Node r = find(c, suffix); if (r != null) return r; }
                return null;
            }
            String px(PerspectiveCamera cam, Vector3 world) {
                scr.set(world); cam.project(scr, 0, 0, W, H);
                return "(" + Math.round(scr.x) + "," + Math.round(H - scr.y) + ")";
            }
            Object get(Object o, String field) throws Exception { Field f = o.getClass().getDeclaredField(field); f.setAccessible(true); return f.get(o); }
            String origin(Class<?> c) { try { return String.valueOf(c.getProtectionDomain().getCodeSource().getLocation()); } catch (Exception e) { return "?"; } }
            String sha256(FileHandle fh) throws Exception { MessageDigest md = MessageDigest.getInstance("SHA-256"); byte[] d = md.digest(fh.readBytes()); StringBuilder b = new StringBuilder(); for (byte x : d) b.append(String.format("%02x", x)); return b.toString(); }
            String v(Vector3 p) { return "(" + f3(p.x) + "," + f3(p.y) + "," + f3(p.z) + ")"; }
            String f3(float x) { return String.format(java.util.Locale.US, "%.3f", x); }
            void say(String s) { System.out.println(s); if (log != null) log.println(s); }

            void flipY(Pixmap pm) {
                int w = pm.getWidth(), h = pm.getHeight(), stride = w * 4;
                ByteBuffer buf = pm.getPixels();
                byte[] a = new byte[stride], b = new byte[stride];
                for (int y = 0; y < h / 2; y++) {
                    int top = y * stride, bot = (h - 1 - y) * stride;
                    buf.position(top); buf.get(a); buf.position(bot); buf.get(b);
                    buf.position(top); buf.put(b); buf.position(bot); buf.put(a);
                }
                buf.position(0);
            }
        }, c);
        System.exit(0);
    }
}

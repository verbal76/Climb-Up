package com.hotatticgames.climbup.render;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.loader.G3dModelLoader;
import com.badlogic.gdx.graphics.g3d.utils.AnimationController;
import com.badlogic.gdx.graphics.g3d.model.Node;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Quaternion;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.JsonReader;
import com.hotatticgames.climbup.sim.Sim;

/**
 * The player: Quaternius' "Character" (CC0), a skinned 3D model with a ready-made animation set (converted from glTF to g3dj by tools/gltf_to_g3dj.py).
 * Idle/Walk/Run/Jump/Jump_Idle/Jump_Land are played from the simulation state; hangs and ropes reuse the airborne pose.
 */
public final class HeroRig implements Disposable {
    private static final float HANG_YAW = Float.parseFloat(System.getProperty("climb.hangYaw", "84"));
    private final float SCALE;    // model units -> world units (bunny/hamster ~3.7 tall, astronauts ~2.85)

    private final Model model;
    private final ModelInstance inst;
    private final AnimationController ac;
    private final boolean ham;                       // the chibi hamster: same skeleton and clips as the bunny, textured instead of vertex-coloured
    private String current = "";
    public float yaw, landT;

    // squash & stretch (spring) driven by gameplay events fed from the play screen
    private float sq, sqV, landSpd; private int pendingEv;
    public void events(int ev, float landSpeed) {
        pendingEv |= ev; landSpd = landSpeed;
        if ((ev & Sim.EV_JUMP) != 0) sqV += 5f;
        if ((ev & Sim.EV_LAND) != 0) sqV -= 4f + Math.min(16f, landSpeed) * 0.45f;
        if ((ev & Sim.EV_BOUNCE) != 0) sqV += 9f;
        if ((ev & Sim.EV_GRAB) != 0) sqV -= 5f;
    }

    // ---- idle director: after a few still seconds he turns to the camera and does a bit (sometimes with a speech bubble)
    private static final class Beat { final String anim; final boolean toCamera; Beat(String a, boolean c) { anim = a; toCamera = c; } }
    private static final Beat[] BEATS = {new Beat("Wave", true), new Beat("No", true), new Beat("Yes", true), new Beat("Duck", false), new Beat("Punch", false), new Beat("No", true)};
    private static final String[] LINES = {"COME ON!", "HELLO? STILL THERE?", "PSST. YOU. THE BUTTON.", "I CAN DO THIS ALL DAY.", "WHAT ARE WE WAITING FOR?",
            "READY WHEN YOU ARE.", "NICE VIEW. NOW JUMP.", "ANY DAY NOW...", "I'M NOT GETTING ANY YOUNGER.", "YOU'RE DOING GREAT. (STANDING.)", "HI! YES, YOU."};
    private final java.util.Random rnd = new java.util.Random(7);   // cosmetic only; never touches the simulation
    private float idleT, beatT, beatDur, nextBeatAt = 3.5f, bubbleT;
    private Beat beat; private int lastBeat = -1, lastLine = -1; private String bubble;

    /** Current speech-bubble text, or null. */
    public String bubble() { return bubbleT > 0 ? bubble : null; }
    public float bubbleAlpha() { return Math.min(1f, Math.min(bubbleT * 4f, (beatDur - beatT) * 4f + 0.2f)); }

    private void startBeat() {
        int i; do { i = rnd.nextInt(BEATS.length); } while (i == lastBeat);
        lastBeat = i; beat = BEATS[i]; beatT = 0;
        com.badlogic.gdx.graphics.g3d.model.Animation a = model.getAnimation(beat.anim);
        beatDur = a != null ? a.duration : 2f;
        if (beat.toCamera && rnd.nextInt(10) < 7) {
            int l; do { l = rnd.nextInt(LINES.length); } while (l == lastLine);
            lastLine = l; bubble = LINES[l]; bubbleT = beatDur;
        }
    }

    // ---- hanging: both arms are posed in code to reach up and grip (the pack has no hang/climb clips)
    private Node upL, loL, fiL, upR, loR, fiR, ulL, ulR, llL, llR;
    private float hangT, lean;
    private final Vector3 pB = new Vector3(), pC = new Vector3(), dir = new Vector3(), tgt = new Vector3();
    private final Quaternion arc = new Quaternion(), part = new Quaternion(), ident = new Quaternion();
    private final Matrix4 rot = new Matrix4(), wNew = new Matrix4(), parInv = new Matrix4();

    private void findNodes() {
        for (Node n : inst.nodes) collect(n);
    }
    private void collect(Node n) {
        String id = n.id;
        if (id.endsWith("_UpperArm.L")) upL = n; else if (id.endsWith("_LowerArm.L")) loL = n; else if (id.endsWith("_Fist.L") || id.endsWith("_Middle1.L")) fiL = n;
        else if (id.endsWith("_UpperLeg.L")) ulL = n; else if (id.endsWith("_UpperLeg.R")) ulR = n; else if (id.endsWith("_LowerLeg.L")) llL = n; else if (id.endsWith("_LowerLeg.R")) llR = n;
        else if (id.endsWith("_UpperArm.R")) upR = n; else if (id.endsWith("_LowerArm.R")) loR = n; else if (id.endsWith("_Fist.R") || id.endsWith("_Middle1.R")) fiR = n;
        for (Node c : n.getChildren()) collect(c);
    }

    /** Rotates bone about its own joint so that the bone->child direction points along target (blended by weight). */
    private void aim(Node bone, Node child, Vector3 target, float weight) {
        bone.globalTransform.getTranslation(pB); child.globalTransform.getTranslation(pC);
        dir.set(pC).sub(pB).nor();
        arc.setFromCross(dir, target);
        part.set(ident).slerp(arc, weight);
        rot.setToTranslation(pB).rotate(part).translate(-pB.x, -pB.y, -pB.z);
        wNew.set(rot).mul(bone.globalTransform);
        Node par = bone.getParent();
        if (par != null) parInv.set(par.globalTransform).inv(); else parInv.idt();
        bone.localTransform.set(parInv).mul(wNew);
        bone.localTransform.getTranslation(bone.translation); bone.localTransform.getRotation(bone.rotation, true); bone.localTransform.getScale(bone.scale);
        inst.calculateTransforms();
    }

    /** Rotates a bone about its own joint (model-space axis). */
    private void rotateBone(Node bone, float ax, float ay, float az, float rad) {
        bone.globalTransform.getTranslation(pB);
        part.setFromAxisRad(ax, ay, az, rad);
        rot.setToTranslation(pB).rotate(part).translate(-pB.x, -pB.y, -pB.z);
        wNew.set(rot).mul(bone.globalTransform);
        Node par = bone.getParent();
        if (par != null) parInv.set(par.globalTransform).inv(); else parInv.idt();
        bone.localTransform.set(parInv).mul(wNew);
        bone.localTransform.getTranslation(bone.translation); bone.localTransform.getRotation(bone.rotation, true); bone.localTransform.getScale(bone.scale);
        inst.calculateTransforms();
    }

    /** Frantic leg kicks while dangling from a ledge; intensity grows the longer he hangs. */
    private void flail(float t) {
        if (ulL == null || ulR == null) return;
        float amp = 0.18f + Math.min(0.20f, hangT * 0.08f), w = 7f + Math.min(3f, hangT);       // a nervous dangle, not a seizure
        rotateBone(ulL, 1f, 0f, 0f, MathUtils.sin(t * w) * amp);
        rotateBone(ulR, 1f, 0f, 0f, MathUtils.sin(t * w + 3.1f) * amp);
        if (llL != null) rotateBone(llL, 1f, 0f, 0f, 0.30f + 0.18f * MathUtils.sin(t * w + 1.2f));
        if (llR != null) rotateBone(llR, 1f, 0f, 0f, 0.30f + 0.18f * MathUtils.sin(t * w + 4.3f));
    }

    private void raiseArms(float weight, float forward) {
        if (upL == null) findNodes();
        if (upL == null || upR == null) return;
        if (DEBUG) { upL.globalTransform.getTranslation(pB); fiL.globalTransform.getTranslation(pC); System.out.printf("BEFORE upL=(%.2f,%.2f,%.2f) fistL=(%.2f,%.2f,%.2f)%n", pB.x, pB.y, pB.z, pC.x, pC.y, pC.z); }
        for (int side = 0; side < 2; side++) {
            Node up = side == 0 ? upL : upR, lo = side == 0 ? loL : loR, fi = side == 0 ? fiL : fiR;
            up.globalTransform.getTranslation(pB);
            float out = Math.signum(pB.x) * (ham ? -0.30f : 0.45f);   // the hamster's shoulders are wide: its paws angle in to meet the rope
            tgt.set(out * (0.45f + 0.35f * (1f - forward)), 0.85f + 0.15f * (1f - forward), forward).nor();   // ledge: forward + up; rope/cable: straight up, a little apart
            aim(up, lo, tgt, weight);
            aim(lo, fi, tgt, weight);
        }
    }

    private static final boolean DEBUG = Boolean.getBoolean("climb.heroDebug");
    private int dbgN;

    private float handModelY() {
        if (DEBUG && dbgN++ < 3) { upL.globalTransform.getTranslation(pB); fiL.globalTransform.getTranslation(pC); System.out.printf("AFTER upL=(%.2f,%.2f,%.2f) fistL=(%.2f,%.2f,%.2f)%n", pB.x, pB.y, pB.z, pC.x, pC.y, pC.z); }
        fiL.globalTransform.getTranslation(pB); fiR.globalTransform.getTranslation(pC);
        return (pB.y + pC.y) * 0.5f;
    }

    public HeroRig() { this(0); }

    public HeroRig(int character) {
        character = Characters.clamp(character);
        ham = character == 1; SCALE = Characters.scale(character);
        model = new G3dModelLoader(new JsonReader()).loadModel(Gdx.files.internal(Characters.model(character)));
        for (com.badlogic.gdx.graphics.g3d.Material m : model.materials) m.set(ColorAttribute.createEmissive(0.10f, 0.10f, 0.12f, 1f));
        if (ham) for (com.badlogic.gdx.graphics.g3d.Material m : model.materials) {
            com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute ta = (com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute) m.get(com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute.Diffuse);
            if (ta != null) ta.textureDescription.texture.setFilter(com.badlogic.gdx.graphics.Texture.TextureFilter.Linear, com.badlogic.gdx.graphics.Texture.TextureFilter.Linear);
        }
        inst = new ModelInstance(model);
        if (Characters.astronaut(character)) { Node gun = inst.getNode("n22_Pistol", true, true); if (gun != null) gun.parts.clear(); }
        ac = new AnimationController(inst);
        play("Idle", -1, 1f, 0f);
    }

    private void play(String id, int loops, float speed, float blend) {
        if (id.equals(current)) { if (ac.current != null) ac.current.speed = speed; return; }
        current = id;
        ac.animate(id, loops, speed, null, blend);
    }

    private static float smooth(float cur, float target, float rate, float dt) { return cur + (target - cur) * Math.min(1f, rate * dt); }

    /** What the player is doing, as the animation system sees it. Both characters implement every state. */
    public enum Anim { IDLE, RUN, JUMP, BIG_JUMP, FALL, LAND, HANG, CLIMB, SHIMMY, PULLUP, HIT }
    public Anim anim = Anim.IDLE;
    private float hitT, hitDir, bigT, fallT, climbPhase; private boolean hitFresh;
    private static final float HIT_TIME = 0.62f, BIG_TIME = 0.95f;

    /** Test hook (-Dclimb.animCycle=true): cycles through every animation state, 1.5 s each, for contact-sheet screenshots. */
    private static final boolean CYCLE = Boolean.getBoolean("climb.animCycle");
    private float cycleClock;

    private Anim pick(Sim sim, float speed) {
        if (CYCLE) {
            Anim[] all = Anim.values(); int i = (int) (cycleClock / 1.5f) % all.length; float f = (cycleClock % 1.5f) / 1.5f;
            if (all[i] == Anim.HIT) { hitT = HIT_TIME * (1f - f * 0.9f); if (hitDir == 0f) hitDir = 1f; } else hitT = 0f;
            if (all[i] == Anim.BIG_JUMP) bigT = BIG_TIME * (1f - f * 0.9f); else bigT = 0f;
            return all[i];
        }
        if (hitT > 0) return Anim.HIT;
        switch (sim.mode) {
            case GROUND: return landT > 0 && speed < 0.5f ? Anim.LAND : (speed < 0.4f ? Anim.IDLE : Anim.RUN);
            case AIR: if (bigT > 0) return Anim.BIG_JUMP; if (sim.vy > 1f) return Anim.JUMP; return sim.vy < -3f || fallT > 0.2f ? Anim.FALL : Anim.JUMP;
            case ROPE: return Math.abs(sim.y - sim.py0) > 0.002f ? Anim.CLIMB : Anim.HANG;
            case CABLE: return Math.abs(sim.course.dsWrap(sim.s, sim.ps0)) > 0.002f ? Anim.SHIMMY : Anim.HANG;
            case LEDGE: return Anim.HANG;
            case BEAM: return sim.mounting() ? Anim.PULLUP : (speed < 0.4f ? Anim.IDLE : Anim.RUN);
            default: return Anim.PULLUP;
        }
    }

    public void update(Sim sim, float dt, float time, float wx, float wy, float wz, float phi, boolean reduced) {
        float speed = Math.abs(sim.vx);
        float targetYaw;
        switch (sim.mode) {
            case ROPE: targetYaw = sim.facing * 22f; break;
            case LEDGE: case PULLUP: targetYaw = sim.ledgeSide * HANG_YAW; break;
            case BEAM: targetYaw = sim.mounting() ? sim.facing * 22f : sim.facing * 68f; break;   // turns to face the ledge he is gripping
            case CABLE: targetYaw = sim.facing * 24f; break;
            default: targetYaw = sim.facing * 68f;   // clearly faces the direction of travel
        }
        if ((pendingEv & (Sim.EV_SHOVE | Sim.EV_HIT)) != 0) { hitT = HIT_TIME; hitFresh = true; current = ""; }
        if ((pendingEv & Sim.EV_BOUNCE) != 0) bigT = BIG_TIME;
        if ((pendingEv & Sim.EV_LAND) != 0) { landT = 0.30f; bigT = 0f; }
        pendingEv = 0;
        if (hitFresh) { hitDir = Math.signum(sim.vx); hitFresh = false; }
        if (hitT > 0) hitT -= dt;
        if (bigT > 0) bigT -= dt;
        fallT = sim.mode == Sim.Mode.AIR && sim.vy < -4f ? fallT + dt : 0f;
        sqV += (-140f * sq - 13f * sqV) * dt; sq = Math.max(-0.45f, Math.min(0.40f, sq + sqV * dt));
        if (landT > 0) landT -= dt;
        cycleClock += dt;
        anim = pick(sim, speed);
        if (CYCLE && (anim == Anim.CLIMB || anim == Anim.SHIMMY)) climbPhase += dt * 6f;
        else if (anim == Anim.CLIMB) climbPhase += Math.abs(sim.y - sim.py0) * 5.5f;
        else if (anim == Anim.SHIMMY) climbPhase += Math.abs(sim.course.dsWrap(sim.s, sim.ps0)) * 4.5f;

        boolean still = sim.mode == Sim.Mode.GROUND && speed < 0.4f && landT <= 0 && hitT <= 0 && !sim.won;
        if (still) {
            idleT += dt;
            if (beat != null) { beatT += dt; if (bubbleT > 0) bubbleT -= dt; if (beatT >= beatDur) { beat = null; bubbleT = 0; nextBeatAt = idleT + 3f + rnd.nextFloat() * 3.5f; } }
            else if (idleT >= nextBeatAt) startBeat();
            if (beat != null && beat.toCamera) targetYaw = 0f;       // breaks the fourth wall: faces the player
        } else { idleT = 0; beat = null; bubbleT = 0; nextBeatAt = 3.5f; }
        yaw = smooth(yaw, targetYaw, 18f, dt);
        switch (anim) {
            case HIT: play("HitReact", 1, 1.15f, 0.04f); break;
            case LAND: play("Jump_Land", 1, 1.5f, 0.05f); break;
            case IDLE: if (beat != null) play(beat.anim, 1, 1f, 0.2f); else play("Idle", -1, 1f, 0.15f); break;
            case RUN: if (speed < 3.8f) play("Walk", -1, Math.max(0.6f, speed / 2.6f), 0.12f); else play("Run", -1, Math.max(0.8f, speed / 5.2f), 0.10f); break;
            case JUMP: play("Jump", 1, 1.2f, 0.05f); break;
            case BIG_JUMP: play("Jump", 1, 1.7f, 0.05f); break;
            case FALL: play("Jump_Idle", -1, 1.35f, 0.10f); break;
            case HANG: case CLIMB: case SHIMMY: play("Jump_Idle", -1, 1f, 0.12f); break;
            default: play("Jump", 1, 1.2f, 0.05f);
        }
        if (sim.won) play("Wave", -1, 1f, 0.2f);

        if (sim.mode == Sim.Mode.BEAM && sim.mounting()) {      // hauling over the beam: starts behind it (like on the rope), ends standing on top of it
            float k = MathUtils.clamp((sim.pullT / Sim.BEAM_MOUNT_TIME - 0.45f) / 0.55f, 0f, 1f);
            wz -= ROPE_FRONT * (1f - k * k * (3f - 2f * k));
        }
        float inch = anim == Anim.CLIMB ? INCH * MathUtils.sin(climbPhase * 2f) : 0f;          // inchworm: the whole body compresses, then stretches, with every pull up the rope
        float sxz = SCALE * (1f - 0.5f * sq - 0.5f * inch), sy = SCALE * (1f + sq + inch);
        float flip = anim == Anim.BIG_JUMP ? -sim.facing * 360f * (1f - bigT / BIG_TIME) : 0f;                   // somersault on a big bounce
        float knock = anim == Anim.HIT ? hitDir * 26f * Math.max(0f, hitT / HIT_TIME) : 0f;                       // thrown back
        float leanT = 0f;           // lean into the run, tuck up while rising, reach forward while falling
        if (!reduced) {
            if (sim.mode == Sim.Mode.GROUND) leanT = anim == Anim.RUN ? MathUtils.clamp(speed / 5.6f, 0f, 1f) * 9f : 0f;
            else if (sim.mode == Sim.Mode.AIR) leanT = sim.vy > 1f ? -5f : MathUtils.clamp(-sim.vy / 12f, 0f, 1f) * 8f;
        }
        lean = smooth(lean, leanT, 9f, dt);
        inst.transform.idt().translate(wx, wy + 0.6f, wz).rotate(0, 0, 1, flip + knock).translate(0, -0.6f, 0)
                .rotate(0, 1, 0, phi * MathUtils.radiansToDegrees + yaw).translate(0, 0.5f, 0).rotate(1, 0, 0, lean).translate(0, -0.5f, 0).scale(sxz, sy, sxz);
        if (ham && upL != null) for (Node n : new Node[]{upL, upR, loL, loR}) if (n != null) n.scale.set(1f, 1f, 1f);     // the stretch below is re-applied every frame, never accumulated
        ac.update(reduced ? Math.min(dt, 1f / 30f) : dt);
        // gripping poses: both arms up on the ledge / rope / cable, hands snapped to the sim's grip point
        hangT = sim.mode == Sim.Mode.LEDGE ? hangT + dt : 0f;
        hangSway += dt * 2.4f;
        float grip = 0f;
        if (anim == Anim.HANG || anim == Anim.CLIMB || anim == Anim.SHIMMY) grip = 1f;
        else if (anim == Anim.PULLUP) grip = CYCLE ? 0.8f : Math.max(0f, 1f - sim.pullT / (sim.mode == Sim.Mode.BEAM ? Sim.BEAM_MOUNT_TIME : sim.T.pullUpTime) * 1.25f);
        if (grip > 0.01f) {
            if (ham) stretchArms(grip);
            boolean line = anim == Anim.CLIMB || anim == Anim.SHIMMY || (anim == Anim.HANG && (sim.mode == Sim.Mode.ROPE || sim.mode == Sim.Mode.CABLE));
            if (line) {                // rope or cable: both hands on the line, alternating hand over hand while moving; knees draw up alternately
                boolean moving = anim != Anim.HANG, cable = anim == Anim.SHIMMY || sim.mode == Sim.Mode.CABLE;
                gripLine(climbPhase, moving, cable); climbLegs(climbPhase, moving);
            }
            else raiseArms(grip, (sim.mode == Sim.Mode.LEDGE || sim.mode == Sim.Mode.PULLUP || (CYCLE && anim == Anim.HANG)) ? 1.0f : 0.12f);
            if ((sim.mode == Sim.Mode.LEDGE || (CYCLE && anim == Anim.HANG)) && !reduced) flail(time);
            if (fiL != null && fiR != null) {
                float handWorld = wy + handModelY() * sy;
                float shift = (sim.y + sim.T.handHeight) - handWorld;
                float tremble = sim.mode == Sim.Mode.LEDGE && !reduced ? MathUtils.sin(time * 22f) * (0.4f + Math.min(0.8f, hangT * 0.3f)) : 0f;
                float behind = line ? ROPE_FRONT * grip : 0f;
                inst.transform.idt().translate(wx, wy + shift * grip, wz - behind).rotate(0, 1, 0, phi * MathUtils.radiansToDegrees + yaw).rotate(0, 0, 1, tremble).scale(sxz, sy, sxz);
            }
        }
    }

    /** The hamster's arms are tiny stubs: while gripping they stretch (cartoon rubber arms) so the paws can reach the rope or ledge above the head. */
    private void stretchArms(float grip) {
        if (upL == null) findNodes();
        if (upL == null || upR == null) return;
        float k = 1f + HAM_REACH * grip;
        for (Node n : new Node[]{upL, upR, loL, loR}) if (n != null) n.localTransform.scale(1f + 0.7f * grip, k, 1f + 0.7f * grip);
        inst.calculateTransforms();
    }
    private static final float HAM_REACH = Float.parseFloat(System.getProperty("climb.hamReach", "1.5"));

    private final Vector3 pF = new Vector3();
    /** While hanging on a rope or cable the hero is drawn this far behind the line (world units) so his arms visibly reach forward and grip it. */
    private static final float ROPE_FRONT = 0.30f;
    private static final float INCH = 0.15f;

    /** Both hands go to the rope (or along the cable): each arm is aimed from its shoulder at a point on the line, one hand reaching high while the other pulls down, swapping with the climb. */
    private void gripLine(float phase, boolean moving, boolean cable) {
        if (upL == null) findNodes();
        if (upL == null || upR == null || fiL == null || fiR == null) return;
        for (int side = 0; side < 2; side++) {
            Node up = side == 0 ? upL : upR, lo = side == 0 ? loL : loR, fi = side == 0 ? fiL : fiR;
            up.globalTransform.getTranslation(pB); lo.globalTransform.getTranslation(pC); fi.globalTransform.getTranslation(pF);
            float len = pB.dst(pC) + pC.dst(pF);
            float reach = moving ? 0.5f + 0.5f * MathUtils.sin(phase + side * MathUtils.PI) : (side == 0 ? 0.95f : 0.62f);
            float sgn = Math.signum(pB.x == 0f ? (side == 0 ? 1f : -1f) : pB.x);
            float ty = pB.y + len * (0.42f + 0.50f * reach);
            float tx = cable ? sgn * len * (0.10f + 0.30f * (1f - reach)) : -sgn * len * 0.04f;     // a rope hangs straight down the middle; a cable runs sideways
            float tz = pB.z + ROPE_FRONT / SCALE;                                                  // the line hangs in front of him (he is drawn a little behind it)
            tgt.set(tx - pB.x, ty - pB.y, tz - pB.z).nor();         // (aim() reuses 'dir' internally, so the target lives in its own vector)
            aim(up, lo, tgt, 1f); aim(lo, fi, tgt, 1f);
        }
    }

    /** Climbing legs: knees draw up alternately (as if stepping up the rope); on a plain hang they dangle with a slow sway. */
    private void climbLegs(float phase, boolean moving) {
        if (ulL == null || ulR == null) return;
        for (int side = 0; side < 2; side++) {
            Node ul = side == 0 ? ulL : ulR, ll = side == 0 ? llL : llR;
            float lift = moving ? Math.max(0f, MathUtils.sin(phase + side * MathUtils.PI)) : 0.12f + 0.1f * MathUtils.sin(hangSway + side * 2.2f);
            rotateBone(ul, 1f, 0f, 0f, 0.25f + 0.65f * lift);
            if (ll != null) rotateBone(ll, 1f, 0f, 0f, 0.35f + 0.85f * lift);
        }
    }
    private float hangSway;

    public void render(ModelBatch batch, Environment env) { batch.render(inst, env); }

    @Override public void dispose() { model.dispose(); }
}

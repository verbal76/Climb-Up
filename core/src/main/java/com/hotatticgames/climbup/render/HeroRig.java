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
    private static final float SCALE = 0.37f;    // model is ~3.7 units tall including ears -> ~1.4 world units

    private final Model model;
    private final ModelInstance inst;
    private final AnimationController ac;
    private final boolean ham;                       // the chibi hamster: a static model animated in code (squash, bob, sway, lean)
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
    private float hangT;
    private final Vector3 pB = new Vector3(), pC = new Vector3(), dir = new Vector3(), tgt = new Vector3();
    private final Quaternion arc = new Quaternion(), part = new Quaternion(), ident = new Quaternion();
    private final Matrix4 rot = new Matrix4(), wNew = new Matrix4(), parInv = new Matrix4();

    private void findNodes() {
        for (Node n : inst.nodes) collect(n);
    }
    private void collect(Node n) {
        String id = n.id;
        if (id.endsWith("_UpperArm.L")) upL = n; else if (id.endsWith("_LowerArm.L")) loL = n; else if (id.endsWith("_Fist.L")) fiL = n;
        else if (id.endsWith("_UpperLeg.L")) ulL = n; else if (id.endsWith("_UpperLeg.R")) ulR = n; else if (id.endsWith("_LowerLeg.L")) llL = n; else if (id.endsWith("_LowerLeg.R")) llR = n;
        else if (id.endsWith("_UpperArm.R")) upR = n; else if (id.endsWith("_LowerArm.R")) loR = n; else if (id.endsWith("_Fist.R")) fiR = n;
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
        float amp = 0.45f + Math.min(0.55f, hangT * 0.3f), w = 15f + Math.min(6f, hangT * 3f);
        rotateBone(ulL, 1f, 0f, 0f, MathUtils.sin(t * w) * amp);
        rotateBone(ulR, 1f, 0f, 0f, MathUtils.sin(t * w + 3.1f) * amp);
        if (llL != null) rotateBone(llL, 1f, 0f, 0f, 0.45f + 0.35f * MathUtils.sin(t * w + 1.2f));
        if (llR != null) rotateBone(llR, 1f, 0f, 0f, 0.45f + 0.35f * MathUtils.sin(t * w + 4.3f));
    }

    private void raiseArms(float weight, float forward) {
        if (upL == null) findNodes();
        if (upL == null || upR == null) return;
        if (DEBUG) { upL.globalTransform.getTranslation(pB); fiL.globalTransform.getTranslation(pC); System.out.printf("BEFORE upL=(%.2f,%.2f,%.2f) fistL=(%.2f,%.2f,%.2f)%n", pB.x, pB.y, pB.z, pC.x, pC.y, pC.z); }
        for (int side = 0; side < 2; side++) {
            Node up = side == 0 ? upL : upR, lo = side == 0 ? loL : loR, fi = side == 0 ? fiL : fiR;
            up.globalTransform.getTranslation(pB);
            float out = Math.signum(pB.x) * 0.45f;
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
        ham = character == 1;
        model = new G3dModelLoader(new JsonReader()).loadModel(Gdx.files.internal(ham ? "hero/hamster.g3dj" : "hero/hero.g3dj"));
        for (com.badlogic.gdx.graphics.g3d.Material m : model.materials) m.set(ColorAttribute.createEmissive(0.10f, 0.10f, 0.12f, 1f));
        if (ham) for (com.badlogic.gdx.graphics.g3d.Material m : model.materials) {
            com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute ta = (com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute) m.get(com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute.Diffuse);
            if (ta != null) ta.textureDescription.texture.setFilter(com.badlogic.gdx.graphics.Texture.TextureFilter.Linear, com.badlogic.gdx.graphics.Texture.TextureFilter.Linear);
        }
        inst = new ModelInstance(model);
        ac = ham ? null : new AnimationController(inst);
        play("Idle", -1, 1f, 0f);
    }

    private void play(String id, int loops, float speed, float blend) {
        if (ham) return;
        if (id.equals(current)) { if (ac.current != null) ac.current.speed = speed; return; }
        current = id;
        ac.animate(id, loops, speed, null, blend);
    }

    private static float smooth(float cur, float target, float rate, float dt) { return cur + (target - cur) * Math.min(1f, rate * dt); }

    public void update(Sim sim, float dt, float time, float wx, float wy, float wz, float phi, boolean reduced) {
        float speed = Math.abs(sim.vx);
        float targetYaw;
        switch (sim.mode) {
            case ROPE: targetYaw = sim.facing * 22f; break;
            case LEDGE: case PULLUP: targetYaw = sim.ledgeSide * HANG_YAW; break;   // turns to face the ledge he is gripping
            case CABLE: targetYaw = sim.facing * 24f; break;
            default: targetYaw = sim.facing * 68f;   // clearly faces the direction of travel
        }
        if ((pendingEv & Sim.EV_LAND) != 0) landT = 0.30f;
        pendingEv = 0;
        sqV += (-140f * sq - 13f * sqV) * dt; sq = Math.max(-0.45f, Math.min(0.40f, sq + sqV * dt));
        if (landT > 0) landT -= dt;

        boolean still = sim.mode == Sim.Mode.GROUND && speed < 0.4f && landT <= 0 && !sim.won;
        if (still) {
            idleT += dt;
            if (beat != null) { beatT += dt; if (bubbleT > 0) bubbleT -= dt; if (beatT >= beatDur) { beat = null; bubbleT = 0; nextBeatAt = idleT + 3f + rnd.nextFloat() * 3.5f; } }
            else if (idleT >= nextBeatAt) startBeat();
            if (beat != null && beat.toCamera) targetYaw = 0f;       // breaks the fourth wall: faces the player
        } else { idleT = 0; beat = null; bubbleT = 0; nextBeatAt = 3.5f; }
        yaw = smooth(yaw, targetYaw, 18f, dt);
        if (ham) { updateHam(sim, dt, time, wx, wy, wz, phi, reduced, speed); return; }
        switch (sim.mode) {
            case GROUND:
                if (landT > 0 && speed < 0.5f) play("Jump_Land", 1, 1.5f, 0.05f);
                else if (beat != null && speed < 0.4f) play(beat.anim, 1, 1f, 0.2f);
                else if (speed < 0.4f) play("Idle", -1, 1f, 0.15f);
                else if (speed < 3.8f) play("Walk", -1, Math.max(0.6f, speed / 2.6f), 0.12f);
                else play("Run", -1, Math.max(0.8f, speed / 5.2f), 0.10f);
                break;
            case AIR:
                if (sim.vy > 1f) play("Jump", 1, 1.2f, 0.05f); else play("Jump_Idle", -1, 1f, 0.10f);
                break;
            case ROPE:
                if (Math.abs(sim.vy) > 0.1f || sim.y != sim.py0) play("Run", -1, 1.0f, 0.12f); else play("Jump_Idle", -1, 1f, 0.12f);
                break;
            case CABLE: case LEDGE:
                play("Jump_Idle", -1, 1f, 0.12f);
                break;
            case PULLUP:
                play("Jump", 1, 1.2f, 0.05f);
                break;
        }
        if (sim.won) play("Wave", -1, 1f, 0.2f);

        float sxz = SCALE * (1f - 0.5f * sq), sy = SCALE * (1f + sq);
        inst.transform.idt().translate(wx, wy, wz).rotate(0, 1, 0, phi * MathUtils.radiansToDegrees + yaw).scale(sxz, sy, sxz);
        ac.update(reduced ? Math.min(dt, 1f / 30f) : dt);
        // gripping poses: both arms up on the ledge / rope / cable, hands snapped to the sim's grip point
        hangT = sim.mode == Sim.Mode.LEDGE ? hangT + dt : 0f;
        float grip = 0f;
        if (sim.mode == Sim.Mode.LEDGE || sim.mode == Sim.Mode.CABLE || sim.mode == Sim.Mode.ROPE) grip = 1f;
        else if (sim.mode == Sim.Mode.PULLUP) grip = Math.max(0f, 1f - sim.pullT / sim.T.pullUpTime * 1.25f);
        if (grip > 0.01f) {
            raiseArms(grip, (sim.mode == Sim.Mode.LEDGE || sim.mode == Sim.Mode.PULLUP) ? 1.0f : 0.12f);
            if (sim.mode == Sim.Mode.LEDGE && !reduced) flail(time);
            if (fiL != null && fiR != null) {
                float handWorld = wy + handModelY() * sy;
                float shift = (sim.y + sim.T.handHeight) - handWorld;
                float tremble = sim.mode == Sim.Mode.LEDGE && !reduced ? MathUtils.sin(time * 38f) * (1.2f + Math.min(2f, hangT)) : 0f;
                inst.transform.idt().translate(wx, wy + shift * grip, wz).rotate(0, 1, 0, phi * MathUtils.radiansToDegrees + yaw).rotate(0, 0, 1, tremble).scale(sxz, sy, sxz);
            }
        }
    }

    private static final float HAM_SCALE = 1.25f / 3.25f, HAM_YAW_FIX = Float.parseFloat(System.getProperty("climb.hamYaw", "0"));
    private float runT, hamLean;

    /** The hamster has no skeleton, so everything is body language: breathing, run bounce + lean, jump stretch, landing squash, hang wiggle, idle gags. */
    private void updateHam(Sim sim, float dt, float time, float wx, float wy, float wz, float phi, boolean reduced, float speed) {
        float k = reduced ? 0.4f : 1f;
        float sxz = 1f, sy = 1f, bob = 0f, roll = 0f, pitch = 0f, yawAdd = 0f, lift = 0f;
        float run = sim.mode == Sim.Mode.GROUND ? Math.min(1f, speed / 5.6f) : 0f;
        runT += dt * (8f + 6f * run);
        hamLean = smooth(hamLean, run * 9f, 10f, dt);
        switch (sim.mode) {
            case GROUND:
                if (speed > 0.4f) { bob = Math.abs(MathUtils.sin(runT)) * 0.12f * run * k; roll = MathUtils.sin(runT) * 6f * run * k; sy += 0.04f * MathUtils.sin(runT * 2f) * run * k; }
                else { sy += 0.022f * MathUtils.sin(time * 2.3f); sxz -= 0.012f * MathUtils.sin(time * 2.3f); }
                if (beat != null && speed < 0.4f) {
                    switch (beat.anim) {
                        case "Wave": roll = MathUtils.sin(beatT * 9f) * 14f; break;
                        case "No": yawAdd = MathUtils.sin(beatT * 10f) * 26f; break;
                        case "Yes": pitch = MathUtils.sin(beatT * 8f) * 14f; bob = Math.abs(MathUtils.sin(beatT * 8f)) * 0.05f; break;
                        case "Duck": sy *= 0.78f; sxz *= 1.1f; break;
                        default: bob = Math.abs(MathUtils.sin(beatT * 11f)) * 0.22f; pitch = 10f; break;
                    }
                }
                break;
            case AIR: sy += MathUtils.clamp(sim.vy * 0.011f, -0.10f, 0.15f); sxz -= MathUtils.clamp(sim.vy * 0.006f, -0.05f, 0.07f); roll = -sim.vx * 1.4f * k; pitch = -4f; break;
            case ROPE: case CABLE: sy += 0.14f; sxz -= 0.06f; roll = MathUtils.sin(time * 7f) * 5f * k; break;
            case LEDGE: sy += 0.16f + 0.02f * MathUtils.sin(time * 20f); sxz -= 0.07f; roll = MathUtils.sin(time * 16f) * (9f + Math.min(8f, hangT * 4f)) * k; lift = MathUtils.sin(time * 33f) * 0.025f * k; break;
            case PULLUP: sy += 0.08f; roll = MathUtils.sin(time * 18f) * 6f * k; break;
        }
        hangT = sim.mode == Sim.Mode.LEDGE ? hangT + dt : 0f;
        sxz *= 1f - 0.5f * sq; sy *= 1f + sq;
        float s0 = HAM_SCALE;
        inst.transform.idt().translate(wx, wy + bob + lift, wz).rotate(0, 1, 0, phi * MathUtils.radiansToDegrees + yaw + yawAdd + HAM_YAW_FIX)
                .rotate(0, 0, 1, roll).rotate(1, 0, 0, pitch + hamLean * 0f).scale(s0 * sxz, s0 * sy, s0 * sxz);
    }

    public void render(ModelBatch batch, Environment env) { batch.render(inst, env); }

    @Override public void dispose() { model.dispose(); }
}

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
        if (ham) {
            hArmR = hamNode("armR", hPArmR); hArmL = hamNode("armL", hPArmL); hFootR = hamNode("footR", hPFootR); hFootL = hamNode("footL", hPFootL);
            hEarR = hamNode("earR", hPEarR); hEarL = hamNode("earL", hPEarL);
        }
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
            default: return Anim.PULLUP;
        }
    }

    public void update(Sim sim, float dt, float time, float wx, float wy, float wz, float phi, boolean reduced) {
        float speed = Math.abs(sim.vx);
        float targetYaw;
        switch (sim.mode) {
            case ROPE: targetYaw = sim.facing * 22f; break;
            case LEDGE: case PULLUP: targetYaw = sim.ledgeSide * HANG_YAW; break;   // turns to face the ledge he is gripping
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
        if (ham) { updateHam(sim, dt, time, wx, wy, wz, phi, reduced, speed); return; }
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

        float sxz = SCALE * (1f - 0.5f * sq), sy = SCALE * (1f + sq);
        float flip = anim == Anim.BIG_JUMP ? -sim.facing * 360f * (1f - bigT / BIG_TIME) : 0f;                   // somersault on a big bounce
        float knock = anim == Anim.HIT ? hitDir * 26f * Math.max(0f, hitT / HIT_TIME) : 0f;                       // thrown back
        inst.transform.idt().translate(wx, wy + 0.6f, wz).rotate(0, 0, 1, flip + knock).translate(0, -0.6f, 0)
                .rotate(0, 1, 0, phi * MathUtils.radiansToDegrees + yaw).scale(sxz, sy, sxz);
        ac.update(reduced ? Math.min(dt, 1f / 30f) : dt);
        // gripping poses: both arms up on the ledge / rope / cable, hands snapped to the sim's grip point
        hangT = sim.mode == Sim.Mode.LEDGE ? hangT + dt : 0f;
        float grip = 0f;
        if (anim == Anim.HANG || anim == Anim.CLIMB || anim == Anim.SHIMMY) grip = 1f;
        else if (anim == Anim.PULLUP) grip = CYCLE ? 0.8f : Math.max(0f, 1f - sim.pullT / sim.T.pullUpTime * 1.25f);
        if (grip > 0.01f) {
            if (anim == Anim.CLIMB || anim == Anim.SHIMMY) { climbArms(climbPhase); climbLegs(climbPhase); }
            else raiseArms(grip, (sim.mode == Sim.Mode.LEDGE || sim.mode == Sim.Mode.PULLUP || (CYCLE && anim == Anim.HANG)) ? 1.0f : 0.12f);
            if ((sim.mode == Sim.Mode.LEDGE || (CYCLE && anim == Anim.HANG)) && !reduced) flail(time);
            if (fiL != null && fiR != null) {
                float handWorld = wy + handModelY() * sy;
                float shift = (sim.y + sim.T.handHeight) - handWorld;
                float tremble = sim.mode == Sim.Mode.LEDGE && !reduced ? MathUtils.sin(time * 38f) * (1.2f + Math.min(2f, hangT)) : 0f;
                inst.transform.idt().translate(wx, wy + shift * grip, wz).rotate(0, 1, 0, phi * MathUtils.radiansToDegrees + yaw).rotate(0, 0, 1, tremble).scale(sxz, sy, sxz);
            }
        }
    }

    /** Hand-over-hand: one arm reaches up and forward while the other pulls down, alternating with the climb. */
    private void climbArms(float phase) {
        if (upL == null) findNodes();
        if (upL == null || upR == null) return;
        for (int side = 0; side < 2; side++) {
            Node up = side == 0 ? upL : upR, lo = side == 0 ? loL : loR, fi = side == 0 ? fiL : fiR;
            up.globalTransform.getTranslation(pB);
            float out = Math.signum(pB.x) * 0.3f, reach = 0.5f + 0.5f * MathUtils.sin(phase + side * MathUtils.PI);
            tgt.set(out, 0.55f + 0.45f * reach, 0.28f * (1f - reach) + 0.05f).nor();
            aim(up, lo, tgt, 1f); aim(lo, fi, tgt, 1f);
        }
    }

    private void climbLegs(float phase) {
        if (ulL == null || ulR == null) return;
        rotateBone(ulL, 1f, 0f, 0f, MathUtils.sin(phase) * 0.55f); rotateBone(ulR, 1f, 0f, 0f, MathUtils.sin(phase + MathUtils.PI) * 0.55f);
        if (llL != null) rotateBone(llL, 1f, 0f, 0f, 0.5f + 0.4f * Math.max(0f, MathUtils.sin(phase)));
        if (llR != null) rotateBone(llR, 1f, 0f, 0f, 0.5f + 0.4f * Math.max(0f, MathUtils.sin(phase + MathUtils.PI)));
    }

    private static final float HAM_SCALE = 1.25f / 3.25f, HAM_YAW_FIX = Float.parseFloat(System.getProperty("climb.hamYaw", "0"));
    private float runT, hamLean;
    private Node hArmR, hArmL, hFootR, hFootL, hEarR, hEarL;
    private final Vector3 hPArmR = new Vector3(), hPArmL = new Vector3(), hPFootR = new Vector3(), hPFootL = new Vector3(), hPEarR = new Vector3(), hPEarL = new Vector3();
    private final Quaternion hq = new Quaternion(), hq2 = new Quaternion();
    private float armR, armL, swingR, swingL, footLiftR, footLiftL, footFwdR, footFwdL, earFlop, earTwitch;

    private Node hamNode(String id, Vector3 pivotOut) {
        Node n = inst.getNode(id);
        if (n != null) pivotOut.set(n.translation);
        return n;
    }

    /** rest (Z): arm angle from straight out (degrees, + raises a right arm); swing (X): forward/back. Left arm mirrored. */
    private void hamArm(Node n, Vector3 pivot, float restDeg, float swingDeg, boolean right) {
        if (n == null) return;
        hq.setFromAxis(0, 0, 1, right ? restDeg : -restDeg);
        hq2.setFromAxis(1, 0, 0, swingDeg);
        hq2.mul(hq);
        n.translation.set(pivot); n.rotation.set(hq2);
    }

    private void hamFoot(Node n, Vector3 pivot, float lift, float fwd, float pitchDeg) {
        if (n == null) return;
        hq.setFromAxis(1, 0, 0, pitchDeg);
        n.translation.set(pivot.x, pivot.y + lift, pivot.z + fwd); n.rotation.set(hq);
    }

    private void hamEar(Node n, Vector3 pivot, float outDeg, float backDeg, boolean right) {
        if (n == null) return;
        hq.setFromAxis(0, 0, 1, right ? -outDeg : outDeg);
        hq2.setFromAxis(1, 0, 0, -backDeg);
        hq2.mul(hq);
        n.translation.set(pivot); n.rotation.set(hq2);
    }

    /** The hamster has no skeleton: arms, feet and ears are separate parts driven by code, plus whole-body squash, bob, lean, flip and knock. */
    private void updateHam(Sim sim, float dt, float time, float wx, float wy, float wz, float phi, boolean reduced, float speed) {
        float k = reduced ? 0.4f : 1f;
        float sxz = 1f, sy = 1f, bob = 0f, roll = 0f, pitch = 0f, yawAdd = 0f, lift = 0f;
        float run = anim == Anim.RUN ? Math.min(1f, speed / 5.6f) : 0f;
        runT += dt * (8f + 6f * run);
        float ph = runT, sw = MathUtils.sin(ph) * 38f * run * k;
        float restR = -68f, restL = -68f, swR = -sw, swL = sw, fl = 0f, fr = 0f, fwdL = 0f, fwdR = 0f, pitchF = 0f, earOut = 6f, earBack = 5f * run;
        switch (anim) {
            case IDLE:
                sy += 0.022f * MathUtils.sin(time * 2.3f); sxz -= 0.012f * MathUtils.sin(time * 2.3f);
                earOut = 6f + 3f * MathUtils.sin(time * 1.7f); earBack = 8f;
                restR += 4f * MathUtils.sin(time * 2.3f); restL += 4f * MathUtils.sin(time * 2.3f + 1f);
                if (beat != null) {
                    switch (beat.anim) {
                        case "Wave": roll = MathUtils.sin(beatT * 9f) * 14f; restR = 80f + 16f * MathUtils.sin(beatT * 12f); swR = 8f * MathUtils.sin(beatT * 12f + 1f); earOut = 14f; break;
                        case "No": yawAdd = MathUtils.sin(beatT * 10f) * 26f; earOut = 18f * MathUtils.sin(beatT * 10f); earBack = 12f; break;
                        case "Yes": pitch = MathUtils.sin(beatT * 8f) * 14f; bob = Math.abs(MathUtils.sin(beatT * 8f)) * 0.05f; restR = restL = -30f; earBack = 18f * Math.abs(MathUtils.sin(beatT * 8f)); break;
                        case "Duck": sy *= 0.78f; sxz *= 1.1f; restR = restL = -20f; fl = fr = 0.1f; break;
                        default: bob = Math.abs(MathUtils.sin(beatT * 11f)) * 0.22f; pitch = 10f; restR = 5f; swR = -50f * Math.abs(MathUtils.sin(beatT * 11f)); restL = -50f; break;
                    }
                }
                break;
            case RUN:
                bob = Math.abs(MathUtils.sin(runT)) * 0.12f * run * k; roll = MathUtils.sin(runT) * 6f * run * k; sy += 0.04f * MathUtils.sin(runT * 2f) * run * k;
                fr = Math.max(0f, MathUtils.sin(ph)) * 0.30f * run; fl = Math.max(0f, MathUtils.sin(ph + MathUtils.PI)) * 0.30f * run;
                fwdR = MathUtils.cos(ph) * 0.22f * run; fwdL = -MathUtils.cos(ph) * 0.22f * run; pitchF = -MathUtils.sin(ph) * 20f * run;
                earBack = 8f + 16f * run + 3f * MathUtils.sin(runT * 2f) * run; pitch = 5f * run;
                break;
            case LAND: restR = restL = 20f; fl = fr = 0.03f; earOut = 12f; earBack = -6f; break;
            case JUMP: {
                float up = MathUtils.clamp(sim.vy / 11f, -1f, 1f);
                sy += MathUtils.clamp(sim.vy * 0.011f, -0.10f, 0.15f); sxz -= MathUtils.clamp(sim.vy * 0.006f, -0.05f, 0.07f);
                restR = restL = 74f + 30f * up; swR = swL = 0f; roll = -sim.vx * 1.4f * k; pitch = -4f;
                fl = fr = 0.22f - 0.08f * up; fwdL = 0.22f; fwdR = -0.1f; pitchF = -25f; earBack = -10f * up + 8f; earOut = 12f;
                break;
            }
            case BIG_JUMP: {
                sy += 0.14f; sxz -= 0.06f; restR = restL = 125f; swR = swL = 0f;
                fl = fr = 0.32f; fwdL = 0.2f; fwdR = 0.2f; pitchF = -45f; earBack = -25f; earOut = 16f;
                break;
            }
            case FALL:
                sy += 0.10f; sxz -= 0.05f; roll = MathUtils.sin(time * 14f) * 7f * k;
                restR = 108f + 24f * MathUtils.sin(time * 19f); restL = 108f + 24f * MathUtils.sin(time * 19f + 1.7f); swR = swL = 0f;
                fr = 0.28f * Math.max(0f, MathUtils.sin(time * 15f)); fl = 0.28f * Math.max(0f, MathUtils.sin(time * 15f + MathUtils.PI));
                fwdR = MathUtils.sin(time * 15f) * 0.2f; fwdL = -MathUtils.sin(time * 15f) * 0.2f; pitchF = MathUtils.sin(time * 15f) * 28f;
                earBack = -40f; earOut = 22f;
                break;
            case HANG:
                if (sim.mode == Sim.Mode.LEDGE || CYCLE) {
                    float w = 16f + Math.min(6f, hangT * 3f);
                    sy += 0.16f + 0.02f * MathUtils.sin(time * 20f); sxz -= 0.07f;
                    roll = MathUtils.sin(time * 16f) * (9f + Math.min(8f, hangT * 4f)) * k; lift = MathUtils.sin(time * 33f) * 0.025f * k;
                    restR = restL = -68f; swR = swL = -160f + 7f * MathUtils.sin(time * 22f);
                    fl = 0.30f * Math.max(0f, MathUtils.sin(time * w)); fr = 0.30f * Math.max(0f, MathUtils.sin(time * w + MathUtils.PI));
                    fwdL = MathUtils.sin(time * w) * 0.25f; fwdR = -MathUtils.sin(time * w) * 0.25f; pitchF = MathUtils.sin(time * w) * 30f;
                    earBack = 20f; earOut = 3f;
                } else {                                      // rope / cable, holding still
                    sy += 0.14f; sxz -= 0.06f; roll = MathUtils.sin(time * 3f) * 3f * k;
                    restR = restL = -68f; swR = swL = -168f; fl = 0.1f; fr = 0.05f; earBack = 18f;
                }
                break;
            case CLIMB: case SHIMMY: {
                float p = climbPhase;
                sy += 0.12f; sxz -= 0.05f; roll = MathUtils.sin(p) * 5f * k; bob = 0.03f * MathUtils.sin(p * 2f) * k;
                restR = restL = -68f; swR = -125f - 38f * MathUtils.sin(p); swL = -125f - 38f * MathUtils.sin(p + MathUtils.PI);
                fr = 0.30f * Math.max(0f, MathUtils.sin(p + MathUtils.PI)); fl = 0.30f * Math.max(0f, MathUtils.sin(p));
                fwdR = 0.12f; fwdL = 0.12f; pitchF = -20f; earBack = 22f;
                break;
            }
            case PULLUP:
                sy += 0.08f; roll = MathUtils.sin(time * 18f) * 6f * k;
                restR = restL = -68f; swR = swL = -110f * (CYCLE ? 0.5f : Math.max(0f, 1f - sim.pullT / sim.T.pullUpTime)) - 20f; fl = fr = 0.2f; earBack = 10f;
                break;
            case HIT: {
                float h = Math.max(0f, hitT / HIT_TIME);
                pitch = -14f * h; sy *= 1f - 0.12f * h; sxz *= 1f + 0.08f * h;
                restR = restL = 118f; swR = swL = 0f; fl = fr = 0.35f * h; fwdL = fwdR = 0.25f * h; pitchF = -50f * h;
                earBack = -35f * h; earOut = 22f; lift = 0.05f * h;
                break;
            }
        }
        hangT = sim.mode == Sim.Mode.LEDGE ? hangT + dt : 0f;
        sxz *= 1f - 0.5f * sq; sy *= 1f + sq;
        hamArm(hArmR, hPArmR, restR, swR, true); hamArm(hArmL, hPArmL, restL, swL, false);
        hamFoot(hFootR, hPFootR, fr, fwdR, pitchF); hamFoot(hFootL, hPFootL, fl, fwdL, -pitchF);
        hamEar(hEarR, hPEarR, earOut, earBack, true); hamEar(hEarL, hPEarL, earOut, earBack, false);
        float flip = anim == Anim.BIG_JUMP ? -sim.facing * 360f * (1f - bigT / BIG_TIME) : 0f;
        if (anim == Anim.HIT) flip += hitDir * 34f * Math.max(0f, hitT / HIT_TIME);
        float s0 = HAM_SCALE;
        inst.transform.idt().translate(wx, wy + bob + lift + 0.6f, wz).rotate(0, 0, 1, flip).translate(0, -0.6f, 0)
                .rotate(0, 1, 0, phi * MathUtils.radiansToDegrees + yaw + yawAdd + HAM_YAW_FIX)
                .rotate(0, 0, 1, roll).rotate(1, 0, 0, pitch).scale(s0 * sxz, s0 * sy, s0 * sxz);
        inst.calculateTransforms();
    }

    public void render(ModelBatch batch, Environment env) { batch.render(inst, env); }

    @Override public void dispose() { model.dispose(); }
}

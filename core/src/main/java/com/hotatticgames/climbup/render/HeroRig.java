package com.hotatticgames.climbup.render;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.loader.G3dModelLoader;
import com.badlogic.gdx.graphics.g3d.utils.AnimationController;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.JsonReader;
import com.hotatticgames.climbup.sim.Sim;

/**
 * The player: Quaternius' "Character" (CC0), a skinned 3D model with a ready-made animation set (converted from glTF to g3dj by tools/gltf_to_g3dj.py).
 * Idle/Walk/Run/Jump/Jump_Idle/Jump_Land are played from the simulation state; hangs and ropes reuse the airborne pose.
 */
public final class HeroRig implements Disposable {
    private static final float SCALE = 0.37f;    // model is ~3.7 units tall including ears -> ~1.4 world units

    private final Model model;
    private final ModelInstance inst;
    private final AnimationController ac;
    private String current = "";
    public float yaw, landT;

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

    public HeroRig() {
        model = new G3dModelLoader(new JsonReader()).loadModel(Gdx.files.internal("hero/hero.g3dj"));
        for (com.badlogic.gdx.graphics.g3d.Material m : model.materials) m.set(ColorAttribute.createEmissive(0.10f, 0.10f, 0.12f, 1f));
        inst = new ModelInstance(model);
        ac = new AnimationController(inst);
        play("Idle", -1, 1f, 0f);
    }

    private void play(String id, int loops, float speed, float blend) {
        if (id.equals(current)) { if (ac.current != null) ac.current.speed = speed; return; }
        current = id;
        ac.animate(id, loops, speed, null, blend);
    }

    private static float smooth(float cur, float target, float rate, float dt) { return cur + (target - cur) * Math.min(1f, rate * dt); }

    public void update(Sim sim, float dt, float time, float wx, float wy, float wz, float phi, boolean reduced) {
        float speed = Math.abs(sim.vx);
        float targetYaw;
        switch (sim.mode) {
            case ROPE: targetYaw = sim.facing * 12f; break;
            case LEDGE: case PULLUP: targetYaw = sim.ledgeSide * 16f; break;
            case CABLE: targetYaw = sim.facing * 18f; break;
            default: targetYaw = sim.facing * 68f;   // clearly faces the direction of travel
        }
        if ((sim.events & Sim.EV_LAND) != 0) landT = 0.30f;
        if (landT > 0) landT -= dt;

        boolean still = sim.mode == Sim.Mode.GROUND && speed < 0.4f && landT <= 0 && !sim.won;
        if (still) {
            idleT += dt;
            if (beat != null) { beatT += dt; if (bubbleT > 0) bubbleT -= dt; if (beatT >= beatDur) { beat = null; bubbleT = 0; nextBeatAt = idleT + 3f + rnd.nextFloat() * 3.5f; } }
            else if (idleT >= nextBeatAt) startBeat();
            if (beat != null && beat.toCamera) targetYaw = 0f;       // breaks the fourth wall: faces the player
        } else { idleT = 0; beat = null; bubbleT = 0; nextBeatAt = 3.5f; }
        yaw = smooth(yaw, targetYaw, 18f, dt);
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

        inst.transform.idt().translate(wx, wy, wz).rotate(0, 1, 0, phi * MathUtils.radiansToDegrees + yaw).scale(SCALE, SCALE, SCALE);
        ac.update(reduced ? Math.min(dt, 1f / 30f) : dt);
    }

    public void render(ModelBatch batch, Environment env) { batch.render(inst, env); }

    @Override public void dispose() { model.dispose(); }
}

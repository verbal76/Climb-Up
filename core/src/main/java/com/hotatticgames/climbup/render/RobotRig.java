package com.hotatticgames.climbup.render;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Disposable;
import com.hotatticgames.climbup.sim.Sim;

/**
 * The Cute Robot (Foozle, CC0): each supplied body-part sprite is extruded into a voxel-style 3D mesh and the parts are
 * animated procedurally as a small skeleton (idle, run, jump, fall, land, rope climb, cable/ledge hang, pull-up).
 */
public final class RobotRig implements Disposable {
    private static final float K = 0.0033f;  // world units per sprite pixel; whole robot ~1.26 high
    private final Model[] models = new Model[6];
    private final Texture[] texs = new Texture[6];
    private final ModelInstance body, head, armL, armR, legL, legR;
    private final Matrix4 root = new Matrix4(), bodyM = new Matrix4(), m = new Matrix4();
    private static final Vector3 X = new Vector3(1, 0, 0), Y = new Vector3(0, 1, 0), Z = new Vector3(0, 0, 1);

    // animation state
    public float yaw, runPhase, squash, climbPhase, blink, pullK;

    public RobotRig(int quality) {
        String[] names = {"Body", "Head", "LeftArm", "RightArm", "LeftLeg", "RightLeg"};
        int[] cells = {6, 6, 5, 5, 5, 5};
        int[] levels = {7, 7, 4, 4, 4, 4};
        float[] depth = {0.028f, 0.034f, 0.026f, 0.026f, 0.026f, 0.026f};
        for (int i = 0; i < 6; i++) {
            Pixmap pm = new Pixmap(Gdx.files.internal("robot/" + names[i] + ".png"));
            texs[i] = new Texture(pm);
            texs[i].setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
            models[i] = VoxelPart.build(pm, texs[i], K, cells[i], levels[i], depth[i]);
            pm.dispose();
        }
        body = new ModelInstance(models[0]); head = new ModelInstance(models[1]);
        armL = new ModelInstance(models[2]); armR = new ModelInstance(models[3]);
        legL = new ModelInstance(models[4]); legR = new ModelInstance(models[5]);
    }

    private static float smooth(float cur, float target, float rate, float dt) { return cur + (target - cur) * Math.min(1f, rate * dt); }

    /** Pose limbs from the sim state and place the rig at world position (wx, wy, wz) rotated by tangent angle phi. */
    public void update(Sim sim, float dt, float time, float wx, float wy, float wz, float phi, boolean reduced) {
        float speed = Math.abs(sim.vx);
        float targetYaw;
        switch (sim.mode) {
            case ROPE: targetYaw = sim.facing * 12f; break;
            case LEDGE: case PULLUP: targetYaw = sim.ledgeSide * 16f; break;
            case CABLE: targetYaw = sim.facing * 18f; break;
            default: targetYaw = sim.facing * 30f;
        }
        yaw = smooth(yaw, targetYaw, 14f, dt);
        squash = smooth(squash, 0f, 12f, dt);
        if ((sim.events & Sim.EV_LAND) != 0) squash = Math.min(1f, sim.landSpeed / 14f) * 0.9f + 0.1f;

        float legSwing = 0, legSwing2 = 0, armSwing = 0, armSwing2 = 0, armSpread = 0, armSpread2 = 0, lean = 0, bob = 0;
        float stretch = 0, headTilt = 0;
        switch (sim.mode) {
            case GROUND: {
                runPhase += speed * dt * 2.4f;
                float a = Math.min(1f, speed / sim.T.runSpeed);
                float sw = (float) Math.sin(runPhase) * a;
                legSwing = sw * 0.95f; legSwing2 = -sw * 0.95f;
                armSwing = -sw * 0.8f; armSwing2 = sw * 0.8f; armSpread = armSpread2 = 0.22f;
                lean = 0.16f * a; bob = Math.abs((float) Math.sin(runPhase)) * 0.04f * a;
                if (a < 0.05f) { bob = 0.012f * (float) Math.sin(time * 2.2f); armSpread = armSpread2 = 0.16f + 0.04f * (float) Math.sin(time * 2.2f); }
                break;
            }
            case AIR: {
                if (sim.vy > 1f) { legSwing = 0.6f; legSwing2 = -0.35f; armSwing = armSwing2 = -0.15f; armSpread = armSpread2 = 2.35f; stretch = 0.08f; lean = 0.1f; }
                else { legSwing = 0.25f; legSwing2 = -0.2f; armSwing = armSwing2 = 0f; armSpread = armSpread2 = 1.2f + Math.min(0.5f, -sim.vy * 0.025f); stretch = -0.03f; }
                break;
            }
            case ROPE: {
                climbPhase += (sim.vy + 0f) * 0.0f;
                legSwing = 0.3f; legSwing2 = -0.2f;
                armSwing = armSwing2 = 0f; armSpread = armSpread2 = 2.85f;
                float c = (float) Math.sin(runPhase);
                armSpread += c * 0.18f; armSpread2 -= c * 0.18f; legSwing += c * 0.5f; legSwing2 -= c * 0.5f;
                break;
            }
            case CABLE: {
                armSwing = armSwing2 = 0f; armSpread = armSpread2 = 2.9f;
                float c = (float) Math.sin(time * 3f);
                legSwing = 0.15f + c * 0.15f; legSwing2 = 0.1f - c * 0.15f; runPhase += Math.abs(sim.vx) * dt;
                break;
            }
            case LEDGE: {
                armSwing = armSwing2 = 0f; armSpread = armSpread2 = 2.9f;
                float c = (float) Math.sin(time * 2.5f);
                legSwing = 0.2f + c * 0.12f; legSwing2 = 0.15f - c * 0.12f;
                break;
            }
            case PULLUP: {
                float k = Math.min(1f, sim.pullT / sim.T.pullUpTime);
                armSwing = armSwing2 = 0f; armSpread = armSpread2 = 2.9f - 2.4f * k;
                legSwing = 0.8f * k; legSwing2 = 0.3f * k; lean = 0.3f * k;
                break;
            }
        }
        if ((sim.events & Sim.EV_BOUNCE) != 0) squash = -0.25f;
        float sy = 1f - squash * 0.28f + stretch, sxz = 1f + squash * 0.14f - stretch * 0.4f;

        root.setToTranslation(wx, wy + bob, wz).rotate(Y, (float) Math.toDegrees(phi) + yaw);
        // body (pivot at hips)
        bodyM.set(root).translate(0, 110 * K, 0).rotate(X, -lean * MathUtils.radiansToDegrees).scale(sxz, sy, sxz);
        body.transform.set(bodyM).translate(0, 60 * K, 0);
        m.set(bodyM).translate(0, (235 - 110) * K, 0).rotate(Z, headTilt).translate(0, 72 * K, 0);
        head.transform.set(m);
        // arms hang from shoulders; swing about X (forward = +Z), spread about Z
        placeArm(armL, bodyM, -82, armSwing, -armSpread);
        placeArm(armR, bodyM, 82, armSwing2, armSpread2);
        // legs from hips on root
        placeLeg(legL, -27, legSwing);
        placeLeg(legR, 27, legSwing2);
    }

    private void placeArm(ModelInstance inst, Matrix4 parent, float px, float swing, float spread) {
        m.set(parent).translate(px * K, (238 - 110) * K, 0).rotate(Z, spread * MathUtils.radiansToDegrees).rotate(X, swing * MathUtils.radiansToDegrees).translate(0, -52 * K, 0);
        inst.transform.set(m);
    }

    private void placeLeg(ModelInstance inst, float px, float swing) {
        m.set(root).translate(px * K, 100 * K, 0).rotate(X, swing * MathUtils.radiansToDegrees).translate(0, -50 * K, 0);
        inst.transform.set(m);
    }

    public void render(ModelBatch batch, Environment env) {
        batch.render(legL, env); batch.render(legR, env); batch.render(body, env);
        batch.render(armL, env); batch.render(armR, env); batch.render(head, env);
    }

    @Override public void dispose() {
        for (Model mdl : models) mdl.dispose();
        for (Texture t : texs) t.dispose();
    }
}

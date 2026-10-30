package com.hotatticgames.climbup.render;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.RandomXS128;
import com.hotatticgames.climbup.sim.Course;
import com.hotatticgames.climbup.sim.Tuning;

/**
 * ATMOSPHERE PROTOTYPE ({@link Atmos#ENABLED} only): a sparse layer of soft glowing motes floating BEHIND the play lane, between it and the
 * tower's hidden axis, so the air itself has depth (the existing ambient motes all live in the play plane). Per world: pollen and seed fluff
 * (Meadow), ice glints (Frost), rising embers (Dusk), slow fireflies (Night), cold star dust (Deep Space). Drawn only at HIGH graphics
 * quality and never under REDUCED MOTION; a fixed pool, its own random stream (never the game's), no allocation per frame. They sit behind
 * the lane, so the depth buffer keeps them behind every platform, hazard and the hero.
 */
final class AtmosMotes {
    static final int MAX = 36;
    /** Depth behind the lane (toward the tower axis), metres. */
    static final float DZ_MIN = 1.6f, DZ_MAX = 6.5f;
    static final Color[] COL = {new Color(1f, 0.95f, 0.70f, 1f), new Color(0.80f, 0.95f, 1f, 1f), new Color(1f, 0.62f, 0.28f, 1f), new Color(0.75f, 1f, 0.55f, 1f), new Color(0.70f, 0.80f, 1f, 1f)};
    static final float[] RATE = {5f, 5f, 6f, 4f, 4f};          // spawns per second at HIGH quality

    private final float[] s = new float[MAX], y = new float[MAX], dz = new float[MAX], vs = new float[MAX], vy = new float[MAX], life = new float[MAX], max = new float[MAX], size = new float[MAX], ph = new float[MAX];
    private final int[] kind = new int[MAX];
    private final ModelInstance[] inst = new ModelInstance[MAX];
    private final ColorAttribute[] diff = new ColorAttribute[MAX], emis = new ColorAttribute[MAX];
    private final BlendingAttribute[] blend = new BlendingAttribute[MAX];
    private final RandomXS128 rnd = new RandomXS128(0x41544D4FL);
    private final Color tmp = new Color();
    private float acc, clock;
    private int next;

    AtmosMotes(Models m) {
        for (int i = 0; i < MAX; i++) {
            inst[i] = new ModelInstance(m.box);
            diff[i] = ColorAttribute.createDiffuse(Color.WHITE); emis[i] = ColorAttribute.createEmissive(Color.BLACK); blend[i] = new BlendingAttribute(true, 0f);
            inst[i].materials.get(0).set(blend[i], diff[i], emis[i]);
        }
    }

    private float r(float a, float b) { return a + (b - a) * rnd.nextFloat(); }

    /** The world origin moved by dy: keep every mote where it is on screen. */
    void shiftY(float dy) { for (int i = 0; i < MAX; i++) if (life[i] > 0f) y[i] -= dy; }

    void update(float dt, float zoneF, float camS, float camY, boolean on) {
        clock += dt;
        if (on) {
            int z = Math.min(Palette.ZONES - 1, (int) zoneF);
            acc += dt * RATE[z];
            while (acc >= 1f) { acc -= 1f; spawn(z, camS, camY); }
        } else acc = 0f;
        for (int i = 0; i < MAX; i++) if (life[i] > 0f) { life[i] -= dt; s[i] += vs[i] * dt; y[i] += vy[i] * dt; }
    }

    private void spawn(int z, float camS, float camY) {
        int i = next; next = (next + 1) % MAX;
        kind[i] = z; s[i] = camS + r(-13f, 13f); y[i] = camY + r(-6f, 7f); dz[i] = r(DZ_MIN, DZ_MAX); ph[i] = r(0f, MathUtils.PI2);
        switch (z) {
            case 0: vs[i] = r(-0.25f, 0.25f); vy[i] = r(0.05f, 0.30f); size[i] = r(0.05f, 0.09f); max[i] = r(5f, 8f); break;
            case 1: vs[i] = r(-0.35f, 0.05f); vy[i] = -r(0.25f, 0.55f); size[i] = r(0.05f, 0.08f); max[i] = r(6f, 9f); break;
            case 2: vs[i] = r(-0.2f, 0.2f); vy[i] = r(0.35f, 0.8f); size[i] = r(0.04f, 0.07f); max[i] = r(4f, 6f); y[i] = camY + r(-7f, 2f); break;
            case 3: vs[i] = r(-0.3f, 0.3f); vy[i] = r(-0.12f, 0.15f); size[i] = r(0.06f, 0.09f); max[i] = r(5f, 8f); break;
            default: vs[i] = r(-0.1f, 0.1f); vy[i] = r(-0.06f, 0.08f); size[i] = r(0.03f, 0.06f); max[i] = r(7f, 10f);
        }
        life[i] = max[i];
    }

    void render(ModelBatch b, Environment env, float camS, Tuning T, Course c) {
        for (int i = 0; i < MAX; i++) {
            if (life[i] <= 0f) continue;
            float k = 1f - life[i] / max[i], a = Math.min(1f, Math.min(k / 0.2f, (1f - k) / 0.3f));           // fade in, hold, fade out
            if (kind[i] == 3) a *= 0.55f + 0.45f * MathUtils.sin(clock * 1.6f + ph[i]);                         // fireflies glow slowly up and down (never a flash)
            else if (kind[i] == 1) a *= 0.75f + 0.25f * MathUtils.sin(clock * 2.2f + ph[i]);                    // ice glints
            if (a <= 0.01f) continue;
            float phi = c.dsWrap(s[i], camS) / T.radius, rr = T.radius - dz[i];
            ModelInstance m = inst[i];
            m.transform.idt().translate(rr * MathUtils.sin(phi), y[i], -T.radius + rr * MathUtils.cos(phi))
                    .rotate(0, 1, 0, phi * MathUtils.radiansToDegrees + ph[i] * 30f).scale(size[i], size[i], size[i]);
            tmp.set(COL[kind[i]]);
            diff[i].color.set(tmp); emis[i].color.set(tmp.r * 0.85f, tmp.g * 0.85f, tmp.b * 0.85f, 1f); blend[i].opacity = a * 0.8f;
            b.render(m, env);
        }
    }
}

package com.hotatticgames.climbup.render;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.math.MathUtils;
import com.hotatticgames.climbup.sim.Course;
import com.hotatticgames.climbup.sim.Tuning;

/** Small cube particles in arc/height space (landing dust, bounce rings, crumble debris, checkpoint sparkle). */
public final class Particles {
    private static final int MAX = 56;
    private final float[] s = new float[MAX], y = new float[MAX], vs = new float[MAX], vy = new float[MAX], life = new float[MAX], max = new float[MAX], size = new float[MAX], g = new float[MAX];
    private final Color[] col = new Color[MAX];
    private final ModelInstance[] inst = new ModelInstance[MAX];
    private final ColorAttribute[] diff = new ColorAttribute[MAX], emis = new ColorAttribute[MAX];
    private int next;
    public float density = 1f;

    public Particles(Models m) {
        for (int i = 0; i < MAX; i++) {
            inst[i] = new ModelInstance(m.box);
            diff[i] = ColorAttribute.createDiffuse(Color.WHITE); emis[i] = ColorAttribute.createEmissive(Color.BLACK);
            inst[i].materials.get(0).set(new BlendingAttribute(1f), diff[i], emis[i]);
            col[i] = new Color(1, 1, 1, 1);
        }
    }

    public void burst(float arc, float yy, int count, Color c, float speed, float up, float sz, float gravity, float life0) {
        count = Math.max(1, Math.round(count * density));
        for (int k = 0; k < count; k++) {
            int i = next; next = (next + 1) % MAX;
            s[i] = arc + MathUtils.random(-0.25f, 0.25f); y[i] = yy;
            vs[i] = MathUtils.random(-speed, speed); vy[i] = MathUtils.random(0.3f, 1f) * up;
            life[i] = max[i] = life0 * MathUtils.random(0.7f, 1.2f); size[i] = sz * MathUtils.random(0.7f, 1.3f); g[i] = gravity;
            col[i].set(c);
        }
    }

    public void update(float dt) {
        for (int i = 0; i < MAX; i++) if (life[i] > 0) {
            life[i] -= dt; vy[i] -= g[i] * dt; s[i] += vs[i] * dt; y[i] += vy[i] * dt;
        }
    }

    public void render(ModelBatch b, Environment env, float camS, Tuning T, Course c) {
        for (int i = 0; i < MAX; i++) {
            if (life[i] <= 0) continue;
            float a = Math.min(1f, life[i] / max[i] * 1.6f);
            float phi = c.dsWrap(s[i], camS) / T.radius;
            ModelInstance m = inst[i];
            m.transform.idt().translate(T.radius * MathUtils.sin(phi), y[i], -T.radius + T.radius * MathUtils.cos(phi) + 0.8f)
                    .rotate(0, 1, 0, phi * MathUtils.radiansToDegrees).rotate(0.3f, 1f, 0.2f, life[i] * 240f).scale(size[i] * a, size[i] * a, size[i] * a);
            diff[i].color.set(col[i]); emis[i].color.set(col[i].r * 0.5f, col[i].g * 0.5f, col[i].b * 0.5f, 1f);
            b.render(m, env);
        }
    }
}

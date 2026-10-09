package com.hotatticgames.climbup.render;

import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.math.MathUtils;
import com.hotatticgames.climbup.sim.Course;
import com.hotatticgames.climbup.sim.Element;
import com.hotatticgames.climbup.sim.Tuning;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;

/**
 * Pure scenery (never touches the simulation): soft 3D clouds scattered through the tower, deterministic per seed. Clouds in the
 * player's plane are bursts waiting to happen - jump or run through one and it breaks into little puffs that drift away and fade.
 * Big hazy clouds sit behind the play plane for depth. Quaternius cloud models (CC0).
 */
final class Clouds {
    private static final float CELL_S = 15f, CELL_Y = 10f, SPAN = 34f;
    private static final String[] MODELS = {"cloud_1", "cloud_2", "cloud_3"};

    private static final class Cloud { float s, y, dz, scale, yaw; int model; ModelInstance inst; }
    private static final class Puff { float s, y, dz, vs, vy, vz, scale, age, life, spin, yaw; int model; ModelInstance inst; }

    private final Models models; private final Tuning T; private final Course course; private final long seed;
    private final HashMap<Long, Cloud> live = new HashMap<>();
    private final HashSet<Long> checked = new HashSet<>(), broken = new HashSet<>();
    private final ArrayList<Puff> puffs = new ArrayList<>();
    public int brokenTotal, brokenThisFrame;
    private double originY, originS;        // floating origin: cells are absolute, so the scenery stays put when the origin moves
    private final ColorAttribute tintAttr = ColorAttribute.createDiffuse(1f, 1f, 1f, 1f);

    /** The world origin moved: carry the live clouds and puffs along (their cells are absolute and unaffected). */
    void setOrigin(double oy, double os) {
        if (oy == originY && os == originS) return;
        float dy = (float) (oy - originY), ds = (float) (os - originS);
        for (Cloud c : live.values()) { c.y -= dy; c.s -= ds; }
        for (Puff p : puffs) { p.y -= dy; p.s -= ds; }
        originY = oy; originS = os;
    }

    Clouds(Models models, Tuning t, Course course) { this.models = models; T = t; this.course = course; seed = course.seed * 0x9E3779B97F4A7C15L; }

    private static long key(int cx, int cy) { return ((long) cx << 32) ^ (cy & 0xffffffffL); }
    private long hash(int cx, int cy, int salt) {
        long h = seed ^ (cx * 0x632BE59BD9B4E019L) ^ (cy * 0x85157AF5L) ^ (salt * 0x2545F4914F6CDD1DL);
        h ^= h >>> 33; h *= 0xff51afd7ed558ccdL; h ^= h >>> 33; h *= 0xc4ceb9fe1a85ec53L; h ^= h >>> 33;
        return h;
    }
    private float rnd(long h, int n) { return ((h >>> (n * 8)) & 0xffff) / 65535f; }

    private ModelInstance instance(int model, float alpha) {
        ModelInstance m = new ModelInstance(models.pack(MODELS[model]));
        m.materials.get(0).set(new BlendingAttribute(alpha), ColorAttribute.createDiffuse(1f, 1f, 1f, 1f), ColorAttribute.createEmissive(0.30f, 0.30f, 0.32f, 1f));
        return m;
    }

    /** Creates (or decides against) the cloud of one cell. Interactive clouds avoid every platform, rope and hazard so nothing is ever hidden. */
    private void spawn(int cx, int cy, int lo, int hi) {
        long k = key(cx, cy);
        if (!checked.add(k)) return;
        long h = hash(cx, cy, 1);
        float roll = rnd(h, 0);
        if (roll > 0.62f) return;
        Cloud c = new Cloud();
        c.s = (float) ((cx + 0.1 + 0.8 * rnd(h, 1)) * CELL_S - originS); c.y = (float) ((cy + 0.1 + 0.8 * rnd(h, 2)) * CELL_Y - originY);
        c.model = (int) (rnd(h, 3) * 2.999f); c.yaw = rnd(h, 4) * 40f - 20f;
        boolean back = rnd(h, 5) < 0.45f;
        if (back) { c.dz = 5f + 9f * rnd(h, 6); c.scale = 0.9f + 1.1f * rnd(h, 7); }
        else { c.dz = 0f; c.scale = 0.38f + 0.62f * rnd(h, 7); if (!clear(c, lo, hi)) return; }
        live.put(k, c);
    }

    private boolean clear(Cloud c, int lo, int hi) {
        float r = 1.9f * c.scale + 1.0f;
        for (int i = Math.max(0, lo); i <= Math.min(course.size() - 1, hi); i++) {
            Element e = course.get(i);
            float ext = e.type == Element.Type.ROPE ? 0.8f : (e.type == Element.Type.CABLE ? e.w * 0.5f : e.w * 0.5f + e.amp);
            float vlo = e.type == Element.Type.ROPE ? e.y - e.len : e.y - 1f, vhi = e.y + (e.type == Element.Type.SWING ? e.len + 1f : 3.2f) + (e.type == Element.Type.MOVE_V ? e.amp : 0f);
            if (Math.abs(course.dsWrap(e.s, c.s)) < r + ext + 0.6f && c.y + r > vlo && c.y - r < vhi) return false;
        }
        for (Element h : course.hazards) {
            if (h.y < c.y - 12f || h.y > c.y + 14f) continue;
            float ext = h.type == Element.Type.CANNON ? h.len + 1f : h.amp + Math.max(h.w * 0.5f, 1.2f);
            float hs = h.type == Element.Type.CANNON ? h.s + h.dir * h.len * 0.5f : h.s;
            if (Math.abs(course.dsWrap(hs, c.s)) < r + ext + 0.6f && Math.abs(h.y + (h.type == Element.Type.SPIKE_DROP ? h.amp * 0.5f : 1f) - c.y) < r + 3.5f) return false;
        }
        return true;
    }

    /** Spawns clouds around the camera, breaks the ones the player is flying through, and advances the drifting puffs. */
    void update(float camS, float camY, float playerS, float playerY, int lo, int hi, float dt, boolean reduced, int quality) {
        brokenThisFrame = 0;
        if (quality > 0) {
            int cx0 = (int) Math.floor((camS + originS - SPAN) / CELL_S), cx1 = (int) Math.floor((camS + originS + SPAN) / CELL_S);
            int cy0 = (int) Math.floor((camY + originY - 16.0) / CELL_Y), cy1 = (int) Math.floor((camY + originY + 18.0) / CELL_Y);
            for (int cx = cx0; cx <= cx1; cx++) for (int cy = cy0; cy <= cy1; cy++) if (!broken.contains(key(cx, cy))) spawn(cx, cy, lo, hi);
        }
        // break the clouds in the player's plane that the player touches
        for (Iterator<java.util.Map.Entry<Long, Cloud>> it = live.entrySet().iterator(); it.hasNext(); ) {
            java.util.Map.Entry<Long, Cloud> en = it.next(); Cloud c = en.getValue();
            if (Math.abs(course.dsWrap(c.s, camS)) > SPAN + 12f || Math.abs(c.y - camY) > 30f) { it.remove(); checked.remove(en.getKey()); continue; }
            if (c.dz != 0f) continue;
            float rx = 1.55f * c.scale, ry = 1.0f * c.scale;
            float dx = course.dsWrap(playerS, c.s), dy = (playerY + 0.7f) - (c.y + 0.8f * c.scale);
            if (dx * dx / (rx * rx + 0.3f) + dy * dy / (ry * ry + 1.0f) < 1f) { burst(c, playerS, reduced); broken.add(en.getKey()); it.remove(); }
        }
        for (int i = puffs.size() - 1; i >= 0; i--) {
            Puff p = puffs.get(i); p.age += dt;
            if (p.age >= p.life) { puffs.remove(i); continue; }
            p.vs *= 1f - 0.8f * dt; p.vy += 0.35f * dt; p.s += p.vs * dt; p.y += p.vy * dt; p.dz += p.vz * dt; p.yaw += p.spin * dt;
        }
        if (broken.size() > 600) broken.clear();
        if (checked.size() > 5000) checked.clear();
    }

    private void burst(Cloud c, float playerS, boolean reduced) {
        brokenTotal++; brokenThisFrame++;
        int n = reduced ? 6 : 10 + (int) (c.scale * 8f);
        float side = Math.signum(course.dsWrap(c.s, playerS));
        for (int i = 0; i < n && puffs.size() < 90; i++) {
            Puff p = new Puff();
            float a = MathUtils.random(MathUtils.PI2);
            p.s = c.s + MathUtils.cos(a) * 1.2f * c.scale; p.y = c.y + 0.7f * c.scale + MathUtils.sin(a) * 0.6f * c.scale; p.dz = MathUtils.random(-0.5f, 0.5f);
            p.vs = MathUtils.cos(a) * MathUtils.random(1.2f, 3.2f) + side * 0.8f + (reduced ? 0f : MathUtils.random(-0.5f, 0.5f)); p.vy = MathUtils.sin(a) * MathUtils.random(0.8f, 2.4f); p.vz = MathUtils.random(-0.6f, 0.6f);
            p.scale = (0.10f + 0.15f * MathUtils.random()) * (0.7f + c.scale); p.life = MathUtils.random(1.5f, 2.6f); p.model = MathUtils.random(2); p.spin = MathUtils.random(-60f, 60f); p.yaw = MathUtils.random(360f);
            p.inst = instance(p.model, 1f);
            puffs.add(p);
        }
    }

    interface Placer { void place(ModelInstance inst, float arc, float y, float dz, float sx, float sy, float sz, float yaw); }

    void render(ModelBatch batch, Environment env, Placer placer, float camS, float camY) {
        for (Cloud c : live.values()) {
            if (Math.abs(course.dsWrap(c.s, camS)) > SPAN || Math.abs(c.y - camY) > 22f) continue;
            if (c.inst == null) c.inst = instance(c.model, c.dz > 0f ? 0.78f : 0.96f);
            placer.place(c.inst, c.s, c.y, c.dz, c.scale, c.scale, c.scale, c.yaw);
            batch.render(c.inst, env);
        }
        for (Puff p : puffs) {
            float k = p.age / p.life, a = k < 0.15f ? k / 0.15f : 1f - (k - 0.15f) / 0.85f;
            ((BlendingAttribute) p.inst.materials.get(0).get(BlendingAttribute.Type)).opacity = Math.max(0f, a) * 0.95f;
            placer.place(p.inst, p.s, p.y, p.dz, p.scale * (1f + 0.5f * k), p.scale * (1f + 0.5f * k), p.scale * (1f + 0.5f * k), p.yaw);
            batch.render(p.inst, env);
        }
    }
}

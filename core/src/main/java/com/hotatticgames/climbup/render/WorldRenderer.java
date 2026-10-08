package com.hotatticgames.climbup.render;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Disposable;
import com.hotatticgames.climbup.sim.Course;
import com.hotatticgames.climbup.sim.Element;
import com.hotatticgames.climbup.sim.Sim;
import com.hotatticgames.climbup.sim.Tuning;

/**
 * Draws the climb as a 2.5D cylindrical world. There is no cylinder mesh: every element is placed on a circle of radius R
 * around a hidden axis, rotated so the player's own arc position always sits at screen centre (the camera never moves
 * sideways, the tower turns to meet it).
 */
public final class WorldRenderer implements Disposable {
    private static final class Part {
        ModelInstance inst; float du, dy, dz, sx = 1, sy = 1, sz = 1, yaw, roll; Color color; boolean fall; boolean tinted = true; ColorAttribute tintAttr;
    }
    private static final class Vis { Part[] parts; Element e; boolean built; }

    private final Tuning T;
    private final Course course;
    private final Models models;
    private final ModelBatch batch = new ModelBatch(new com.badlogic.gdx.graphics.g3d.utils.DefaultShaderProvider(boneConfig()));
    private final PerspectiveCamera cam = new PerspectiveCamera(40f, 16, 9);
    private final Environment env = new Environment();
    private final DirectionalLight sun = new DirectionalLight();
    private final ShapeRenderer shapes = new ShapeRenderer();
    private final SpriteBatch sb = new SpriteBatch();
    private HeroRig hero;
    private int character;
    private final java.util.ArrayList<Vis> vis = new java.util.ArrayList<>();
    private int pruneCursor;
    private final Background bg = new Background();
    private Clouds clouds;
    public final Particles particles;
    private static final float CAM_DIST = Float.parseFloat(System.getProperty("climb.camDist", "9.6"));
    public int quality = 2;           // 0 low, 1 medium, 2 high
    public boolean reducedMotion;

    private float camY, camShake, shakeT;
    private final Color skyTop = new Color(), skyBot = new Color(), tint = new Color(), amb = new Color();
    private final ModelInstance shadow;
    private float renderS, renderY;
    public float lastCamS;
    public int cloudsBroken;          // PlayScreen reads and clears this to play the poof sound
    private final Array<ModelInstance> tmp = new Array<>();

    private static com.badlogic.gdx.graphics.g3d.shaders.DefaultShader.Config boneConfig() {
        com.badlogic.gdx.graphics.g3d.shaders.DefaultShader.Config c = new com.badlogic.gdx.graphics.g3d.shaders.DefaultShader.Config();
        c.numBones = 32;   // the hero rig has 29 bones
        return c;
    }

    public WorldRenderer(Tuning t, Course c, Models models, int quality) { this(t, c, models, quality, 0); }

    public WorldRenderer(Tuning t, Course c, Models models, int quality, int character) {
        this.T = t; this.course = c; this.models = models; this.quality = quality;
        this.character = character; hero = new HeroRig(character);
        dip = new float[Math.max(16, c.size())]; dipV = new float[Math.max(16, c.size())];
        syncVis();
        env.set(new ColorAttribute(ColorAttribute.AmbientLight, 0.62f, 0.62f, 0.66f, 1f));
        sun.set(1f, 0.97f, 0.9f, -0.5f, -0.9f, -0.6f);
        env.add(sun);
        env.set(new ColorAttribute(ColorAttribute.Fog, 0.8f, 0.9f, 1f, 1f));
        cam.near = 0.5f; cam.far = 75f;
        for (String n : new String[]{"block-grass", "block-grass-low", "block-grass-long", "block-grass-low-long", "block-snow", "block-snow-low", "block-snow-long", "block-snow-low-long",
                "block-moving", "block-moving-blue", "platform-fortified", "flag", "chest", "jewel", "tree", "tree-pine-small", "flowers", "mushrooms", "rocks", "plant",
                "tree-pine-snow-small", "tree-snow", "stones", "sign"}) models.obj(n);
        particles = new Particles(models);
        shadow = new ModelInstance(models.disc);
        shadow.materials.get(0).set(ColorAttribute.createDiffuse(0f, 0f, 0f, 1f), new BlendingAttribute(0.28f));
        shadow.materials.get(0).set(ColorAttribute.createEmissive(0, 0, 0, 1));
    }

    /** The course grows while climbing (endless mode): keep per-element visuals and spring state in step with it. */
    private void syncVis() {
        int n = course.size();
        while (vis.size() < n) { Vis v = new Vis(); v.e = course.get(vis.size()); vis.add(v); }
        if (dip.length < n) { int m = Math.max(n, dip.length * 3 / 2); dip = java.util.Arrays.copyOf(dip, m); dipV = java.util.Arrays.copyOf(dipV, m); }
    }

    /** Switches the player model (Settings: CHARACTER) without rebuilding the world. */
    public void setCharacter(int c) { if (c == character) return; character = c; hero.dispose(); hero = new HeroRig(c); }

    public void resize(int w, int h) { cam.viewportWidth = w; cam.viewportHeight = h; cam.update(); bg.resize(w, h); }

    public void snapCamera(Sim s) { camY = s.y + 0.3f; }

    public void shake(float amount) { if (!reducedMotion) { camShake = Math.max(camShake, amount); shakeT = 0.25f; } }

    // ------------------------------------------------------------------ visuals per element

    private ModelInstance inst(Model m) { return new ModelInstance(m); }

    private Part part(Model m, float du, float dy, float dz, float sx, float sy, float sz) {
        Part p = new Part(); p.inst = new ModelInstance(m); p.du = du; p.dy = dy; p.dz = dz; p.sx = sx; p.sy = sy; p.sz = sz;
        p.tintAttr = ColorAttribute.createDiffuse(1f, 1f, 1f, 1f);
        for (com.badlogic.gdx.graphics.g3d.Material mat : p.inst.materials) mat.set(p.tintAttr);
        return p;
    }

    private Part boxPart(float du, float dy, float dz, float sx, float sy, float sz, Color col) {
        Part p = part(models.box, du, dy, dz, sx, sy, sz); p.color = col; p.tinted = false;
        p.tintAttr.color.set(col); p.tinted = false; p.fall = true; p.color = col;      // fixed colour, applied via the shared attribute
        p.inst.materials.get(0).set(ColorAttribute.createEmissive(col.r * 0.15f, col.g * 0.15f, col.b * 0.15f, 1f));
        return p;
    }

    private static int hash(int a, int b) { int h = a * 73856093 ^ b * 19349663; h ^= h >>> 13; h *= 0x5bd1e995; h ^= h >>> 15; return h & 0x7fffffff; }

    private void build(int idx) {
        Vis v = vis.get(idx); Element e = v.e; v.built = true;
        Array<Part> ps = new Array<>();
        boolean snow = e.zone == 1 || e.zone == 3;
        String base = snow ? "block-snow" : "block-grass";
        switch (e.type) {
            case STATIC: case GOAL: case CRUMBLE: {
                int w = Math.max(1, Math.round(e.w));
                boolean thick = w >= 5 || e.type == Element.Type.GOAL;
                String name = thick ? base : base + "-low";
                Model m = models.obj(name), ml = models.obj(name + "-long");
                float h = thick ? 1f : 0.5f;
                float left = -w / 2f; int pos = 0;
                while (pos < w) {
                    boolean two = w - pos >= 2;
                    float cx = left + pos + (two ? 1f : 0.5f);
                    Part p = part(two ? ml : m, cx, -h, 0f, two ? 0.96f : 0.96f, 1f, 1.7f);
                    if (e.type == Element.Type.CRUMBLE) { p.color = new Color(1f, 0.74f, 0.52f, 1f); p.fall = true; }
                    ps.add(p);
                    pos += two ? 2 : 1;
                }
                if (e.type == Element.Type.CRUMBLE) {
                    // cracks: small dark pebbles on the surface hint that it will fall
                    ps.add(boxPart(-0.35f, 0.02f, 0.2f, 0.22f, 0.05f, 0.18f, new Color(0.35f, 0.25f, 0.18f, 1f)));
                    ps.add(boxPart(0.4f, 0.02f, -0.1f, 0.18f, 0.05f, 0.22f, new Color(0.35f, 0.25f, 0.18f, 1f)));
                }
                if (thick && e.type != Element.Type.GOAL && quality > 0) decorate(ps, e, idx, w, snow);
                else if (!thick && e.type == Element.Type.STATIC && quality > 0 && hash(idx, 7) % 3 == 0) decorate(ps, e, idx, w, snow);
                if (e.checkpoint) {
                    Model f = models.obj("flag");
                    Part fp = part(f, -(w / 2f) + 0.55f, 0f, 0.3f, 1.6f, 1.6f, 1.6f); fp.tinted = false; ps.add(fp);
                }
                if (e.type == Element.Type.GOAL) {
                    Part fp = part(models.obj("flag"), 0f, 0f, 0.2f, 4.2f, 4.2f, 4.2f); fp.tinted = false; ps.add(fp);
                    Part c1 = part(models.obj("chest"), 1.8f, 0f, 0.2f, 1.4f, 1.4f, 1.4f); c1.tinted = false; ps.add(c1);
                    Part k1 = part(models.obj("jewel"), -1.8f, 0.2f, 0f, 2.2f, 2.2f, 2.2f); k1.tinted = false; ps.add(k1);
                }
                break;
            }
            case MOVE_H: case MOVE_V: {
                int w = Math.max(1, Math.round(e.w));
                Model m = models.obj(e.type == Element.Type.MOVE_H ? "block-moving-blue" : "block-moving");
                for (int i = 0; i < w; i++) { Part p = part(m, i - (w - 1) / 2f, -0.3f, 0f, 0.95f, 1f, 1.7f); p.tinted = false; ps.add(p); }
                // rails/arrows hint the travel direction
                Color rail = new Color(0.25f, 0.28f, 0.36f, 1f);
                if (e.type == Element.Type.MOVE_H) {
                    // drawn per-frame as a static track at the element's centre (see renderTrack)
                }
                break;
            }
            case SWING: {
                int w = Math.max(1, Math.round(e.w));
                Model m = models.obj("platform-fortified");
                for (int i = 0; i < w; i++) { Part p = part(m, i - (w - 1) / 2f, -0.2f, 0f, 0.95f, 1f, 1.7f); p.tinted = false; ps.add(p); }
                break;
            }
            case PAD: {
                Color base2 = new Color(0.20f, 0.30f, 0.65f, 1f), plate = new Color(1f, 0.82f, 0.18f, 1f), coil = new Color(0.8f, 0.82f, 0.9f, 1f);
                ps.add(boxPart(0, -0.18f, 0, 1.9f, 0.18f, 1.3f, base2));
                ps.add(boxPart(0, -0.02f, 0, 1.85f, 0.14f, 1.25f, plate));
                Part c = boxPart(0, -0.10f, 0, 0.5f, 0.1f, 0.5f, coil); ps.add(c);
                break;
            }
            case SPRING: {
                float a = e.amp, sa = (float) Math.sin(a), ca = (float) Math.cos(a), deg = -a * MathUtils.radiansToDegrees;
                Color plate = new Color(0.25f, 0.27f, 0.34f, 1f), coil = new Color(0.78f, 0.80f, 0.86f, 1f), cap = new Color(0.85f, 0.16f, 0.16f, 1f);
                ps.add(boxPart(0, -0.12f, 0, 1.9f, 0.14f, 1.3f, plate));
                float[] hs = {0.12f, 0.30f, 0.48f};
                for (float h : hs) { Part c1 = boxPart(h * sa, -0.0f + h * ca, 0, 0.62f, 0.09f, 0.62f, coil); c1.roll = deg; ps.add(c1); }
                Part cp = boxPart(0.66f * sa, 0.66f * ca, 0, 1.2f, 0.26f, 1.1f, cap); cp.roll = deg; ps.add(cp);
                Part arrow = part(models.pack("arrow_up"), 1.3f * sa, 1.6f * ca, 0.2f, 0.33f, 0.33f, 0.33f); arrow.roll = deg; arrow.tinted = false; ps.add(arrow);
                break;
            }
            case ROPE: {
                Color rope = new Color(0.86f, 0.70f, 0.42f, 1f), beam = new Color(0.45f, 0.30f, 0.18f, 1f);
                ps.add(boxPart(0, -e.len * 0.5f, 0, 0.11f, e.len, 0.11f, rope));
                int knots = (int) e.len;
                for (int k = 1; k <= knots; k++) ps.add(boxPart(0, -k + 0.5f, 0, 0.2f, 0.12f, 0.2f, new Color(0.7f, 0.52f, 0.28f, 1f)));
                ps.add(boxPart(0, 0.12f, 0, 2.4f, 0.24f, 0.7f, beam));
                ps.add(boxPart(-1.1f, -0.9f, 0, 0.2f, 1.7f, 0.2f, beam));
                ps.add(boxPart(1.1f, -0.9f, 0, 0.2f, 1.7f, 0.2f, beam));
                break;
            }
            case CABLE: {
                Color steel = new Color(0.16f, 0.17f, 0.22f, 1f), post = new Color(0.45f, 0.30f, 0.18f, 1f), hook = new Color(1f, 0.55f, 0.15f, 1f);
                int seg = Math.max(1, (int) Math.ceil(e.w));
                for (int i = 0; i < seg; i++) ps.add(boxPart(-e.w / 2f + (i + 0.5f) * e.w / seg, 0f, 0f, e.w / seg + 0.05f, 0.07f, 0.07f, steel));
                for (int i = 0; i <= 1; i++) {
                    float du = (i == 0 ? -1 : 1) * e.w / 2f;
                    ps.add(boxPart(du, -1.2f, 0, 0.22f, 2.6f, 0.22f, post));
                    ps.add(boxPart(du, 0.05f, 0, 0.3f, 0.3f, 0.3f, hook));
                }
                break;
            }
        }
        v.parts = new Part[ps.size];
        for (int i = 0; i < ps.size; i++) v.parts[i] = ps.get(i);
    }

    private void decorate(Array<Part> ps, Element e, int idx, int w, boolean snow) {
        int h = hash(idx, 11);
        String[] a = snow ? new String[]{"tree-pine-snow-small", "rocks", "stones", "tree-snow", "sign"} : new String[]{"tree", "tree-pine-small", "flowers", "mushrooms", "rocks", "plant"};
        int n = w >= 5 ? 2 : 1;
        for (int i = 0; i < n; i++) {
            String name = a[(h >> (i * 4)) % a.length];
            boolean tall = name.startsWith("tree");
            float side = (i == 0 ? -1 : 1) * (w / 2f - 0.7f);
            if (n == 1) side = ((h >> 9) % 2 == 0 ? -1 : 1) * (w / 2f - 0.5f);
            Part p = part(models.obj(name), side, 0f, 0.9f + (tall ? 0.6f : 0f), 1.35f, 1.35f, 1.35f);
            p.tinted = true; ps.add(p);
        }
    }

    // ------------------------------------------------------------------ frame

    private float wrapDiff(float a, float b) { return course.dsWrap(a, b); }

    private void place(ModelInstance inst, float arc, float y, float dz, float camS, float sx, float sy, float sz, float yawExtraDeg) { place(inst, arc, y, dz, camS, sx, sy, sz, yawExtraDeg, 0f); }

    private void place(ModelInstance inst, float arc, float y, float dz, float camS, float sx, float sy, float sz, float yawExtraDeg, float rollDeg) {
        float phi = wrapDiff(arc, camS) / T.radius;
        float r = T.radius - dz;
        inst.transform.idt().translate(r * MathUtils.sin(phi), y, -T.radius + r * MathUtils.cos(phi))
                .rotate(0, 1, 0, phi * MathUtils.radiansToDegrees + yawExtraDeg).rotate(0, 0, 1, rollDeg).scale(sx, sy, sz);
    }

    private boolean visible(float arc, float y, float camS, float margin) {
        float d = wrapDiff(arc, camS);
        return Math.abs(d) < 1.3f * T.radius * 1.0f + margin && Math.abs(y - camY) < 16f;
    }

    public void render(Sim sim, float alpha, float dt, float time, boolean showPlayer) {
        frameDt = Math.min(dt, 0.05f);
        // interpolated player position (render only; simulation stays on the fixed step)
        float ps = sim.teleported ? sim.s : sim.ps0 + course.dsWrap(sim.s, sim.ps0) * alpha;
        float py = sim.teleported ? sim.y : sim.py0 + (sim.y - sim.py0) * alpha;
        renderS = ps; renderY = py;
        lastCamS = ps;
        // vertical camera: follow with smoothing, snap on respawn
        float lead = reducedMotion ? 0f : MathUtils.clamp(sim.vy * 0.10f, -2.2f, 1.8f);   // camera leads fast rises and drops
        float target = py + 0.3f + lead;
        if (sim.teleported) camY = target;
        else camY += (target - camY) * Math.min(1f, (target > camY ? 7f : 10f) * dt);
        float zf = camY / (T.rampHeight / 4f);
        float zoneF = Math.min(3.999f, ((zf % 4f) + 4f) % 4f);        // the four worlds repeat for ever
        Palette.blend(Palette.SKY_TOP, zoneF, skyTop); Palette.blend(Palette.SKY_BOT, zoneF, skyBot);
        Palette.blend(Palette.TINT, zoneF, tint); Palette.blend(Palette.AMBIENT, zoneF, amb);
        ((ColorAttribute) env.get(ColorAttribute.AmbientLight)).color.set(amb);
        ((ColorAttribute) env.get(ColorAttribute.Fog)).color.set(skyBot).lerp(skyTop, 0.25f);

        // camera
        float sh = 0;
        if (shakeT > 0 && !reducedMotion) { shakeT -= dt; sh = camShake * MathUtils.sin(time * 70f) * (shakeT / 0.25f); } else camShake = 0;
        fovV += (-90f * fovK - 11f * fovV) * dt; fovK += fovV * dt; cam.fieldOfView = 40f + (reducedMotion ? 0f : fovK);
        for (int di = 0; di < dip.length; di++) if (dip[di] != 0 || dipV[di] != 0) { dipV[di] += (-160f * dip[di] - 12f * dipV[di]) * dt; dip[di] += dipV[di] * dt; if (Math.abs(dip[di]) < 0.002f && Math.abs(dipV[di]) < 0.02f) { dip[di] = 0; dipV[di] = 0; } }
        if (quality > 0 && !reducedMotion) ambientMotes(dt, zoneF, ps);
        cam.position.set(sh * 0.1f, camY + (CAM_DIST < 6f ? -0.6f : 2.2f) + sh * 0.1f, CAM_DIST);
        cam.lookAt(0f, camY + (CAM_DIST < 6f ? -1.15f : 1.15f), -0.4f);
        cam.up.set(0, 1, 0);
        cam.update();

        Gdx.gl.glViewport(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
        Gdx.gl.glClearColor(skyBot.r, skyBot.g, skyBot.b, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
        bg.render(sb, shapes, skyTop, skyBot, ps, camY, zoneF, T.circumference(), reducedMotion, time);
        Gdx.gl.glClear(GL20.GL_DEPTH_BUFFER_BIT);

        batch.begin(cam);
        syncVis();
        int lo = Math.max(0, sim.winLo), hi = Math.min(vis.size() - 1, sim.winHi);
        if (clouds == null) clouds = new Clouds(models, T, course);
        clouds.update(ps, camY, ps, py, lo, hi, dt, reducedMotion, quality);
        cloudsBroken += clouds.brokenThisFrame;
        clouds.render(batch, env, (inst, arc, yy, dz, sx, sy, sz, yaw) -> place(inst, arc, yy, dz, ps, sx, sy, sz, yaw), ps, camY);
        for (int i = lo; i <= hi; i++) {
            Vis v = vis.get(i); Element e = v.e;
            float es = sim.es1[i] , ey = sim.ey1[i];
            if (e.isMoving()) { // interpolate moving platforms
                es = sim.es0[i] + course.dsWrap(sim.es1[i], sim.es0[i]) * alpha; ey = sim.ey0[i] + (sim.ey1[i] - sim.ey0[i]) * alpha;
            }
            float vy = e.type == Element.Type.ROPE ? e.y - e.len * 0.5f : ey;
            if (!visible(es, vy, ps, e.type == Element.Type.CABLE ? e.w * 0.5f : 3f)) continue;
            if (!v.built) build(i);
            drawElement(sim, i, v, es, ey, ps, time);
        }
        for (int k = 0, cnt = sim.hz == null ? course.hazards.size() : sim.hz.length; k < cnt; k++) { int hi2 = sim.hz == null ? k : sim.hz[k]; drawHazard(course.hazards.get(hi2), sim.time + alpha * Sim.DT, ps, time, hi2 < sim.featDone.length && sim.featDone[hi2]); }
        for (int q = 0; q < 24 && pruneCursor < lo; q++, pruneCursor++) { Vis pv = vis.get(pruneCursor); pv.parts = null; pv.built = false; }
        drawFlung(dt, ps);
        if (showPlayer) drawPlayer(sim, dt, time, ps, py, alpha);
        particles.render(batch, env, ps, T, course);
        batch.end();
    }

    private void drawElement(Sim sim, int i, Vis v, float es, float ey, float camS, float time) {
        Element e = v.e;
        float shakeX = 0, fallY = 0;
        boolean gone = sim.gone[i];
        float crumble = sim.crumbleT[i];
        for (Part p : v.parts) {
            float du = p.du, dy = p.dy, extraYaw = 0f; float sy = p.sy;
            float y = e.isPlatform() ? ey - Math.max(-0.1f, Math.min(0.22f, dip[i])) : ey;
            switch (e.type) {
                case ROPE: y = e.y; break;
                case SWING: {
                    // platform hangs from a pivot: ropes are drawn separately below
                    break;
                }
                default: break;
            }
            if (e.type == Element.Type.CRUMBLE) {
                if (gone) {
                    float el = T.crumbleRespawn - sim.goneT[i];
                    if (el > 1.1f) continue;
                    fallY = -0.5f * 30f * el * el; extraYaw = el * 40f * (p.du >= 0 ? 1 : -1);
                } else if (crumble >= 0) {
                    shakeX = 0.05f * MathUtils.sin(time * 90f + p.du * 5f);
                }
            }
            if (e.type == Element.Type.PAD && p.sy < 0.2f && p.du == 0 && p.dy < -0.05f) { // coil squash
                float sq = sim.padSquash[i] / 0.25f; sy = p.sy * (1f - 0.6f * sq);
            }
            if (e.type == Element.Type.PAD && p.dy > -0.05f) { float sq = sim.padSquash[i] / 0.25f; dy = p.dy - 0.18f * sq; }
            if (p.tintAttr != null) {
                if (p.color != null && p.fall) p.tintAttr.color.set(p.color);          // crumbling tiles keep their warm tint
                else if (p.tinted) p.tintAttr.color.set(tint);
                else p.tintAttr.color.set(Color.WHITE);
            }
            place(p.inst, es + du + shakeX, y + dy + fallY, p.dz, camS, p.sx, sy, p.sz, extraYaw, p.roll);
            batch.render(p.inst, env);
        }
        if (e.type == Element.Type.SWING) drawSwingRopes(e, es, ey, camS);
        if (e.type == Element.Type.MOVE_V) drawRail(e, camS, true);
        if (e.type == Element.Type.MOVE_H) drawRail(e, camS, false);
    }


    // ------------------------------------------------------------------ hazards (Quaternius Ultimate Platformer Pack, CC0)

    private static final float PK = 0.37f;                    // pack units -> game units (same scale as the hero)
    private final java.util.HashMap<String, ModelInstance> packInst = new java.util.HashMap<>();
    private static final float CANNON_YAW = -90f;

    private ModelInstance pack(String name) {
        ModelInstance m = packInst.get(name);
        if (m == null) { m = new ModelInstance(models.pack(name)); packInst.put(name, m); }
        return m;
    }

    private void drawPack(String name, float arc, float y, float dz, float camS, float sx, float sy, float sz, float yaw, float roll) {
        ModelInstance m = pack(name);
        float phi = wrapDiff(arc, camS) / T.radius, r = T.radius - dz;
        m.transform.idt().translate(r * MathUtils.sin(phi), y, -T.radius + r * MathUtils.cos(phi))
                .rotate(0, 1, 0, phi * MathUtils.radiansToDegrees + yaw).rotate(0, 0, 1, roll).scale(sx, sy, sz);
        batch.render(m, env);
    }

    static final float[][] KEY_RGB = {{0.95f, 0.22f, 0.20f}, {0.25f, 0.55f, 1f}, {0.30f, 0.85f, 0.35f}, {1f, 0.82f, 0.18f}};
    private final java.util.HashMap<String, ModelInstance> colorInst = new java.util.HashMap<>();

    /** A pack model with its flag / key material recoloured for one of the four key colours. */
    private ModelInstance colored(String name, int color) {
        String k = name + color;
        ModelInstance m = colorInst.get(k);
        if (m == null) {
            m = new ModelInstance(models.pack(name));
            float[] c = KEY_RGB[color];
            for (com.badlogic.gdx.graphics.g3d.Material mat : m.materials) {
                if (mat.id.contains("Flag") || mat.id.contains("Gold") || (name.equals("tower") && mat.id.contains("Wood"))) { mat.set(ColorAttribute.createDiffuse(c[0], c[1], c[2], 1f)); mat.set(ColorAttribute.createEmissive(c[0] * 0.25f, c[1] * 0.25f, c[2] * 0.25f, 1f)); }
            }
            colorInst.put(k, m);
        }
        return m;
    }

    /** Animated pack creatures (crab walk cycle, bee flapping), one rig per element, created when first seen. */
    private static final class Rig { ModelInstance inst; com.badlogic.gdx.graphics.g3d.utils.AnimationController ctrl; }
    private final java.util.HashMap<Element, Rig> rigs = new java.util.HashMap<>();
    private float frameDt = 1f / 60f;

    private Rig rig(Element e, String model, String anim) {
        Rig r = rigs.get(e);
        if (r == null) {
            if (rigs.size() > 48) rigs.clear();
            r = new Rig(); r.inst = new ModelInstance(models.pack(model));
            for (com.badlogic.gdx.graphics.g3d.Material m : r.inst.materials) m.set(ColorAttribute.createEmissive(0.22f, 0.22f, 0.2f, 1f));
            r.ctrl = new com.badlogic.gdx.graphics.g3d.utils.AnimationController(r.inst);
            r.ctrl.animate(anim, -1, 1f, null, 0f);
            r.ctrl.update(MathUtils.random(2f));         // desynchronise the cycles
            rigs.put(e, r);
        }
        return r;
    }

    private void drawRig(Rig r, float arc, float y, float dz, float camS, float scale, float yaw, float roll, float speed) {
        r.ctrl.update(frameDt * speed);
        float phi = wrapDiff(arc, camS) / T.radius, rr = T.radius - dz;
        r.inst.transform.idt().translate(rr * MathUtils.sin(phi), y, -T.radius + rr * MathUtils.cos(phi))
                .rotate(0, 1, 0, phi * MathUtils.radiansToDegrees + yaw).rotate(0, 0, 1, roll).scale(scale, scale, scale);
        batch.render(r.inst, env);
    }

    private ModelInstance stone;
    private void drawBox(float arc, float y, float dz, float camS, float w, float h, float d, float cr, float cg, float cb) {
        if (stone == null) stone = new ModelInstance(models.box);
        stone.materials.get(0).set(ColorAttribute.createDiffuse(cr, cg, cb, 1f));
        place(stone, arc, y + h * 0.5f, dz, camS, w, h, d, 0f);
        batch.render(stone, env);
    }

    private void drawHazard(Element h, float t, float camS, float time, boolean done) {
        float cs = h.type == Element.Type.CANNON ? h.s + h.dir * h.len * 0.5f : h.s;
        float half = h.type == Element.Type.CANNON ? h.len * 0.5f + 1.5f : (h.type == Element.Type.SAW_H ? h.amp + 1.5f : 2f);
        float cy = h.type == Element.Type.SAW_V ? h.y + h.amp * 0.5f : h.y;
        if (!visible(cs, cy, camS, half)) return;
        switch (h.type) {
            case SAW_H: {
                drawBox(h.s, h.y - 0.05f, 0.25f, camS, h.amp * 2f + 1.2f, 0.1f, 0.14f, 0.3f, 0.32f, 0.4f);
                drawPack("hazard_saw", h.sAt(t), h.yAt(t), 0.25f, camS, PK, PK, PK, 0f, -time * 600f);
                break;
            }
            case SAW_V: {
                drawBox(h.s, h.y - 0.6f, 0.25f, camS, 0.12f, h.amp + 1.2f, 0.14f, 0.3f, 0.32f, 0.4f);
                drawPack("hazard_saw", h.s, h.yAt(t), 0.25f, camS, PK, PK, PK, 0f, -time * 600f);
                break;
            }
            case CANNON: {
                float base = h.y - 2.35f;
                drawBox(h.s, base, 0f, camS, 1.25f, 1.5f, 1.25f, 0.42f, 0.38f, 0.5f);
                drawBox(h.s, base + 1.5f, 0f, camS, 1.45f, 0.16f, 1.45f, 0.3f, 0.27f, 0.36f);
                drawPack("cannon", h.s, base + 1.66f + 0.12f, 0f, camS, PK, PK, PK, h.dir > 0 ? CANNON_YAW : -CANNON_YAW, 0f);
                if (h.lethalAt(t)) drawPack("spikyball", h.sAt(t), h.yAt(t), 0f, camS, 0.55f, 0.55f, 0.55f, 0f, -h.sAt(t) * 160f);
                break;
            }
            case SPIKE_TRAP: {
                float sp = h.spikeHeight(t);
                drawBox(h.s, h.y - 0.04f, 0f, camS, h.w + 0.3f, 0.07f, 1.5f, 0.55f, 0.2f, 0.18f);
                if (sp > 0.02f) {
                    float hk = sp / (3.4f * 0.37f);
                    for (int k = -1; k <= 1; k++) drawPack("spikes", h.s + k * h.w * 0.32f, h.y, 0f, camS, 0.3f, 0.37f * hk * 1.3f, 0.3f, 0f, 0f);
                }
                break;
            }
            case KEY: {
                if (done) break;
                ModelInstance k = colored("key", h.color);
                float bob = 0.12f * MathUtils.sin(time * 3f);
                float phi = wrapDiff(h.s, camS) / T.radius;
                k.transform.idt().translate(T.radius * MathUtils.sin(phi), h.y + bob, -T.radius + T.radius * MathUtils.cos(phi))
                        .rotate(0, 1, 0, phi * MathUtils.radiansToDegrees + time * 140f).rotate(0, 0, 1, 25f).scale(0.5f, 0.5f, 0.5f).translate(-0.45f, 0f, 0f);
                batch.render(k, env);
                break;
            }
            case GATE: {
                ModelInstance g = colored("tower", h.color);
                float phi = wrapDiff(h.s, camS) / T.radius;
                g.transform.idt().translate(T.radius * MathUtils.sin(phi), h.y, -T.radius + T.radius * MathUtils.cos(phi))
                        .rotate(0, 1, 0, phi * MathUtils.radiansToDegrees).scale(0.4f, 0.4f, 0.4f);
                batch.render(g, env);
                break;
            }
            case CRAB: {
                if (done) break;
                float crabX = h.sAt(t), vel = h.amp * (float) Math.cos(2 * Math.PI * t / h.period + h.phase);
                drawRig(rig(h, "crab_anim", "Walk"), crabX, h.y, 0f, camS, 0.34f, vel >= 0 ? 90f : -90f, 0f, 1.6f);
                break;
            }
            case BEE: {
                if (done || !h.beePresent(t)) break;
                float bs = h.beeS(t), by = h.beeY(t), vel = h.beeS(t + 0.05f) - bs;
                drawRig(rig(h, "bee_anim", "Flying"), bs, by, 0f, camS, 0.36f, vel >= 0 ? 70f : -70f, MathUtils.clamp(vel * 120f, -25f, 25f) * (vel >= 0 ? -1f : 1f), 1f);
                break;
            }
            case CLUB: {
                if (done) break;
                drawPack("hazard_cylinder", h.s, h.y + 0.12f * MathUtils.sin(time * 2.6f + h.phase), 0f, camS, 0.28f, 0.28f, 0.28f, time * 90f, 12f);
                if (quality > 0 && MathUtils.randomBoolean(0.06f)) { ambCol.set(1f, 0.95f, 0.5f, 1f); particles.spawn(h.s + MathUtils.random(-0.4f, 0.4f), h.y + MathUtils.random(0f, 1.1f), 0f, 0.4f, ambCol, 0.06f, 0f, 0.8f); }
                break;
            }
            case SPIKE_DROP: {
                float b = h.dropBottom(t), top = h.y + h.amp + Element.DROP_H;
                drawBox(h.s - h.w * 0.5f - 0.1f, h.y, 0.2f, camS, 0.1f, h.amp + Element.DROP_H + 0.6f, 0.14f, 0.3f, 0.32f, 0.4f);
                drawBox(h.s + h.w * 0.5f + 0.1f, h.y, 0.2f, camS, 0.1f, h.amp + Element.DROP_H + 0.6f, 0.14f, 0.3f, 0.32f, 0.4f);
                drawBox(h.s, top + 0.5f, 0.2f, camS, h.w + 0.5f, 0.14f, 0.3f, 0.3f, 0.32f, 0.4f);
                drawBox(h.s, b + 0.5f, 0f, camS, h.w, 0.5f, 1.3f, 0.42f, 0.38f, 0.5f);
                drawBox(h.s, b + 0.5f, 0.1f, camS, 0.08f, top + 0.5f - (b + 1.0f), 0.1f, 0.5f, 0.45f, 0.35f);
                for (int k = -1; k <= 1; k += 2) drawPack("spikes", h.s + k * h.w * 0.25f, b + 0.5f, 0f, camS, h.w * 0.22f, 0.5f / 3.4f, 0.3f, 0f, 180f);
                break;
            }
            case SPIKE_BLOCK: {
                float base = Math.min(0.5f, h.len * 0.5f), sh = Math.max(0.3f, h.len - base);
                drawBox(h.s, h.y, 0f, camS, h.w, base, 1.3f, 0.42f, 0.38f, 0.5f);
                drawPack("spikes", h.s, h.y + base, 0f, camS, h.w * 0.46f, sh / 3.4f, h.w * 0.46f, 0f, 0f);
                break;
            }
            default: break;
        }
    }

    private ModelInstance railInst, ropeInst;

    private void drawSwingRopes(Element e, float es, float ey, float camS) {
        if (ropeInst == null) { ropeInst = new ModelInstance(models.box); ropeInst.materials.get(0).set(ColorAttribute.createDiffuse(0.8f, 0.65f, 0.4f, 1f)); }
        float pivotY = e.y + e.len;
        float pivotS = e.s;
        for (int side = -1; side <= 1; side += 2) {
            float sx = es + side * (e.w * 0.5f - 0.15f), sy = ey;
            float px = pivotS + side * (e.w * 0.5f - 0.15f);
            float dx = course.dsWrap(px, sx), dy = pivotY - sy;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            float ang = (float) Math.toDegrees(Math.atan2(dx, dy)); // rotation about Z so the box leans toward the pivot
            float midS = sx + dx * 0.5f, midY = sy + dy * 0.5f;
            float phi = course.dsWrap(midS, camS) / T.radius;
            ropeInst.transform.idt().translate(T.radius * MathUtils.sin(phi), midY, -T.radius + T.radius * MathUtils.cos(phi))
                    .rotate(0, 1, 0, phi * MathUtils.radiansToDegrees).rotate(0, 0, 1, -ang).scale(0.07f, len, 0.07f);
            batch.render(ropeInst, env);
        }
        // pivot beam
        if (railInst == null) { railInst = new ModelInstance(models.box); railInst.materials.get(0).set(ColorAttribute.createDiffuse(0.45f, 0.30f, 0.18f, 1f)); }
        place(railInst, pivotS, pivotY, 0f, camS, e.w + 0.6f, 0.22f, 0.5f, 0f);
        batch.render(railInst, env);
    }

    private void drawRail(Element e, float camS, boolean vertical) {
        if (railInst == null) { railInst = new ModelInstance(models.box); }
        railInst.materials.get(0).set(ColorAttribute.createDiffuse(0.30f, 0.34f, 0.44f, 1f));
        if (vertical) {
            float h = e.amp + 0.4f;
            float x = e.s - e.w * 0.5f - 0.1f;
            place(railInst, x, e.y + h * 0.5f - 0.35f, 0.6f, camS, 0.1f, h, 0.1f, 0f); batch.render(railInst, env);
            place(railInst, e.s + e.w * 0.5f + 0.1f, e.y + h * 0.5f - 0.35f, 0.6f, camS, 0.1f, h, 0.1f, 0f); batch.render(railInst, env);
        } else {
            float len = e.amp * 2f + e.w;
            place(railInst, e.s, e.y - 0.25f, 0.9f, camS, len, 0.08f, 0.12f, 0f); batch.render(railInst, env);
        }
    }

    private void drawPlayer(Sim sim, float dt, float time, float ps, float py, float alpha) {
        float phi = 0f; // player is the anchor: always at centre, tangent = camera-facing
        // shadow blob on the nearest platform below
        if (quality > 0) {
            float gy = groundBelow(sim, ps, py);
            if (gy > -1e8f && py - gy < 9f) {
                float k = MathUtils.clamp(1f - (py - gy) / 9f, 0.2f, 1f);
                shadow.transform.idt().translate(0, gy + 0.03f, 0.2f).scale(0.55f * k + 0.15f, 1f, 0.4f * k + 0.15f);
                batch.render(shadow, env);
            }
        }
        float wy = py; heroY = py;
        hero.update(sim, dt, time, 0f, wy, 0f, phi, reducedMotion);
        hero.render(batch, env);
        if (sim.clubTime > 0f) {
            float k = sim.swingT > 0f ? 1f - sim.swingT / Sim.SWING_TIME : -1f;
            float ang = k < 0f ? 35f + 5f * MathUtils.sin(time * 5f) : MathUtils.lerp(-70f, 110f, Math.min(1f, k * 1.25f));       // degrees forward of straight up
            ModelInstance cl = pack("hazard_cylinder");
            cl.transform.idt().translate(sim.facing * 0.4f, wy + 0.65f, 0.2f).rotate(0, 0, 1, -sim.facing * ang).scale(0.2f, 0.2f, 0.2f);
            batch.render(cl, env);
            if (k >= 0f && k > 0.25f && k < 0.75f && quality > 0) particles.burst(renderS + sim.facing * 1.2f, wy + 0.6f, 1, ambCol.set(1f, 1f, 1f, 1f), 1.2f, 0.4f, 0.07f, 0f, 0.25f);
        }
    }

    private float groundBelow(Sim sim, float ps, float py) {
        float best = -1e9f;
        for (int i = Math.max(0, sim.winLo); i <= Math.min(vis.size() - 1, sim.winHi); i++) {
            Element e = vis.get(i).e;
            if (!e.isPlatform() || sim.gone[i]) continue;
            float top = sim.ey1[i];
            if (top > py + 0.05f || py - top > 9f) continue;
            if (Math.abs(course.dsWrap(sim.es1[i], ps)) > e.halfW() + 0.1f) continue;
            if (top > best) best = top;
        }
        return best;
    }

    private float heroY;
    private float[] dip, dipV; private float fovK, fovV, ambientAcc;
    private final Color ambCol = new Color();

    private static final class Flung { float s, y, vx, vy, rot, age; boolean bee; }
    private final java.util.ArrayList<Flung> flung = new java.util.ArrayList<>();
    /** A crab was knocked off its platform: it tumbles away (purely visual). */
    public void crabFlung(float s, float y, int dir, boolean bee) { Flung f = new Flung(); f.s = s; f.y = y; f.vx = dir * 6f; f.vy = bee ? 5f : 7f; f.bee = bee; flung.add(f); }

    private void drawFlung(float dt, float camS) {
        for (int i = flung.size() - 1; i >= 0; i--) {
            Flung f = flung.get(i); f.age += dt; f.vy -= 30f * dt; f.s += f.vx * dt; f.y += f.vy * dt; f.rot += 540f * dt;
            if (f.age > 2f) { flung.remove(i); continue; }
            drawPack(f.bee ? "bee_anim" : "crab", f.s, f.y, 0f, camS, f.bee ? 0.36f : 0.34f, f.bee ? 0.36f : 0.34f, f.bee ? 0.36f : 0.34f, 0f, f.rot);
        }
    }

    public void heroEvents(int ev, float landSpeed) { hero.events(ev, landSpeed); }
    public void platformLanded(int idx, float speed) { syncVis(); if (dip != null && idx >= 0 && idx < dip.length) dipV[idx] += Math.min(14f, speed) * 0.9f; }
    public void kick(float deg) { if (!reducedMotion) fovV += deg * 14f; }
    private final com.badlogic.gdx.math.Vector3 headTmp = new com.badlogic.gdx.math.Vector3();
    public String heroBubble() { return hero.bubble(); }
    public float heroBubbleAlpha() { return hero.bubbleAlpha(); }

    /** Screen position of the hero's head in a virtual UI space of size (uiW, uiH), y up. */
    public void heroHeadScreen(float uiW, float uiH, float[] out) {
        headTmp.set(0f, heroY + 1.7f, 0f);
        cam.project(headTmp, 0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
        out[0] = headTmp.x * uiW / Gdx.graphics.getBackBufferWidth();
        out[1] = headTmp.y * uiH / Gdx.graphics.getBackBufferHeight();
    }

    /** Zone-flavoured drifting specks: pollen (meadow), snow (frost), embers (dusk), fireflies (night). */
    private void ambientMotes(float dt, float zoneF, float camS) {
        ambientAcc += dt * (quality >= 2 ? 7f : 3.5f);
        while (ambientAcc >= 1f) {
            ambientAcc -= 1f;
            int z = Math.min(3, (int) zoneF);
            float arc = camS + MathUtils.random(-9f, 9f), yy = camY + MathUtils.random(-4f, 6f);
            switch (z) {
                case 0: ambCol.set(1f, 0.97f, 0.7f, 1f); particles.spawn(arc, yy, MathUtils.random(-0.3f, 0.3f), MathUtils.random(0.2f, 0.6f), ambCol, 0.07f, 0f, 4.5f); break;
                case 1: ambCol.set(1f, 1f, 1f, 1f); particles.spawn(arc, camY + 6f, MathUtils.random(-0.5f, 0.1f), -MathUtils.random(0.8f, 1.5f), ambCol, 0.09f, 0f, 6f); break;
                case 2: ambCol.set(1f, 0.55f, 0.2f, 1f); particles.spawn(arc, camY - 4f, MathUtils.random(-0.4f, 0.4f), MathUtils.random(0.9f, 1.8f), ambCol, 0.07f, 0f, 5f); break;
                default: ambCol.set(0.7f, 1f, 0.8f, 1f); particles.spawn(arc, yy, MathUtils.random(-0.6f, 0.6f), MathUtils.random(-0.3f, 0.4f), ambCol, 0.08f, 0f, 4f);
            }
        }
    }

    /** World position (relative to the screen centre) for effects; arc coordinate -> x,z. */
    public float getCamY() { return camY; }

    @Override public void dispose() {
        batch.dispose(); shapes.dispose(); sb.dispose(); hero.dispose(); bg.dispose();
    }
}

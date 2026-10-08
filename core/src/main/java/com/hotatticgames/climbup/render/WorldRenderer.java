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
        ModelInstance inst; float du, dy, dz, sx = 1, sy = 1, sz = 1, yaw; Color color; boolean fall; boolean tinted = true; ColorAttribute tintAttr;
    }
    private static final class Vis { Part[] parts; Element e; boolean built; }

    private final Tuning T;
    private final Course course;
    private final Models models;
    private final ModelBatch batch = new ModelBatch();
    private final PerspectiveCamera cam = new PerspectiveCamera(40f, 16, 9);
    private final Environment env = new Environment();
    private final DirectionalLight sun = new DirectionalLight();
    private final ShapeRenderer shapes = new ShapeRenderer();
    private final SpriteBatch sb = new SpriteBatch();
    private final RobotRig robot;
    private final Vis[] vis;
    private final Background bg = new Background();
    public final Particles particles;
    private static final float CAM_DIST = Float.parseFloat(System.getProperty("climb.camDist", "9.6"));
    public int quality = 2;           // 0 low, 1 medium, 2 high
    public boolean reducedMotion;

    private float camY, camShake, shakeT;
    private final Color skyTop = new Color(), skyBot = new Color(), tint = new Color(), amb = new Color();
    private final ModelInstance shadow;
    private float renderS, renderY;
    public float lastCamS;
    private final Array<ModelInstance> tmp = new Array<>();

    public WorldRenderer(Tuning t, Course c, Models models, int quality) {
        this.T = t; this.course = c; this.models = models; this.quality = quality;
        robot = new RobotRig(quality);
        vis = new Vis[c.size()];
        for (int i = 0; i < vis.length; i++) { vis[i] = new Vis(); vis[i].e = c.get(i); }
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
        Vis v = vis[idx]; Element e = v.e; v.built = true;
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

    private void place(ModelInstance inst, float arc, float y, float dz, float camS, float sx, float sy, float sz, float yawExtraDeg) {
        float phi = wrapDiff(arc, camS) / T.radius;
        float r = T.radius - dz;
        inst.transform.idt().translate(r * MathUtils.sin(phi), y, -T.radius + r * MathUtils.cos(phi))
                .rotate(0, 1, 0, phi * MathUtils.radiansToDegrees + yawExtraDeg).scale(sx, sy, sz);
    }

    private boolean visible(float arc, float y, float camS, float margin) {
        float d = wrapDiff(arc, camS);
        return Math.abs(d) < 1.3f * T.radius * 1.0f + margin && Math.abs(y - camY) < 16f;
    }

    public void render(Sim sim, float alpha, float dt, float time, boolean showPlayer) {
        // interpolated player position (render only; simulation stays on the fixed step)
        float ps = sim.teleported ? sim.s : sim.ps0 + course.dsWrap(sim.s, sim.ps0) * alpha;
        float py = sim.teleported ? sim.y : sim.py0 + (sim.y - sim.py0) * alpha;
        renderS = ps; renderY = py;
        lastCamS = ps;
        // vertical camera: follow with smoothing, snap on respawn
        float target = py + 0.3f;
        if (sim.teleported) camY = target;
        else camY += (target - camY) * Math.min(1f, (target > camY ? 7f : 10f) * dt);
        float zoneF = Math.min(3.999f, Math.max(0f, camY / T.courseHeight * 4f));
        Palette.blend(Palette.SKY_TOP, zoneF, skyTop); Palette.blend(Palette.SKY_BOT, zoneF, skyBot);
        Palette.blend(Palette.TINT, zoneF, tint); Palette.blend(Palette.AMBIENT, zoneF, amb);
        ((ColorAttribute) env.get(ColorAttribute.AmbientLight)).color.set(amb);
        ((ColorAttribute) env.get(ColorAttribute.Fog)).color.set(skyBot).lerp(skyTop, 0.25f);

        // camera
        float sh = 0;
        if (shakeT > 0 && !reducedMotion) { shakeT -= dt; sh = camShake * MathUtils.sin(time * 70f) * (shakeT / 0.25f); } else camShake = 0;
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
        int n = vis.length;
        for (int i = 0; i < n; i++) {
            Vis v = vis[i]; Element e = v.e;
            float es = sim.es1[i] , ey = sim.ey1[i];
            if (e.isMoving()) { // interpolate moving platforms
                es = sim.es0[i] + course.dsWrap(sim.es1[i], sim.es0[i]) * alpha; ey = sim.ey0[i] + (sim.ey1[i] - sim.ey0[i]) * alpha;
            }
            float vy = e.type == Element.Type.ROPE ? e.y - e.len * 0.5f : ey;
            if (!visible(es, vy, ps, e.type == Element.Type.CABLE ? e.w * 0.5f : 3f)) continue;
            if (!v.built) build(i);
            drawElement(sim, i, v, es, ey, ps, time);
        }
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
            float y = ey;
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
            place(p.inst, es + du + shakeX, y + dy + fallY, p.dz, camS, p.sx, sy, p.sz, extraYaw);
            batch.render(p.inst, env);
        }
        if (e.type == Element.Type.SWING) drawSwingRopes(e, es, ey, camS);
        if (e.type == Element.Type.MOVE_V) drawRail(e, camS, true);
        if (e.type == Element.Type.MOVE_H) drawRail(e, camS, false);
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
        float wy = py;
        if (sim.mode == Sim.Mode.CABLE) wy = py;
        robot.update(sim, dt, time, 0f, wy, 0f, phi, reducedMotion);
        robot.render(batch, env);
    }

    private float groundBelow(Sim sim, float ps, float py) {
        float best = -1e9f;
        for (int i = 0; i < vis.length; i++) {
            Element e = vis[i].e;
            if (!e.isPlatform() || sim.gone[i]) continue;
            float top = sim.ey1[i];
            if (top > py + 0.05f || py - top > 9f) continue;
            if (Math.abs(course.dsWrap(sim.es1[i], ps)) > e.halfW() + 0.1f) continue;
            if (top > best) best = top;
        }
        return best;
    }

    /** World position (relative to the screen centre) for effects; arc coordinate -> x,z. */
    public float getCamY() { return camY; }

    @Override public void dispose() {
        batch.dispose(); shapes.dispose(); sb.dispose(); robot.dispose(); bg.dispose();
    }
}

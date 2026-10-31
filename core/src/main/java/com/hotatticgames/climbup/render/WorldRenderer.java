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
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Disposable;
import com.hotatticgames.climbup.sim.Course;
import com.hotatticgames.climbup.sim.Element;
import com.hotatticgames.climbup.sim.CheckpointGem;
import com.hotatticgames.climbup.sim.Sim;
import com.hotatticgames.climbup.sim.Tuning;

/**
 * Draws the climb as a 2.5D cylindrical world. There is no cylinder mesh: every element is placed on a circle of radius R
 * around a hidden axis, rotated so the player's own arc position always sits at screen centre (the camera never moves
 * sideways, the tower turns to meet it).
 */
public final class WorldRenderer implements Disposable {
    private static final class Part {
        ModelInstance inst; float du, dy, dz, sx = 1, sy = 1, sz = 1, yaw, roll; Color color; boolean fall; boolean tinted = true; boolean fixed; ColorAttribute tintAttr;
        boolean belt; float beltSpan, beltSlope, beltInset;   // Build 72: a conveyor slat that rides the belt, wrapping round within beltSpan (and along the ramp's slope); Build 76: cut off beltInset short of each end (the rollers)
        int hole = -1;  // Build 79: the plank section of a holed bridge this plank belongs to (it drops out with it), else -1
        float localZ;   // optional pre-rotation translate along the model's own Z (model units, applied before scale): used to recentre an off-origin mesh (the ramp) so it can sit at the element centre without a big arc offset
    }
    private static final class Vis { Part[] parts; Element e; boolean built; float beltU, beltT; }      // beltU: how far a balance conveyor's belt has run (its speed follows the tilt)

    private final Tuning T;
    private final Course course;
    private final Models models;
    /** Atmosphere prototype (null unless the -Dclimb.atmos master switch is on; see {@link Atmos}). */
    private final Atmos atmos = Atmos.ENABLED ? new Atmos() : null;
    private AtmosMotes atmosMotes;
    private final ModelBatch batch = new ModelBatch(atmos != null ? atmos.provider(boneConfig()) : new com.badlogic.gdx.graphics.g3d.utils.DefaultShaderProvider(Grade.config(boneConfig())));      // Build 72: colour-graded
    private final PerspectiveCamera cam = new PerspectiveCamera(40f, 16, 9);
    private final Environment env = new Environment();
    private final DirectionalLight sun = new DirectionalLight(), fillL = new DirectionalLight(), bounceL = new DirectionalLight();
    private final Environment heroEnv = new Environment();
    private final DirectionalLight hKey = new DirectionalLight(), hFill = new DirectionalLight(), hBounce = new DirectionalLight(), hRim = new DirectionalLight();
    private final Color lc = new Color(); private final com.badlogic.gdx.math.Vector3 lv = new com.badlogic.gdx.math.Vector3();
    private com.badlogic.gdx.graphics.Texture vignette;
    private final ShapeRenderer shapes = new ShapeRenderer();
    private final SpriteBatch sb = new SpriteBatch();
    private HeroRig hero;
    private int character;
    private SpaceScene space;
    private com.badlogic.gdx.graphics.PerspectiveCamera farCam;
    private boolean whooshPending;
    /** True once after a spaceship has flown past the player (the play screen plays the sound). */
    public boolean takeWhoosh() { boolean w = whooshPending; whooshPending = false; return w; }
    private boolean scanPending;
    /** True once when the scanning ship starts to hover (Build 78): the play screen plays its hum and scan sweep. */
    public boolean takeScan() { boolean w = scanPending; scanPending = false; return w; }
    private final java.util.ArrayList<Vis> vis = new java.util.ArrayList<>();
    private int[] builtIdx = new int[256]; private int builtN, builtScan;       // every element whose model parts exist right now, so they can be freed wherever the player has moved away from them (up or down)
    private int pruneCursor;
    private final Background bg = new Background();
    private Clouds clouds;
    public final Particles particles;
    private static final float CAM_DIST = Float.parseFloat(System.getProperty("climb.camDist", "9.6"));
    public int quality = 2;           // 0 low, 1 medium, 2 high
    public boolean reducedMotion;
    /** COLOURBLIND-SAFE COLOURS: the keys and castle gates use the safe palette (see {@link KeyColors}); the shape markers are always drawn. */
    public boolean colourblind;
    private boolean paletteSafe;      // the palette the cached coloured instances were built with

    private float camY, camShake, shakeT, camPull;
    private double originY, originS;          // the world is expressed relative to a floating origin; absolute height = originY + local y (see Tower)
    private final Color skyTop = new Color(), skyBot = new Color(), skyMid = new Color(), tint = new Color(), amb = new Color();
    private float renderS, renderY;
    public float lastCamS;
    public int cloudsBroken;          // PlayScreen reads and clears this to play the poof sound
    private final Array<ModelInstance> tmp = new Array<>();

    private static com.badlogic.gdx.graphics.g3d.shaders.DefaultShader.Config boneConfig() {
        com.badlogic.gdx.graphics.g3d.shaders.DefaultShader.Config c = new com.badlogic.gdx.graphics.g3d.shaders.DefaultShader.Config();
        c.numBones = 32;   // the hero rig has 29 bones
        c.numDirectionalLights = 4;   // key, fill, bounce (+ the hero's rim light)
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
        fillL.set(0.2f, 0.3f, 0.45f, 0.75f, -0.25f, -0.45f); bounceL.set(0.3f, 0.25f, 0.18f, 0.1f, 1f, -0.3f);
        env.add(sun); env.add(fillL); env.add(bounceL);
        heroEnv.set(new ColorAttribute(ColorAttribute.AmbientLight, 0.7f, 0.7f, 0.74f, 1f));
        hKey.set(1f, 0.97f, 0.9f, -0.5f, -0.9f, -0.6f); hFill.set(0.3f, 0.35f, 0.5f, 0.75f, -0.25f, -0.45f); hBounce.set(0.3f, 0.25f, 0.18f, 0.1f, 1f, -0.3f); hRim.set(0.55f, 0.75f, 1f, -0.35f, -0.2f, 1f);
        heroEnv.add(hKey); heroEnv.add(hFill); heroEnv.add(hBounce); heroEnv.add(hRim);
        env.set(new ColorAttribute(ColorAttribute.Fog, 0.8f, 0.9f, 1f, 1f));
        cam.near = 0.5f; cam.far = 64f;      // the climb you are heading into is drawn ~30 units out (see visible()); a nearer far-plane makes the distance-fog haze that geometry in gradually instead of popping it in hard
        for (String n : new String[]{"block-grass", "block-grass-low", "block-grass-long", "block-grass-low-long", "block-snow", "block-snow-low", "block-snow-long", "block-snow-low-long",
                "block-moving", "block-moving-blue", "platform-fortified", "flag", "chest", "jewel", "tree", "tree-pine-small", "flowers", "mushrooms", "rocks", "plant",
                "tree-pine-snow-small", "tree-snow", "stones", "sign"}) models.obj(n);
        for (String n : new String[]{"ramp", "rock_1", "rock_2", "rock_3", "rock_4", "rock_large_1", "rock_large_2", "rock_large_3"}) models.space(n);
        particles = new Particles(models);
    }

    public void setOrigin(double y, double s) { originY = y; originS = s; }

    /** The tower's world was rebuilt around a different set of slices and origin (see Tower.rebuild): keep the per-element visuals, and shift everything that remembers a height, so nothing visible moves. */
    public void rebased(com.hotatticgames.climbup.sim.Tower.Remap m, double newOriginY, double newOriginS) {
        int n = m.newN;
        java.util.ArrayList<Vis> nv = new java.util.ArrayList<>(n);
        for (int j = 0; j < n; j++) nv.add(null);
        float[] nd = new float[Math.max(16, n)], ndv = new float[Math.max(16, n)];
        for (int i = 0; i < m.elem.length && i < vis.size(); i++) {
            int j = m.elem[i]; if (j < 0) continue;
            Vis v = vis.get(i); v.e = course.get(j); nv.set(j, v);
            if (i < dip.length) { nd[j] = dip[i]; ndv[j] = dipV[i]; }
        }
        for (int j = 0; j < n; j++) if (nv.get(j) == null) { Vis v = new Vis(); v.e = course.get(j); nv.set(j, v); }
        vis.clear(); vis.addAll(nv); dip = nd; dipV = ndv;
        builtN = 0; for (int j = 0; j < n; j++) if (vis.get(j).built) { if (builtN == builtIdx.length) builtIdx = java.util.Arrays.copyOf(builtIdx, builtN * 2); builtIdx[builtN++] = j; }
        dipN = 0; dipOn = new boolean[dip.length]; for (int j = 0; j < n; j++) if (dip[j] != 0f || dipV[j] != 0f) { if (dipN == dipAct.length) dipAct = java.util.Arrays.copyOf(dipAct, dipN * 2); dipAct[dipN++] = j; dipOn[j] = true; }
        float dy = m.dy;
        camY -= dy; renderY -= dy; heroY -= dy;
        for (Flung f : flung) f.y -= dy;
        particles.shiftY(dy);
        if (atmosMotes != null) atmosMotes.shiftY(dy);
        originY = newOriginY; originS = newOriginS;
    }

    /** The course grows while climbing (endless mode): keep per-element visuals and spring state in step with it. */
    private void syncVis() {
        int n = course.size();
        while (vis.size() < n) { Vis v = new Vis(); v.e = course.get(vis.size()); vis.add(v); }
        if (dip.length < n) { int m = Math.max(n, dip.length * 3 / 2); dip = java.util.Arrays.copyOf(dip, m); dipV = java.util.Arrays.copyOf(dipV, m); }
    }

    /** Switches the player model (Settings: CHARACTER) without rebuilding the world. */
    public void setCharacter(int c) { if (c == character) return; character = c; hero.dispose(); hero = new HeroRig(c); }
    public HeroRig heroRig() { return hero; }   // diagnostic access (grip-pose probes)

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
        if (builtN == builtIdx.length) builtIdx = java.util.Arrays.copyOf(builtIdx, builtN * 2);
        builtIdx[builtN++] = idx;
        Array<Part> ps = new Array<>();
        boolean snow = e.zone == 1 || e.zone == 3;
        String base = snow ? "block-snow" : "block-grass";
        switch (e.type) {
            case SEESAW: bridgeParts(ps, e.w, true, e.tiltBelt()); break;
            case RAMP: {
                float sc = e.len;
                // The ramp wedge's walking surface runs model-Z 2.0 (high, top y=1.39) -> 4.1 (low); its centre is model-Z ~3.1.
                // Lay it IN the walking lane centred on the element: du=0 keeps the mesh's origin at the element centre so place()'s tower-rotation barely turns it
                // (a large arc offset used to swing the whole rigid wedge ~30 deg around the cylinder, tilting it out of the lane in depth -> "I'm outside the ramp"). localZ recentres the off-origin mesh onto that origin.
                // sy=0.84 matches the wedge's steeper native slope to the sim's RAMP_SLOPE (0.576); sx shapes the ~1.35 m depth centred on the lane; dy drops the surface mid-point onto e.y.
                // Build 72 (owner): the spinning ramp is as deep as it is long, so the lane always crosses its full length whichever way it faces
                Part rp = part(models.space("ramp"), 0f, -0.571f * sc, 0f, (e.spins() ? 1.17f : 0.72f) * sc, 0.84f * sc, sc); rp.yaw = -90f; rp.localZ = -3.1f; rp.tinted = false; ps.add(rp);
                // Build 79 (owner: the middle vanished when the spinning ramp showed its back): the deck is a single-sided plate, so draw both sides of it
                if (e.spins()) for (com.badlogic.gdx.graphics.g3d.Material m : rp.inst.materials) m.set(com.badlogic.gdx.graphics.g3d.attributes.IntAttribute.createCullFace(GL20.GL_NONE));
                if (e.spins()) {            // it turns on a round turntable: a dark plinth with a glowing rim, which stays still
                    Color glow = new Color(0.30f, 0.85f, 1f, 1f), pad = new Color(0.22f, 0.24f, 0.32f, 1f);
                    float rad = e.halfW() * 2.3f, plinth = -e.amp * e.halfW() - 0.12f;
                    Part pl = part(models.disc, 0f, plinth, 0f, rad, 6f, rad); pl.color = pad; pl.fall = true; pl.fixed = true; pl.tinted = false; pl.tintAttr.color.set(pad); ps.add(pl);
                    Part rim = part(models.disc, 0f, plinth - 0.07f, 0f, rad * 1.06f, 2f, rad * 1.06f); rim.color = glow; rim.fall = true; rim.fixed = true; rim.tinted = false; rim.tintAttr.color.set(glow);
                    rim.inst.materials.get(0).set(ColorAttribute.createEmissive(glow.r * 0.6f, glow.g * 0.6f, glow.b * 0.6f, 1f)); ps.add(rim);
                }
                if (e.belt()) {             // Build 72 (owner): a conveyor belt running downhill: dark rubber slats with yellow-striped ones, rolling down the slope, and a roller at each end
                    float L = e.w, pitch = 0.32f, th = (float) Math.atan(e.amp) * MathUtils.radiansToDegrees; int n = Math.max(6, Math.round(L / pitch));
                    Color slat = new Color(0.20f, 0.21f, 0.25f, 1f), stripe = new Color(1f, 0.78f, 0.16f, 1f), roller = new Color(0.55f, 0.57f, 0.62f, 1f);
                    for (int k = 0; k < n; k++) {
                        float x = -L / 2f + (k + 0.5f) * L / n;
                        Part sl = boxPart(x, e.amp * x + 0.035f, 0f, L / n * 0.72f, 0.07f, 1.25f, k % 4 == 0 ? stripe : slat);
                        sl.roll = th; sl.belt = true; sl.beltSpan = L; sl.beltSlope = e.amp; ps.add(sl);
                    }
                    for (int sd = -1; sd <= 1; sd += 2) { Part rl = boxPart(sd * L / 2f, sd * e.amp * L / 2f - 0.02f, 0f, 0.22f, 0.22f, 1.35f, roller); rl.roll = 45f; ps.add(rl); }
                }
                if (e.turbo()) {            // rules 19: the turbo booster at the top - a dark launcher tilted to its launch angle with glowing orange chevrons, seen from afar
                    float dir = e.amp >= 0f ? 1f : -1f, xTop = dir * (e.w / 2f - 0.3f), yTop = e.amp * xTop, ang = e.turboAngle() * MathUtils.radiansToDegrees * dir;
                    Color body = new Color(0.16f, 0.17f, 0.22f, 1f), hot = new Color(1f, 0.45f, 0.08f, 1f);
                    Part b = boxPart(xTop, yTop + 0.16f, 0f, 0.62f, 0.3f, 1.4f, body); b.roll = ang; ps.add(b);
                    for (int k = 0; k < 2; k++) {
                        Part ch = boxPart(xTop - dir * 0.14f + dir * k * 0.26f, yTop + 0.33f + k * 0.02f, 0f, 0.1f, 0.06f, 1.2f, hot); ch.roll = ang;
                        ch.inst.materials.get(0).set(ColorAttribute.createEmissive(hot.r * 0.8f, hot.g * 0.8f, hot.b * 0.8f, 1f)); ps.add(ch);
                    }
                }
                break;
            }
            case STATIC: case GOAL: case CRUMBLE: {
                if ((e.skin == 1 || e.skin == 4) && e.type == Element.Type.STATIC) { int p0 = ps.size; bridgeParts(ps, e.w, false, e.skin == 4); if (e.holed()) markHoles(ps, p0, e); break; }
                int w = Math.max(1, Math.round(e.w));
                boolean thick = w >= 5 || e.type == Element.Type.GOAL;
                String name = thick ? base : base + "-low";
                Model m = models.obj(name), ml = models.obj(name + "-long");
                float h = thick ? 1f : 0.5f;
                float left = -w / 2f; int pos = 0;
                boolean deck = e.skin == 2 && e.type == Element.Type.STATIC;        // castle deck: stretched front-to-back so the tower stands fully on it
                float zs = deck ? 4.0f : 1.7f;
                boolean orbit = e.zone == Palette.SPACE && e.type != Element.Type.GOAL;
                if (orbit) { spacePlatform(ps, e, idx); pos = w; }          // deep space: a metal deck on a drifting asteroid
                while (pos < w) {
                    boolean two = w - pos >= 2;
                    float cx = left + pos + (two ? 1f : 0.5f);
                    Part p = part(two ? ml : m, cx, -h, 0f, two ? 0.96f : 0.96f, 1f, zs);
                    if (e.type == Element.Type.CRUMBLE) { p.color = new Color(1f, 0.74f, 0.52f, 1f); p.fall = true; }
                    ps.add(p);
                    pos += two ? 2 : 1;
                }
                if (deck) {          // a stone plinth that widens toward the bottom, like the castle's footing
                    Color st = new Color(0.50f, 0.50f, 0.56f, 1f), st2 = new Color(0.40f, 0.40f, 0.46f, 1f);
                    ps.add(boxPart(0f, -h - 0.35f, 0f, w * 0.78f, 0.7f, zs * 1.04f, st));
                    ps.add(boxPart(0f, -h - 0.95f, 0f, w * 0.88f, 0.5f, zs * 1.12f, st2));
                }
                if (e.type == Element.Type.CRUMBLE) {
                    // cracks: small dark pebbles on the surface hint that it will fall
                    ps.add(boxPart(-0.35f, 0.02f, 0.2f, 0.22f, 0.05f, 0.18f, new Color(0.35f, 0.25f, 0.18f, 1f)));
                    ps.add(boxPart(0.4f, 0.02f, -0.1f, 0.18f, 0.05f, 0.22f, new Color(0.35f, 0.25f, 0.18f, 1f)));
                }
                if (!orbit && quality > 0 && (e.zone == 2 || e.zone == 3) && e.type != Element.Type.GOAL) {       // dusk and night: islands hang from a Space Kit rock
                    int hh = hash(idx, 41); boolean big = w >= 5;
                    String rock = big ? new String[]{"rock_large_1", "rock_large_2", "rock_large_3"}[hh % 3] : new String[]{"rock_1", "rock_2", "rock_3", "rock_4"}[hh % 4];
                    float sc = Math.max(0.3f, Math.min(w, 6f) * 0.85f / (big ? 7.6f : 3.0f)), top = big ? 0.3f : 0.4f;
                    sc = fitRock(idx, e, rock, sc, h + 0.25f, w);          // Build 79 (owner): never hangs down over anything below
                    if (sc > 0f) {
                        Part rk = part(models.space(rock), ((hh >> 5) % 5 - 2) * 0.08f * w, -h - 0.25f - top * sc, ((hh >> 9) % 3 - 1) * 0.15f, sc, sc, sc);
                        rk.roll = 180f; rk.yaw = (hh >> 3) % 360; ps.add(rk);
                    }
                }
                if (orbit || deck) { /* decorated by spacePlatform / bare so the tower fits */ }
                else if (insideCastle(e)) { /* Build 79 (owner): nothing inside castles */ }
                else if (thick && e.type != Element.Type.GOAL && quality > 0) decorate(ps, e, idx, w, snow);
                else if (!thick && e.type == Element.Type.STATIC && quality > 0 && hash(idx, 7) % 3 == 0) decorate(ps, e, idx, w, snow);
                // checkpoints are marked by a hovering red gem (drawn every frame in drawGem), not baked into the platform
                if (e.type == Element.Type.GOAL) {
                    Part fp = part(models.obj("flag"), 0f, 0f, 0.2f, 4.2f, 4.2f, 4.2f); fp.tinted = false; ps.add(fp);
                    Part c1 = part(models.obj("chest"), 1.8f, 0f, 0.2f, 1.4f, 1.4f, 1.4f); c1.tinted = false; ps.add(c1);
                    Part k1 = part(models.obj("jewel"), -1.8f, 0.2f, 0f, 2.2f, 2.2f, 2.2f); k1.tinted = false; ps.add(k1);
                }
                break;
            }
            case MOVE_H: case MOVE_V: case MOVE_Z: {
                int w = Math.max(1, Math.round(e.w));
                Model m = models.obj(e.type == Element.Type.MOVE_H ? "block-moving-blue" : "block-moving");
                for (int i = 0; i < w; i++) {
                    Part p = part(m, i - (w - 1) / 2f, moverBlockDy(e.type), 0f, 0.95f, 1f, 1.7f); p.tinted = false;
                    if (e.skin == 1) { p.color = new Color(1f, 0.74f, 0.52f, 1f); p.fall = true; }          // falls apart after you stand on it: the same warm tint as crumbling tiles
                    ps.add(p);
                }
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
                Color rope = ROPE_COLOR, beam = new Color(0.45f, 0.30f, 0.18f, 1f);
                ps.add(boxPart(0, -e.len * 0.5f, 0, 0.11f, e.len, 0.11f, rope));
                int knots = (int) e.len;
                for (int k = 1; k <= knots; k++) ps.add(boxPart(0, -k + 0.5f, 0, 0.2f, 0.12f, 0.2f, new Color(0.7f, 0.52f, 0.28f, 1f)));
                // Sturdy wooden hanger, entirely behind the walking lane (+dz = away from the camera): a chunky top beam the rope hangs off the front of,
                // carried by two upright posts tied with a back rail. (The old posts used -dz, which is TOWARDS the camera: they stood in front of the
                // climber and splayed out like table legs. +0.85 is now genuinely behind the player plane, so nothing ground or climber clips the boards.)
                Color railC = new Color(0.40f, 0.27f, 0.16f, 1f);
                ps.add(boxPart(0f, 0.12f, 0.45f, 2.5f, 0.26f, 0.95f, beam));            // top beam (lintel): its front face sits at the lane so the rope hangs from it, its body extends back to the posts
                for (int sd = -1; sd <= 1; sd += 2)                                     // two upright posts, flush behind the lane, flanking the rope (vertical, not splayed)
                    ps.add(boxPart(sd * 1.05f, -0.55f, 0.85f, 0.22f, 1.7f, 0.22f, beam));
                ps.add(boxPart(0f, 0.0f, 0.85f, 2.32f, 0.16f, 0.16f, railC));           // back cross-rail tying the two posts together
                break;
            }
            case TARZAN: {
                // rules 19: the ROPE's look, hanging from a fixed beam; the rope and its knots swing about the pivot each frame (drawElement)
                Color rope = ROPE_COLOR, beam = new Color(0.45f, 0.30f, 0.18f, 1f), railC = new Color(0.40f, 0.27f, 0.16f, 1f);
                ps.add(boxPart(0, -e.len * 0.5f, 0, 0.11f, e.len, 0.11f, rope));
                for (int k = 1; k <= (int) e.len; k++) ps.add(boxPart(0, -k + 0.5f, 0, 0.2f, 0.12f, 0.2f, new Color(0.7f, 0.52f, 0.28f, 1f)));
                Part b0 = boxPart(0f, 0.12f, 0.45f, 2.5f, 0.26f, 0.95f, beam); b0.fixed = true; ps.add(b0);
                for (int sd = -1; sd <= 1; sd += 2) { Part pp = boxPart(sd * 1.05f, -0.55f, 0.85f, 0.22f, 1.7f, 0.22f, beam); pp.fixed = true; ps.add(pp); }
                Part rl = boxPart(0f, 0.0f, 0.85f, 2.32f, 0.16f, 0.16f, railC); rl.fixed = true; ps.add(rl);
                break;
            }
            case CABLE: {
                // the traverse bar in the rope's colour (owner 2026-10-27: the near-black steel all but vanished against the night and space skies); same size and place
                Color line = ROPE_COLOR, post = new Color(0.45f, 0.30f, 0.18f, 1f), hook = new Color(1f, 0.55f, 0.15f, 1f);
                int seg = Math.max(1, (int) Math.ceil(e.w));
                for (int i = 0; i < seg; i++) ps.add(boxPart(-e.w / 2f + (i + 0.5f) * e.w / seg, 0f, 0f, e.w / seg + 0.05f, 0.07f, 0.07f, line));
                for (int i = 0; i <= 1; i++) {
                    float du = (i == 0 ? -1 : 1) * e.w / 2f;
                    ps.add(boxPart(du, -1.2f, 0.6f, 0.22f, 2.6f, 0.22f, post));     // posts stand BEHIND the lane (like the rope's), so they never punch through a platform the traverse passes over
                    ps.add(boxPart(du, 0.05f, 0.3f, 0.3f, 0.3f, 0.6f, hook));       // the hook reaches back from the cable (lane) to its post
                }
                break;
            }
        }
        v.parts = new Part[ps.size];
        for (int i = 0; i < ps.size; i++) v.parts[i] = ps.get(i);
    }

    /** Wooden bridge: plank deck on two beams, back rail with posts; the seesaw also gets a stone fulcrum and red end caps. */
    /** Build 79: the planks over a holed bridge's drop-out sections: a paler, cracked-looking wood so you can read them before you land, tagged to fall with their section. */
    private void markHoles(Array<Part> ps, int from, Element e) {
        int n = e.holeSections(); float sec = e.w / n;
        Color loose = new Color(0.80f, 0.66f, 0.44f, 1f);
        for (int i = from; i < ps.size; i++) {
            Part p = ps.get(i);
            if (p.sx > 0.6f || p.dy != -0.10f) continue;           // planks only (not the beams, posts and rails)
            int k = (int) Math.floor((p.du + e.w * 0.5f) / sec);
            if (k < 0 || k >= n || (e.color & (1 << k)) == 0) continue;
            p.hole = k; p.color = loose; p.fall = true; p.tinted = false; if (p.tintAttr != null) p.tintAttr.color.set(loose);
        }
    }
    private void bridgeParts(Array<Part> ps, float w, boolean seesaw, boolean belt) {
        Color wood1 = new Color(0.66f, 0.47f, 0.26f, 1f), wood2 = new Color(0.58f, 0.40f, 0.22f, 1f), beam = new Color(0.36f, 0.24f, 0.14f, 1f), rope = new Color(0.62f, 0.50f, 0.30f, 1f);
        Color painted = new Color(0.86f, 0.36f, 0.16f, 1f);         // Build 72: on a plank conveyor every fourth plank is painted, so you can see the deck rolling left
        int n = Math.max(4, Math.round(w / 0.5f)); float pl = w / n;
        for (int i = 0; i < n; i++) {
            Part pk = boxPart(-w / 2f + (i + 0.5f) * pl, -0.10f, 0f, pl * (belt ? 0.82f : 0.92f), 0.16f, 1.5f, belt && i % 4 == 0 ? painted : i % 2 == 0 ? wood1 : wood2);
            if (belt) { pk.belt = true; pk.beltSpan = w; pk.beltInset = 0.31f; }      // Build 76 (owner): stop at the rollers instead of passing through them
            ps.add(pk);
        }
        if (belt) {           // a big iron roller under each end that the plank belt wraps round
            Color iron = new Color(0.40f, 0.42f, 0.48f, 1f);
            for (int sd = -1; sd <= 1; sd += 2) { Part rl = boxPart(sd * (w / 2f - 0.05f), -0.22f, 0f, 0.36f, 0.36f, 1.6f, iron); rl.roll = 45f; ps.add(rl); }
        }
        ps.add(boxPart(0f, -0.26f, 0.45f, w, 0.16f, 0.2f, beam));
        ps.add(boxPart(0f, -0.26f, -0.45f, w, 0.16f, 0.2f, beam));
        int posts = Math.max(2, Math.round(w / 1.75f) + 1);
        for (int i = 0; i < posts; i++) {
            float x = -w / 2f + 0.15f + i * (w - 0.3f) / (posts - 1);
            ps.add(boxPart(x, 0.38f, 0.62f, 0.14f, 0.92f, 0.14f, beam));
        }
        ps.add(boxPart(0f, 0.78f, 0.62f, w, 0.09f, 0.11f, rope));
        ps.add(boxPart(0f, 0.38f, 0.62f, w, 0.05f, 0.07f, rope));
        if (seesaw) {
            Color cap = new Color(0.85f, 0.18f, 0.16f, 1f), stone = new Color(0.46f, 0.47f, 0.52f, 1f), dark = new Color(0.30f, 0.31f, 0.36f, 1f);
            ps.add(boxPart(-w / 2f + 0.15f, -0.08f, 0f, 0.3f, 0.2f, 1.55f, cap));
            ps.add(boxPart(w / 2f - 0.15f, -0.08f, 0f, 0.3f, 0.2f, 1.55f, cap));
            Part[] f = {
                boxPart(0f, -2.3f, 0f, 2.6f, 0.5f, 1.5f, dark), boxPart(0f, -1.85f, 0f, 1.9f, 0.5f, 1.4f, stone),
                boxPart(0f, -1.4f, 0f, 1.3f, 0.5f, 1.3f, dark), boxPart(0f, -0.95f, 0f, 0.8f, 0.5f, 1.2f, stone), boxPart(0f, -0.52f, 0f, 0.45f, 0.4f, 1.1f, dark)};
            for (Part q : f) { q.fixed = true; ps.add(q); }
        }
    }

    private ModelInstance rainLow, rainHigh;
    private final Color rainCol = new Color(0.62f, 0.78f, 1f, 1f);
    private ModelInstance rainCloud(boolean low) {
        ModelInstance m = low ? rainLow : rainHigh;
        if (m == null) {
            m = new ModelInstance(models.pack(low ? "cloud_2" : "cloud_3"));
            m.materials.get(0).set(new BlendingAttribute(0.9f), ColorAttribute.createDiffuse(low ? new Color(0.42f, 0.38f, 0.74f, 1f) : new Color(0.94f, 0.95f, 1f, 1f)),
                    ColorAttribute.createEmissive(low ? 0.10f : 0.26f, low ? 0.08f : 0.26f, low ? 0.22f : 0.30f, 1f));
            if (low) rainLow = m; else rainHigh = m;
        }
        return m;
    }

    /**
     * A wet platform: a foreground rain cloud (purplish-blue underside, white above) over it, rain falling from the cloud onto the platform, and a wet shimmer on its top face. With reduced motion the
     * rain is a few still streaks and the shimmer does not move.
     */
    private void drawRain(Element e, float es, float ey, float camS, float time) {
        float w = e.w, cy = ey + 4.3f, sc = Math.max(0.9f, w * 0.23f);
        ModelInstance lo = rainCloud(true), hi = rainCloud(false);
        place(lo, es, cy, -2.2f, camS, sc, sc * 0.8f, sc, 0f); batch.render(lo, env);
        place(hi, es + 0.2f, cy + 0.55f * sc, -1.9f, camS, sc * 1.05f, sc * 0.9f, sc * 1.05f, 18f); batch.render(hi, env);
        // wet shimmer on the top face (clear of the platform top by 2 cm). Build 70 (owner: "rectangular pieces of plexiglass"): a puddle - rounded, wavy edges - with a
        // small second puddle beside it and a pale highlight gliding across, instead of one flat sheet over the whole top. The platform's grip is unchanged (Sim, T.wetGrip).
        int pv = Math.abs(Float.floatToIntBits(e.y) * 31 + (int) (e.w * 7f));                 // a fixed variant per platform
        float side = (pv & 4) == 0 ? 1f : -1f, off = side * w * 0.06f;
        drawPuddle(pv % 3, es + off, ey + SHIMMER_LIFT, 0f, camS, w * 0.78f, 0.02f, 1.25f, 0.55f, 0.72f, 0.92f);
        drawPuddle((pv + 1) % 3, es - side * w * 0.40f, ey + SHIMMER_LIFT, 0.18f, camS, Math.min(0.9f, w * 0.16f), 0.02f, 0.5f, 0.55f, 0.72f, 0.92f);
        float hl = reducedMotion ? 0f : MathUtils.sin(time * 1.3f) * w * 0.26f;
        drawPuddle((pv + 2) % 3, es + off + hl, ey + SHIMMER_LIFT + 0.01f, -0.1f, camS, w * 0.14f, 0.02f, 0.55f, 0.86f, 0.94f, 1f);
        if (reducedMotion) {
            for (int k = -2; k <= 2; k++) drawBox(es + k * w * 0.17f, ey + 0.4f, 0.1f, camS, 0.04f, cy - ey - 0.8f, 0.04f, rainCol.r, rainCol.g, rainCol.b);
        } else if (quality > 0) {
            float rate = (quality > 1 ? 70f : 40f) * frameDt;
            for (int n = (int) rate + (MathUtils.random() < rate - (int) rate ? 1 : 0); n > 0; n--)
                particles.spawn(es + MathUtils.random(-0.5f, 0.5f) * w * 0.8f, cy - 0.4f, 0f, -10f - MathUtils.random(3f), rainCol, 0.05f, 0f, (cy - ey - 0.4f) / 11.5f);
        }
    }
    static final float SHIMMER_LIFT = 0.02f;

    /** Block heights of the two mover models (block-moving-blue.obj is 0.5 m tall, block-moving.obj 0.3 m). */
    public static final float BLUE_MOVER_BLOCK_H = 0.5f, MOVER_BLOCK_H = 0.3f;
    /** Vertical offset that puts a mover block's top face exactly on the simulation surface (the blue block used to be placed as if it were 0.3 m tall, so it stood 0.2 m proud and the hero sank into it). */
    public static float moverBlockDy(Element.Type t) { return -(t == Element.Type.MOVE_H ? BLUE_MOVER_BLOCK_H : MOVER_BLOCK_H); }

    /** Build 79 (owner: "the bottoms of the big rocks get in the way... they cannot block platforms"): head room kept between a hanging rock's bottom and anything under it. */
    static final float ROCK_CLEAR = 3.2f;
    private final java.util.HashMap<String, float[]> rockBox = new java.util.HashMap<>();
    /**
     * The scale a rock hanging under platform idx may have: {@code sc}, or smaller so that its bottom (depth {@code base} plus its height below the platform's top) stays
     * ROCK_CLEAR above any platform, castle or rope top beneath its span; 0 (no rock) when even a small one would not fit or a rope runs up through the platform.
     */
    /** Build 79 (owner: "nothing inside castles"): platform e lies within a castle's walls (its span and height), so it gets no props or hanging rock. */
    private boolean insideCastle(Element e) {
        for (Element o : course.hazards) if (o.type == Element.Type.GATE && Math.abs(course.dsWrap(o.s, e.s)) < 2.5f + e.halfW() && e.y > o.y - 0.5f && e.y < o.y + o.len + 0.5f) return true;
        return false;
    }
    private float fitRock(int idx, Element e, String rock, float sc, float base, float w) {
        if (insideCastle(e)) return 0f;
        float[] bb = rockBox.get(rock);
        if (bb == null) { com.badlogic.gdx.math.collision.BoundingBox b = new com.badlogic.gdx.math.collision.BoundingBox(); models.space(rock).calculateBoundingBox(b); bb = new float[]{b.getHeight(), Math.max(b.getWidth(), b.getDepth())}; rockBox.put(rock, bb); }
        float span = Math.max(w * 0.5f + 0.5f, bb[1] * sc * 0.5f), room = 1e9f;
        for (int j = 0; j < course.size(); j++) {
            if (j == idx) continue;
            Element o = course.get(j);
            boolean rope = o.type == Element.Type.ROPE || o.type == Element.Type.TARZAN;
            if (!rope && !o.isPlatform()) continue;
            if (Math.abs(course.dsWrap(o.s, e.s)) > span + (rope ? 0.8f + (o.type == Element.Type.TARZAN ? o.len * (float) Math.sin(Math.min(1.5f, o.amp)) : 0f) : o.halfW())) continue;
            float top = rope ? o.y + 1.2f : o.y + (o.type == Element.Type.MOVE_V ? Math.abs(o.amp) : 0f);
            if (rope && o.y - o.len < e.y && top > e.y - base) return 0f;              // a rope running up past the platform: no rock in its way
            if (top >= e.y - 0.05f) continue;
            room = Math.min(room, e.y - top - ROCK_CLEAR);
        }
        for (Element o : course.hazards) {
            if (o.type != Element.Type.GATE || Math.abs(course.dsWrap(o.s, e.s)) > span + 2.5f) continue;
            float top = o.y + o.len;
            if (o.y >= e.y - 0.05f) continue;
            room = Math.min(room, e.y - Math.max(top, o.y) - ROCK_CLEAR);
        }
        float most = (room - base) / Math.max(0.01f, bb[0]);
        if (most >= sc) return sc;
        return most >= 0.3f ? most : 0f;
    }

    /** Space biome platform: a metal deck with beacon lights, hanging from a Space Kit asteroid. */
    private void spacePlatform(Array<Part> ps, Element e, int idx) {
        float w = e.w;
        Color deck = new Color(0.30f, 0.33f, 0.43f, 1f), plate = new Color(0.17f, 0.19f, 0.26f, 1f), glow = new Color(0.35f, 0.92f, 1f, 1f);
        if (e.type == Element.Type.CRUMBLE) { deck = new Color(0.52f, 0.40f, 0.34f, 1f); glow = new Color(1f, 0.55f, 0.25f, 1f); }
        ps.add(boxPart(0f, -0.15f, 0f, w * 0.985f, 0.30f, 1.7f, deck));
        ps.add(boxPart(0f, -0.36f, 0f, w * 0.90f, 0.12f, 1.5f, plate));
        Part strip = boxPart(0f, -0.04f, -0.86f, w * 0.97f, 0.05f, 0.05f, glow); strip.tintAttr.color.set(glow); ps.add(strip);
        for (int sd = -1; sd <= 1; sd += 2) {
            float x = sd * (w / 2f - 0.18f);
            ps.add(boxPart(x, 0.18f, 0.72f, 0.08f, 0.36f, 0.08f, plate));
            ps.add(boxPart(x, 0.42f, 0.72f, 0.15f, 0.12f, 0.15f, glow));
        }
        if (quality > 0) {
            int h = hash(idx, 23);
            boolean big = w >= 4.5f;
            String rock = big ? new String[]{"rock_large_1", "rock_large_2", "rock_large_3"}[h % 3] : new String[]{"rock_1", "rock_2", "rock_3", "rock_4"}[h % 4];
            float sc = Math.max(0.35f, w * 1.1f / (big ? 7.6f : 3.0f)), top = big ? 0.3f : 0.4f;
            sc = fitRock(idx, e, rock, sc, 0.42f, w);              // Build 79 (owner): big rocks are fine, but never over a platform, rope or castle below
            if (sc > 0f) {
                Part rk = part(models.space(rock), ((h >> 5) % 5 - 2) * 0.1f * w, -0.42f - top * sc, ((h >> 9) % 3 - 1) * 0.2f, sc, sc, sc);
                rk.roll = 180f; rk.yaw = (h >> 3) % 360; rk.tinted = false; ps.add(rk);
            }
            Part small = part(models.space("rock_" + (1 + (h >> 11) % 4)), -w * 0.25f + ((h >> 13) % 5) * w * 0.12f, 0f, 0.55f, 0.16f, 0.16f, 0.16f);
            small.yaw = (h >> 17) % 360; small.tinted = false; ps.add(small);
        }
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
            // Back-of-slab decoration: pushed back (+dz) and flattened in depth (sz) so the whole prop sits behind the walking lane (its front face clears z ~ -0.26, behind the climber's ~+/-0.2 body)
            // while its back still stays within the slab (z >= -0.85). The player can now stand fully in front of trees/rocks instead of clipping into them. Width and height (sx, sy) are unchanged, so the front silhouette is unaffected.
            Part p = part(models.obj(name), side, 0f, 0.6f, 1.35f, 1.35f, 0.58f);
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
        return Math.abs(d) < 1.3f * T.radius * 1.0f + margin && Math.abs(y - camY) < 30f;   // vertical draw reach: show ~30 units of the climb ahead/below (was 16, "only a few tiles"); the sim window already streams far more than this, and the distance-fog fades the far edge in
    }

    public void render(Sim sim, float alpha, float dt, float time, boolean showPlayer) {
        applyPalette();
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
        float bgY = (float) ((camY + originY) % 65536.0);          // continuous across origin moves (and across the shift, nothing pops); wraps only every 65 km
        float zf = (float) Math.max(0.0, (camY + originY) / T.zoneHeight);      // Build 70 (owner): below the start is still the start area (Meadow), never the last world wrapped round
        float zoneF = Math.min(Palette.ZONES - 0.001f, ((zf % Palette.ZONES) + Palette.ZONES) % Palette.ZONES);        // the five worlds repeat for ever
        Palette.blend(Palette.SKY_TOP, zoneF, skyTop); Palette.blend(Palette.SKY_BOT, zoneF, skyBot); Palette.blend(Palette.SKY_MID, zoneF, skyMid);
        if (zoneF < 2f) {         // Build 77 (owner): the sun comes up as you climb the two morning worlds - the violet low sky slowly lifts and lightens
            float rise = MathUtils.clamp(zoneF / 1.8f, 0f, 1f) * 0.35f;
            skyBot.lerp(skyMid, rise); skyMid.lerp(skyTop, rise * 0.4f);
        }
        Palette.blend(Palette.TINT, zoneF, tint); Palette.blend(Palette.AMBIENT, zoneF, amb);
        float gold = goldenHour(zoneF);            // Build 71 (owner): castle 1 to castle 2 is a late-afternoon Meadow, not just more of the start
        if (gold > 0f) {
            skyTop.lerp(0.46f, 0.66f, 0.96f, 1f, gold * 0.6f); skyBot.lerp(0.86f, 0.70f, 0.62f, 1f, gold * 0.6f); skyMid.lerp(0.90f, 0.82f, 0.74f, 1f, gold * 0.6f);      // Build 77: in the morning worlds the golden stretch warms the low sky to cream, not sunset orange
            tint.lerp(1.00f, 0.93f, 0.82f, 1f, gold); amb.lerp(0.66f, 0.58f, 0.52f, 1f, gold);
        }
        Grade.saturate(skyTop); Grade.saturate(skyBot); Grade.saturate(skyMid);
        { float mw = Palette.blendF(Palette.SKY_MORNING, zoneF); float r0 = MathUtils.lerp(skyBot.r, skyTop.r, Palette.SKY_MID_AT), g0 = MathUtils.lerp(skyBot.g, skyTop.g, Palette.SKY_MID_AT), b0 = MathUtils.lerp(skyBot.b, skyTop.b, Palette.SKY_MID_AT);
          skyMid.set(MathUtils.lerp(r0, skyMid.r, mw), MathUtils.lerp(g0, skyMid.g, mw), MathUtils.lerp(b0, skyMid.b, mw), 1f); }      // two-colour skies stay exactly as they were          // Build 72 (owner): richer colour, the sky included
        ((ColorAttribute) env.get(ColorAttribute.AmbientLight)).color.set(amb.r * Grade.AMBIENT, amb.g * Grade.AMBIENT, amb.b * Grade.AMBIENT, 1f);
        Palette.blend(Palette.SUN, zoneF, lc); if (gold > 0f) lc.lerp(1.00f, 0.78f, 0.52f, 1f, gold); sun.color.set(lc.r * Grade.KEY, lc.g * Grade.KEY, lc.b * Grade.KEY, 1f); Palette.blendDir(Palette.SUN_DIR, zoneF, lv); if (gold > 0f) lv.lerp(GOLD_SUN, gold).nor(); sun.direction.set(lv);
        Palette.blend(Palette.FILL, zoneF, lc); fillL.color.set(lc);
        Palette.blend(Palette.BOUNCE, zoneF, lc); bounceL.color.set(lc);
        // the hero gets his own three-point rig: a stronger key, a fill, the bounce, and a contrasting rim light from behind so his silhouette always separates from the background
        ((ColorAttribute) heroEnv.get(ColorAttribute.AmbientLight)).color.set(amb.r + 0.10f, amb.g + 0.10f, amb.b + 0.10f, 1f);
        Palette.blend(Palette.SUN, zoneF, lc); hKey.color.set(lc.r * 1.15f, lc.g * 1.15f, lc.b * 1.15f, 1f); hKey.direction.set(lv);
        Palette.blend(Palette.FILL, zoneF, lc); hFill.color.set(lc.r * 1.4f, lc.g * 1.4f, lc.b * 1.4f, 1f);
        Palette.blend(Palette.BOUNCE, zoneF, lc); hBounce.color.set(lc);
        Palette.blend(Palette.RIM, zoneF, lc); hRim.color.set(lc.r * 0.95f, lc.g * 0.95f, lc.b * 0.95f, 1f);
        ((ColorAttribute) env.get(ColorAttribute.Fog)).color.set(skyBot).lerp(skyTop, 0.25f);
        if (atmos != null) {          // atmosphere prototype: the castle's colour on the sky (and, unless colourblind-safe colours are on, the world's lights - never the hero's), plus this frame's haze, mist and glow
            atmos.frame(zoneF, camY + originY, T.castleSpacing, T.zoneHeight * T.zoneCount, quality, colourblind, gold, skyTop, skyMid, skyBot);
            ((ColorAttribute) env.get(ColorAttribute.Fog)).color.set(skyBot).lerp(skyTop, 0.25f);
            float[] lk = atmos.light;
            ((ColorAttribute) env.get(ColorAttribute.AmbientLight)).color.mul(lk[0], lk[1], lk[2], 1f); sun.color.mul(lk[0], lk[1], lk[2], 1f);
        }

        // camera
        float sh = 0;
        if (shakeT > 0 && !reducedMotion) { shakeT -= dt; sh = camShake * MathUtils.sin(time * 70f) * (shakeT / 0.25f); } else camShake = 0;
        fovV += (-90f * fovK - 11f * fovV) * dt; fovK += fovV * dt; cam.fieldOfView = 40f + (reducedMotion ? 0f : fovK);
        for (int k = 0; k < dipN; ) {
            int di = dipAct[k];
            dipV[di] += (-160f * dip[di] - 12f * dipV[di]) * dt; dip[di] += dipV[di] * dt;
            if (Math.abs(dip[di]) < 0.002f && Math.abs(dipV[di]) < 0.02f) { dip[di] = 0; dipV[di] = 0; dipOn[di] = false; dipAct[k] = dipAct[--dipN]; }
            else k++;
        }
        if (quality > 0 && !reducedMotion) ambientMotes(dt, zoneF, ps);
        if (atmos != null) { if (atmosMotes == null) atmosMotes = new AtmosMotes(models); atmosMotes.update(frameDt, zoneF, ps, camY, quality >= 2 && !reducedMotion); }
        float pullT = (!reducedMotion && sim.mode == Sim.Mode.AIR && sim.vy < -10f) ? MathUtils.clamp((-sim.vy - 10f) / 14f, 0f, 1f) * 2.4f : 0f;       // pull back in a long fall to see where you will land
        camPull += (pullT - camPull) * Math.min(1f, 3f * dt);
        cam.position.set(sh * 0.1f, camY + (CAM_DIST < 6f ? -0.6f : 2.2f) + sh * 0.1f, CAM_DIST + camPull);
        cam.lookAt(0f, camY + (CAM_DIST < 6f ? -1.15f : 1.15f), -0.4f);
        cam.up.set(0, 1, 0);
        cam.update();

        boolean scaled = beginScaledScene();
        if (!scaled) Gdx.gl.glViewport(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
        Gdx.gl.glClearColor(skyBot.r, skyBot.g, skyBot.b, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
        bg.render(sb, shapes, skyTop, skyMid, skyBot, ps, bgY, zoneF, T.circumference(), reducedMotion, time);
        if (atmos != null) atmos.drawSky(shapes, cam.viewportWidth, cam.viewportHeight);       // atmosphere prototype: deeper zenith, haze rising from below (under the sun glow and the far layer)
        Gdx.gl.glClear(GL20.GL_DEPTH_BUFFER_BIT);
        if (quality > 0 && GfxHooks.skyEffects) bg.renderGlow(sb, zoneF);
        if (quality > 0) {                 // planets and sky ships far behind the tower (wide-range camera, drawn first)
            if (space == null) { farCam = new com.badlogic.gdx.graphics.PerspectiveCamera(40f, cam.viewportWidth, cam.viewportHeight); farCam.near = 1f; farCam.far = 2500f; space = new SpaceScene(models, farCam, T.radius); }
            space.cloudGlow.set(skyTop).lerp(skyBot, 0.5f).lerp(Color.WHITE, 0.4f);
            space.playerCharacter = character;
            space.setZone(zoneF);
            space.update(frameDt, py, reducedMotion);
            if (space.whoosh) whooshPending = true;
            if (space.scanSound) scanPending = true;
            farCam.viewportWidth = cam.viewportWidth; farCam.viewportHeight = cam.viewportHeight; farCam.fieldOfView = cam.fieldOfView;
            farCam.position.set(cam.position); farCam.direction.set(cam.direction); farCam.up.set(cam.up); farCam.update();
            if (atmos != null) Atmos.fogFarLayer(space.env, ((ColorAttribute) env.get(ColorAttribute.Fog)).color);       // atmosphere prototype: the far layer takes the haze
            if (GfxHooks.skyEffects) { batch.begin(farCam); space.renderFar(batch, ps, bgY, zoneF); batch.end(); }
            Gdx.gl.glClear(GL20.GL_DEPTH_BUFFER_BIT);
        }

        if (quality > 0 && GfxHooks.skyEffects) bg.renderShafts(sb, zoneF, reducedMotion, time);
        batch.begin(cam);
        syncVis();
        int lo = Math.max(0, sim.winLo), hi = Math.min(vis.size() - 1, sim.winHi);
        if (clouds == null) clouds = new Clouds(models, T, course);
        boolean inSpace = zoneF >= 3.9f && zoneF < Palette.ZONES - 0.1f;           // no weather in deep space
        clouds.setOrigin(originY, originS);
        if (!inSpace) clouds.update(ps, camY, ps, py, lo, hi, dt, reducedMotion, quality);
        cloudsBroken += inSpace ? 0 : clouds.brokenThisFrame;
        if (!inSpace) clouds.render(batch, env, (inst, arc, yy, dz, sx, sy, sz, yaw) -> place(inst, arc, yy, dz, ps, sx, sy, sz, yaw), ps, camY);
        for (int i = lo; i <= hi; i++) {
            Vis v = vis.get(i); Element e = v.e;
            float es = sim.es1[i] , ey = sim.ey1[i];
            if (e.isMoving()) { // interpolate moving platforms
                es = sim.es0[i] + course.dsWrap(sim.es1[i], sim.es0[i]) * alpha; ey = sim.ey0[i] + (sim.ey1[i] - sim.ey0[i]) * alpha;
            }
            float vy = e.type == Element.Type.ROPE || e.type == Element.Type.TARZAN ? e.y - e.len * 0.5f : ey;
            if (!visible(es, vy, ps, e.type == Element.Type.CABLE ? e.w * 0.5f : e.type == Element.Type.TARZAN ? 3f + e.len : 3f)) continue;
            if (!v.built) build(i);
            drawElement(sim, i, v, es, ey, ps, time);
        }
        for (int k = 0, cnt = sim.hz == null ? course.hazards.size() : sim.hz.length; k < cnt; k++) { int hi2 = sim.hz == null ? k : sim.hz[k]; rsim = sim; rhz = hi2; drawHazard(course.hazards.get(hi2), sim.time + alpha * Sim.DT, ps, time, hi2 < sim.featDone.length && sim.featDone[hi2]); }
        if (space != null && quality > 0) space.renderNear(batch, env, (inst, arc, yy, dz, sx, sy, sz, yaw) -> place(inst, arc, yy, dz, ps, sx, sy, sz, yaw), ps, camY, py, 0f, reducedMotion, originY);
        // free the model parts of anything the player has left behind, above or below the simulated window (a fall or a long descent can build far more than a climb ever does); freed parts are rebuilt on demand
        for (int q = 0, n = Math.min(builtN, 48); q < n && builtN > 0; q++) {
            if (builtScan >= builtN) builtScan = 0;
            int bi = builtIdx[builtScan];
            if (bi < lo - 12 || bi > hi + 12) { Vis pv = vis.get(bi); pv.parts = null; pv.built = false; builtIdx[builtScan] = builtIdx[--builtN]; }
            else builtScan++;
        }
        drawFlung(dt, ps);
        if (showPlayer) drawPlayer(sim, dt, time, ps, py, alpha);
        particles.render(batch, env, ps, T, course);
        if (atmosMotes != null && quality >= 2 && !reducedMotion) atmosMotes.render(batch, env, ps, T, course);
        batch.end();
        drawShadows(sim, alpha, ps, py, zoneF);
        if (scaled) endScaledScene();
        drawVignette(Palette.blendF(Palette.VIGNETTE, zoneF));
    }

    // ---- optional reduced-resolution scene (GfxHooks.renderScale < 1): the 3D scene is drawn into a smaller buffer and stretched to the window; at 1 nothing here runs
    private com.badlogic.gdx.graphics.glutils.FrameBuffer sceneFbo;

    private boolean beginScaledScene() {
        float k = GfxHooks.renderScale;
        if (!(k < 0.99f)) { if (sceneFbo != null) { sceneFbo.dispose(); sceneFbo = null; } return false; }
        int bw = Math.max(1, Gdx.graphics.getBackBufferWidth()), bh = Math.max(1, Gdx.graphics.getBackBufferHeight());
        int w = Math.max(64, Math.round(bw * Math.max(0.25f, k))), h = Math.max(36, Math.round(bh * Math.max(0.25f, k)));
        try {
            if (sceneFbo == null || sceneFbo.getWidth() != w || sceneFbo.getHeight() != h) {
                if (sceneFbo != null) sceneFbo.dispose();
                sceneFbo = new com.badlogic.gdx.graphics.glutils.FrameBuffer(com.badlogic.gdx.graphics.Pixmap.Format.RGBA8888, w, h, true, true);      // with stencil, for the shadows (ShadowPass)
                sceneFbo.getColorBufferTexture().setFilter(com.badlogic.gdx.graphics.Texture.TextureFilter.Linear, com.badlogic.gdx.graphics.Texture.TextureFilter.Linear);
            }
            sceneFbo.begin();
            return true;
        } catch (RuntimeException e) {          // this graphics driver cannot do it: draw at full size from now on
            if (sceneFbo != null) { try { sceneFbo.dispose(); } catch (RuntimeException ignored) { } sceneFbo = null; }
            GfxHooks.renderScale = 1f;
            return false;
        }
    }

    private void endScaledScene() {
        sceneFbo.end();
        int bw = Math.max(1, Gdx.graphics.getBackBufferWidth()), bh = Math.max(1, Gdx.graphics.getBackBufferHeight());
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
        sb.getProjectionMatrix().setToOrtho2D(0, 0, bw, bh); sb.setTransformMatrix(new com.badlogic.gdx.math.Matrix4());
        sb.begin(); sb.disableBlending(); sb.setColor(Color.WHITE);
        sb.draw(sceneFbo.getColorBufferTexture(), 0, 0, bw, bh, 0, 0, 1, 1);         // v runs bottom-to-top in a framebuffer texture, so (0,0)-(1,1) is upright here
        sb.end(); sb.enableBlending();
    }

    /** A soft dark vignette that pulls the eye to the middle of the screen (stronger at night and in space). */
    private void drawVignette(float strength) {
        if (vignette == null) {
            int n = 128; com.badlogic.gdx.graphics.Pixmap pm = new com.badlogic.gdx.graphics.Pixmap(n, n, com.badlogic.gdx.graphics.Pixmap.Format.RGBA8888);
            for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
                float dx = (x + 0.5f) / n * 2f - 1f, dy = (y + 0.5f) / n * 2f - 1f, r = (float) Math.sqrt(dx * dx * 0.8f + dy * dy);
                float a = MathUtils.clamp((r - 0.55f) / 0.75f, 0f, 1f); a = a * a;
                pm.setColor(0f, 0f, 0.03f, a); pm.drawPixel(x, y);
            }
            vignette = new com.badlogic.gdx.graphics.Texture(pm); vignette.setFilter(com.badlogic.gdx.graphics.Texture.TextureFilter.Linear, com.badlogic.gdx.graphics.Texture.TextureFilter.Linear); pm.dispose();
        }
        float w = Gdx.graphics.getWidth(), h = Gdx.graphics.getHeight();
        sb.getProjectionMatrix().setToOrtho2D(0, 0, w, h); sb.enableBlending();
        sb.begin(); sb.setColor(1f, 1f, 1f, strength * 2.2f); sb.draw(vignette, 0, 0, w, h); sb.setColor(Color.WHITE); sb.end();
    }

    private void drawElement(Sim sim, int i, Vis v, float es, float ey, float camS, float time) {
        Element e = v.e;
        float shakeX = 0, fallY = 0;
        boolean gone = sim.gone[i];
        float crumble = sim.crumbleT[i];
        if (e.tiltBelt()) { float st = sim.time - v.beltT; if (st > 0f && st < 0.25f) v.beltU += sim.surfaceCarry(i) * st; v.beltT = sim.time; }
        for (Part p : v.parts) {
            float du = p.du, dy = p.dy, extraYaw = p.yaw; float sx = p.sx, sy = p.sy, roll = p.roll;
            float y = e.isPlatform() ? ey - Math.max(-0.1f, Math.min(0.22f, dip[i])) : ey;
            if (e.type == Element.Type.RAMP) {
                y = e.y;
                if (e.skin == 2) { float on = Math.min(1f, sim.onT[i] / Sim.RAMP_SHAKE_EVERY), a = reducedMotion ? 0f : 0.06f + 0.16f * on; shakeX = a * MathUtils.sin(time * 85f + p.du); y += a * 0.8f * MathUtils.sin(time * 97f); }   // Build 72: a much harder tremble
                if (e.spins() && !p.fixed) extraYaw = p.yaw - e.spinAngle(sim.time) * MathUtils.radiansToDegrees;      // turning like a record (heading 0 = uphill toward +s)
            }
            if (p.belt) {       // Build 72: a conveyor slat rides along the belt and wraps round to the far end
                float span = p.beltSpan, u = ((p.du + span / 2f + (e.tiltBelt() ? v.beltU : e.beltV() * sim.time)) % span + span) % span - span / 2f;
                // Build 76 (owner: the planks clipped through the end rollers): the slat is cut where it reaches a roller, so it shrinks into one end and grows out of the other
                float lo = Math.max(u - p.sx / 2f, -span / 2f + p.beltInset), hi = Math.min(u + p.sx / 2f, span / 2f - p.beltInset);
                if (hi - lo < 0.02f) continue;
                u = (lo + hi) / 2f; sx = hi - lo;
                dy = p.dy + p.beltSlope * (u - p.du); du = u;
            }
            if (e.type == Element.Type.SEESAW) {
                y = e.y - Math.max(-0.1f, Math.min(0.22f, dip[i]));
                if (!p.fixed) {         // tilt the plank about its pivot
                    float th = (float) Math.atan(sim.tilt[i]), c = (float) Math.cos(th), sn = (float) Math.sin(th);
                    float u0 = du, v0 = dy; du = u0 * c - v0 * sn; dy = u0 * sn + v0 * c; roll = p.roll + th * MathUtils.radiansToDegrees;      // (du, dy: after a belt slat has moved along)
                }
            }
            switch (e.type) {
                case ROPE: y = e.y; break;
                case TARZAN: {
                    y = e.y;
                    if (!p.fixed) { float th = e.tarzanAngle(sim.time), c = (float) Math.cos(th), sn = (float) Math.sin(th), u0 = du, v0 = dy; du = u0 * c - v0 * sn; dy = u0 * sn + v0 * c; roll = p.roll + th * MathUtils.radiansToDegrees; }
                    break;
                }
                case SWING: {
                    // platform hangs from a pivot: ropes are drawn separately below
                    break;
                }
                default: break;
            }
            float holeY = 0f, holeX = 0f;
            if (p.hole >= 0) {                                 // Build 79: a drop-out plank: it rattles once you land, then falls and is gone until the bridge resets
                float ct = i < sim.crumbleT.length ? sim.crumbleT[i] : -1f;
                if (ct >= Element.HOLE_DELAY) { float el = ct - Element.HOLE_DELAY; if (el > 1.1f) continue; holeY = -0.5f * 30f * el * el; extraYaw = el * 50f * (p.du >= 0 ? 1 : -1); }
                else if (ct >= 0f && !reducedMotion) holeX = 0.04f * MathUtils.sin(time * 80f + p.du * 7f);
            }
            if (e.crumbles()) {
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
            if (p.tintAttr != null && e.type == Element.Type.RAMP && p.fixed == false && p.tinted == false && p.color == null) {
                if (e.skin == 1) p.tintAttr.color.set(1f, 0.78f, 0.6f, 1f); else if (e.skin == 2) p.tintAttr.color.set(1f, 0.9f, 0.55f, 1f); else if (e.skin == 4) p.tintAttr.color.set(0.62f, 0.64f, 0.70f, 1f); else if (e.skin == 5) p.tintAttr.color.set(1f, 0.66f, 0.52f, 1f); else if (e.skin == 3) p.tintAttr.color.set(0.8f, 0.95f, 1f, 1f); else p.tintAttr.color.set(Color.WHITE);
            } else if (p.tintAttr != null) {
                if (p.color != null && p.fall) p.tintAttr.color.set(p.color);          // crumbling tiles keep their warm tint
                else if (p.tinted) p.tintAttr.color.set(tint);
                else p.tintAttr.color.set(Color.WHITE);
            }
            place(p.inst, es + du + shakeX + holeX, y + dy + fallY + holeY, p.dz + (e.type == Element.Type.MOVE_Z ? e.zAt(sim.time) : 0f), camS, sx, sy, p.sz, extraYaw, roll);
            if (p.localZ != 0f) p.inst.transform.translate(0f, 0f, p.localZ);     // recentre an off-origin mesh along its own Z (applied before scale): see Part.localZ / the ramp
            batch.render(p.inst, env);
        }
        if (e.wet()) drawRain(e, es, ey - (i < dip.length ? Math.max(-0.1f, Math.min(0.22f, dip[i])) : 0f), camS, time);      // Build 71 (owner): the puddle dips and springs back with its platform when you land
        if (e.type == Element.Type.MOVE_Z) drawDepthRail(e, es, ey, camS, sim.time);
        if (course.isCheckpoint(e) && i > 0 && e.type == Element.Type.STATIC) drawGem(sim, i, es, ey, camS, time);
        if (e.type == Element.Type.SWING) drawSwingRopes(e, es, ey, camS);
        if (e.type == Element.Type.MOVE_V) drawRail(e, camS, true);
        if (e.type == Element.Type.MOVE_H) drawRail(e, camS, false);
    }


    // ------------------------------------------------------------------ checkpoint gems
    private ModelInstance gem, gemGlow;
    private ColorAttribute gemDiff, gemEmis, glowEmis;
    private BlendingAttribute gemBlend, glowBlend;
    private final java.util.HashMap<Integer, Float> gemAct = new java.util.HashMap<>();
    private final java.util.HashSet<Integer> gemSeenWaiting = new java.util.HashSet<>();      // checkpoints this renderer has seen not yet reached
    private final Color keyCol = new Color(1f, 1f, 1f, 1f);
    private final Color gemRed = new Color(1f, 0.12f, 0.16f, 1f);

    /** Diagnostic hook: the solid checkpoint gem as last drawn (its transform is the one the frame used). */
    public ModelInstance gemDiag() { return gem; }

    private void initGem() {
        gem = new ModelInstance(models.pack("gem_pink")); gemGlow = new ModelInstance(models.pack("gem_pink"));
        for (com.badlogic.gdx.graphics.g3d.Material m : gem.materials) { gemDiff = ColorAttribute.createDiffuse(1f, 0.1f, 0.15f, 1f); gemEmis = ColorAttribute.createEmissive(0.6f, 0.05f, 0.08f, 1f); gemBlend = new BlendingAttribute(true, 1f); m.set(gemDiff, gemEmis, gemBlend); }
        for (com.badlogic.gdx.graphics.g3d.Material m : gemGlow.materials) { glowEmis = ColorAttribute.createEmissive(1f, 0.1f, 0.12f, 1f); glowBlend = new BlendingAttribute(true, 0.15f); m.set(ColorAttribute.createDiffuse(1f, 0.1f, 0.12f, 1f), glowEmis, glowBlend); }
    }

    /** A red gem hovers above every checkpoint platform: it bobs, wobbles and glows until the checkpoint is reached, pops, and then stays as a small dim marker. */
    private void drawGem(Sim sim, int i, float es, float ey, float camS, float time) {
        if (gem == null) initGem();
        boolean active = sim.checkpoint >= i;
        Float t0 = gemAct.get(i);
        if (!active) gemSeenWaiting.add(i);
        if (active && t0 == null) {
            // the pop and its red dust only happen when the player really reaches the gem; a hero who simply starts or resumes standing at a checkpoint gets no dust over his head
            if (sim.checkpoint > i || !gemSeenWaiting.contains(i)) t0 = -1000f; else { t0 = time; particles.burst(es, ey + CheckpointGem.LIFT, 16, gemRed, 2.6f, 3.4f, 0.1f, -1f, 1.1f); }
            gemAct.put(i, t0);
        } else if (!active && t0 != null) { gemAct.remove(i); t0 = null; }      // respawned behind a gem that was reached: the world was reset
        // owner rule (Build 71): the gem's bottom edge never rises above the standing character's face, even at the top of its bob - it is walked into, never jumped for
        float ph = i * 1.7f, base = 0.46f;
        float yy = ey + CheckpointGem.LIFT + (reducedMotion ? 0f : CheckpointGem.BOB * MathUtils.sin(time * 2.1f + ph));
        float roll = reducedMotion ? 0f : CheckpointGem.ROLL_DEG * MathUtils.sin(time * 1.7f + ph), yaw = reducedMotion ? 0f : time * 55f + ph * 40f;
        float pulse = 0.5f + 0.5f * MathUtils.sin(time * 2.6f + ph);
        if (t0 == null) {                       // waiting to be reached
            gemEmis.color.set(0.45f + 0.35f * pulse, 0.04f, 0.07f, 1f); gemDiff.color.set(1f, 0.1f, 0.15f, 1f); gemBlend.opacity = 1f;
            place(gem, es, yy, 0f, camS, base, base, base, yaw, roll); batch.render(gem, env);
            glowBlend.opacity = 0.10f + 0.07f * pulse; glowEmis.color.set(1f, 0.1f, 0.12f, 1f);
            float gs = base * (1.7f + 0.15f * pulse);
            place(gemGlow, es, yy, 0f, camS, gs, gs, gs, yaw, roll); batch.render(gemGlow, env);
            if (!reducedMotion && quality > 0 && MathUtils.random() < frameDt * 2.5f)
                particles.spawn(es + MathUtils.random(-0.25f, 0.25f), yy - 0.2f, MathUtils.random(-0.1f, 0.1f), MathUtils.random(0.25f, 0.55f), gemRed, 0.05f, 0f, 1.1f);
        } else {
            float age = time - t0;
            if (age < 0.55f) {                  // pop
                float k = age / 0.55f, sc = base * (1f + 1.2f * k);
                gemEmis.color.set(1f, 0.3f, 0.3f, 1f); gemBlend.opacity = 1f - k;
                place(gem, es, yy + 0.4f * k, 0f, camS, sc, sc, sc, yaw * 3f, roll); batch.render(gem, env);
            } else {                            // spent: a small, dim marker on the platform
                gemDiff.color.set(0.42f, 0.07f, 0.1f, 1f); gemEmis.color.set(0.05f, 0f, 0.01f, 1f); gemBlend.opacity = 1f;
                float sc = base * 0.5f;
                place(gem, es, ey + 0.62f, 0f, camS, sc, sc, sc, reducedMotion ? 0f : time * 20f + ph * 40f, 0f); batch.render(gem, env);
            }
        }
    }

    // ------------------------------------------------------------------ hazards (Quaternius Ultimate Platformer Pack, CC0)

    private static final float PK = 0.37f;                    // pack units -> game units (same scale as the hero)
    private final java.util.HashMap<String, ModelInstance> packInst = new java.util.HashMap<>();
    /**
     * The cannon pack model (Quaternius): its barrel runs along its own +X, muzzle at +X 2.26 units from the axle and the lit fuse at -X; the wheels
     * (node n1) sit on an axle along Z and the barrel with its cradle and fuse are node n0; the barrel's axis is 1.02 units above the axle. Build 63:
     * the old drawing assumed the barrel ran along Z, so the side cannon faced the camera, the turret spun about its own barrel instead of turning, and
     * the mortar lay on its side (owner's PC report).
     */
    public static final float CANNON_AXIS_UP = 1.02f * PK, CANNON_WHEEL_R = 0.94f * PK;
    /** Height of the short plinth under a mortar's cap (cosmetic only; the simulation fires from the mouth, 3 m below the path, and nothing below the mouth is part of any clearance rule). */
    static final float MORTAR_PLINTH = 0.45f;
    /** Pedestal height under a side cannon: its barrel axis flies the ball 2.35 above the slab, the wheels stand on the 0.16 cap sunk 0.04 into it. */
    public static final float CANNON_LIFT = 2.35f - CANNON_AXIS_UP - CANNON_WHEEL_R - 0.16f + 0.04f;
    /** The same under an aimed turret, whose axis sits 2.0 above its slab. */
    public static final float AIM_LIFT = 2.0f - CANNON_AXIS_UP - CANNON_WHEEL_R - 0.16f + 0.04f;
    /** How far BEHIND the walking lane every cannon's own slab stands (owner: "a separate platform behind that... land on the grass and it's just behind it"), so the player never walks through it and lands on clear grass; the ball leaves the muzzle there and swoops into the play plane. */
    public static final float AIM_DEPTH = Float.parseFloat(System.getProperty("climb.aimDepth", "1.7"));
    /** Over this much of its flight a ball drawn from a cannon behind the lane glides forward into the play plane (where it is simulated). */
    private static final float BALL_SWOOP = 1.6f;
    /** Fraction of a side cannon's cycle its launch puff lasts (Build 67). */
    private static final float CANNON_PUFF = 0.05f;
    /** The cannon model's muzzle ring centre, in model units from the axle: the barrel runs along +X and its axis is 1.018 above the axle (the g3dj's ring vertices, x 2.258, y 0.026..2.011). */
    public static final float MOUTH_X = 2.258f, MOUTH_Y = 1.018f;
    /**
     * Where the drawn mouth is relative to the axle, in the cannon's own frame (x along the lane toward +s, y up, world metres) once the cradle is tilted
     * {@code tilt} degrees about the axle and the whole cannon is turned {@code yaw} (0 = muzzle toward +s, 180 = toward -s): the same rotations
     * {@link #drawCannon} applies, so a cannon placed with this lands its mouth exactly where the simulation launches its ball (Build 67, owner:
     * "they need to spawn from the center of the mouth of the cannon"). Pure, for the fit test.
     */
    public static float[] mouthFromAxle(float yaw, float tilt, float[] out) {
        double t = Math.toRadians(tilt), c = Math.cos(t), s = Math.sin(t);
        float x = (float) ((MOUTH_X * c - MOUTH_Y * s) * PK), y = (float) ((MOUTH_X * s + MOUTH_Y * c) * PK);
        x *= (float) Math.cos(Math.toRadians(yaw));           // Build 79: the turret turns flat like a record, so the mouth's reach along the lane shrinks through the turn (0 and 180 as before)
        out[0] = x; out[1] = y; return out;
    }
    /** Lane units (s) that a rigid length of {@code metres} along the lane covers at depth {@code dz} behind it: the ring there is drawn at radius - dz, so the same metres span more arc. */
    public static float laneUnits(float metres, float dz, float radius) { return metres * radius / (radius - dz); }
    private final float[] mouthTmp = new float[2];
    /** Dev hook (-Dclimb.hazMarks=true): small boxes at the simulation's own positions of cannon balls and mouths, to check the drawing against the rules. */
    private static final boolean HAZ_MARKS = Boolean.getBoolean("climb.hazMarks");
    /** Dev hook (-Dclimb.hazNoBody=true): leaves the cannon model out so the markers are not hidden by it. */
    private static final boolean HAZ_NO_BODY = Boolean.getBoolean("climb.hazNoBody");

    private ModelInstance pack(String name) {
        ModelInstance m = packInst.get(name);
        if (m == null) {
            m = new ModelInstance(models.pack(name)); packInst.put(name, m);
            if (name.startsWith("hazard_") || name.equals("spikes") || name.equals("cube_spikes") || name.equals("spikyball") || name.equals("cannonball") || name.equals("bomb") || name.equals("skull"))
                for (com.badlogic.gdx.graphics.g3d.Material mat : m.materials) mat.set(ColorAttribute.createEmissive(0.30f, 0.05f, 0.03f, 1f));       // dangers glow a little warm so they read against any background
        }
        return m;
    }

    /** A pack model stood on end (its barrel axis, model z, pointing up), centred at (arc, y). */
    /**
     * The cannon, axle at (arc, y, dz) with its wheels level: turned {@code yaw} degrees about the vertical (0 = muzzle toward +s, 180 = toward -s)
     * and its cradle (barrel, carriage and fuse, node n0) tilted {@code tilt} degrees about the axle, muzzle up for positive. See CANNON_AXIS_UP.
     */
    private void drawCannon(float arc, float y, float dz, float camS, float yaw, float tilt) {
        ModelInstance m = pack("cannon");
        com.badlogic.gdx.graphics.g3d.model.Node cradle = m.getNode("n0");
        if (cradle != null) { cradle.rotation.setFromAxis(0f, 0f, 1f, tilt); m.calculateTransforms(); }
        float phi = wrapDiff(arc, camS) / T.radius, r = T.radius - dz;
        m.transform.idt().translate(r * MathUtils.sin(phi), y, -T.radius + r * MathUtils.cos(phi))
                .rotate(0, 1, 0, phi * MathUtils.radiansToDegrees + yaw).scale(PK, PK, PK);
        batch.render(m, env);
        if (HAZ_MARKS && cradle != null) {          // dev: a magenta cube at the muzzle ring's centre and a cyan one at the axle, from the engine's own matrices
            com.badlogic.gdx.math.Vector3 muz = new com.badlogic.gdx.math.Vector3(2.258f, 1.018f, 0f).mul(cradle.globalTransform).mul(m.transform);
            com.badlogic.gdx.math.Vector3 ax = new com.badlogic.gdx.math.Vector3(0f, 0f, 0f).mul(m.transform);
            drawMarkCube(muz, 0.2f, 1f, 0f, 1f); drawMarkCube(ax, 0.12f, 0f, 1f, 1f);
            markLog("muzzle arc=" + arc + " y=" + y + " dz=" + dz + " yaw=" + yaw + " tilt=" + tilt, muz); markLog("axle", ax);
        }
    }
    private void drawMarkCube(com.badlogic.gdx.math.Vector3 at, float size, float cr, float cg, float cb) {
        int key = ((int) (cr * 255) << 16) | ((int) (cg * 255) << 8) | (int) (cb * 255);
        ModelInstance st = boxByColour.get(key);
        if (st == null) { st = new ModelInstance(models.box); st.materials.get(0).set(ColorAttribute.createDiffuse(cr, cg, cb, 1f)); boxByColour.put(key, st); }
        st.transform.idt().translate(at.x, at.y, at.z).scale(size, size, size);
        batch.render(st, env);
    }
    private final com.badlogic.gdx.math.Vector3 markTmp = new com.badlogic.gdx.math.Vector3();
    /** Dev (HAZ_MARKS): the world point of (arc, y, dz) in the camera's frame, as place() computes it. */
    private com.badlogic.gdx.math.Vector3 worldAt(float arc, float y, float dz, float camS, com.badlogic.gdx.math.Vector3 out) {
        float phi = wrapDiff(arc, camS) / T.radius, r = T.radius - dz;
        return out.set(r * MathUtils.sin(phi), y, -T.radius + r * MathUtils.cos(phi));
    }
    /** Dev (HAZ_MARKS): prints a world point and where the camera puts it on the back buffer (x right, y down). */
    private void markLog(String label, com.badlogic.gdx.math.Vector3 w) {
        float wx = w.x, wy = w.y, wz = w.z;
        markTmp.set(w); cam.project(markTmp, 0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
        System.out.printf(java.util.Locale.ROOT, "MARK %s world=(%.3f,%.3f,%.3f) screen=(%.1f,%.1f)%n", label, wx, wy, wz, markTmp.x, Gdx.graphics.getBackBufferHeight() - markTmp.y);
    }

    private final java.util.HashMap<String, ModelInstance> slabInst = new java.util.HashMap<>();
    private final java.util.HashMap<String, ColorAttribute> slabTint = new java.util.HashMap<>();
    /** A cannon's own small platform (owner, Build 63): a two-unit piece in the lane's style - grass, snow, or a metal deck in deep space - with its top at {@code top}, set {@code dz} behind the lane. */
    private void drawSlab(float arc, float top, float dz, float camS, int zone) {
        if (zone == Palette.SPACE) {
            drawBox(arc, top - 0.5f, dz, camS, 2.6f, 0.5f, 1.5f, 0.30f, 0.32f, 0.40f);
            drawBox(arc, top - 0.02f, dz, camS, 2.5f, 0.04f, 1.4f, 0.30f, 0.85f, 1f);
            return;
        }
        boolean snow = zone == 1 || zone == 3;
        String name = (snow ? "block-snow" : "block-grass") + "-low-long";
        ModelInstance m = slabInst.get(name);
        if (m == null) {
            m = new ModelInstance(models.obj(name)); ColorAttribute ca = ColorAttribute.createDiffuse(1f, 1f, 1f, 1f);
            for (com.badlogic.gdx.graphics.g3d.Material mat : m.materials) mat.set(ca);
            slabInst.put(name, m); slabTint.put(name, ca);
        }
        slabTint.get(name).color.set(tint);
        place(m, arc, top - 0.5f, dz, camS, 1.3f, 1f, 1.5f, 0f, 0f);       // 2.6 wide, 1.5 deep: it shows on both sides of the 1.25 pedestal and under all of it
        batch.render(m, env);
    }

    private void drawPack(String name, float arc, float y, float dz, float camS, float sx, float sy, float sz, float yaw, float roll) {
        ModelInstance m = pack(name);
        float phi = wrapDiff(arc, camS) / T.radius, r = T.radius - dz;
        m.transform.idt().translate(r * MathUtils.sin(phi), y, -T.radius + r * MathUtils.cos(phi))
                .rotate(0, 1, 0, phi * MathUtils.radiansToDegrees + yaw).rotate(0, 0, 1, roll).scale(sx, sy, sz);
        batch.render(m, env);
    }

    private float[] keyRgb(int color) { return KeyColors.rgb(color, colourblind); }
    /** Drops every instance coloured with the other palette when COLOURBLIND-SAFE COLOURS is toggled (from the pause menu's settings, say). */
    private void applyPalette() { if (paletteSafe != colourblind) { paletteSafe = colourblind; colorInst.clear(); gateInsts.clear(); markerInsts.clear(); } }
    private final java.util.HashMap<String, ModelInstance> colorInst = new java.util.HashMap<>();

    /** A pack model with its flag / key material recoloured for one of the four key colours. */
    private BlendingAttribute gateBlend = new BlendingAttribute(true, 0.38f);
    private final java.util.HashMap<Element, ModelInstance> gateInsts = new java.util.HashMap<>();
    /** One tower instance per castle so its fade-while-inside never leaks onto another castle of the same colour. */
    private ModelInstance gateInst(Element h) {
        ModelInstance m = gateInsts.get(h);
        if (m == null) {
            if (gateInsts.size() > 16) gateInsts.clear();
            m = new ModelInstance(models.pack("tower2"));          // the castle with a door on both sides (tools/make_tower_door.py)
            float[] c = keyRgb(h.color);
            for (com.badlogic.gdx.graphics.g3d.Material mat : m.materials) {
                if (mat.id.contains("Flag") || mat.id.contains("Gold") || mat.id.contains("Wood")) { mat.set(ColorAttribute.createDiffuse(c[0], c[1], c[2], 1f)); mat.set(ColorAttribute.createEmissive(c[0] * 0.25f, c[1] * 0.25f, c[2] * 0.25f, 1f)); }
            }
            gateInsts.put(h, m);
        }
        return m;
    }

    // ---- the shape marker that always goes with a key colour (red circle, blue square, green triangle, gold diamond): the identity of a key or
    // castle gate never depends on colour alone. One small solid, lit like the keys, floating above a key and in front of a castle's door wall.
    private final Model[] markerModels = new Model[4];
    private final java.util.HashMap<Integer, ModelInstance> markerInsts = new java.util.HashMap<>();
    private Model markerModel(int shape) {
        if (markerModels[shape] == null) {
            ModelBuilder mb = new ModelBuilder();
            com.badlogic.gdx.graphics.g3d.Material mat = new com.badlogic.gdx.graphics.g3d.Material(ColorAttribute.createDiffuse(1f, 1f, 1f, 1f));
            long attrs = com.badlogic.gdx.graphics.VertexAttributes.Usage.Position | com.badlogic.gdx.graphics.VertexAttributes.Usage.Normal;
            switch (shape) {
                case KeyColors.CIRCLE: markerModels[shape] = mb.createSphere(1f, 1f, 1f, 14, 10, mat, attrs); break;
                case KeyColors.TRIANGLE: markerModels[shape] = mb.createCone(1f, 1f, 1f, 3, mat, attrs); break;        // a three-sided cone reads as a triangle from the front
                default: markerModels[shape] = mb.createBox(0.85f, 0.85f, 0.85f, mat, attrs);                           // square; the diamond is the same box rolled 45 degrees
            }
        }
        return markerModels[shape];
    }
    private ModelInstance markerInst(int color) {
        int shape = KeyColors.shape(color);
        ModelInstance m = markerInsts.get(shape);
        if (m == null) {
            m = new ModelInstance(markerModel(shape));
            float[] c = keyRgb(color);
            for (com.badlogic.gdx.graphics.g3d.Material mat : m.materials) { mat.set(ColorAttribute.createDiffuse(c[0], c[1], c[2], 1f)); mat.set(ColorAttribute.createEmissive(c[0] * 0.35f, c[1] * 0.35f, c[2] * 0.35f, 1f)); }
            markerInsts.put(shape, m);
        }
        return m;
    }
    /** Draws the colour's shape marker at (arc, y), {@code dz} toward the tower axis (negative = toward the camera), {@code size} world units across. */
    private void drawMarker(int color, float arc, float y, float dz, float camS, float size) {
        ModelInstance m = markerInst(color);
        place(m, arc, y, dz, camS, size, size, size, 0f, KeyColors.shape(color) == KeyColors.DIAMOND ? 45f : 0f);
        batch.render(m, env);
    }

    private ModelInstance colored(String name, int color) {
        String k = name + color;
        ModelInstance m = colorInst.get(k);
        if (m == null) {
            m = new ModelInstance(models.pack(name));
            float[] c = keyRgb(color);
            for (com.badlogic.gdx.graphics.g3d.Material mat : m.materials) {
                if (mat.id.contains("Flag") || mat.id.contains("Gold") || (name.equals("tower") && mat.id.contains("Wood"))) { mat.set(ColorAttribute.createDiffuse(c[0], c[1], c[2], 1f)); mat.set(ColorAttribute.createEmissive(c[0] * 0.25f, c[1] * 0.25f, c[2] * 0.25f, 1f)); }
            }
            colorInst.put(k, m);
        }
        return m;
    }

    /** Animated pack creatures (crab walk cycle, bee flapping), one rig per element, created when first seen. */
    private static final class Rig { ModelInstance inst; com.badlogic.gdx.graphics.g3d.utils.AnimationController ctrl; String cur; }
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
            r.cur = anim;
            rigs.put(e, r);
        } else if (!anim.equals(r.cur)) { r.ctrl.animate(anim, -1, 1f, null, 0.15f); r.cur = anim; }      // Build 71: creatures change clip with their state (the skull's dash, the blob's leap)
        return r;
    }

    private void drawRig(Rig r, float arc, float y, float dz, float camS, float scale, float yaw, float roll, float speed) { drawRig(r, arc, y, dz, camS, scale, scale, scale, yaw, roll, speed); }

    private void drawRig(Rig r, float arc, float y, float dz, float camS, float sx, float sy, float sz, float yaw, float roll, float speed) {
        r.ctrl.update(frameDt * speed);
        float phi = wrapDiff(arc, camS) / T.radius, rr = T.radius - dz;
        r.inst.transform.idt().translate(rr * MathUtils.sin(phi), y, -T.radius + rr * MathUtils.cos(phi))
                .rotate(0, 1, 0, phi * MathUtils.radiansToDegrees + yaw).rotate(0, 0, 1, roll).scale(sx, sy, sz);
        batch.render(r.inst, env);
    }

    /** The Enforcer's body paint per tier (yellow, orange, red) and its size: the model is 4.48 units tall, drawn Element.ENF_H (1.6 m) tall at tier 0. */
    private static final float[][] ENF_TINT = {{0.98f, 0.80f, 0.16f}, {1.0f, 0.48f, 0.10f}, {0.86f, 0.13f, 0.11f}};
    private static final float ENF_SCALE = Element.ENF_H / 4.48f;
    /** The climbing rope's colour, shared by the horizontal traverse bar (cable) so both read the same against every sky. */
    static final Color ROPE_COLOR = new Color(0.86f, 0.70f, 0.42f, 1f);
    /** cube_spikes is 3.16 units across: drawn at the avalanche block's collision diameter. */
    private static final float AVL_SCALE = 2f * Element.AVL_R / 3.16f;
    private ModelInstance stone;
    private final Color tmpDoor = new Color();

    /** Two guide posts and a faint track in depth show where a depth mover travels; the rail brightens while the platform is in your plane (landable). */
    private void drawDepthRail(Element e, float es, float ey, float camS, float t) {
        float z = e.zAt(t), live = Math.abs(z) <= Element.Z_REACH ? 1f : 0f;
        drawBox(es, ey - 0.62f, -e.amp * 0.0f, camS, 0.12f, 0.1f, e.amp * 2f + 1.6f, 0.30f + 0.5f * live, 0.34f + 0.4f * live, 0.44f);
        // (no end posts: the two 0.9 m posts that stood beside the rail's ends read as stray mounts; owner playtest 2026-10-30)
    }

    /** A coloured block placed in a rig's model space (the base transform already holds its position, yaw, tilt and scale). */
    private void eyeBox(Matrix4 base, float lx, float ly, float lz, float w, float h, float d, float cr, float cg, float cb) {
        int key = ((int) (cr * 255) << 16) | ((int) (cg * 255) << 8) | (int) (cb * 255);
        ModelInstance st = boxByColour.get(key);
        if (st == null) { st = new ModelInstance(models.box); st.materials.get(0).set(ColorAttribute.createDiffuse(cr, cg, cb, 1f)); boxByColour.put(key, st); }
        st.transform.set(base).translate(lx, ly, lz).scale(w, h, d);
        batch.render(st, env);
    }

    private void drawBox(float arc, float y, float dz, float camS, float w, float h, float d, float cr, float cg, float cb) {
        // one instance per colour: the batch draws at end(), so a shared instance would take the colour of the last box of the frame
        int key = ((int) (cr * 255) << 16) | ((int) (cg * 255) << 8) | (int) (cb * 255);
        ModelInstance st = boxByColour.get(key);
        if (st == null) { st = new ModelInstance(models.box); st.materials.get(0).set(ColorAttribute.createDiffuse(cr, cg, cb, 1f)); boxByColour.put(key, st); }
        place(st, arc, y + h * 0.5f, dz, camS, w, h, d, 0f);
        batch.render(st, env);
    }
    private void drawBoxYaw(float arc, float y, float dz, float camS, float w, float h, float d, float yaw, float cr, float cg, float cb) {
        int key = ((int) (cr * 255) << 16) | ((int) (cg * 255) << 8) | (int) (cb * 255);
        ModelInstance st = boxByColour.get(key);
        if (st == null) { st = new ModelInstance(models.box); st.materials.get(0).set(ColorAttribute.createDiffuse(cr, cg, cb, 1f)); boxByColour.put(key, st); }
        place(st, arc, y + h * 0.5f, dz, camS, w, h, d, yaw);
        batch.render(st, env);
    }
    private final java.util.HashMap<Integer, ModelInstance> boxByColour = new java.util.HashMap<>();
    /** A flat puddle (Models.puddles), coloured like {@link #drawBox}: one instance per variant and colour. */
    private void drawPuddle(int variant, float arc, float y, float dz, float camS, float w, float h, float d, float cr, float cg, float cb) {
        int key = (variant << 24) | ((int) (cr * 255) << 16) | ((int) (cg * 255) << 8) | (int) (cb * 255);
        ModelInstance st = puddleByColour.get(key);
        if (st == null) { st = new ModelInstance(models.puddles[variant]); st.materials.get(0).set(ColorAttribute.createDiffuse(cr, cg, cb, 1f)); puddleByColour.put(key, st); }
        place(st, arc, y + h * 0.5f, dz, camS, w, h, d, 0f);
        batch.render(st, env);
    }
    private final java.util.HashMap<Integer, ModelInstance> puddleByColour = new java.util.HashMap<>();

    /**
     * The red spike mat lies on its platform: its bottom is buried in the slab, its top face used to be only 0.03 above the platform top and 0.01 under the player's blob shadow
     * (which lies 0.02-0.04 above whatever he stands over), so the three nearly coplanar surfaces fought for the depth buffer and the retracted mat flickered. The top now clears both by a safe margin.
     */
    public static final float SPIKE_MAT_BOTTOM = -0.04f, SPIKE_MAT_TOP = 0.06f, SHADOW_LIFT = 0.03f, SHADOW_THICK = 0.02f;

    /** A cannonball that has left the screen is not drawn: further than the widest view (21:9) reaches. (How many launches fly at once: Element.CANNON_TRAIL, shared with the simulation, which tests every one of them.) */
    private static final float CANNON_OFFSCREEN = 14f;

    private final Color mistCol = new Color();
    /**
     * How golden the light is (0..1): the Meadow's second half, castle 1 (500 m, zone 0.5) to castle 2, eases into late afternoon over the 50 m before castle 1
     * and hands over to the next world's own colours where the worlds already blend (the last fifth of a world). Only the first world has this second look.
     */
    private static final com.badlogic.gdx.math.Vector3 GOLD_SUN = new com.badlogic.gdx.math.Vector3(-0.85f, -0.45f, -0.55f).nor();      // a low afternoon sun
    static float goldenHour(float zoneF) {
        if (zoneF >= 1f) return 0f;
        float in = MathUtils.clamp((zoneF - 0.45f) / 0.05f, 0f, 1f), out = 1f - MathUtils.clamp((zoneF - 0.8f) / 0.2f, 0f, 1f);
        return in * in * (3f - 2f * in) * out;
    }
    /** Build 77: how far the skull's face turns toward its way of travel (degrees; the model's face looks straight at the camera at yaw -22 (the skull turns against the tower curve to face you, measured on CreatureShot renders), so +22 going right and -22 going left), and where its two eye sockets sit
     *  in the model's own space (measured from skull_anim's mesh: centres at x = +-0.36, y = 0.84; the eyes are drawn with the skull's own transform so they stay in the sockets at any yaw, tilt or squash). */
    private static final float SKULL_YAW = Float.parseFloat(System.getProperty("climb.skullYaw", "22")), SKULL_EYE_X = Float.parseFloat(System.getProperty("climb.skullEyeX", "0.36")),
            SKULL_EYE_Y = Float.parseFloat(System.getProperty("climb.skullEyeY", "0.82")), SKULL_EYE_Z = Float.parseFloat(System.getProperty("climb.skullEyeZ", "0.4")),
            SKULL_EYE_W = Float.parseFloat(System.getProperty("climb.skullEyeW", "0.46")), SKULL_EYE_H = Float.parseFloat(System.getProperty("climb.skullEyeH", "0.42"));
    private Sim rsim; private int rhz;            // the simulation and hazard index being drawn (aimed cannons draw the sim's own aim, crabs where the sim has them)

    /** Build 71 (owner): how far the platform a trap sits on has dipped from a landing (the same spring the platform is drawn with); traps on it move with it. */
    private float anchorDip(Element h) {
        int a = h.anchor;
        if (a < 0 || a >= dip.length || a >= course.size() || !course.get(a).isPlatform()) return 0f;
        if (Math.abs(course.dsWrap(h.s, course.get(a).s)) > course.get(a).halfW() + 0.3f) return 0f;      // a saw or spike block in the gap beside the platform stays put
        return Math.max(-0.1f, Math.min(0.22f, dip[a]));
    }

    private void drawHazard(Element h, float t, float camS, float time, boolean done) {
        float dipY = h.ridesAnchor() || h.type == Element.Type.BLOB ? anchorDip(h) : 0f;
        float cs = h.type == Element.Type.CANNON ? h.s + h.dir * h.len * 0.5f : h.s;
        float half = h.type == Element.Type.AIMED ? 17f : h.type == Element.Type.CANNON ? h.len * 0.5f + 1.5f + CANNON_OFFSCREEN : (h.type == Element.Type.SAW_H ? h.amp + 1.5f : h.type == Element.Type.PENDULUM ? h.len + 1.5f : h.type == Element.Type.SKULL || h.type == Element.Type.BLOB ? h.w * 0.5f + 1.5f : 2f);
        float cy = h.type == Element.Type.SAW_V ? h.y + h.amp * 0.5f : h.y;
        if (rsim != null && rhz >= 0 && rhz < rsim.mobState.length) {       // Build 79: creatures roam to the decks either side of their own: cull them where they are
            if (h.type == Element.Type.CRAB) { cs = rsim.crabAt(rhz, h); cy = rsim.crabY(rhz, h); half = 2f; }
            else if ((h.type == Element.Type.SKULL || h.type == Element.Type.BLOB) && rsim.mobState[rhz] > 0) { int j = rsim.mobDeckIdx(rhz, h); cs = rsim.mobS[rhz]; if (j >= 0) cy = rsim.deckY(j); half = 3f; }
        }
        if (!visible(cs, cy, camS, half)) return;
        switch (h.type) {
            case SAW_H: {
                drawBox(h.s, h.y - 0.05f - dipY, 0.25f, camS, h.amp * 2f + 1.2f, 0.1f, 0.14f, 0.3f, 0.32f, 0.4f);
                drawPack("hazard_saw", h.sAt(t), h.yAt(t) - dipY, 0.25f, camS, PK, PK, PK, 0f, -time * 600f);
                break;
            }
            case PENDULUM: {
                // pendulum experiment (2026-10-28): a spiked ball on a chain from a beam above the gap; the ball is drawn exactly where the simulation's
                // hit disc is (Element.sAt/yAt, BALL_R), the chain is drawn as short links from the pivot to the ball, all at the play plane's depth
                float px = h.s, py = h.y + h.len, bx = h.sAt(t), by = h.yAt(t);
                drawBox(px, py, 0.25f, camS, 1.6f, 0.18f, 0.5f, 0.14f, 0.3f, 0.32f);
                drawBox(px, py - 0.1f, 0.25f, camS, 0.22f, 0.22f, 0.22f, 0.3f, 0.32f, 0.4f);
                for (int k = 1; k <= 7; k++) { float f = k / 8f; drawBox(px + (bx - px) * f, py + (by - py) * f - 0.05f, 0.25f, camS, 0.09f, 0.09f, 0.09f, 0.42f, 0.42f, 0.48f); }
                drawPack("spikyball", bx, by, 0.25f, camS, 0.6f, 0.6f, 0.6f, 0f, -bx * 160f);
                break;
            }
            case SAW_V: {
                drawBox(h.s, h.y - 0.6f, 0.25f, camS, 0.12f, h.amp + 1.2f, 0.14f, 0.3f, 0.32f, 0.4f);
                drawPack("hazard_saw", h.s, h.yAt(t), 0.25f, camS, PK, PK, PK, 0f, -time * 600f);
                break;
            }
            case CANNON: {
                // a side cannon on its own slab, level with its platform and set behind the lane (owner, Build 63); its barrel's axis is at the
                // ball's height (h.y), muzzle down the lane; every ball on its way is drawn - this cycle's and the earlier launches flying on -
                // and every one of them hits (Element.CANNON_TRAIL). A ball leaves the muzzle behind the lane and glides into the play plane.
                // Build 67 (owner: the ball must come out of the centre of the mouth): the simulation launches at h.s, so the cannon, its pedestal
                // and its slab stand a barrel's length behind that point (in lane units at this depth, mouthFromAxle / laneUnits) and the mouth
                // sits exactly on it; before, the axle stood on h.s and the ball erupted out of the carriage. The simulation, the lane and the
                // hit discs are untouched.
                float deck = h.y - 2.35f, dz = AIM_DEPTH;
                float[] mz = mouthFromAxle(h.dir > 0 ? 0f : 180f, 0f, mouthTmp);
                float body = h.s - laneUnits(mz[0], dz, T.radius);
                drawSlab(body, deck, dz, camS, h.zone);
                drawBox(body, deck, dz, camS, 1.25f, CANNON_LIFT, 1.25f, 0.42f, 0.38f, 0.5f);
                drawBox(body, deck + CANNON_LIFT, dz, camS, 1.45f, 0.16f, 1.45f, 0.3f, 0.27f, 0.36f);
                drawCannon(body, h.y - CANNON_AXIS_UP, dz, camS, h.dir > 0 ? 0f : 180f, 0f);
                float c0 = h.cyc(t);
                if (c0 < CANNON_PUFF && !reducedMotion) {          // the shot: a warm puff that grows and fades just outside the mouth as the ball leaves it
                    float k = c0 / CANNON_PUFF, sz = 0.24f + 0.36f * k;
                    drawBox(h.s + h.dir * (0.2f + 0.35f * k), h.y - sz * 0.5f, dz - 0.25f, camS, sz, sz, sz, 1f, 0.62f - 0.3f * k, 0.3f - 0.15f * k);
                }
                for (int back = 0; back < Element.CANNON_TRAIL; back++) {
                    if (!h.cannonBallAlive(t, back)) continue;                    // exactly the balls the simulation tests
                    float bs = h.cannonBallS(t, back), flown = h.cannonFlown(t, back);
                    if (Math.abs(course.dsWrap(bs, camS)) > CANNON_OFFSCREEN) continue;
                    float fizzle = Math.min(1f, (h.cannonReach() - flown) / 1.5f);   // it shrinks away over its last metre and a half instead of popping out
                    float sc = 0.55f * Math.max(0.05f, fizzle);
                    drawPack("spikyball", bs, h.yAt(t), dz * Math.max(0f, 1f - flown / BALL_SWOOP), camS, sc, sc, sc, 0f, -bs * 160f);
                }
                if (HAZ_MARKS) {           // dev: a thin green pole on the launch arc at the cannon's depth, and the numbers
                    drawBox(h.s, h.y - 2.5f, dz, camS, 0.06f, 5f, 0.06f, 0.1f, 1f, 0.2f);
                    markLog("cannon mouth s=" + h.s + " y=" + h.y + " dir=" + h.dir, worldAt(h.s, h.y, dz, camS, markTmp));
                }
                break;
            }
            case AIMED: {
                // a turret on its own slab set BEHIND the walking lane (owner: land on the grass, cannon just behind it); its cradle is tilted by
                // the SIMULATION's own aim, so the picture is where the ball goes, and it faces down the lane or back up it rather than ever
                // turning upside down. Locked = a red glow at the muzzle that pulses faster; the ball then glides into the play plane in front.
                float dz = AIM_DEPTH, deck = h.y - 2.0f;
                float ang = rsim != null && rhz < rsim.aimAng.length ? rsim.aimAng[rhz] : 0f;
                // Build 79 (owner): the simulation turns the turret flat like a record (aimYaw) and only tilts the barrel a little: draw exactly that
                float yawDeg = rsim != null && rhz < rsim.aimYaw.length ? rsim.aimYaw[rhz] * MathUtils.radiansToDegrees : 0f;
                float deg = ang * MathUtils.radiansToDegrees;
                float tilt = Math.cos(yawDeg * MathUtils.degreesToRadians) >= 0f ? deg : 180f - deg; tilt = ((tilt + 180f) % 360f + 360f) % 360f - 180f;
                drawSlab(h.s, deck, dz, camS, h.zone);
                drawBox(h.s, deck, dz, camS, 1.25f, AIM_LIFT, 1.25f, 0.34f, 0.31f, 0.40f);
                drawBox(h.s, deck + AIM_LIFT, dz, camS, 1.45f, 0.16f, 1.45f, 0.3f, 0.27f, 0.36f);
                drawCannon(h.s, h.y - CANNON_AXIS_UP, dz, camS, yawDeg, tilt);
                // Build 67 (owner: the ball must leave the centre of the mouth): the cradle turns about its axle, so the drawn mouth wanders
                // up to half a metre from the simulation's launch point (AIM_MUZZLE along the aim from the barrel's resting axis) as it aims;
                // the turret stays put and the ball is drawn from the mouth instead, gliding onto its simulated path over the same BALL_SWOOP
                // it already glides from the slab's depth into the play plane. The flash sits on the drawn mouth too. The simulation is untouched.
                float[] mz = mouthFromAxle(yawDeg, tilt, mouthTmp);
                float mouthS = h.s + laneUnits(mz[0], dz, T.radius), mouthY = h.y - CANNON_AXIS_UP + mz[1];
                float offS = mouthS - (h.s + (float) Math.cos(ang) * Element.AIM_MUZZLE), offY = mouthY - (h.y + (float) Math.sin(ang) * Element.AIM_MUZZLE);
                float c = h.cyc(t);
                if (c >= Element.AIM_LOCK && c < Element.AIM_FIRE) {
                    float k = (c - Element.AIM_LOCK) / (Element.AIM_FIRE - Element.AIM_LOCK), pulse = reducedMotion ? 1f : 0.7f + 0.3f * MathUtils.sin(time * (18f + 30f * k));
                    float sz = (0.16f + 0.2f * k) * pulse;
                    drawBox(mouthS, mouthY - sz * 0.5f, dz - 0.2f, camS, sz, sz, sz, 1f, 0.2f, 0.12f);   // muzzle flash centred on the barrel mouth
                }
                float[] b = rsim != null ? rsim.aimedBall(rhz, h) : null;
                if (b != null) {
                    float flown = Math.max(0f, (float) Math.hypot(course.dsWrap(b[0], h.s), b[1] - h.y) - Element.AIM_MUZZLE);
                    float w = Math.max(0f, 1f - flown / BALL_SWOOP);
                    drawPack("spikyball", b[0] + offS * w, b[1] + offY * w, dz * w, camS, 0.5f, 0.5f, 0.5f, 0f, -b[0] * 160f);   // from the mouth behind the lane onto its path in the play plane
                }
                if (HAZ_MARKS) {           // dev: the numbers - the mouth the formula places against the engine's own (drawCannon's magenta cube)
                    markLog("turret mouth formula ang=" + ang + " offS=" + offS + " offY=" + offY, worldAt(mouthS, mouthY, dz, camS, markTmp));
                    markLog("turret launch", worldAt(h.s + (float) Math.cos(ang) * Element.AIM_MUZZLE, h.y + (float) Math.sin(ang) * Element.AIM_MUZZLE, dz, camS, markTmp));
                }
                break;
            }
            case MORTAR: {
                // a cannon sunk in the ground below the path with its cradle tipped straight up on its wheels; the muzzle tip is where the ball (h.y) leaves from and falls back into
                float[] mz = mouthFromAxle(0f, 90f, mouthTmp);                                       // tipped up: the mouth is a barrel's length above the axle and a barrel-axis height to its left
                float axle = h.y - mz[1], capBot = axle - CANNON_WHEEL_R - 0.14f;
                drawBox(h.s, capBot - MORTAR_PLINTH, 0f, camS, 1.5f, MORTAR_PLINTH, 1.3f, 0.34f, 0.31f, 0.40f);          // (owner 2026-10-30: was a 2 m block reaching 6.3 m below the path, into the revolution below)
                drawBox(h.s, capBot, 0f, camS, 1.7f, 0.18f, 1.5f, 0.30f, 0.27f, 0.36f);
                if (!HAZ_NO_BODY) drawCannon(h.s - mz[0], axle, 0f, camS, 0f, 90f);                 // so the axle stands to the right of h.s and the mouth exactly on it
                float charge = h.mortarCharge(t);           // the reload pause: the muzzle glows brighter and brighter, then the ball bursts out (warning for the timing)
                if (charge > 0f) { float pulse = reducedMotion ? 1f : 0.75f + 0.25f * MathUtils.sin(time * 28f); float k = (0.12f + 0.28f * charge) * pulse; drawBox(h.s, h.y - k * 0.5f, 0f, camS, k, k, k, 1f, 0.55f + 0.3f * charge, 0.15f); }   // Build 67: centred on the mouth (it sat half its width to the left)
                if (h.lethalAt(t)) drawPack("spikyball", h.s, h.yAt(t), 0f, camS, 0.55f, 0.55f, 0.55f, 0f, -h.yAt(t) * 160f);
                if (HAZ_MARKS) {           // dev: an orange pole on the ball's simulated arc at the cannon's depth, an orange cube at the mouth point, and the numbers
                    drawBox(h.s, h.y - 1.5f, 0f, camS, 0.06f, 5f, 0.06f, 1f, 0.5f, 0f); drawBox(h.s, h.y - 0.08f, 0f, camS, 0.16f, 0.16f, 0.16f, 1f, 0.5f, 0f);
                    markLog("mortar mouth s=" + h.s + " y=" + h.y, worldAt(h.s, h.y, 0f, camS, markTmp));
                    if (h.lethalAt(t)) markLog("mortar ball", worldAt(h.s, h.yAt(t), 0f, camS, markTmp));
                }
                break;
            }
            case SPIKE_TRAP: {
                float hcs = rsim != null ? rsim.hazCarryS(h) : 0f, hcy = (rsim != null ? rsim.hazCarryY(h) : 0f) - dipY;   // ride the platform (0 unless it is a mover) so the pad stays with its block, and its landing dip
                float sp = h.spikeHeight(t);
                drawBox(h.s + hcs, h.y + hcy + SPIKE_MAT_BOTTOM, 0f, camS, h.w + 0.3f, SPIKE_MAT_TOP - SPIKE_MAT_BOTTOM, 1.5f, 0.55f, 0.2f, 0.18f);
                if (sp > 0.02f) {
                    float hk = sp / (3.4f * 0.37f);
                    for (int k = -1; k <= 1; k++) drawPack("spikes", h.s + hcs + k * h.w * 0.32f, h.y + hcy, 0f, camS, 0.3f, 0.37f * hk * 1.3f, 0.3f, 0f, 0f);
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
                drawMarker(h.color, h.s, h.y + bob + 1.0f, -0.2f, camS, 0.42f);          // the key's shape floats above it
                if (!reducedMotion && quality > 0 && MathUtils.random() < frameDt * 12f) {       // a thin stream of sparkles in the key's colour rises from it: a hidden key can be spotted from a distance
                    float[] rgb = keyRgb(h.color); keyCol.set(rgb[0], rgb[1], rgb[2], 1f);
                    particles.spawn(h.s + MathUtils.random(-0.15f, 0.15f), h.y + 0.5f, 0f, 1.6f + MathUtils.random(0.6f), keyCol, 0.09f, 0f, 2.4f);
                }
                break;
            }
            case GATE: {
                float dxg = wrapDiff(h.s, camS);
                boolean inside = Math.abs(dxg) < 1.6f;
                ModelInstance g = gateInst(h);
                for (com.badlogic.gdx.graphics.g3d.Material mt : g.materials) { if (inside) mt.set(gateBlend); else mt.remove(BlendingAttribute.Type); }       // the walls turn glassy while you walk through, so you stay visible inside
                for (com.badlogic.gdx.graphics.g3d.model.Node nd : g.nodes) for (com.badlogic.gdx.graphics.g3d.model.NodePart np : nd.parts) if (np.material.id.contains("Black")) np.enabled = !inside;       // the two dark doorway boxes would show through the glassy walls: hide them then
                float phi = dxg / T.radius;
                // the tower is turned a quarter turn so its door faces down the path (right); the second door sits exactly opposite (left)
                g.transform.idt().translate(T.radius * MathUtils.sin(phi), h.y, -T.radius + T.radius * MathUtils.cos(phi))
                        .rotate(0, 1, 0, phi * MathUtils.radiansToDegrees + 90f).scale(0.4f, 0.4f, 0.4f);
                batch.render(g, env);
                drawMarker(h.color, h.s, h.y + 2.4f, -2.0f, camS, 0.6f);                // the gate's shape on its near wall, above the door line
                break;
            }
            case CRAB: {
                if (done) break;
                // where the simulation has it: on its sweep, or running at the player / back to the sweep (Build 63); it scuttles faster on the chase
                float crabX = rsim != null ? rsim.crabAt(rhz, h) : h.sAt(t);
                float face = rsim != null ? rsim.crabFacing(rhz, h) : (h.amp * (float) Math.cos(2 * Math.PI * t / h.period + h.phase) >= 0f ? 1f : -1f);
                boolean chasing = rsim != null && rsim.crabChasing(rhz);
                float crabY = rsim != null ? rsim.crabY(rhz, h) : h.y;                  // Build 79: on a deck it followed you onto, mid-hop, or falling with a crumbled deck
                boolean home = rsim == null || (rhz < rsim.deckOff.length && rsim.deckOff[rhz] == 0f && rsim.crabJump[rhz] < 0f && !rsim.crabFalling(rhz));
                if (home) crabY -= dipY;
                boolean falling = rsim != null && rsim.crabFalling(rhz);
                Rig crabRig = rig(h, "crab_anim", "Walk"); if (!falling) castShadow(crabRig.inst, crabX, crabY);
                drawRig(crabRig, crabX, crabY, 0f, camS, 0.34f, face >= 0f ? 90f : -90f, falling ? rsim.crabFall[rhz] * 300f : 0f, chasing ? 2.4f : 1.6f);
                break;
            }
            case BEE: {
                if (done || !h.beePresent(t)) break;
                float bs = h.beeS(t), by = h.beeY(t), vel = h.beeS(t + 0.05f) - bs;
                if (rsim != null) { float[] b = rsim.beeAt(rhz, h, false); vel = b[0] - bs + vel; bs = b[0]; by = b[1]; }     // Build 71: where the simulation has it (homing on you, swooping in its dive)
                Rig beeRig = rig(h, "bee_anim", "Flying"); castShadow(beeRig.inst, bs, by);
                drawRig(beeRig, bs, by, 0f, camS, 0.36f, vel >= 0 ? 70f : -70f, MathUtils.clamp(vel * 120f, -25f, 25f) * (vel >= 0 ? -1f : 1f), 1f);
                break;
            }
            case SKULL: {
                // Build 71: a floating skull with glowing eyes. It bobs at the hover height, drops low and shivers before a dash, streaks along its deck leaving mist, rises and turns round.
                if (done) break;      // Build 79: batted away by the club (drawn as it flies off: drawFlung)
                int dj = rsim != null ? rsim.mobDeckIdx(rhz, h) : h.anchor;      // Build 79: the deck it is hunting on now (its own, or the one before or after)
                Element deck = dj >= 0 && dj < course.size() ? course.get(dj) : null;
                float dY = deck != null ? (rsim != null ? rsim.deckY(dj) : deck.y) : h.y, end = h.w * 0.5f - Element.SKULL_END;
                int st = rsim != null && rhz < rsim.mobState.length ? rsim.mobState[rhz] : 1;
                float ks = rsim != null && st > 0 ? rsim.mobS[rhz] : h.s + end, ky = dY + (rsim != null && st > 0 ? rsim.mobY[rhz] : Element.SKULL_HOVER + 0.12f * MathUtils.sin(time * 2.6f + h.phase));
                float dir = rsim != null && st > 0 ? rsim.mobDir[rhz] : -1f;
                if (st == 2 && !reducedMotion && rsim.mobT[rhz] > Element.SKULL_LOOP_T) ks += 0.04f * MathUtils.sin(time * 70f);      // it shivers as it swoops down for the dash (after its loop-the-loop, Build 77)
                float sc = 0.6f, yaw = -22f + (dir >= 0f ? SKULL_YAW : -SKULL_YAW) * (st == 3 ? 0.6f : 1f) - wrapDiff(ks, camS) / T.radius * MathUtils.radiansToDegrees;      // its face toward YOU wherever it is round the cylinder, turned a little toward where it is going (mirrored per direction)
                float mt = rsim != null ? rsim.mobT[rhz] : 0f, roll = 0f, qx = 1f, qy = 1f;               // personality (renderer only): a lazy wobble and breathing while it hovers, a lean through the loop, a stretch in the dash
                if (!reducedMotion) {
                    if (st <= 1) { ky += 0.05f * MathUtils.sin(time * 5.3f + h.phase); roll = 7f * MathUtils.sin(time * 2.1f + h.phase); qy = 1f + 0.05f * MathUtils.sin(time * 5.3f + h.phase + 1.2f); qx = 2f - qy; }
                    else if (st == 2 && mt < Element.SKULL_LOOP_T) { float a = MathUtils.PI2 * mt / Element.SKULL_LOOP_T; roll = -dir * 22f * MathUtils.sin(a); qy = 1f + 0.1f * MathUtils.sin(a * 2f); qx = 2f - qy; }
                    else if (st == 2) { float k = (mt - Element.SKULL_LOOP_T) / (Element.SKULL_WINDUP - Element.SKULL_LOOP_T); qy = 1f - 0.12f * k; qx = 1f + 0.12f * k; roll = dir * 10f * k; }   // crouching to spring
                    else if (st == 3) { qx = 1.18f; qy = 0.9f; roll = -dir * 12f; }
                    else if (st == 4) { qy = 1f + 0.08f * MathUtils.sin(mt * 18f) * Math.max(0f, 1f - mt * 2f); qx = 2f - qy; }
                }
                Rig skullRig = rig(h, "skull_anim", "Idle"); castShadow(skullRig.inst, ks, ky - 0.62f * sc);
                drawRig(skullRig, ks, ky - 0.62f * sc, 0f, camS, sc * qx, sc * qy, sc * (st == 3 ? 1f : 0.5f * (qx + qy)), yaw, roll, st == 3 ? 2f : 1f);
                float glow = st == 2 || st == 3 ? 1f : 0.65f + 0.2f * MathUtils.sin(time * 4f);
                for (int e = -1; e <= 1; e += 2) {                                                       // the eyes: a glowing block filling each socket with a dark pupil, placed in the skull's own space
                    eyeBox(skullRig.inst.transform, e * SKULL_EYE_X, SKULL_EYE_Y, SKULL_EYE_Z, SKULL_EYE_W, SKULL_EYE_H, 0.1f, 1f, 0.35f + 0.4f * glow, 0.1f);
                    eyeBox(skullRig.inst.transform, e * SKULL_EYE_X + dir * 0.07f, SKULL_EYE_Y + 0.04f, SKULL_EYE_Z + 0.07f, SKULL_EYE_W * 0.45f, SKULL_EYE_H * 0.5f, 0.06f, 0.05f, 0.08f, 0.1f);
                }
                if (st == 3 || (st == 2 && rsim != null && rsim.mobT[rhz] > Element.SKULL_LOOP_T)) {     // the misty trail
                    for (int q = 0; q < 3; q++) { mistCol.set(0.82f, 0.9f, 1f, 0.55f); particles.spawn(ks - dir * MathUtils.random(0.2f, 0.6f), ky + MathUtils.random(-0.3f, 0.3f), -dir * MathUtils.random(0.2f, 0.8f), MathUtils.random(0.1f, 0.5f), mistCol, MathUtils.random(0.12f, 0.22f), -0.3f, MathUtils.random(0.45f, 0.8f)); }
                }
                break;
            }
            case BLOB: {
                // Build 71: the green blob. It wobbles, leaps off the top of the screen, and drops: only its growing shadow shows where, in the last BLOB_SHADOW seconds.
                int dj = rsim != null ? rsim.mobDeckIdx(rhz, h) : h.anchor;      // Build 79: the deck it is on now (its own, or the one before or after)
                Element deck = dj >= 0 && dj < course.size() ? course.get(dj) : null;
                float dY = deck != null ? (rsim != null ? rsim.deckY(dj) : deck.y) : h.y;
                if (dj != h.anchor) dipY = 0f;
                int st = rsim != null && rhz < rsim.mobState.length ? rsim.mobState[rhz] : 1;
                float bs = rsim != null && st > 0 ? rsim.mobS[rhz] : h.s;
                float surf = (rsim != null ? rsim.blobSurfaceY(rhz, deck, dj, dY) : dY) - dipY;
                float lift = rsim != null && st > 0 ? rsim.mobY[rhz] : 0f;
                float sx = 1f, sy = 1f;
                if (st == 2 && !reducedMotion) { float w = MathUtils.sin(time * 26f) * 0.13f; sx = 1f + w; sy = 1f - w; }          // the wobble before the leap
                if (st == 4 && rsim != null && rsim.mobT[rhz] < 0.25f) { float k = 1f - rsim.mobT[rhz] / 0.25f; sx = 1f + 0.3f * k; sy = 1f - 0.35f * k; }   // splat on landing
                float shadow = rsim != null ? rsim.blobShadow(rhz) : -1f;
                if (shadow >= 0f) {
                    float tgt = rsim.mobTgt[rhz], k = 1f - shadow / Element.BLOB_SHADOW, sw = 0.5f + 0.9f * k;
                    float ty = rsim.blobSurfaceY(rhz, deck, dj, dY);
                    drawPuddle(0, tgt, ty + SHADOW_LIFT, 0f, camS, sw, SHADOW_THICK, sw * 0.8f, 0.06f, 0.07f, 0.09f);
                }
                float sc = 0.6f, face = rsim != null && course.dsWrap(rsim.s, bs) < 0f ? -1f : 1f;
                Rig r = rig(h, "blob_anim", st == 3 ? "Jump" : "Idle");
                r.ctrl.update(frameDt);
                float phi = wrapDiff(bs, camS) / T.radius;
                r.inst.transform.idt().translate(T.radius * MathUtils.sin(phi), surf + lift, -T.radius + T.radius * MathUtils.cos(phi))
                        .rotate(0, 1, 0, phi * MathUtils.radiansToDegrees + (face >= 0f ? 40f : -40f)).scale(sc * sx, sc * sy, sc * sx);
                batch.render(r.inst, env);
                if (st != 3) castShadow(r.inst, bs, surf + lift);           // (in its leap it is off the top of the screen: the landing marker shows where it will come down)
                break;
            }
            case ENFORCER: {
                // PUNISHER (owner directive 2026-10-27): the Enforcer robot (Quaternius Animated Robot, CC0), painted by tier - yellow, orange, red - and
                // never by colour alone: each tier is a little bigger and wears one, two or three lamps on its head (spec 12). It glows hot while it winds up a kick.
                if (done) break;
                int st = rsim != null ? rsim.enforcerState(rhz) : 1, tier = MathUtils.clamp(h.skin, 0, 2);
                float es = rsim != null ? rsim.enforcerS(rhz, h) : h.s, ey = (rsim != null ? rsim.enforcerY(rhz, h) : h.y) - (st <= 3 ? dipY : 0f);
                float dir = rsim != null && st != 0 ? rsim.mobDir[rhz] : -1f, mt = rsim != null ? rsim.mobT[rhz] : 0f;
                String clip = st == 2 || st == 6 ? "Running" : st == 3 ? "Punch" : st == 4 || st == 5 || st == 8 ? "Jump" : st == 7 ? "Death" : "Idle";
                Rig r = rig(h, "robot_anim", clip);
                if (r.inst.userData == null) {          // paint the body once, by tier
                    for (com.badlogic.gdx.graphics.g3d.Material m : r.inst.materials) if (m.id != null && m.id.contains("Main")) m.set(ColorAttribute.createDiffuse(ENF_TINT[tier][0], ENF_TINT[tier][1], ENF_TINT[tier][2], 1f));
                    r.inst.userData = Boolean.TRUE;
                }
                float wind = st == 3 ? MathUtils.clamp(mt / (Element.ENF_WINDUP[tier] / (rsim != null ? rsim.T.react() : 1f)), 0f, 1f) : 0f, hot = st == 3 && wind < 1f ? wind : 0f;
                for (com.badlogic.gdx.graphics.g3d.Material m : r.inst.materials) {          // (release hardening, Build 82 review finding 10: the rig's own emissive, recoloured in place - no allocation per frame)
                    ColorAttribute em = (ColorAttribute) m.get(ColorAttribute.Emissive);
                    if (em != null) em.color.set(0.22f + 0.6f * hot, 0.22f + 0.1f * hot, 0.2f, 1f); else m.set(ColorAttribute.createEmissive(0.22f + 0.6f * hot, 0.22f + 0.1f * hot, 0.2f, 1f));
                }
                float sc = ENF_SCALE * (1f + 0.08f * tier), spin = st == 7 ? mt * 240f : 0f;
                if (st != 7 && st != 8) castShadow(r.inst, es, ey);
                drawRig(r, es, ey, 0f, camS, sc, dir >= 0f ? 70f : -70f, spin, st == 2 || st == 6 ? 1.3f + 0.15f * tier : 1f);
                for (int q = 0; q <= tier; q++) drawBox(es + (q - tier * 0.5f) * 0.18f, ey + Element.ENF_H * (1f + 0.08f * tier) + 0.08f, 0.1f, camS, 0.1f, 0.1f, 0.1f, 1f, 0.95f - 0.3f * tier, 0.4f);
                if (st == 2 && mt < 0.5f && !reducedMotion && quality > 0 && MathUtils.randomBoolean(0.3f)) { ambCol.set(1f, 0.9f, 0.3f, 1f); particles.spawn(es, ey + Element.ENF_H + 0.4f, 0f, 1.2f, ambCol, 0.08f, 0f, 0.4f); }      // it has seen you
                break;
            }
            case AVALANCHE: {
                // spike-block avalanche (owner 2026-10-27; rules 18, owner 2026-10-29: no warning arrows - the falling blocks are the warning): the
                // cube_spikes blocks fall, bounce and tumble, turning as the simulation spins them, kicking up grit where they strike hard
                if (rsim == null) break;
                for (int b = 0; b < Element.AVL_MAX; b++) {
                    float[] k = rsim.avalancheBlock(rhz, h, b);
                    if (k == null || k[3] != 1f) continue;
                    float sc = AVL_SCALE * k[4] / Element.AVL_R;          // (NOT GONNA HAPPEN blocks each have their own size: drawn at their collision radius)
                    drawPack("cube_spikes", k[0], k[1], 0f, camS, sc, sc, sc, 25f, k[2]);          // (rules 18: the simulation's angle is counter-clockwise as seen, +arc to the right: roll matches it, so a tumble turns the way it rolls)
                    if (k[5] > 4f && quality > 0 && !reducedMotion) for (int q = 0; q < 3; q++) { ambCol.set(0.78f, 0.7f, 0.56f, 1f); particles.spawn(k[0] + MathUtils.random(-0.35f, 0.35f), k[1] - k[4], MathUtils.random(-0.2f, 0.2f), MathUtils.random(1f, 3f), ambCol, MathUtils.random(0.05f, 0.11f), -9f, 0.5f); }
                }
                break;
            }
            case CLUB: {
                if (done) break;
                drawPack("hazard_cylinder", h.s, h.y + 0.12f * MathUtils.sin(time * 2.6f + h.phase), 0f, camS, 0.28f, 0.28f, 0.28f, time * 90f, 12f);
                if (quality > 0 && MathUtils.randomBoolean(0.06f)) { ambCol.set(1f, 0.95f, 0.5f, 1f); particles.spawn(h.s + MathUtils.random(-0.4f, 0.4f), h.y + MathUtils.random(0f, 1.1f), 0f, 0.4f, ambCol, 0.06f, 0f, 0.8f); }
                break;
            }
            case SPIKE_DROP: {
                float hcs = rsim != null ? rsim.hazCarryS(h) : 0f, hcy = (rsim != null ? rsim.hazCarryY(h) : 0f) - dipY;   // the drop frame rides its platform when it is a mover (bug #14), and its landing dip
                float b = h.dropBottom(t), top = h.y + h.amp + Element.DROP_H;
                drawBox(h.s + hcs - h.w * 0.5f - 0.1f, h.y + hcy, 0.2f, camS, 0.1f, h.amp + Element.DROP_H + 0.6f, 0.14f, 0.3f, 0.32f, 0.4f);
                drawBox(h.s + hcs + h.w * 0.5f + 0.1f, h.y + hcy, 0.2f, camS, 0.1f, h.amp + Element.DROP_H + 0.6f, 0.14f, 0.3f, 0.32f, 0.4f);
                drawBox(h.s + hcs, top + 0.5f + hcy, 0.2f, camS, h.w + 0.5f, 0.14f, 0.3f, 0.3f, 0.32f, 0.4f);
                drawBox(h.s + hcs, b + 0.5f + hcy, 0f, camS, h.w, 0.5f, 1.3f, 0.42f, 0.38f, 0.5f);
                for (int k = -1; k <= 1; k += 2) drawPack("spikes", h.s + hcs + k * h.w * 0.25f, b + 0.5f + hcy, 0f, camS, h.w * 0.22f, 0.5f / 3.4f, 0.3f, 0f, 180f);
                break;
            }
            case SPIKE_BLOCK: {
                float hcs = rsim != null ? rsim.hazCarryS(h) : 0f, hcy = (rsim != null ? rsim.hazCarryY(h) : 0f) - dipY;   // the red spike block rides its platform when it is a mover (bug #14), and its landing dip
                float base = Math.min(0.5f, h.len * 0.5f), sh = Math.max(0.3f, h.len - base);
                drawBox(h.s + hcs, h.y + hcy, 0f, camS, h.w, base, 1.3f, 0.42f, 0.38f, 0.5f);
                drawPack("spikes", h.s + hcs, h.y + hcy + base, 0f, camS, h.w * 0.46f, sh / 3.4f, h.w * 0.46f, 0f, 0f);
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
        float heroZ = 0f;
        if ((sim.mode == Sim.Mode.GROUND) && sim.onElem >= 0 && course.get(sim.onElem).type == Element.Type.MOVE_Z) heroZ = -course.get(sim.onElem).zAt(sim.time);      // carried toward / away from the camera with the platform
        groundBelow(sim, ps, py, alpha);          // (the ground slope under the hero, for his stance on ramps; his shadow is drawn in drawShadows, at every graphics quality)
        heroCaster = true;
        float onRampLift = 0f;
        if (quality > 0 && sim.mode == Sim.Mode.GROUND && sim.onElem >= 0 && course.get(sim.onElem).type == Element.Type.RAMP) onRampLift = 0.07f;      // the ramp's edge never cuts across his legs
        float wy = py + onRampLift; heroY = py;
        hero.update(sim, dt, time, 0f, wy, heroZ, phi, reducedMotion);
        hero.render(batch, heroEnv);
        if (sim.clubTime > 0f) {
            float k = sim.swingT > 0f ? 1f - sim.swingT / Sim.SWING_TIME : -1f;
            float ang = k < 0f ? 35f + 5f * MathUtils.sin(time * 5f) : MathUtils.lerp(-70f, 110f, Math.min(1f, k * 1.25f));       // degrees forward of straight up
            ModelInstance cl = pack("hazard_cylinder");
            cl.transform.idt().translate(sim.facing * 0.4f, wy + 0.65f, 0.2f).rotate(0, 0, 1, -sim.facing * ang).scale(0.2f, 0.2f, 0.2f);
            batch.render(cl, env);
            if (k >= 0f && k > 0.25f && k < 0.75f && quality > 0) particles.burst(renderS + sim.facing * 1.2f, wy + 0.6f, 1, ambCol.set(1f, 1f, 1f, 1f), 1.2f, 0.4f, 0.07f, 0f, 0.25f);
        }
    }

    private float groundSlope; private boolean groundOnRamp;       // of the surface groundBelow found: the shadow lies along it
    // ------------------------------------------------------------------ dynamic shadows (Build 72, owner)
    private ShadowPass shadowPass;
    private boolean heroCaster;
    private final java.util.ArrayList<ModelInstance> casterInst = new java.util.ArrayList<>();
    private final com.badlogic.gdx.utils.FloatArray casterPos = new com.badlogic.gdx.utils.FloatArray();
    private final Color shadowCol = new Color();
    /** Strength of a shadow on the ground right under its caster; it fades out by SHADOW_REACH above it. */
    public static final float SHADOW_ALPHA = 0.42f, SHADOW_REACH = 9f;
    /** The least sideways lean of the shadow light (per unit of height): enough for a crab's shadow to show which way the light falls. */
    public static final float SHADOW_SIDE = 0.35f;

    /** A creature drawn this frame casts its shadow on whatever platform is under it (arc s, feet at y). */
    private void castShadow(ModelInstance m, float s, float y) { casterInst.add(m); casterPos.add(s); casterPos.add(y); }

    /** Draws every caster's real shadow (the hero's and the creatures') flattened onto the platform under it and cut off at its edges: see ShadowPass. */
    private void drawShadows(Sim sim, float alpha, float camS, float py, float zoneF) {
        if (shadowPass == null) shadowPass = new ShadowPass(32);
        // toward the light: mostly overhead so a shadow sits under its caster (where a jump will land), leaning a little with the world's sun and toward the camera so it shows past the feet
        // (owner: short creatures like the crab show the direction too, so the sideways lean has a floor of SHADOW_SIDE in every world)
        float lx = -Math.signum(sun.direction.x) * SHADOW_SIDE - 0.15f * sun.direction.x, ly = 1f, lz = -0.40f, ln = (float) Math.sqrt(lx * lx + ly * ly + lz * lz);
        lx /= ln; ly /= ln; lz /= ln;
        if (heroCaster) shadowFor(sim, alpha, camS, hero.shadowParts(), camS, py, lx, ly, lz);
        for (int k = 0; k < casterInst.size(); k++) shadowFor(sim, alpha, camS, new ModelInstance[]{casterInst.get(k)}, casterPos.get(2 * k), casterPos.get(2 * k + 1), lx, ly, lz);
        heroCaster = false; casterInst.clear(); casterPos.clear();
    }

    private void shadowFor(Sim sim, float alpha, float camS, ModelInstance[] parts, float s, float y, float lx, float ly, float lz) {
        int gi = groundIdx(sim, s, y, alpha);
        if (gi < 0) return;
        Element e = course.get(gi);
        float top = groundTop;
        float k = MathUtils.clamp(1f - (y - top) / SHADOW_REACH, 0f, 1f);
        if (k <= 0.02f) return;
        // the platform in world space, as place() puts it round the tower: its centre, its slope along the tangent there (ramps, seesaws), and its footprint
        float es = e.isMoving() ? sim.es0[gi] + course.dsWrap(sim.es1[gi], sim.es0[gi]) * alpha : sim.es1[gi];
        float dz = e.type == Element.Type.MOVE_Z ? e.zAt(sim.time) : 0f, phi = wrapDiff(es, camS) / T.radius, rr = T.radius - dz;
        float cs = MathUtils.cos(phi), sn = MathUtils.sin(phi);
        float xc = rr * sn, zc = -T.radius + rr * cs, hw = e.halfW(), hd = shadowHalfDepth(e);
        float slope = e.type == Element.Type.RAMP ? e.rampSlope(sim.time) : e.type == Element.Type.SEESAW ? sim.tilt[gi] : 0f;
        float yc = e.type == Element.Type.RAMP ? e.y : e.type == Element.Type.SEESAW ? e.y - Math.max(-0.1f, Math.min(0.22f, dip[gi])) : top;
        // plane y = yc + slope * u + lift, u = the distance along the tangent (cos phi, 0, -sin phi) from the centre:
        //   -slope*cs*x + y + slope*sn*z - (yc + lift - slope*cs*xc + slope*sn*zc) = 0
        float lift = 0.025f;
        float a = -slope * cs, c = slope * sn, d = -(yc + lift - slope * cs * xc + slope * sn * zc);
        // the footprint, turned with the tower there, boxed in world x and z
        float bx = hw * Math.abs(cs) + hd * Math.abs(sn), bz = hd * Math.abs(cs) + hw * Math.abs(sn);
        shadowCol.set(0.03f, 0.04f, 0.09f, SHADOW_ALPHA * k);
        shadowPass.draw(cam, parts, a, 1f, c, d, lx, ly, lz, xc - bx, xc + bx, zc - bz, zc + bz, shadowCol);
    }

    /** Half the front-to-back depth of a platform's walkable top, as built in build(). */
    private static float shadowHalfDepth(Element e) {
        switch (e.type) {
            case PAD: case SPRING: return 0.62f;
            case RAMP: return e.spins() ? 0.6f * e.len * 1.17f : 0.62f;
            case SEESAW: return 0.74f;
            case STATIC: return e.skin == 2 ? 2.0f : (e.skin == 1 || e.skin == 4) ? 0.74f : 0.85f;
            default: return 0.85f;
        }
    }

    private float groundTop;
    /** The platform whose surface is closest below (s, y), as drawn this frame (its top in groundTop), or -1. */
    private int groundIdx(Sim sim, float s, float y, float alpha) {
        int bestI = -1; float best = -1e9f;
        for (int i = Math.max(0, sim.winLo); i <= Math.min(vis.size() - 1, sim.winHi); i++) {
            Element e = vis.get(i).e;
            if (!e.isPlatform() || sim.gone[i]) continue;
            float es = e.isMoving() ? sim.es0[i] + course.dsWrap(sim.es1[i], sim.es0[i]) * alpha : sim.es1[i];
            float dx = course.dsWrap(s, es);
            if (Math.abs(dx) > e.halfW() + 0.6f) continue;           // (a caster half over the edge still shades the part of the platform under it)
            float top;
            if (e.type == Element.Type.RAMP) top = e.y + e.rampSlope(sim.time) * Math.max(-e.halfW(), Math.min(e.halfW(), dx));
            else if (e.type == Element.Type.SEESAW) top = e.y + sim.tilt[i] * Math.max(-e.halfW(), Math.min(e.halfW(), dx));
            else top = (e.isMoving() ? sim.ey0[i] + (sim.ey1[i] - sim.ey0[i]) * alpha : sim.ey1[i]) - Math.max(-0.1f, Math.min(0.22f, dip[i]));
            if (top > y + 0.08f || y - top > SHADOW_REACH) continue;
            if (top > best) { best = top; bestI = i; }
        }
        groundTop = best;
        return bestI;
    }

    private float groundBelow(Sim sim, float ps, float py, float alpha) {
        float best = -1e9f; groundSlope = 0f; groundOnRamp = false;
        for (int i = Math.max(0, sim.winLo); i <= Math.min(vis.size() - 1, sim.winHi); i++) {
            Element e = vis.get(i).e;
            if (!e.isPlatform() || sim.gone[i]) continue;
            // the surface exactly as it is drawn (interpolated for movers, minus the landing dip of a platform), so the shadow never clips in and out of it
            float top = e.isMoving() ? sim.ey0[i] + (sim.ey1[i] - sim.ey0[i]) * alpha : sim.ey1[i];
            if (e.isPlatform() && e.type != Element.Type.RAMP && e.type != Element.Type.SEESAW) top -= Math.max(-0.1f, Math.min(0.22f, dip[i]));
            if (top > py + 0.05f || py - top > 9f) continue;
            if (Math.abs(course.dsWrap(sim.es1[i], ps)) > e.halfW() + 0.1f) continue;
            if (top > best) { best = top; groundSlope = e.type == Element.Type.RAMP ? e.amp : (e.type == Element.Type.SEESAW ? sim.tilt[i] : 0f); groundOnRamp = e.type == Element.Type.RAMP; }
        }
        return best;
    }

    private float heroY;
    private float[] dip, dipV; private float fovK, fovV, ambientAcc;
    private int[] dipAct = new int[64]; private int dipN; private boolean[] dipOn = new boolean[16];      // platforms whose landing spring is still moving: the per-frame update touches only these, never the whole (ever-growing) tower
    private final Color ambCol = new Color();

    private static final class Flung { float s, y, z, vx, vy, rot, age; int kind; }
    private final java.util.ArrayList<Flung> flung = new java.util.ArrayList<>();
    /** A crab was knocked off its platform: it tumbles away (purely visual). */
    /** Something the club knocked away (kind 0 crab, 1 bee, 2 skull): it tumbles away (purely visual). Build 79: a skull is batted like a hooked golf shot, up and away, curling in toward the middle of the tower. */
    public void crabFlung(float s, float y, int dir, int kind) { Flung f = new Flung(); f.s = s; f.y = y; f.vx = dir * (kind == 2 ? 11f : 6f); f.vy = kind == 1 ? 5f : kind == 2 ? 9f : 7f; f.kind = kind; flung.add(f); }

    private void drawFlung(float dt, float camS) {
        for (int i = flung.size() - 1; i >= 0; i--) {
            Flung f = flung.get(i); f.age += dt;
            if (f.kind == 2) {          // the hook: it climbs, slows along the lane and curls away into the depth, shrinking out of sight
                f.vy -= 6f * dt; f.vx *= (float) Math.pow(0.35f, dt); f.s += f.vx * dt; f.y += f.vy * dt; f.z -= (2f + 14f * f.age) * dt; f.rot += 900f * dt;
                if (f.age > 2.4f) { flung.remove(i); continue; }
                float sc = 0.6f * Math.max(0.15f, 1f - f.age / 2.6f);
                drawPack("skull", f.s, f.y, f.z, camS, sc, sc, sc, f.rot * 0.4f, f.rot);
                if (quality > 0 && !reducedMotion) { mistCol.set(0.82f, 0.9f, 1f, 0.5f); particles.spawn(f.s, f.y, -f.vx * 0.1f, 0.2f, mistCol, 0.14f, -0.3f, 0.5f); }
                continue;
            }
            f.vy -= 30f * dt; f.s += f.vx * dt; f.y += f.vy * dt; f.rot += 540f * dt;
            if (f.age > 2f) { flung.remove(i); continue; }
            boolean bee = f.kind == 1;
            drawPack(bee ? "bee_anim" : "crab", f.s, f.y, 0f, camS, bee ? 0.36f : 0.34f, bee ? 0.36f : 0.34f, bee ? 0.36f : 0.34f, 0f, f.rot);
        }
    }

    public void heroEvents(int ev, float landSpeed) { hero.events(ev, landSpeed); }
    public void platformLanded(int idx, float speed) {
        syncVis();
        if (dip == null || idx < 0 || idx >= dip.length) return;
        dipV[idx] += Math.min(14f, speed) * 0.9f;
        if (dipOn.length < dip.length) dipOn = java.util.Arrays.copyOf(dipOn, dip.length);
        if (!dipOn[idx]) { dipOn[idx] = true; if (dipN == dipAct.length) dipAct = java.util.Arrays.copyOf(dipAct, dipN * 2); dipAct[dipN++] = idx; }
    }
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
            int z = Math.min(Palette.ZONES - 1, (int) zoneF);
            float arc = camS + MathUtils.random(-9f, 9f), yy = camY + MathUtils.random(-4f, 6f);
            switch (z) {
                case 0: ambCol.set(1f, 0.97f, 0.7f, 1f); particles.spawn(arc, yy, MathUtils.random(-0.3f, 0.3f), MathUtils.random(0.2f, 0.6f), ambCol, 0.07f, 0f, 4.5f); break;
                case 1: ambCol.set(1f, 1f, 1f, 1f); particles.spawn(arc, camY + 6f, MathUtils.random(-0.5f, 0.1f), -MathUtils.random(0.8f, 1.5f), ambCol, 0.09f, 0f, 6f); break;
                case 2: ambCol.set(1f, 0.55f, 0.2f, 1f); particles.spawn(arc, camY - 4f, MathUtils.random(-0.4f, 0.4f), MathUtils.random(0.9f, 1.8f), ambCol, 0.07f, 0f, 5f); break;
                case 3: ambCol.set(0.7f, 1f, 0.8f, 1f); particles.spawn(arc, yy, MathUtils.random(-0.6f, 0.6f), MathUtils.random(-0.3f, 0.4f), ambCol, 0.08f, 0f, 4f); break;
                default: ambCol.set(0.75f, 0.85f, 1f, 1f); particles.spawn(arc, yy, MathUtils.random(-0.2f, 0.2f), MathUtils.random(-0.15f, 0.25f), ambCol, 0.05f, 0f, 7f);       // space dust
            }
        }
    }

    /** World position (relative to the screen centre) for effects; arc coordinate -> x,z. */
    public float getCamY() { return camY; }

    @Override public void dispose() {
        batch.dispose(); shapes.dispose(); sb.dispose(); hero.dispose(); bg.dispose(); if (shadowPass != null) shadowPass.dispose(); if (sceneFbo != null) sceneFbo.dispose(); if (vignette != null) vignette.dispose();
        for (Model m : markerModels) if (m != null) m.dispose();
    }
}

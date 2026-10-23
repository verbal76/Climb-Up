package com.hotatticgames.climbup.render;

import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;

/**
 * Background scenery from the Quaternius Ultimate Space Kit (CC0): planets far behind the tower with slow parallax, planets and rocks floating inside the
 * spiral (they turn with the tower), and spaceships that cruise across the sky and now and then buzz the player. Purely cosmetic: nothing here touches the simulation.
 */
public final class SpaceScene {
    public interface Placer { void place(ModelInstance inst, float arc, float y, float dz, float sx, float sy, float sz, float yawDeg); }

    private static final int PLANETS = 11, ROCKS = 7;
    private static final String[] SHIPS = {"spaceship_finnthefrog", "spaceship_raetheredpanda", "spaceship_fernandotheflamingo", "spaceship_barbarathebee"};
    private static final String[] ROCK_NAMES = {"rock_1", "rock_2", "rock_3", "rock_4", "rock_large_1", "rock_large_2", "rock_large_3"};
    /** Which way the ship models point in their own space (+1 = +z is the nose). */
    private static final float SHIP_NOSE = Float.parseFloat(System.getProperty("climb.shipNose", "1"));

    private final Models models;
    private final float radius, circumference;
    public final Environment env = new Environment();
    private final Camera far;
    private final ModelInstance[] planets = new ModelInstance[PLANETS];
    private final ModelInstance[] rocks = new ModelInstance[ROCKS];
    private final ModelInstance[] ships = new ModelInstance[SHIPS.length];
    // Build 78 (owner): in Deep Space an astronaut now and then tumbles across the far sky, cartwheeling like the hero's big-bounce flip, at a random
    // angle, and drifts out of range. Never the astronaut the player is using. Far layer only, so it can never come between the camera and the course.
    private static final String[] ASTRONAUTS = {"astronaut_finnthefrog", "astronaut_raetheredpanda", "astronaut_fernandotheflamingo", "astronaut_barbarathebee"};
    private final ModelInstance[] astros = new ModelInstance[ASTRONAUTS.length];
    private final com.badlogic.gdx.graphics.g3d.utils.AnimationController[] astroAnim = new com.badlogic.gdx.graphics.g3d.utils.AnimationController[ASTRONAUTS.length];
    private final float[] astroMid = new float[ASTRONAUTS.length];     // half the model's height (model units): the cartwheel turns about its middle
    /** The character the player is using (Characters index; 2..5 are the astronauts): that astronaut never floats by. */
    public int playerCharacter = -1;
    private float astroT = -1, astroDur, astroAng, astroOff, astroDist, astroSpin, astroYaw, nextAstro = 12f; private int astro;
    private final float[] planetExt = new float[PLANETS], rockExt = new float[ROCKS];      // horizontal radius of each model (model units): how far it reaches from its own centre
    private final java.util.Random rnd = new java.util.Random(31);   // cosmetic only

    // far layer: planets pinned to the sky
    private static final class Far { int planet; float u, v, dist, ang, pkx, pky, spin; }
    private final Far[] farPlanets = new Far[5];

    // far clouds: the regular cloud models, very far behind the tower, tinted by the glow of the sky so they read as distant, with slow parallax
    private static final int FAR_CLOUDS = 12;
    private static final class FarCloud { int band; float u, v, dist, ang, pkx, pky; ModelInstance inst; }
    private final FarCloud[] farClouds = new FarCloud[FAR_CLOUDS];
    /** Colour of the sky glow the far clouds take (set by the renderer from the sky gradient). */
    public final com.badlogic.gdx.graphics.Color cloudGlow = new com.badlogic.gdx.graphics.Color(0.8f, 0.9f, 1f, 1f);

    // sky ship: crosses the sky slowly; close ship: a short fast fly-by behind the course, seen through the gaps
    private float skyT = -1, skyDur, skyY, skyDist, skyDir, nextSky = 6f; private int skyShip;
    private float buzzT = -1, buzzDir, buzzY, nextBuzz = 30f, clock; private int buzzShip;
    private static final float BUZZ_TIME = 2.8f, FREEZE = Float.parseFloat(System.getProperty("climb.spaceFreeze", "-1"));   // FREEZE: test hook that parks both ships at that point of their flight
    private boolean buzzSounded;
    private float spaceW, zoneNow;                  // how deep in the space world we are (0..1)
    public boolean whoosh;                 // set for one frame when the close ship passes the player (the play screen plays the sound)

    private final Vector3 tmp = new Vector3(), right = new Vector3(), up = new Vector3(), fwd = new Vector3(), back = new Vector3();

    public SpaceScene(Models models, Camera farCam, float radius) {
        if (Boolean.getBoolean("climb.spaceFast")) { nextSky = 0.5f; nextBuzz = 2.5f; }       // test hook
        this.models = models; this.far = farCam; this.radius = radius; this.circumference = (float) (2 * Math.PI * radius);
        for (int i = 0; i < PLANETS; i++) planets[i] = fade(new ModelInstance(models.space("planet_" + (i + 1))));
        for (int i = 0; i < ROCKS; i++) rocks[i] = fade(new ModelInstance(models.space(ROCK_NAMES[i])));
        for (int i = 0; i < PLANETS; i++) planetExt[i] = reach(planets[i]);
        for (int i = 0; i < ROCKS; i++) rockExt[i] = reach(rocks[i]);
        for (int i = 0; i < SHIPS.length; i++) ships[i] = fade(new ModelInstance(models.space(SHIPS[i])));
        for (int i = 0; i < ASTRONAUTS.length; i++) {
            astros[i] = new ModelInstance(models.space(ASTRONAUTS[i]));
            astroAnim[i] = new com.badlogic.gdx.graphics.g3d.utils.AnimationController(astros[i]);
            astroAnim[i].setAnimation("Jump_Idle", -1);                  // the tucked mid-air pose the hero uses while airborne
            com.badlogic.gdx.math.collision.BoundingBox bb = new com.badlogic.gdx.math.collision.BoundingBox(); astros[i].calculateBoundingBox(bb);
            astroMid[i] = 0.5f * (bb.min.y + bb.max.y);
        }
        env.set(new ColorAttribute(ColorAttribute.AmbientLight, 0.62f, 0.62f, 0.70f, 1f));
        env.add(new DirectionalLight().set(1f, 0.96f, 0.88f, -0.5f, -0.6f, -0.6f));
        final float[] bandDist = {130f, 190f, 260f, 350f, 460f}; final int[] bandCount = {2, 2, 3, 3, 2};
        int n = 0;
        for (int band = 0; band < bandDist.length; band++) for (int j = 0; j < bandCount[band]; j++, n++) {
            FarCloud c = new FarCloud(); int i = n;
            c.band = band;
            c.u = (i * 0.618f + 0.11f) % 1f; c.v = (i * 0.4143f + 0.23f) % 1f; c.dist = bandDist[band] + 12f * j;
            c.ang = 5.5f + 4f * ((i * 0.77f) % 1f) - band * 0.4f;                    // farther bands are not drawn bigger: they read as farther
            c.pkx = 0.16f - 0.025f * band; c.pky = 0.17f - 0.025f * band;            // nearer bands drift faster, farther ones barely move
            c.inst = new ModelInstance(models.pack("cloud_" + (1 + i % 3)));
            for (Material m : c.inst.materials) m.set(new BlendingAttribute(true, 0.6f), ColorAttribute.createDiffuse(1f, 1f, 1f, 1f), ColorAttribute.createEmissive(0.3f, 0.3f, 0.35f, 1f));
            farClouds[i] = c;
        }
        for (int i = 0; i < farPlanets.length; i++) {
            Far f = new Far(); f.planet = (i * 5 + 2) % PLANETS; f.u = (i * 0.618f) % 1f; f.v = ((i * 0.37f) % 1f);
            f.dist = 380f + 90f * (i % 3); f.ang = 1.8f + 3.2f * ((i * 0.73f) % 1f); f.pkx = 0.010f + 0.010f * (i % 3); f.pky = 0.020f + 0.012f * (i % 3); f.spin = 1.2f + i;
            farPlanets[i] = f;
        }
    }

    private ModelInstance fade(ModelInstance mi) {
        for (Material m : mi.materials) m.set(new BlendingAttribute(true, 1f));
        return mi;
    }
    private void alpha(ModelInstance mi, float a) {
        for (Material m : mi.materials) { BlendingAttribute b = (BlendingAttribute) m.get(BlendingAttribute.Type); if (b != null) b.opacity = a; }
    }

    /** Called every frame (before the render calls). */
    public void update(float dt, float playerY, boolean reduced) {
        clock += dt; whoosh = false;
        if (reduced) { skyT = -1; buzzT = -1; astroT = -1; return; }
        if (FREEZE >= 0) { skyT = FREEZE * 24f; skyDur = 24f; skyDist = 300f; skyDir = 1f; buzzT = FREEZE * BUZZ_TIME; buzzDir = 1f; buzzY = 1.5f;
            astroT = FREEZE * 16f; astroDur = 16f; astroAng = 0.5f; astroOff = 0.1f; astroDist = 40f; astroSpin = 70f; astroYaw = 15f; astro = otherAstro(0); astroAnim[astro].update(dt); return; }
        // astronauts: Deep Space only, one every 20-40 s
        if (astroT < 0) { if (spaceW > 0.5f) { nextAstro -= dt; if (nextAstro <= 0) {
            astroT = 0; astroDur = 14f + rnd.nextFloat() * 8f; astroAng = rnd.nextFloat() * MathUtils.PI2; astroOff = (rnd.nextFloat() - 0.5f) * 1.1f;
            astroDist = 36f + rnd.nextFloat() * 14f; astroSpin = (rnd.nextBoolean() ? 1f : -1f) * (45f + rnd.nextFloat() * 50f); astroYaw = (rnd.nextFloat() - 0.5f) * 40f;
            astro = otherAstro(rnd.nextInt(ASTRONAUTS.length)); } } }
        else { astroT += dt; astroAnim[astro].update(dt); if (astroT > astroDur) { astroT = -1; nextAstro = 20f + rnd.nextFloat() * 20f; } }
        if (skyT < 0) { nextSky -= dt * (1f + spaceW); if (nextSky <= 0) { skyT = 0; skyDur = 20f + rnd.nextFloat() * 14f; skyY = rnd.nextFloat(); skyDist = 240f + rnd.nextFloat() * 120f; skyDir = rnd.nextBoolean() ? 1f : -1f; skyShip = rnd.nextInt(SHIPS.length); } }
        else { skyT += dt; if (skyT >= skyDur) { skyT = -1; nextSky = 8f + rnd.nextFloat() * 14f; } }
        if (buzzT < 0) { nextBuzz -= dt; if (nextBuzz <= 0) { buzzT = 0; buzzDir = rnd.nextBoolean() ? 1f : -1f; buzzY = 0.8f + rnd.nextFloat() * 2.2f; buzzShip = rnd.nextInt(SHIPS.length); buzzSounded = false; } }
        else { buzzT += dt; if (!buzzSounded && buzzT > 0.55f) { buzzSounded = true; whoosh = true; } if (buzzT > BUZZ_TIME) { buzzT = -1; nextBuzz = 45f + rnd.nextFloat() * 60f; } }
    }

    /** Planets and sky ships, drawn with the wide-range camera before the tower. */
    public void renderFar(ModelBatch batch, float camS, float camY, float zoneF) {
        zoneNow = zoneF;
        float wrap = MathUtils.clamp((zoneF - Palette.ZONES + 0.25f) / 0.25f, 0f, 1f);
        spaceW = MathUtils.clamp((zoneF - 3.6f) / 0.5f, 0f, 1f) * (1f - wrap);       // 1 inside the Deep Space world: bigger, brighter, busier
        float a = MathUtils.lerp(MathUtils.lerp(0.3f, 0.85f, MathUtils.clamp(zoneF / 2.4f, 0f, 1f)), 1f, spaceW);
        fwd.set(far.direction).nor(); right.set(fwd).crs(far.up).nor(); up.set(right).crs(fwd).nor();
        for (Far f : farPlanets) {
            float W = 1.7f * f.dist, H = 1.2f * f.dist;
            float x = mod(f.u * W - camS * f.pkx * f.dist * 0.1f, W) - W / 2f;
            float y = mod(f.v * H - camY * f.pky * f.dist * 0.1f, H) - H / 2f;
            tmp.set(far.position).mulAdd(fwd, f.dist).mulAdd(right, x).mulAdd(up, y);
            ModelInstance pl = planets[f.planet];
            float sc = f.dist * MathUtils.tanDeg(f.ang * (1f + 0.9f * spaceW)) / 1.9f;
            pl.transform.idt().translate(tmp).rotate(0, 1, 0, clock * f.spin).rotate(1, 0, 0, 12f).scale(sc, sc, sc);
            alpha(pl, a); batch.render(pl, env);
        }
        float night = MathUtils.clamp((zoneF - 2.4f) / 0.8f, 0f, 1f) * (1f - wrap);
        float cloudA = (1f - 0.8f * night) * (1f - spaceW) * 0.65f;      // fewer at night, none in deep space
        // Build 77 (owner: in the space levels the stars looked weird coming through the clouds): where stars show, clouds thin out by NUMBER, one at a time,
        // and the ones left stay solid; only the cloud fading in or out is see-through for a moment. Daytime clouds look as before.
        float starK = Math.max(night, spaceW), presence = cloudA / 0.65f * FAR_CLOUDS;
        if (cloudA > 0.02f) for (int ci = 0; ci < FAR_CLOUDS; ci++) {
            FarCloud c = farClouds[ci];
            float fade = starK > 0f ? MathUtils.clamp(presence - ci, 0f, 1f) : 1f;
            if (fade <= 0f) continue;
            float W = 1.7f * c.dist, H = 1.2f * c.dist;
            float x = mod(c.u * W - camS * c.pkx * c.dist * 0.1f, W) - W / 2f;
            float y = mod(c.v * H - camY * c.pky * c.dist * 0.1f, H) - H / 2f;
            tmp.set(far.position).mulAdd(fwd, c.dist).mulAdd(right, x).mulAdd(up, y);
            float sc = c.dist * MathUtils.tanDeg(c.ang) / 3.2f;
            c.inst.transform.idt().translate(tmp).rotate(0, 1, 0, 8f).scale(sc * 1.7f, sc * 0.85f, sc);
            for (Material m : c.inst.materials) {
                float haze = c.band / 4f;                              // 0 = nearest band, 1 = farthest: farther clouds are hazier and take more of the sky colour
                ((ColorAttribute) m.get(ColorAttribute.Diffuse)).color.set(1f, 1f, 1f, 1f).lerp(cloudGlow, 0.30f + 0.55f * haze).mul(1f - 0.55f * starK, 1f - 0.5f * starK, 1f - 0.35f * starK, 1f);   // solid night clouds are dusky, not glowing white
                ((ColorAttribute) m.get(ColorAttribute.Emissive)).color.set(cloudGlow).mul(0.55f * (1f - 0.7f * starK));
                ((BlendingAttribute) m.get(BlendingAttribute.Type)).opacity = fade * MathUtils.lerp(cloudA * (0.95f - 0.45f * haze), 0.97f - 0.1f * haze, starK);
            }
            batch.render(c.inst, env);
        }
        if (skyT >= 0) {
            float u = skyT / skyDur, dist = skyDist, W = 1.7f * dist;
            float x = (u - 0.5f) * (W + 120f) * skyDir, y = (skyY - 0.5f) * 0.5f * dist + 6f * MathUtils.sin(skyT * 0.9f);
            tmp.set(far.position).mulAdd(fwd, dist).mulAdd(right, x).mulAdd(up, y);
            float sc = dist * MathUtils.tanDeg(1.6f) / 11.6f;
            ModelInstance sh = ships[skyShip];
            sh.transform.idt().translate(tmp).rotate(0, 1, 0, (skyDir > 0 ? 90f : -90f) * SHIP_NOSE).rotate(0, 0, 1, 8f * MathUtils.sin(skyT * 0.7f)).scale(sc, sc, sc);
            alpha(sh, a); batch.render(sh, env);
        }
        if (astroT >= 0 && spaceW > 0.05f) {
            // a straight drift across the view at a random angle, from beyond one edge to beyond the other, sinking 30% farther away as it goes
            float u = astroT / astroDur, dist = astroDist * (1f + 0.3f * u);
            float halfH = dist * MathUtils.tanDeg(far instanceof com.badlogic.gdx.graphics.PerspectiveCamera ? ((com.badlogic.gdx.graphics.PerspectiveCamera) far).fieldOfView * 0.5f : 20f);
            float halfW = halfH * far.viewportWidth / Math.max(1f, far.viewportHeight), reach = (float) Math.sqrt(halfW * halfW + halfH * halfH) + 4f;
            float dx = MathUtils.cos(astroAng), dy = MathUtils.sin(astroAng), along = (u * 2f - 1f) * reach, side = astroOff * halfH;
            tmp.set(far.position).mulAdd(fwd, dist).mulAdd(right, dx * along - dy * side).mulAdd(up, dy * along + dx * side);
            ModelInstance m = astros[astro]; float sc = 2.2f;
            m.transform.set(right, up, back.set(fwd).scl(-1f), tmp)                                  // faces the camera
                    .rotate(0, 0, 1, astroSpin * astroT).rotate(0, 1, 0, astroYaw + 25f * MathUtils.sin(astroT * 0.4f))           // the cartwheel, with a slow turn
                    .scale(sc, sc, sc).translate(0, -astroMid[astro], 0);
            batch.render(m, env);
        }
    }

    /** An astronaut other than the one the player is using, starting the search at {@code i}. */
    private int otherAstro(int i) {
        for (int k = 0; k < ASTRONAUTS.length; k++) { int c = (i + k) % ASTRONAUTS.length; if (c + 2 != playerCharacter) return c; }
        return i;
    }

    /** Planets and rocks that float inside the spiral (turning with it) and the ship that buzzes the player, drawn with the game camera. */
    public void renderNear(ModelBatch batch, Environment mainEnv, Placer placer, float camS, float camY, float playerY, float playerZ, boolean reduced, double originY) {
        long b0 = (long) Math.floor((camY + originY - 40.0) / 70.0), b1 = (long) Math.floor((camY + originY + 40.0) / 70.0);       // bands are absolute, so scenery stays put when the floating origin moves
        for (long bandL = b0; bandL <= b1; bandL++) {
            int band = (int) bandL; float bandY = (float) (bandL * 70.0 - originY);
            long h = mixSeed(band);
            // one planet per band, inside the cylinder (only from dusk onward; the sunny worlds keep the view clear)
            if (zoneNow >= 1.8f) {
            ModelInstance pl = planets[(int) ((h >>> 3) % PLANETS)];
            float arc = ((h >>> 10) % 1000) / 1000f * circumference, y = bandY + ((h >>> 20) % 1000) / 1000f * 60f, r = 3.2f + ((h >>> 30) % 1000) / 1000f * 4.5f;
            float sc = 0.45f + ((h >>> 40) % 1000) / 1000f * 0.8f;
            r = inside(r, sc, planetExt[(int) ((h >>> 3) % PLANETS)]);
            float k = clearOfCourse(arc, r, sc * planetExt[(int) ((h >>> 3) % PLANETS)], camS);
            if (k > 0f) {
            alpha(pl, k);
            placer.place(pl, arc, y, radius - r, sc, sc, sc, reduced ? 0f : clock * (6f + (h % 7)));
            batch.render(pl, mainEnv);
            }
            }
            if (spaceW > 0.3f) {            // deep space: a second planet per band
                ModelInstance p2 = planets[(int) ((h >>> 5) % PLANETS)];
                float a2 = ((h >>> 12) % 1000) / 1000f * circumference, y2 = bandY + ((h >>> 22) % 1000) / 1000f * 60f, r2 = 3.5f + ((h >>> 32) % 1000) / 1000f * 4f, s2 = 0.5f + ((h >>> 42) % 1000) / 1000f * 0.9f;
                r2 = inside(r2, s2, planetExt[(int) ((h >>> 5) % PLANETS)]);
                float k = clearOfCourse(a2, r2, s2 * planetExt[(int) ((h >>> 5) % PLANETS)], camS);
                if (k > 0f) {
                alpha(p2, k);
                placer.place(p2, a2, y2, radius - r2, s2, s2, s2, reduced ? 0f : clock * 5f);
                batch.render(p2, mainEnv);
                }
            }
            // two drifting rocks (three in deep space)
            for (int k = 0; k < (spaceW > 0.3f ? 3 : 2); k++) {
                long g = mixSeed(band * 31 + k + 977);
                if ((g >>> 50) % 100 >= 20 + 80 * MathUtils.clamp(zoneNow / 3f, 0f, 1f)) continue;       // fewer rocks in the sunny worlds
                ModelInstance rk = rocks[(int) ((g >>> 4) % ROCKS)];
                float ra = ((g >>> 12) % 1000) / 1000f * circumference, ry = bandY + ((g >>> 22) % 1000) / 1000f * 70f, rr = 2.5f + ((g >>> 32) % 1000) / 1000f * 5.5f;
                float rs = 0.3f + ((g >>> 42) % 1000) / 1000f * 0.6f;
                rr = inside(rr, rs, rockExt[(int) ((g >>> 4) % ROCKS)]);
                if (rr < 0.3f) continue;
                float vis = clearOfCourse(ra, rr, rs * rockExt[(int) ((g >>> 4) % ROCKS)], camS);
                if (vis <= 0f) continue;
                alpha(rk, vis);
                placer.place(rk, ra, ry + (reduced ? 0f : 0.4f * MathUtils.sin(clock * 0.6f + k)), radius - rr, rs, rs, rs, reduced ? 0f : clock * (8f + (g % 9)));
                batch.render(rk, mainEnv);
            }
        }
        if (buzzT >= 0) {
            float u = buzzT / BUZZ_TIME, x = (u - 0.5f) * 64f * buzzDir, y = playerY + buzzY + 0.25f * MathUtils.sin(buzzT * 6f);
            // owner rule (Build 78): nothing comes between the camera and the course, so the ship passes behind all of it (its widest wingspan, 5.8 model
            // units either side, points at the camera while it flies sideways: 0.6 x 5.8 = 3.5 m, nearest point 13.5 m behind the front of the tower)
            float z = playerZ - radius - 7f;
            ModelInstance sh = ships[buzzShip]; float sc = 0.6f;
            alpha(sh, 1f);
            sh.transform.idt().translate(x, y, z).rotate(0, 1, 0, (buzzDir > 0 ? 90f : -90f) * SHIP_NOSE).rotate(0, 0, 1, 14f * buzzDir * MathUtils.sin(u * MathUtils.PI)).scale(sc, sc, sc);
            batch.render(sh, mainEnv);
        }
    }

    private static float reach(ModelInstance m) {
        com.badlogic.gdx.math.collision.BoundingBox bb = new com.badlogic.gdx.math.collision.BoundingBox(); m.calculateBoundingBox(bb);
        com.badlogic.gdx.math.Vector3 d = bb.getDimensions(new com.badlogic.gdx.math.Vector3());
        return 0.5f * (float) Math.sqrt(d.x * d.x + d.z * d.z);
    }

    /**
     * Owner rule (Build 78): nothing may block the player's view of the course. Scenery inside the cylinder turns with the tower, so as the climb
     * goes round it comes toward the camera; it is drawn only while its nearest point is behind the farthest part of the course the renderer can
     * draw (WorldRenderer.visible: 1.3 rad either side plus a 3 m margin; platforms and the hazards set behind the lane reach about 2 m deeper), and it
     * fades out over the last 1.5 m before that limit, where it still covers nothing. Returns its opacity, 0 = not drawn.
     */
    private float clearOfCourse(float arc, float r, float ext, float camS) {
        float phi = (mod(arc - camS + circumference * 0.5f, circumference) - circumference * 0.5f) / radius;
        float nearZ = -radius + r * MathUtils.cos(phi) + ext;                                    // its nearest point toward the camera (+z)
        float courseFarZ = -radius + radius * MathUtils.cos(Math.min(MathUtils.PI, 1.3f + 3f / radius)) - 2f;
        return MathUtils.clamp((courseFarZ - nearZ) / 1.5f, 0f, 1f);
    }

    /** Scenery floating inside the cylinder must never reach the plane the player and the platforms use (distance `radius` from the axis): keep its far edge a margin short of it. */
    private float inside(float r, float scale, float ext) { return Math.min(r, radius - ext * scale - 2.2f); }

    private static float mod(float a, float m) { return ((a % m) + m) % m; }
    private static long mixSeed(long x) {
        long z = x * 0x9E3779B97F4A7C15L + 0x7F4A7C15L; z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L; z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL; z ^= z >>> 31;
        return z & Long.MAX_VALUE;
    }
}

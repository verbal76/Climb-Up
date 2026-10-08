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
    private final java.util.Random rnd = new java.util.Random(31);   // cosmetic only

    // far layer: planets pinned to the sky
    private static final class Far { int planet; float u, v, dist, ang, pkx, pky, spin; }
    private final Far[] farPlanets = new Far[5];

    // sky ship: crosses the sky slowly; close ship: a short fast fly-by in front of the player
    private float skyT = -1, skyDur, skyY, skyDist, skyDir, nextSky = 6f; private int skyShip;
    private float buzzT = -1, buzzDir, buzzY, nextBuzz = 30f, clock; private int buzzShip;
    private static final float BUZZ_TIME = 2.8f, FREEZE = Float.parseFloat(System.getProperty("climb.spaceFreeze", "-1"));   // FREEZE: test hook that parks both ships at that point of their flight
    private boolean buzzSounded;
    public boolean whoosh;                 // set for one frame when the close ship passes the player (the play screen plays the sound)

    private final Vector3 tmp = new Vector3(), right = new Vector3(), up = new Vector3(), fwd = new Vector3();

    public SpaceScene(Models models, Camera farCam, float radius) {
        if (Boolean.getBoolean("climb.spaceFast")) { nextSky = 0.5f; nextBuzz = 2.5f; }       // test hook
        this.models = models; this.far = farCam; this.radius = radius; this.circumference = (float) (2 * Math.PI * radius);
        for (int i = 0; i < PLANETS; i++) planets[i] = fade(new ModelInstance(models.space("planet_" + (i + 1))));
        for (int i = 0; i < ROCKS; i++) rocks[i] = new ModelInstance(models.space(ROCK_NAMES[i]));
        for (int i = 0; i < SHIPS.length; i++) ships[i] = fade(new ModelInstance(models.space(SHIPS[i])));
        env.set(new ColorAttribute(ColorAttribute.AmbientLight, 0.62f, 0.62f, 0.70f, 1f));
        env.add(new DirectionalLight().set(1f, 0.96f, 0.88f, -0.5f, -0.6f, -0.6f));
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
        if (reduced) { skyT = -1; buzzT = -1; return; }
        if (FREEZE >= 0) { skyT = FREEZE * 24f; skyDur = 24f; skyDist = 300f; skyDir = 1f; buzzT = FREEZE * BUZZ_TIME; buzzDir = 1f; buzzY = 1.5f; return; }
        if (skyT < 0) { nextSky -= dt; if (nextSky <= 0) { skyT = 0; skyDur = 20f + rnd.nextFloat() * 14f; skyY = rnd.nextFloat(); skyDist = 240f + rnd.nextFloat() * 120f; skyDir = rnd.nextBoolean() ? 1f : -1f; skyShip = rnd.nextInt(SHIPS.length); } }
        else { skyT += dt; if (skyT >= skyDur) { skyT = -1; nextSky = 8f + rnd.nextFloat() * 14f; } }
        if (buzzT < 0) { nextBuzz -= dt; if (nextBuzz <= 0) { buzzT = 0; buzzDir = rnd.nextBoolean() ? 1f : -1f; buzzY = 0.8f + rnd.nextFloat() * 2.2f; buzzShip = rnd.nextInt(SHIPS.length); buzzSounded = false; } }
        else { buzzT += dt; if (!buzzSounded && buzzT > 0.55f) { buzzSounded = true; whoosh = true; } if (buzzT > BUZZ_TIME) { buzzT = -1; nextBuzz = 45f + rnd.nextFloat() * 60f; } }
    }

    /** Planets and sky ships, drawn with the wide-range camera before the tower. */
    public void renderFar(ModelBatch batch, float camS, float camY, float zoneF) {
        float a = MathUtils.lerp(0.5f, 0.85f, MathUtils.clamp(zoneF / 2.4f, 0f, 1f));
        fwd.set(far.direction).nor(); right.set(fwd).crs(far.up).nor(); up.set(right).crs(fwd).nor();
        for (Far f : farPlanets) {
            float W = 1.7f * f.dist, H = 1.2f * f.dist;
            float x = mod(f.u * W - camS * f.pkx * f.dist * 0.1f, W) - W / 2f;
            float y = mod(f.v * H - camY * f.pky * f.dist * 0.1f, H) - H / 2f;
            tmp.set(far.position).mulAdd(fwd, f.dist).mulAdd(right, x).mulAdd(up, y);
            ModelInstance pl = planets[f.planet];
            float sc = f.dist * MathUtils.tanDeg(f.ang) / 1.9f;
            pl.transform.idt().translate(tmp).rotate(0, 1, 0, clock * f.spin).rotate(1, 0, 0, 12f).scale(sc, sc, sc);
            alpha(pl, a); batch.render(pl, env);
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
    }

    /** Planets and rocks that float inside the spiral (turning with it) and the ship that buzzes the player, drawn with the game camera. */
    public void renderNear(ModelBatch batch, Environment mainEnv, Placer placer, float camS, float camY, float playerY, float playerZ, boolean reduced) {
        int b0 = (int) Math.floor((camY - 40f) / 70f), b1 = (int) Math.floor((camY + 40f) / 70f);
        for (int band = b0; band <= b1; band++) {
            long h = mixSeed(band);
            // one planet per band, inside the cylinder
            ModelInstance pl = planets[(int) ((h >>> 3) % PLANETS)];
            float arc = ((h >>> 10) % 1000) / 1000f * circumference, y = band * 70f + ((h >>> 20) % 1000) / 1000f * 60f, r = 3.2f + ((h >>> 30) % 1000) / 1000f * 4.5f;
            float sc = 0.45f + ((h >>> 40) % 1000) / 1000f * 0.8f;
            alpha(pl, 1f);
            placer.place(pl, arc, y, radius - r, sc, sc, sc, reduced ? 0f : clock * (6f + (h % 7)));
            batch.render(pl, mainEnv);
            // two drifting rocks
            for (int k = 0; k < 2; k++) {
                long g = mixSeed(band * 31 + k + 977);
                ModelInstance rk = rocks[(int) ((g >>> 4) % ROCKS)];
                float ra = ((g >>> 12) % 1000) / 1000f * circumference, ry = band * 70f + ((g >>> 22) % 1000) / 1000f * 70f, rr = 2.5f + ((g >>> 32) % 1000) / 1000f * 5.5f;
                float rs = 0.35f + ((g >>> 42) % 1000) / 1000f * 0.8f;
                placer.place(rk, ra, ry + (reduced ? 0f : 0.4f * MathUtils.sin(clock * 0.6f + k)), radius - rr, rs, rs, rs, reduced ? 0f : clock * (8f + (g % 9)));
                batch.render(rk, mainEnv);
            }
        }
        if (buzzT >= 0) {
            float u = buzzT / BUZZ_TIME, x = (u - 0.5f) * 64f * buzzDir, y = playerY + buzzY + 0.25f * MathUtils.sin(buzzT * 6f);
            float z = playerZ - 1.2f;
            ModelInstance sh = ships[buzzShip]; float sc = 0.30f;
            alpha(sh, 1f);
            sh.transform.idt().translate(x, y, z).rotate(0, 1, 0, (buzzDir > 0 ? 90f : -90f) * SHIP_NOSE).rotate(0, 0, 1, 14f * buzzDir * MathUtils.sin(u * MathUtils.PI)).scale(sc, sc, sc);
            batch.render(sh, mainEnv);
        }
    }

    private static float mod(float a, float m) { return ((a % m) + m) % m; }
    private static long mixSeed(long x) {
        long z = x * 0x9E3779B97F4A7C15L + 0x7F4A7C15L; z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L; z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL; z ^= z >>> 31;
        return z & Long.MAX_VALUE;
    }
}

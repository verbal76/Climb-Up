package com.hotatticgames.climbup.render;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.Shader;
import com.badlogic.gdx.graphics.g3d.shaders.DefaultShader;
import com.badlogic.gdx.graphics.g3d.utils.DefaultShaderProvider;
import com.badlogic.gdx.graphics.g3d.utils.RenderContext;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.MathUtils;

/**
 * ATMOSPHERE PROTOTYPE (visual/build83-atmosphere): aerial haze, a low mist below the climber, selective glow, a refined sky and a
 * per-castle colour identity. Pure drawing: it never reads or changes the simulation, the course, the RNG, input or tuning.
 *
 * <p>Everything sits behind ONE master switch, {@link #ENABLED}, which is OFF unless the JVM is started with {@code -Dclimb.atmos=true}
 * (or {@link #DEFAULT_ON} is flipped). With it off {@link WorldRenderer} builds exactly the shader, lights and draw calls it always did:
 * no {@code Atmos} object exists and every hook is skipped.
 *
 * <p>Accessibility and budgets: nothing here moves except {@link AtmosMotes}, which are off under REDUCED MOTION; nothing flashes. The
 * haze and mist begin beyond the play plane (the hero, the platforms in reach, hazards, keys and gates are never hazed), strongly
 * coloured glowing things (gems, keys, gate flags, hazards) are protected from the haze and glow a little more, and under
 * COLOURBLIND-SAFE COLOURS the per-castle tint touches only the sky, never the light the keys and gates are seen in. Graphics quality:
 * LOW adds no haze, mist, glow, sky overlay or motes (their strengths are zero; only the free per-castle colour shift remains), MEDIUM adds the
 * haze, mist, glow and sky refinement, HIGH also the motes. The hero's own light rig is never touched (he is drawn without fog, so none of the
 * shader additions run on him).
 */
public final class Atmos {
    /** Compile-time default of the master switch. Keep false: the prototype is opt-in. */
    public static final boolean DEFAULT_ON = false;
    /** THE master switch: {@code -Dclimb.atmos=true} turns the prototype on. Read once; not a player setting. */
    public static final boolean ENABLED = DEFAULT_ON || readSwitch();

    private static boolean readSwitch() {
        try { return Boolean.parseBoolean(System.getProperty("climb.atmos", "false")); } catch (RuntimeException e) { return false; }
    }

    // ------------------------------------------------------------------ per-world data (Meadow, Frost, Dusk, Night, Deep Space)
    /** Aerial-perspective haze colour, mixed half-and-half with the world's own fog colour so it always belongs to that sky. */
    static final Color[] HAZE = {c(0.80f, 0.86f, 0.94f), c(0.82f, 0.90f, 1.00f), c(1.00f, 0.66f, 0.54f), c(0.22f, 0.30f, 0.56f), c(0.32f, 0.20f, 0.50f)};
    static final float[] HAZE_A = {0.38f, 0.40f, 0.38f, 0.34f, 0.26f};
    /** The low mist below the climber: looking down reads as a long way down. */
    static final Color[] MIST = {c(0.76f, 0.82f, 0.88f), c(0.88f, 0.93f, 1.00f), c(0.66f, 0.40f, 0.46f), c(0.10f, 0.15f, 0.32f), c(0.14f, 0.07f, 0.24f)};
    static final float[] MIST_A = {0.22f, 0.26f, 0.24f, 0.26f, 0.16f};
    /** Strength of the haze band rising from the bottom of the sky (drawn under the sun glow and the far layer): the air thickens looking down. */
    static final float[] HORIZON_A = {0.26f, 0.30f, 0.22f, 0.22f, 0.18f};
    /** Strength of the deeper, richer colour at the very top of the sky (zenith). */
    static final float[] ZENITH_A = {0.28f, 0.26f, 0.24f, 0.24f, 0.10f};

    /**
     * Per-castle identity: the climb between castle k and castle k+1 (k = 0 is the start) gets its own small colour shift within its
     * world's palette. Multipliers near 1; segment 1 (castle 1 to 2) already has its own golden hour, so it stays neutral, and so does
     * the start the owner has already approved. Plain floats, not Colors: a libGDX Color clamps to 0..1, which would flatten every multiplier above 1.
     */
    static final float[][] CASTLE = {
        {1.00f, 1.00f, 1.00f},   // start -> castle 1: Meadow morning, as approved
        {1.00f, 1.00f, 1.00f},   // castle 1 -> 2: the existing golden afternoon
        {0.96f, 1.00f, 1.05f},   // castle 2 -> 3: Frost, crisp and cold
        {1.03f, 1.00f, 1.03f},   // castle 3 -> 4: Frost, pale pastel light on the ice
        {1.05f, 0.99f, 0.93f},   // castle 4 -> 5: Dusk, amber
        {1.03f, 0.94f, 1.03f},   // castle 5 -> 6: Dusk, the rose of a late sunset
        {0.95f, 0.99f, 1.06f},   // castle 6 -> 7: Night, blue moonlight
        {0.94f, 1.04f, 1.02f},   // castle 7 -> 8: Night, a teal aurora hint
        {1.03f, 0.95f, 1.06f},   // castle 8 -> 9: Deep Space, violet
        {0.95f, 0.99f, 1.07f},   // castle 9 -> 10: Deep Space, indigo toward the finish
    };
    /** How much of the castle tint reaches the world's lights (the sky gets all of it). */
    static final float LIGHT_SHARE = 0.6f;
    /**
     * Haze ramp, in metres of view depth BEHIND the play plane (the shader measures each fragment's depth along the camera's view direction and
     * subtracts the plane's own depth, so the camera pulling back in a long fall changes nothing): nothing within HAZE_NEAR of the lane, full
     * strength HAZE_FAR behind it. In this camera that is the tower curving away at the sides of the screen, and the scenery behind the lane.
     */
    static final float HAZE_NEAR = 1.0f, HAZE_FAR = 6f;
    /** The mist starts this far below the camera (the camera sits ~2.5 m above the hero, the bottom of the screen ~5 m below it), full strength MIST_SPAN further down. */
    static final float MIST_BELOW = 5.5f, MIST_SPAN = 12f;
    /** Extra emissive for strongly coloured glowing materials (gems, keys, gate flags, hazards); grey and faint emissives get none. */
    static final float GLOW_GAIN = 0.45f;

    private static Color c(float r, float g, float b) { return new Color(r, g, b, 1f); }

    // ------------------------------------------------------------------ per-frame state (no allocation)
    /** rgb haze colour, a = strength (0 = off). */
    final float[] haze = new float[4];
    final float[] mist = new float[4];
    /** x, y: haze ramp behind the play plane (m); z: mist start below the camera (m); w: glow gain. */
    final float[] params = new float[4];
    /** The castle tint (multipliers) the world's lights get this frame; {1,1,1} = none. Applied with Color.mul, so a light channel never goes past 1. */
    public final float[] light = {1f, 1f, 1f};
    /** This frame's castle tint (multipliers, may exceed 1). */
    final float[] castle = {1f, 1f, 1f};
    private final Color tmp = new Color(), tmp2 = new Color(), fog = new Color(), horizon = new Color(), zenith = new Color();
    private float horizonA, zenithA;
    int quality = 2;

    /**
     * Works out this frame's atmosphere and tints the sky colours in place by castle (call after the sky is blended, before it is drawn).
     * @param absHeight the camera's absolute height in metres
     * @param gold the Meadow's golden-hour weight (0..1, see WorldRenderer.goldenHour): the haze then takes the warm sky's own colour, so the approved afternoon look stays golden
     */
    public void frame(float zoneF, double absHeight, float castleSpacing, float cycleHeight, int quality, boolean colourblind, float gold, Color skyTop, Color skyMid, Color skyBot) {
        this.quality = quality;
        castleTint(absHeight, castleSpacing, cycleHeight, castle);
        float cr = castle[0], cg = castle[1], cb = castle[2];
        skyTop.mul(cr, cg, cb, 1f); skyMid.mul(cr, cg, cb, 1f); skyBot.mul(cr, cg, cb, 1f);       // (Color.mul clamps to 0..1)
        if (colourblind) light[0] = light[1] = light[2] = 1f;
        else { light[0] = 1f + (cr - 1f) * LIGHT_SHARE; light[1] = 1f + (cg - 1f) * LIGHT_SHARE; light[2] = 1f + (cb - 1f) * LIGHT_SHARE; }
        boolean on = quality >= 1;
        fog.set(skyBot).lerp(skyTop, 0.25f);                    // the renderer's own fog colour
        Palette.blend(HAZE, zoneF, tmp); tmp.lerp(fog, 0.5f + 0.5f * MathUtils.clamp(gold, 0f, 1f));
        haze[0] = tmp.r; haze[1] = tmp.g; haze[2] = tmp.b; haze[3] = on ? Palette.blendF(HAZE_A, zoneF) : 0f;
        horizon.set(tmp); horizonA = on ? Palette.blendF(HORIZON_A, zoneF) : 0f;
        Palette.blend(MIST, zoneF, tmp2); tmp2.lerp(skyBot, 0.35f);
        mist[0] = tmp2.r; mist[1] = tmp2.g; mist[2] = tmp2.b; mist[3] = on ? Palette.blendF(MIST_A, zoneF) : 0f;
        params[0] = HAZE_NEAR; params[1] = HAZE_FAR; params[2] = MIST_BELOW; params[3] = on ? GLOW_GAIN : 0f;
        zenith.set(skyTop).mul(0.72f, 0.74f, 0.86f, 1f); zenithA = on ? Palette.blendF(ZENITH_A, zoneF) * (1f - 0.5f * MathUtils.clamp(gold, 0f, 1f)) : 0f;
    }

    /** The castle-segment tint at an absolute height: one colour per climb between castles, blended over the last 5% before each castle. */
    static float[] castleTint(double absHeight, float castleSpacing, float cycleHeight, float[] out) {
        if (!(castleSpacing > 0f) || !(cycleHeight > 0f)) { out[0] = out[1] = out[2] = 1f; return out; }
        double h = Math.max(0.0, absHeight) % cycleHeight;
        float f = (float) (h / castleSpacing);
        int segs = Math.max(1, Math.round(cycleHeight / castleSpacing));
        int s = Math.min(segs - 1, (int) f);
        float t = MathUtils.clamp((f - s - 0.95f) / 0.05f, 0f, 1f); t = t * t * (3f - 2f * t);
        float[] a = CASTLE[s % CASTLE.length], b = CASTLE[((s + 1) % segs) % CASTLE.length];
        for (int i = 0; i < 3; i++) out[i] = a[i] + (b[i] - a[i]) * t;
        return out;
    }

    /**
     * Sky refinement, drawn over the sky gradient and UNDER the sun glow, the far layer and the shafts (so none of those is dimmed): a deeper zenith
     * and a haze band rising from below. (The far layer itself is graded by the shader's haze: see {@link #fogFarLayer}.)
     */
    public void drawSky(ShapeRenderer sr, float w, float h) {
        if (horizonA < 0.005f && zenithA < 0.005f) return;
        sr.getProjectionMatrix().setToOrtho2D(0, 0, w, h);
        sr.setTransformMatrix(IDT);
        Gdx.gl.glEnable(GL20.GL_BLEND); Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        sr.begin(ShapeRenderer.ShapeType.Filled);
        if (zenithA >= 0.005f) {
            float y0 = h * 0.55f;
            tmp.set(zenith.r, zenith.g, zenith.b, 0f); tmp2.set(zenith.r, zenith.g, zenith.b, zenithA);
            sr.rect(0, y0, w, h - y0, tmp, tmp, tmp2, tmp2);
        }
        if (horizonA >= 0.005f) {
            float y1 = h * 0.42f;
            tmp.set(horizon.r, horizon.g, horizon.b, horizonA); tmp2.set(horizon.r, horizon.g, horizon.b, 0f);
            sr.rect(0, 0, w, y1, tmp, tmp, tmp2, tmp2);
        }
        sr.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);
    }
    private static final com.badlogic.gdx.math.Matrix4 IDT = new com.badlogic.gdx.math.Matrix4();

    /**
     * Depth-grades the far sky layer (planets, far clouds, sky ships, astronauts): gives its environment the world's fog colour so the atmosphere
     * shader runs on it, and those objects - hundreds of metres behind the play plane - take the full haze. The libGDX distance fog stays
     * negligible there (its range is the far camera's 2500 m). Call every frame with the world environment's fog colour.
     */
    public static void fogFarLayer(com.badlogic.gdx.graphics.g3d.Environment far, Color fogColor) {
        com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute f = (com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute) far.get(com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute.Fog);
        if (f == null) far.set(new com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute(com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute.Fog, fogColor));
        else f.color.set(fogColor);
    }

    // ------------------------------------------------------------------ the shader: libGDX's default + the colour grade + the atmosphere
    /** The shader provider for the world's ModelBatch: the graded default shader with the atmosphere added, fed this object's per-frame values. */
    public DefaultShaderProvider provider(DefaultShader.Config cfg) {
        String vsrc = DefaultShader.getDefaultVertexShader();
        cfg.vertexShader = vertexShader(vsrc);
        cfg.fragmentShader = fragmentShader(Grade.fragmentShader(), vsrc);
        final Atmos self = this;
        return new DefaultShaderProvider(cfg) {
            @Override protected Shader createShader(Renderable renderable) { return new AtmosShader(renderable, config, self); }
        };
    }

    private static final class AtmosShader extends DefaultShader {
        private final Atmos a;
        private int uHaze = -1, uMist = -1, uK = -1, uDir = -1;
        AtmosShader(Renderable r, Config c, Atmos a) { super(r, c); this.a = a; }
        @Override public void init() {
            super.init();
            uHaze = program.fetchUniformLocation("u_atmosHaze", false);
            uMist = program.fetchUniformLocation("u_atmosMist", false);
            uK = program.fetchUniformLocation("u_atmosK", false);
            uDir = program.fetchUniformLocation("u_atmosView", false);
        }
        @Override public void begin(Camera camera, RenderContext context) {
            super.begin(camera, context);
            if (uHaze >= 0) program.setUniformf(uHaze, a.haze[0], a.haze[1], a.haze[2], a.haze[3]);
            if (uMist >= 0) program.setUniformf(uMist, a.mist[0], a.mist[1], a.mist[2], a.mist[3]);
            if (uK >= 0) program.setUniformf(uK, a.params[0], a.params[1], a.params[2], a.params[3]);
            if (uDir >= 0) {        // the view direction, and the depth of the play plane (z = 0, under the camera's lateral position x = 0) along it
                com.badlogic.gdx.math.Vector3 d = camera.direction, p = camera.position;
                program.setUniformf(uDir, d.x, d.y, d.z, -(p.x * d.x + p.z * d.z));
            }
        }
    }

    private static String glsl(float v) { return String.format(java.util.Locale.ROOT, "%.4f", v); }

    static final String V_ANCHOR = "v_fog = min(fog, 1.0);";
    static final String F_ANCHOR = "\t#ifdef fogFlag\n\t\tgl_FragColor.rgb = mix(gl_FragColor.rgb, u_fogColor.rgb, v_fog);";

    /** The default vertex shader plus one varying (only where fog is on): x = height relative to the camera, y = view depth behind the play plane. */
    static String vertexShader(String src) {
        int at = src.indexOf(V_ANCHOR);
        if (at < 0) return src;          // an unknown libGDX shader: the fragment side then falls back too (see fragmentShader)
        at += V_ANCHOR.length();
        return "#ifdef fogFlag\nuniform vec4 u_atmosView;\nvarying vec2 v_atmos;\n#endif\n" + src.substring(0, at)
            + "\n\t\tv_atmos = vec2(pos.y - u_cameraPosition.y, dot(pos.xyz - u_cameraPosition.xyz, u_atmosView.xyz) - u_atmosView.w);" + src.substring(at);
    }

    /** The graded fragment shader plus the haze, mist and glow, applied just before the distance fog and only where fog is on (the world, never the hero). */
    static String fragmentShader(String graded, String vertexSrc) {
        int main = graded.indexOf("void main() {"), fog = graded.indexOf(F_ANCHOR);
        if (main < 0 || fog < 0 || vertexSrc.indexOf(V_ANCHOR) < 0) return graded;      // both halves or neither: the varying must exist on both sides
        String decl = "#ifdef fogFlag\nuniform vec4 u_atmosHaze;\nuniform vec4 u_atmosMist;\nuniform vec4 u_atmosK;\nvarying vec2 v_atmos;\n#endif\n\n";
        String body = "\t#ifdef fogFlag\n"
            + "\t{\n"
            + "\t\tfloat atmC = max(emissive.r, max(emissive.g, emissive.b)) - min(emissive.r, min(emissive.g, emissive.b));\n"
            + "\t\tfloat atmGlow = smoothstep(0.12, 0.24, atmC);\n"
            + "\t\tgl_FragColor.rgb += emissive.rgb * (atmGlow * u_atmosK.w);\n"
            + "\t\tfloat atmKeep = 1.0 - 0.85 * atmGlow;\n"
            + "\t\tfloat atmMist = clamp((-v_atmos.x - u_atmosK.z) / " + glsl(MIST_SPAN) + ", 0.0, 1.0) * u_atmosMist.a * atmKeep;\n"
            + "\t\tgl_FragColor.rgb = mix(gl_FragColor.rgb, u_atmosMist.rgb, atmMist);\n"
            + "\t\tfloat atmHaze = clamp((v_atmos.y - u_atmosK.x) / (u_atmosK.y - u_atmosK.x), 0.0, 1.0) * u_atmosHaze.a * atmKeep;\n"
            + "\t\tgl_FragColor.rgb = mix(gl_FragColor.rgb, u_atmosHaze.rgb, atmHaze);\n"
            + "\t}\n"
            + "\t#endif\n";
        String withBody = graded.substring(0, fog) + body + graded.substring(fog);
        return withBody.substring(0, main) + decl + withBody.substring(main);
    }
}

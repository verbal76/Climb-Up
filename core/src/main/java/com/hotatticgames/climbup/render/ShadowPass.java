package com.hotatticgames.climbup.render;

import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g3d.Attributes;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.Shader;
import com.badlogic.gdx.graphics.g3d.shaders.DefaultShader;
import com.badlogic.gdx.graphics.g3d.utils.DefaultShaderProvider;
import com.badlogic.gdx.graphics.g3d.utils.RenderContext;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.utils.Disposable;

/**
 * Build 72 (owner: "make it an actual dynamic shadow"): the hero's and the creatures' real shadows. Each caster's own model, posed and animated exactly as it is
 * drawn, is drawn again flattened onto the plane of the platform under it along the light, in one flat translucent colour, and cut off where the platform's
 * top ends, so a hero standing half over the edge casts half a shadow. No shadow map and no extra framebuffer: the cost is drawing the casters once more,
 * so it runs at every graphics quality.
 * <p>
 * Overlapping parts of one silhouette (an arm over the body) are drawn once, not darkened twice: the flattened triangles all lie in one plane, and the depth
 * test (strictly less) lets only the first of them colour a pixel.
 */
public final class ShadowPass implements Disposable {
    private static final Matrix4 PROJ = new Matrix4();
    private static final float[] CLIP = new float[4];
    private static final Color COL = new Color();

    private final ModelBatch batch;

    public ShadowPass(int bones) {
        DefaultShader.Config c = new DefaultShader.Config();
        c.numBones = bones; c.numDirectionalLights = 0; c.numPointLights = 0; c.numSpotLights = 0;
        c.vertexShader = vertexShader(); c.fragmentShader = FRAGMENT;
        batch = new ModelBatch(new DefaultShaderProvider(c) {
            @Override protected Shader createShader(Renderable renderable) { return new Flat(renderable, config); }
        });
    }

    /**
     * Draws one caster's shadow. The plane is {@code a*x + b*y + c*z + d = 0} (b > 0, the side the caster is on positive); {@code light} points toward the light
     * (its y above 0); the shadow shows only for x in [x0, x1] and z in [z0, z1] (world units), in colour {@code col} (alpha = its strength).
     */
    public void draw(Camera cam, ModelInstance[] casters, float a, float b, float c, float d, float lx, float ly, float lz, float x0, float x1, float z0, float z1, Color col) {
        planarShadow(PROJ, a, b, c, d, lx, ly, lz);
        CLIP[0] = x0; CLIP[1] = x1; CLIP[2] = z0; CLIP[3] = z1; COL.set(col);
        batch.begin(cam);
        for (ModelInstance m : casters) if (m != null) batch.render(m);
        batch.end();
    }

    /** The matrix that flattens any point onto the plane P along the direction toward the light l: S = (P.l) I - l P^T (column-major, as libGDX stores it). */
    static void planarShadow(Matrix4 out, float a, float b, float c, float d, float lx, float ly, float lz) {
        float[] p = {a, b, c, d}, l = {lx, ly, lz, 0f};
        float dot = a * lx + b * ly + c * lz;
        for (int row = 0; row < 4; row++) for (int col = 0; col < 4; col++) out.val[col * 4 + row] = (row == col ? dot : 0f) - l[row] * p[col];
    }

    private static final class Flat extends DefaultShader {
        Flat(Renderable renderable, Config config) { super(renderable, config); }
        @Override public void begin(Camera camera, RenderContext context) {
            super.begin(camera, context);
            program.setUniformMatrix("u_shadowProj", PROJ);
            program.setUniformf("u_clip", CLIP[0], CLIP[1], CLIP[2], CLIP[3]);
            program.setUniformf("u_shadowCol", COL.r, COL.g, COL.b, COL.a);
        }
        @Override protected void bindMaterial(Attributes attributes) {
            super.bindMaterial(attributes);
            context.setCullFace(0);                                              // a flattened triangle can face either way
            context.setBlending(true, GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
            context.setDepthTest(GL20.GL_LESS, 0f, 1f);                          // one coat of shadow per pixel (see the class note)
            context.setDepthMask(true);
        }
    }

    static String vertexShader() { return inject(DefaultShader.getDefaultVertexShader()); }

    /** libGDX's default vertex shader with the flattening added right before the camera transform. */
    static String inject(String src) {
        String anchor = "gl_Position = u_projViewTrans * pos;";
        int at = src.indexOf(anchor);
        if (at < 0) throw new IllegalStateException("libGDX default vertex shader changed: no '" + anchor + "'");
        return "uniform mat4 u_shadowProj;\nvarying vec3 v_shWorld;\n"
            + src.substring(0, at) + "pos = u_shadowProj * pos; pos /= pos.w; v_shWorld = pos.xyz;\n\t" + src.substring(at);
    }

    static final String FRAGMENT =
        "#ifdef GL_ES\nprecision mediump float;\n#endif\n"
        + "uniform vec4 u_clip;\nuniform vec4 u_shadowCol;\nvarying vec3 v_shWorld;\n"
        + "void main() {\n"
        + "\tif (v_shWorld.x < u_clip.x || v_shWorld.x > u_clip.y || v_shWorld.z < u_clip.z || v_shWorld.z > u_clip.w) discard;\n"
        + "\tgl_FragColor = u_shadowCol;\n"
        + "}\n";

    @Override public void dispose() { batch.dispose(); }
}

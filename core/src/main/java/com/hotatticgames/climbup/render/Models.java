package com.hotatticgames.climbup.render;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttributes.Usage;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.graphics.g3d.loader.ObjLoader;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.utils.Disposable;
import java.util.HashMap;
import java.util.Map;

/** Loads the Kenney Platformer Kit OBJ models (CC0) once and builds the few procedural props (rope, cable, pad). */
public final class Models implements Disposable {
    private final Map<String, Model> map = new HashMap<>();
    private final Map<String, Model> pack = new HashMap<>();
    private final ObjLoader loader = new ObjLoader();
    public final Model box, disc;
    /** Build 70 (owner): flat puddle shapes for the wet-platform film - rounded, with wavy edges, instead of a rectangular sheet. Unit size like {@link #box}. */
    public final Model[] puddles = new Model[3];
    private final Texture[] sharedTex = new Texture[1];

    public Models() {
        ModelBuilder mb = new ModelBuilder();
        box = mb.createBox(1f, 1f, 1f, new Material(ColorAttribute.createDiffuse(Color.WHITE)), Usage.Position | Usage.Normal);
        disc = mb.createCylinder(1f, 0.02f, 1f, 20, new Material(ColorAttribute.createDiffuse(Color.WHITE)), Usage.Position | Usage.Normal);
        for (int v = 0; v < puddles.length; v++) puddles[v] = puddle(mb, v);
    }

    /**
     * A puddle prism, 1 x 1 x 1 like the box: its outline is a circle whose radius wobbles with two slow waves (deterministic per variant), so scaled to a
     * platform it reads as a rounded, wavy-edged pool of water or ice rather than a rectangle.
     */
    private static Model puddle(ModelBuilder mb, int variant) {
        final int n = 40;
        float a = 0.9f + variant * 1.7f, b = 2.3f + variant * 0.8f;
        float[] x = new float[n], z = new float[n];
        for (int k = 0; k < n; k++) {
            float t = k * com.badlogic.gdx.math.MathUtils.PI2 / n;
            float r = 0.5f * (0.86f + 0.08f * com.badlogic.gdx.math.MathUtils.sin(3f * t + a) + 0.06f * com.badlogic.gdx.math.MathUtils.sin(5f * t + b));
            x[k] = r * com.badlogic.gdx.math.MathUtils.cos(t); z[k] = r * com.badlogic.gdx.math.MathUtils.sin(t);
        }
        mb.begin();
        com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder p = mb.part("puddle", com.badlogic.gdx.graphics.GL20.GL_TRIANGLES, Usage.Position | Usage.Normal,
                new Material(ColorAttribute.createDiffuse(Color.WHITE)));
        com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder.VertexInfo c = new com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder.VertexInfo(),
                v1 = new com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder.VertexInfo(), v2 = new com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder.VertexInfo(),
                v3 = new com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder.VertexInfo(), v4 = new com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder.VertexInfo();
        for (int k = 0; k < n; k++) {
            int j = (k + 1) % n;
            // top face (counter-clockwise seen from above)
            c.setPos(0f, 0.5f, 0f).setNor(0f, 1f, 0f); v1.setPos(x[j], 0.5f, z[j]).setNor(0f, 1f, 0f); v2.setPos(x[k], 0.5f, z[k]).setNor(0f, 1f, 0f);
            p.triangle(c, v1, v2);
            // the thin rim facing outward
            float nx = z[j] - z[k], nz = -(x[j] - x[k]), len = (float) Math.sqrt(nx * nx + nz * nz); nx /= len; nz /= len;
            v1.setPos(x[k], 0.5f, z[k]).setNor(nx, 0f, nz); v2.setPos(x[j], 0.5f, z[j]).setNor(nx, 0f, nz);
            v3.setPos(x[j], -0.5f, z[j]).setNor(nx, 0f, nz); v4.setPos(x[k], -0.5f, z[k]).setNor(nx, 0f, nz);
            p.triangle(v1, v2, v3); p.triangle(v1, v3, v4);
        }
        return mb.end();
    }

    public Model obj(String name) {
        Model m = map.get(name);
        if (m == null) {
            m = loader.loadModel(Gdx.files.internal("models/" + name + ".obj"), true);
            for (com.badlogic.gdx.graphics.g3d.Material mat : m.materials) {
                TextureAttribute ta = (TextureAttribute) mat.get(TextureAttribute.Diffuse);
                if (ta != null) {
                    ta.textureDescription.texture.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
                }
            }
            map.put(name, m);
        }
        return m;
    }

    /** Quaternius Ultimate Platformer Pack model (CC0), converted to g3dj by tools/gltf_static_to_g3dj.py. */
    public Model pack(String name) {
        Model m = pack.get(name);
        if (m == null) {
            m = new com.badlogic.gdx.graphics.g3d.loader.G3dModelLoader(new com.badlogic.gdx.utils.JsonReader()).loadModel(Gdx.files.internal("pack/" + name + ".g3dj"));
            pack.put(name, m);
        }
        return m;
    }

    /** Quaternius Ultimate Space Kit model (CC0), baked to per-vertex colour by tools/space_kit_to_g3dj.py. */
    public Model space(String name) {
        Model m = pack.get("space/" + name);
        if (m == null) {
            m = new com.badlogic.gdx.graphics.g3d.loader.G3dModelLoader(new com.badlogic.gdx.utils.JsonReader()).loadModel(Gdx.files.internal("space/" + name + ".g3dj"));
            pack.put("space/" + name, m);
        }
        return m;
    }

    @Override public void dispose() {
        for (Model m : pack.values()) m.dispose();
        for (Model m : map.values()) m.dispose();
        box.dispose(); disc.dispose();
        for (Model m : puddles) m.dispose();
    }
}

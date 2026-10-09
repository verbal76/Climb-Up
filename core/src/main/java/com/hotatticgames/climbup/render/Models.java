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
    private final Texture[] sharedTex = new Texture[1];

    public Models() {
        ModelBuilder mb = new ModelBuilder();
        box = mb.createBox(1f, 1f, 1f, new Material(ColorAttribute.createDiffuse(Color.WHITE)), Usage.Position | Usage.Normal);
        disc = mb.createCylinder(1f, 0.02f, 1f, 20, new Material(ColorAttribute.createDiffuse(Color.WHITE)), Usage.Position | Usage.Normal);
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
    }
}

package com.hotatticgames.climbup.desktop;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight;
import com.badlogic.gdx.graphics.g3d.loader.G3dModelLoader;
import com.badlogic.gdx.graphics.g3d.utils.AnimationController;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.utils.JsonReader;

/** Dev tool (not part of the game): renders a character model with a transparent background to a PNG, used to make the app icon.
 *  usage: -Dclimb.iconShot=out.png [-Dclimb.iconModel=hero/hero.g3dj] [-Dclimb.iconAnim=Idle] [-Dclimb.iconT=0.6] */
public final class IconShot extends ApplicationAdapter {
    private final int size = 1024;
    @Override public void create() {
        String root = System.getProperty("climb.assets", "assets");
        Model m = new G3dModelLoader(new JsonReader()).loadModel(Gdx.files.absolute(root + "/" + System.getProperty("climb.iconModel", "hero/hero.g3dj")));
        ModelInstance inst = new ModelInstance(m);
        AnimationController ac = new AnimationController(inst);
        try { ac.setAnimation(System.getProperty("climb.iconAnim", "Idle")); ac.update(Float.parseFloat(System.getProperty("climb.iconT", "0.6"))); } catch (Exception ignored) { }
        inst.calculateTransforms();
        BoundingBox bb = new BoundingBox(); inst.calculateBoundingBox(bb);
        Vector3 c = bb.getCenter(new Vector3()), d = bb.getDimensions(new Vector3());
        float r = Math.max(d.y, d.x) * 0.5f;
        PerspectiveCamera cam = new PerspectiveCamera(30f, size, size);
        float zoom = Float.parseFloat(System.getProperty("climb.iconZoom", "1")), lookY = Float.parseFloat(System.getProperty("climb.iconLookY", "0"));   // zoom < 1 moves in; lookY shifts the aim up (fraction of height)
        float fy = c.y + d.y * lookY;
        cam.position.set(c.x + r * 0.55f * zoom, fy + r * 0.1f * zoom, c.z + r * 3.4f / (float) Math.tan(Math.toRadians(15)) * 0.42f * zoom);
        cam.lookAt(c.x, fy - r * 0.02f * zoom, c.z); cam.near = 0.1f; cam.far = 500f; cam.update();
        Environment env = new Environment();
        env.set(new ColorAttribute(ColorAttribute.AmbientLight, 0.62f, 0.6f, 0.68f, 1f));
        env.add(new DirectionalLight().set(1.0f, 0.95f, 0.85f, -0.5f, -0.8f, -0.6f));
        env.add(new DirectionalLight().set(0.35f, 0.4f, 0.6f, 0.7f, -0.2f, -0.4f));
        FrameBuffer fb = new FrameBuffer(Pixmap.Format.RGBA8888, size, size, true);
        ModelBatch mb = new ModelBatch(new com.badlogic.gdx.graphics.g3d.utils.DefaultShaderProvider(boneCfg()));
        fb.begin(); Gdx.gl.glClearColor(0, 0, 0, 0); Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
        mb.begin(cam); mb.render(inst, env); mb.end();
        Pixmap raw = Pixmap.createFromFrameBuffer(0, 0, size, size);
        fb.end();
        Pixmap out = new Pixmap(size, size, Pixmap.Format.RGBA8888); out.setBlending(Pixmap.Blending.None);
        for (int y = 0; y < size; y++) out.drawPixmap(raw, 0, y, size, 1, 0, size - 1 - y, size, 1);
        PixmapIO.writePNG(Gdx.files.absolute(System.getProperty("climb.iconShot")), out);
        Gdx.app.exit();
    }
    private static com.badlogic.gdx.graphics.g3d.shaders.DefaultShader.Config boneCfg() {
        com.badlogic.gdx.graphics.g3d.shaders.DefaultShader.Config c = new com.badlogic.gdx.graphics.g3d.shaders.DefaultShader.Config(); c.numBones = 32; return c;
    }
}

package com.hotatticgames.climbup.desktop;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.graphics.VertexAttributes.Usage;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight;
import com.badlogic.gdx.graphics.g3d.loader.G3dModelLoader;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.JsonReader;
import java.nio.ByteBuffer;

/**
 * Renders the cannon pack model exactly as WorldRenderer.drawPackAimed poses it (rotate Z by the aim, then the fixed
 * CANNON_YAW), at several aim angles, with a bright red ball drawn at the launch point and stepping out along the ball's
 * actual flight direction cos/sin(aim). Lets a human confirm the muzzle opening points where the ball goes (not 180 off).
 * Run: xvfb-run -a -s "-screen 0 320x240x24" ./gradlew :desktop:cannonShot  (PNGs in assets/build/cannonshot/)
 */
public final class CannonShot {
    private static final int W = 720, H = 720;
    private static final float CANNON_YAW = Float.parseFloat(System.getProperty("climb.cannonYaw", "-90"));
    private static final String OUT = "build/cannonshot";

    public static void main(String[] args) {
        Lwjgl3ApplicationConfiguration c = new Lwjgl3ApplicationConfiguration();
        c.setTitle("cannon shot"); c.setWindowedMode(W, H); c.useVsync(false); c.disableAudio(true);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override public void create() {
                try { run(); } catch (Throwable e) { System.out.println("CANNONSHOT ERROR " + e); e.printStackTrace(); }
                Gdx.app.exit();
            }
            void run() {
                ModelBatch batch = new ModelBatch();
                Environment env = new Environment();
                env.set(new ColorAttribute(ColorAttribute.AmbientLight, 0.75f, 0.75f, 0.78f, 1f));
                DirectionalLight key = new DirectionalLight(); key.set(1f, 0.97f, 0.9f, -0.4f, -0.7f, -0.7f);
                DirectionalLight fill = new DirectionalLight(); fill.set(0.35f, 0.4f, 0.5f, 0.6f, -0.2f, -0.5f);
                env.add(key); env.add(fill);
                PerspectiveCamera cam = new PerspectiveCamera(45f, W, H); cam.near = 0.1f; cam.far = 100f;
                FrameBuffer fbo = new FrameBuffer(Pixmap.Format.RGBA8888, W, H, true);
                Model cannon = new G3dModelLoader(new JsonReader()).loadModel(Gdx.files.internal("pack/cannon.g3dj"));
                ModelInstance ci = new ModelInstance(cannon);
                ModelBuilder mb = new ModelBuilder();
                Model ball = mb.createSphere(0.5f, 0.5f, 0.5f, 10, 10, new Material(ColorAttribute.createDiffuse(1f, 0.1f, 0.1f, 1f), ColorAttribute.createEmissive(0.6f, 0.03f, 0.03f, 1f)), Usage.Position | Usage.Normal);
                ModelInstance bi = new ModelInstance(ball);
                new FileHandle(OUT).mkdirs();
                float PK = 0.37f;
                // DECISIVE CHECK: transform the barrel's muzzle (model -Z end) and breech (+Z end) through the exact
                // drawPackAimed matrix and print the barrel's screen direction vs the ball's fire direction (cos,sin).
                // The barrel long axis is model Z (mesh p1_0 spans Z +/-1.66, circular in X/Y); muzzle end chosen as -Z.
                com.badlogic.gdx.math.Vector3 muzzle = new com.badlogic.gdx.math.Vector3(), breech = new com.badlogic.gdx.math.Vector3();
                com.badlogic.gdx.math.Matrix4 tf = new com.badlogic.gdx.math.Matrix4();
                for (float deg : new float[]{0, 45, 90, 135, 180, -45, -90}) {
                    tf.idt().rotate(0, 0, 1, deg).rotate(0, 1, 0, CANNON_YAW).scale(PK, PK, PK);
                    muzzle.set(0, 0, -1.66f).mul(tf);
                    breech.set(0, 0, 1.66f).mul(tf);
                    float bx = muzzle.x - breech.x, by = muzzle.y - breech.y;
                    float bl = (float) Math.hypot(bx, by);
                    float barrelAng = (float) Math.toDegrees(Math.atan2(by, bx));
                    System.out.printf("  AIM deg=%4.0f  ball=(%.2f,%.2f)  barrelScreen=(%.2f,%.2f) ang=%.0f  z=%.2f%n",
                            deg, Math.cos(Math.toRadians(deg)), Math.sin(Math.toRadians(deg)), bx / bl, by / bl, barrelAng, (muzzle.z - breech.z));
                }
                float[] degs = {0, 45, 90, 135, -45};
                for (float deg : degs) {
                    float rad = deg * MathUtils.degreesToRadians;
                    cam.position.set(0.5f, 1.5f, 11f); cam.up.set(0, 1, 0); cam.lookAt(0, 0.5f, 0); cam.update();
                    // cannon posed exactly like drawPackAimed (phi=0): rotate Z by aim, then the fixed model yaw
                    ci.transform.idt().rotate(0, 0, 1, deg).rotate(0, 1, 0, CANNON_YAW).scale(PK, PK, PK);
                    fbo.begin();
                    Gdx.gl.glViewport(0, 0, W, H);
                    Gdx.gl.glClearColor(0.62f, 0.78f, 0.95f, 1f);
                    Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
                    Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
                    batch.begin(cam);
                    batch.render(ci, env);
                    // the ball steps out along the ACTUAL flight direction (cos,sin): where the game sends it
                    for (float t = 0f; t <= 3.2f; t += 0.8f) {
                        bi.transform.idt().translate((float) Math.cos(rad) * t, (float) Math.sin(rad) * t, 0f).scale(t == 0f ? 1.2f : 1f, t == 0f ? 1.2f : 1f, 1f);
                        batch.render(bi, env);
                    }
                    batch.end();
                    Pixmap pm = Pixmap.createFromFrameBuffer(0, 0, W, H);
                    fbo.end();
                    flipY(pm);
                    FileHandle fh = new FileHandle(OUT + "/cannon_deg" + (int) deg + ".png");
                    PixmapIO.writePNG(fh, pm); pm.dispose();
                    System.out.println("  shot " + fh.path() + " (yaw=" + CANNON_YAW + ")");
                }
                batch.dispose(); fbo.dispose(); cannon.dispose(); ball.dispose();
            }
        }, c);
        System.out.println("CANNONSHOT_DONE");
        System.exit(0);
    }

    private static void flipY(Pixmap pm) {
        int w = pm.getWidth(), h = pm.getHeight(), stride = w * 4;
        ByteBuffer buf = pm.getPixels();
        byte[] a = new byte[stride], b = new byte[stride];
        for (int y = 0; y < h / 2; y++) {
            int top = y * stride, bot = (h - 1 - y) * stride;
            buf.position(top); buf.get(a); buf.position(bot); buf.get(b);
            buf.position(top); buf.put(b); buf.position(bot); buf.put(a);
        }
        buf.position(0);
    }
}

package com.hotatticgames.climbup.desktop;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight;
import com.badlogic.gdx.graphics.g3d.shaders.DefaultShader;
import com.badlogic.gdx.graphics.g3d.utils.DefaultShaderProvider;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.hotatticgames.climbup.render.Characters;
import com.hotatticgames.climbup.render.HeroRig;
import com.hotatticgames.climbup.sim.Course;
import com.hotatticgames.climbup.sim.CourseGenerator;
import com.hotatticgames.climbup.sim.InputState;
import com.hotatticgames.climbup.sim.Sim;
import com.hotatticgames.climbup.sim.Tuning;
import java.nio.ByteBuffer;

/**
 * Build 67 (owner: "a small render of the character ... so we can see who we are choosing"): renders every selectable character's head and
 * shoulders, standing idle under the game's own hero lights, to a transparent 256 x 256 PNG that the settings screen shows beside the
 * CHARACTER row ({@code assets/ui/portraits/N.png}, read by {@code render.Portraits}). The pictures come from the real rig and the real
 * model files, so a changed character is re-shot by running this again rather than drawn by hand.
 *
 * Run: xvfb-run -a -s "-screen 0 320x240x24" ./gradlew :desktop:portraits   (writes into assets/ui/portraits/; -Dclimb.portraitDir=DIR elsewhere)
 */
public final class PortraitShot {
    private static final int SIZE = Integer.getInteger("climb.portraitSize", 256);     // the PNG; the frame is rendered at twice that and cropped to the character before it is scaled down
    private static final int RENDER = SIZE * 2;
    private static final String OUT = System.getProperty("climb.portraitDir", "ui/portraits");     // relative to the working directory (assets/)
    /** Where the camera looks (above the feet), how far it stands and how far round from straight ahead, per character: the astronauts are bigger and wear helmets. */
    private static final float[] LOOK_Y = {1.24f, 1.12f, 1.42f, 1.42f, 1.46f, 1.42f}, DIST = {2.2f, 2.0f, 2.0f, 2.0f, 2.05f, 2.0f};
    private static final float ANGLE = Float.parseFloat(System.getProperty("climb.portraitAngle", "0.55"));

    public static void main(String[] args) {
        Lwjgl3ApplicationConfiguration c = new Lwjgl3ApplicationConfiguration();
        c.setTitle("Upwardly portraits");
        c.setWindowedMode(SIZE, SIZE);
        c.useVsync(false);
        c.disableAudio(true);
        final int[] saved = {0};
        new Lwjgl3Application(new ApplicationAdapter() {
            ModelBatch batch; Environment env; PerspectiveCamera cam; FrameBuffer fbo;

            @Override public void create() {
                try { run(); } catch (Throwable e) { System.out.println("PORTRAIT ERROR " + e); e.printStackTrace(); }
                Gdx.app.exit();
            }

            void run() {
                DefaultShader.Config cfg = new DefaultShader.Config();
                cfg.numBones = 32; cfg.numDirectionalLights = 4;
                batch = new ModelBatch(new DefaultShaderProvider(cfg));
                env = new Environment();                        // the hero's own four-light rig (WorldRenderer / RigShot)
                env.set(new ColorAttribute(ColorAttribute.AmbientLight, 0.72f, 0.72f, 0.76f, 1f));
                DirectionalLight key = new DirectionalLight(), fill = new DirectionalLight(), bounce = new DirectionalLight(), rim = new DirectionalLight();
                key.set(1f, 0.97f, 0.9f, -0.5f, -0.9f, -0.6f); fill.set(0.3f, 0.35f, 0.5f, 0.75f, -0.25f, -0.45f);
                bounce.set(0.3f, 0.25f, 0.18f, 0.1f, 1f, -0.3f); rim.set(0.55f, 0.75f, 1f, -0.35f, -0.2f, 1f);
                env.add(key); env.add(fill); env.add(bounce); env.add(rim);
                cam = new PerspectiveCamera(40f, RENDER, RENDER); cam.near = 0.2f; cam.far = 40f;
                fbo = new FrameBuffer(Pixmap.Format.RGBA8888, RENDER, RENDER, true);
                Tuning t = Tuning.parse(Gdx.files.internal("data/tuning.json").readString("UTF-8"));
                Course course = CourseGenerator.chunk(11L, 0, null, t);
                new FileHandle(OUT).mkdirs();
                for (int ch = 0; ch < Characters.COUNT; ch++) {
                    HeroRig rig = new HeroRig(ch);
                    Sim sim = Sim.startOn(course, t, 0); sim.setRange(0, course.size() - 1);
                    InputState in = new InputState();
                    float time = 0f;
                    for (int f = 0; f < 50; f++) { in.clear(); sim.step(in); rig.update(sim, Sim.DT, time, 0f, sim.y, 0f, 0f, false); time += Sim.DT; }   // standing still: the Idle clip settles
                    float ly = sim.y + LOOK_Y[Characters.clamp(ch)], d = DIST[Characters.clamp(ch)];
                    cam.position.set((float) Math.sin(ANGLE) * d, ly + 0.22f, (float) Math.cos(ANGLE) * d);
                    cam.up.set(0, 1, 0); cam.lookAt(0, ly, 0); cam.update();
                    fbo.begin();
                    Gdx.gl.glViewport(0, 0, RENDER, RENDER);
                    Gdx.gl.glClearColor(0f, 0f, 0f, 0f);           // transparent: the settings panel shows through around the character
                    Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
                    Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
                    batch.begin(cam);
                    rig.render(batch, env);
                    batch.end();
                    Pixmap pm = Pixmap.createFromFrameBuffer(0, 0, RENDER, RENDER);
                    fbo.end();
                    flipY(pm);
                    Pixmap out = cropToCharacter(pm, SIZE);            // the head fills the picture whatever the character's size
                    FileHandle fh = new FileHandle(OUT + "/" + ch + ".png");
                    PixmapIO.writePNG(fh, out);
                    pm.dispose(); out.dispose(); rig.dispose(); saved[0]++;
                    System.out.println("PORTRAIT " + ch + " " + Characters.name(ch) + " -> " + fh.file().getAbsolutePath());
                }
            }

            @Override public void dispose() { if (batch != null) batch.dispose(); if (fbo != null) fbo.dispose(); }
        }, c);
        System.out.println("PORTRAIT_DONE saved=" + saved[0]);
        System.exit(saved[0] == Characters.COUNT ? 0 : 1);
    }

    /** The square around everything that is not transparent (plus a small margin), scaled to {@code size}: the character fills its portrait. */
    private static Pixmap cropToCharacter(Pixmap src, int size) {
        int w = src.getWidth(), h = src.getHeight(), x0 = w, y0 = h, x1 = -1, y1 = -1;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) if ((src.getPixel(x, y) & 0xff) > 8) { if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y; }
        Pixmap out = new Pixmap(size, size, Pixmap.Format.RGBA8888);
        if (x1 < 0) return out;                                        // nothing rendered: an empty picture rather than a crash
        int side = (int) (Math.max(x1 - x0, y1 - y0) * 1.10f) + 2, cx = (x0 + x1) / 2, cy = (y0 + y1) / 2;
        int sx = Math.max(0, Math.min(w - side, cx - side / 2)), sy = Math.max(0, Math.min(h - side, cy - side / 2));
        side = Math.min(side, Math.min(w, h));
        out.setFilter(Pixmap.Filter.BiLinear);
        out.setBlending(Pixmap.Blending.None);
        out.drawPixmap(src, sx, sy, side, side, 0, 0, size, size);
        return out;
    }

    /** glReadPixels is bottom-up; flip the pixmap in place so the PNG is right-way-up. */
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

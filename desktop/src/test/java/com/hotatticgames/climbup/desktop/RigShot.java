package com.hotatticgames.climbup.desktop;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight;
import com.badlogic.gdx.graphics.g3d.utils.DefaultShaderProvider;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.graphics.VertexAttributes.Usage;
import com.badlogic.gdx.graphics.g3d.shaders.DefaultShader;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.PixmapIO;
import com.hotatticgames.climbup.render.HeroRig;
import com.hotatticgames.climbup.sim.Autopilot;
import com.hotatticgames.climbup.sim.Course;
import com.hotatticgames.climbup.sim.CourseGenerator;
import com.hotatticgames.climbup.sim.InputState;
import com.hotatticgames.climbup.sim.Sim;
import com.hotatticgames.climbup.sim.Tuning;
import java.nio.ByteBuffer;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Renders the REAL player rig (every selectable character) in each grip/stance it actually reaches during an autopilot
 * playthrough (idle, rope hang, rope climb, cable hang, cable shimmy, ledge hang, pull-up) to PNG files, with a thin
 * reference bar drawn where the rope/cable line is, so a human can SEE whether the hands land on and grip the line or
 * splay off it. This is the visual counterpart to GripProbe (which only measures bone scale): it exists so grip-pose
 * changes can be inspected on a headless machine before shipping a build. Not a pass/fail gate - it just writes images.
 *
 * Run: xvfb-run -a -s "-screen 0 320x240x24" ./gradlew :desktop:rigShot   (PNGs land in desktop/build/rigshot/)
 */
public final class RigShot {
    private static final int CHARACTERS = Integer.getInteger("climb.probeChars", 6);
    private static final int SLICES = 14;
    private static final int FRAMES_PER_SLICE = 1600;
    private static final long SEED = Long.getLong("climb.seed", 11L);
    private static final int W = 720, H = 720;
    private static final int SETTLE = 9;                 // capture this many frames into a state, so the pose has eased in (lineMove, climbPhase mid-cycle)
    private static final String OUT = System.getProperty("climb.shotDir", "build/rigshot");

    /** One desired shot: a label and the predicate that recognises its state. */
    private interface Want { String label(); boolean match(Sim s, HeroRig r); }

    public static void main(String[] args) {
        Lwjgl3ApplicationConfiguration c = new Lwjgl3ApplicationConfiguration();
        c.setTitle("Upwardly rig shot");
        c.setWindowedMode(W, H);
        c.useVsync(false);
        c.disableAudio(true);
        final int[] saved = {0};
        new Lwjgl3Application(new ApplicationAdapter() {
            ModelBatch batch; Environment heroEnv; PerspectiveCamera cam; FrameBuffer fbo;
            Model barModel; ModelInstance bar;

            @Override public void create() {
                try { run(); } catch (Throwable e) { System.out.println("RIGSHOT ERROR " + e); e.printStackTrace(); }
                Gdx.app.exit();
            }

            void run() {
                DefaultShader.Config cfg = new DefaultShader.Config();
                cfg.numBones = 32; cfg.numDirectionalLights = 4;
                batch = new ModelBatch(new DefaultShaderProvider(cfg));
                heroEnv = new Environment();
                heroEnv.set(new ColorAttribute(ColorAttribute.AmbientLight, 0.72f, 0.72f, 0.76f, 1f));
                DirectionalLight hKey = new DirectionalLight(), hFill = new DirectionalLight(), hBounce = new DirectionalLight(), hRim = new DirectionalLight();
                hKey.set(1f, 0.97f, 0.9f, -0.5f, -0.9f, -0.6f); hFill.set(0.3f, 0.35f, 0.5f, 0.75f, -0.25f, -0.45f);
                hBounce.set(0.3f, 0.25f, 0.18f, 0.1f, 1f, -0.3f); hRim.set(0.55f, 0.75f, 1f, -0.35f, -0.2f, 1f);
                heroEnv.add(hKey); heroEnv.add(hFill); heroEnv.add(hBounce); heroEnv.add(hRim);
                cam = new PerspectiveCamera(40f, W, H); cam.near = 0.3f; cam.far = 80f;
                fbo = new FrameBuffer(Pixmap.Format.RGBA8888, W, H, true);
                // a bright magenta reference bar: positioned as the rope (vertical) or cable (horizontal) line each shot
                ModelBuilder mb = new ModelBuilder();
                barModel = mb.createBox(1f, 1f, 1f, new Material(ColorAttribute.createDiffuse(1f, 0.1f, 0.7f, 1f), ColorAttribute.createEmissive(0.5f, 0.02f, 0.3f, 1f)),
                        Usage.Position | Usage.Normal);
                bar = new ModelInstance(barModel);

                Tuning t = Tuning.parse(Gdx.files.internal("data/tuning.json").readString("UTF-8"));
                new FileHandle(OUT).mkdirs();
                // Predicates are anim-based so the same harness works under the real autopilot AND under the forced
                // animation cycle (-Dclimb.animCycle=true), which reaches cable/ledge/hang deterministically.
                Want[] wants = {
                    want("idle", (s, r) -> r.anim == HeroRig.Anim.IDLE),
                    want("rope_climb", (s, r) -> r.anim == HeroRig.Anim.CLIMB),
                    want("cable_shimmy", (s, r) -> r.anim == HeroRig.Anim.SHIMMY),
                    want("ledge_hang", (s, r) -> r.anim == HeroRig.Anim.HANG),
                    want("pullup", (s, r) -> r.anim == HeroRig.Anim.PULLUP),
                };

                for (int ch = 0; ch < CHARACTERS; ch++) {
                    HeroRig rig = new HeroRig(ch);
                    Set<String> done = new LinkedHashSet<>();
                    String holding = null; int held = 0;
                    float time = 0f, dt = Sim.DT;
                    InputState in = new InputState();
                    outer:
                    for (int k = 0; k < SLICES; k++) {
                        Course course = CourseGenerator.chunk(SEED, k, null, t);
                        Sim sim = Sim.startOn(course, t, 0);
                        sim.setRange(0, course.size() - 1);
                        Autopilot.Driver driver = new Autopilot.Driver(sim);
                        for (int f = 0; f < FRAMES_PER_SLICE; f++) {
                            in.clear(); driver.drive(sim, in); sim.step(in);
                            int ev = sim.consumeEvents(); rig.events(ev, Math.abs(sim.vy));
                            rig.update(sim, dt, time, 0f, sim.y, 0f, 0f, false);
                            time += dt;
                            // find which wanted state we're in
                            String now = null;
                            for (Want w : wants) if (!done.contains(w.label()) && w.match(sim, rig)) { now = w.label(); break; }
                            if (now != null && now.equals(holding)) {
                                if (++held == SETTLE) { capture(ch, now, sim, rig, t); done.add(now); saved[0]++; holding = null; held = 0;
                                    if (done.size() == wants.length) break outer; }
                            } else { holding = now; held = now == null ? 0 : 1; }
                            if (sim.won) break;
                            if (driver.failed) { sim.respawn(); sim.consumeEvents(); driver = new Autopilot.Driver(sim); holding = null; held = 0; }
                        }
                    }
                    System.out.println("RIGSHOT char=" + ch + " captured=" + done);
                    rig.dispose();
                }
            }

            void capture(int ch, String label, Sim sim, HeroRig rig, Tuning t) {
                float y = sim.y;
                float handH = t.handHeight;                        // grip height above the sim origin
                boolean line = label.startsWith("rope") || label.startsWith("cable");
                boolean cable = label.startsWith("cable");
                // frame the character
                float cy = y + 1.2f;
                // ledge/cable turn the hero; pull the camera to a slight 3/4 so arms read
                float camAng = label.startsWith("ledge") ? 0.9f : 0.25f, dist = 9.0f;
                cam.position.set((float) Math.sin(camAng) * dist, cy + 0.6f, (float) Math.cos(camAng) * dist);
                cam.up.set(0, 1, 0); cam.lookAt(0, cy, 0); cam.update();

                fbo.begin();
                Gdx.gl.glViewport(0, 0, W, H);
                Gdx.gl.glClearColor(0.62f, 0.78f, 0.95f, 1f);
                Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
                Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
                batch.begin(cam);
                if (line) {
                    if (cable) bar.transform.idt().translate(0, y + handH, 0).scale(7f, 0.08f, 0.08f);       // horizontal line along s (x)
                    else bar.transform.idt().translate(0, y + handH - 0.5f, 0).scale(0.08f, 5f, 0.08f);      // vertical rope along y
                    batch.render(bar, heroEnv);
                }
                rig.render(batch, heroEnv);
                batch.end();
                Pixmap pm = Pixmap.createFromFrameBuffer(0, 0, W, H);
                fbo.end();
                flipY(pm);
                FileHandle fh = new FileHandle(OUT + "/" + ch + "_" + label + ".png");
                PixmapIO.writePNG(fh, pm);
                pm.dispose();
                System.out.println("  shot " + fh.path());
            }

            @Override public void dispose() { if (batch != null) batch.dispose(); if (fbo != null) fbo.dispose(); if (barModel != null) barModel.dispose(); }
        }, c);
        System.out.println("RIGSHOT_DONE saved=" + saved[0]);
        System.exit(0);
    }

    private static Want want(String label, java.util.function.BiPredicate<Sim, HeroRig> p) {
        return new Want() { public String label() { return label; } public boolean match(Sim s, HeroRig r) { return p.test(s, r); } };
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

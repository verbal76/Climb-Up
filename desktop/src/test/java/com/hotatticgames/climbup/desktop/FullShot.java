package com.hotatticgames.climbup.desktop;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.files.FileHandle;
import com.hotatticgames.climbup.render.Models;
import com.hotatticgames.climbup.render.WorldRenderer;
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
 * TRUE in-game verification of the grip (and cannon) poses: drives a REAL Sim up REAL generated ropes/cables with the
 * autopilot and renders each moment through the REAL {@link WorldRenderer} (its own camera, lights, rope geometry and the
 * live HeroRig) to a PNG. Unlike RigShot (which poses the bare rig against a synthetic reference bar), this shows exactly
 * what the player sees - whether the hands actually land on the rope the renderer draws. Run:
 *   xvfb-run -a -s "-screen 0 1280x720x24" ./gradlew :desktop:fullShot   (PNGs in assets/build/fullshot/)
 */
public final class FullShot {
    private static final int CHARACTERS = Integer.getInteger("climb.probeChars", 6);
    private static final int SLICES = 16, FRAMES = 1600, W = 1280, H = 720, SETTLE = 10;
    private static final long SEED = Long.getLong("climb.seed", 11L);
    private static final String OUT = System.getProperty("climb.shotDir", "build/fullshot");

    public static void main(String[] args) {
        Lwjgl3ApplicationConfiguration c = new Lwjgl3ApplicationConfiguration();
        c.setTitle("fullshot"); c.setWindowedMode(W, H); c.useVsync(false); c.disableAudio(true);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override public void create() {
                try { run(); } catch (Throwable e) { System.out.println("FULLSHOT ERROR " + e); e.printStackTrace(); }
                Gdx.app.exit();
            }
            void run() {
                Tuning t = Tuning.parse(Gdx.files.internal("data/tuning.json").readString("UTF-8"));
                Models models = new Models();
                FrameBuffer fbo = new FrameBuffer(Pixmap.Format.RGBA8888, W, H, true);
                new FileHandle(OUT).mkdirs();
                // what we want to catch, by the sim mode + motion that defines each grip
                String[] wants = {"idle", "rope_climb", "cable_shimmy", "ledge_hang"};
                for (int ch = 0; ch < CHARACTERS; ch++) {
                    Set<String> done = new LinkedHashSet<>();
                    String holding = null; int held = 0; float time = 0f, dt = Sim.DT;
                    InputState in = new InputState();
                    outer:
                    for (int k = 0; k < SLICES; k++) {
                        Course course = CourseGenerator.chunk(SEED, k, null, t);
                        WorldRenderer wr = new WorldRenderer(t, course, models, 2, ch);
                        Sim sim = Sim.startOn(course, t, 0); sim.setRange(0, course.size() - 1);
                        Autopilot.Driver driver = new Autopilot.Driver(sim);
                        for (int f = 0; f < FRAMES; f++) {
                            in.clear(); driver.drive(sim, in); sim.step(in); sim.consumeEvents(); time += dt;
                            String now = modeLabel(sim);
                            if (now != null && !done.contains(now) && now.equals(holding)) {
                                if (++held == SETTLE) { capture(wr, sim, dt, time, fbo, ch, now); done.add(now); holding = null; held = 0;
                                    if (done.size() == wants.length) { wr.dispose(); break outer; } }
                            } else { holding = now; held = now == null ? 0 : 1; }
                            if (sim.won) break;
                            if (driver.failed) { sim.respawn(); sim.consumeEvents(); driver = new Autopilot.Driver(sim); holding = null; held = 0; }
                        }
                        wr.dispose();
                    }
                    System.out.println("FULLSHOT char=" + ch + " captured=" + done);
                }
                fbo.dispose(); models.dispose();
            }

            String modeLabel(Sim s) {
                switch (s.mode) {
                    case GROUND: return Math.abs(s.vx) < 0.4f ? "idle" : null;
                    case ROPE:   return Math.abs(s.y - s.py0) > 0.004f ? "rope_climb" : null;
                    case CABLE:  return Math.abs(s.course.dsWrap(s.s, s.ps0)) > 0.004f ? "cable_shimmy" : null;
                    case LEDGE:  return "ledge_hang";
                    default: return null;
                }
            }

            void capture(WorldRenderer wr, Sim sim, float dt, float time, FrameBuffer fbo, int ch, String label) {
                fbo.begin();
                Gdx.gl.glViewport(0, 0, W, H);
                Gdx.gl.glClearColor(0.6f, 0.78f, 0.95f, 1f);
                Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
                wr.render(sim, 1f, dt, time, true);
                Pixmap pm = Pixmap.createFromFrameBuffer(0, 0, W, H);
                fbo.end();
                flipY(pm);
                FileHandle fh = new FileHandle(OUT + "/" + ch + "_" + label + ".png");
                PixmapIO.writePNG(fh, pm); pm.dispose();
                System.out.println("  shot " + fh.path());
            }

            void flipY(Pixmap pm) {
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
        }, c);
        System.out.println("FULLSHOT_DONE");
        System.exit(0);
    }
}

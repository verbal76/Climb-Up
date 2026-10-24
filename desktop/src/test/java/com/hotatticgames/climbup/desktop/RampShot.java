package com.hotatticgames.climbup.desktop;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.hotatticgames.climbup.render.Models;
import com.hotatticgames.climbup.render.WorldRenderer;
import com.hotatticgames.climbup.sim.*;
import java.nio.ByteBuffer;

/** Build 79: twelve screenshots through one turn of the first spinning ramp of a seed, through the real WorldRenderer (the deck must show from both sides). */
public final class RampShot {
    private static final int W = 1280, H = 720;
    private static final long SEED = Long.getLong("climb.seed", 7L);
    private static final String OUT = System.getProperty("climb.shotDir", "build/rampshot");

    public static void main(String[] args) {
        Lwjgl3ApplicationConfiguration c = new Lwjgl3ApplicationConfiguration();
        c.setTitle("rampshot"); c.setWindowedMode(W, H); c.useVsync(false); c.disableAudio(true);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override public void create() {
                try { run(); } catch (Throwable e) { System.out.println("RAMPSHOT ERROR " + e); e.printStackTrace(); }
                Gdx.app.exit();
            }
            void run() {
                Tuning t = Tuning.parse(Gdx.files.internal("data/tuning.json").readString("UTF-8"));
                Models models = new Models();
                FrameBuffer fbo = new FrameBuffer(Pixmap.Format.RGBA8888, W, H, true);
                new FileHandle(OUT).mkdirs();
                Course prev = null;
                for (int k = 0; k < 80; k++) {
                    Course course = CourseGenerator.chunk(SEED, k, prev, t); prev = course;
                    int r = -1;
                    for (int i = 1; i < course.size(); i++) if (course.get(i).spins()) { r = i; break; }
                    if (r < 0) continue;
                    float base = (float) Math.floor(course.get(0).y);
                    Course shifted = shiftDown(course, base);
                    WorldRenderer wr = new WorldRenderer(t, shifted, models, 2, 0); wr.setOrigin(base, 0);
                    course = shifted; Element e = course.get(r);
                    Sim sim = Sim.startOn(course, t, r - 1); sim.setRange(0, course.size() - 1);
                    InputState in = new InputState(); float time = 0f;
                    wr.snapCamera(sim);
                    int shots = 0; float next = 0f;
                    for (int f = 0; f < 60 * 12 && shots < 12; f++) {
                        in.clear(); sim.step(in); sim.consumeEvents(); time += Sim.DT; render(wr, sim, time);
                        if (f > 90 && time >= next) { capture(wr, sim, time, fbo, "ramp_" + shots + "_" + Math.round(Math.toDegrees(e.spinAngle(sim.time)) % 360)); shots++; next = time + e.period / 12f; }
                    }
                    System.out.println("RAMPSHOT slice " + k + " elem " + r + " period " + e.period);
                    wr.dispose(); break;
                }
                fbo.dispose(); models.dispose();
            }
            Course shiftDown(Course c, float base) {
                byte[] blob = SliceCodec.encode(new CourseGenerator.Framed(c, 0.0, 0.0));
                Course d = SliceCodec.decode(blob).c;
                for (int i = 0; i < d.size(); i++) d.get(i).y -= base;
                for (Element e : d.hazards) e.y -= base;
                return d;
            }
            void render(WorldRenderer wr, Sim sim, float time) {
                fbo().begin(); Gdx.gl.glViewport(0, 0, W, H); Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT); wr.render(sim, 1f, Sim.DT, time, true); fbo().end();
            }
            FrameBuffer scratch;
            FrameBuffer fbo() { if (scratch == null) scratch = new FrameBuffer(Pixmap.Format.RGBA8888, W, H, true); return scratch; }
            void capture(WorldRenderer wr, Sim sim, float time, FrameBuffer fbo, String label) {
                fbo.begin();
                Gdx.gl.glViewport(0, 0, W, H);
                Gdx.gl.glClearColor(0.6f, 0.78f, 0.95f, 1f);
                Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
                wr.render(sim, 1f, Sim.DT, time, true);
                Pixmap pm = Pixmap.createFromFrameBuffer(0, 0, W, H);
                fbo.end();
                int w = pm.getWidth(), hh = pm.getHeight(), stride = w * 4; ByteBuffer buf = pm.getPixels(); byte[] a = new byte[stride], b = new byte[stride];
                for (int y = 0; y < hh / 2; y++) { int top = y * stride, bot = (hh - 1 - y) * stride; buf.position(top); buf.get(a); buf.position(bot); buf.get(b); buf.position(top); buf.put(b); buf.position(bot); buf.put(a); }
                buf.position(0);
                FileHandle fh = new FileHandle(OUT + "/" + label + ".png");
                PixmapIO.writePNG(fh, pm); pm.dispose();
            }
        }, c);
        System.out.println("RAMPSHOT_DONE");
        System.exit(0);
    }
}

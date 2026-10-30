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
import com.hotatticgames.climbup.render.Atmos;
import com.hotatticgames.climbup.render.Models;
import com.hotatticgames.climbup.render.WorldRenderer;
import com.hotatticgames.climbup.sim.*;
import java.nio.ByteBuffer;

/**
 * Atmosphere prototype A/B screenshots: one frame in the climb between every pair of castles (ten heights, all five worlds), through the REAL
 * WorldRenderer with the autopilot playing the real slice at that height. Run it once with the master switch off and once on; the file names
 * carry atmos-on / atmos-off. Slices are generated once and cached as SliceCodec blobs, so later runs do not regenerate them.
 * Locally: xvfb-run -a -s "-screen 0 1280x720x24" ./gradlew :desktop:atmosShot [-Dclimb.atmos=true] [-Dclimb.w=1600 -Dclimb.h=720]
 */
public final class AtmosShot {
    private static final int W = Integer.getInteger("climb.w", 1280), H = Integer.getInteger("climb.h", 720);
    private static final long SEED = Long.getLong("climb.seed", 7L);
    private static final int QUALITY = Integer.getInteger("climb.quality", 2);
    private static final boolean REDUCED = Boolean.getBoolean("climb.reducedMotion");
    private static final int RUN = Integer.getInteger("climb.runFrames", 75);
    private static final String OUT = System.getProperty("climb.shotDir", "build/atmosshot");
    private static final boolean CHAIN = Boolean.getBoolean("climb.chain");
    private static final String HEIGHTS = System.getProperty("climb.heights", "250,750,1250,1750,2250,2750,3250,3750,4250,4750");

    public static void main(String[] args) {
        Lwjgl3ApplicationConfiguration c = new Lwjgl3ApplicationConfiguration();
        c.setTitle("atmosshot"); c.setWindowedMode(W, H); c.setResizable(false); c.useVsync(false); c.disableAudio(true);
        new Lwjgl3Application(new ApplicationAdapter() {
            FrameBuffer fbo;
            @Override public void create() {
                try { run(); } catch (Throwable e) { System.out.println("ATMOSSHOT ERROR " + e); e.printStackTrace(); }
                Gdx.app.exit();
            }
            void run() {
                Tuning t = Tuning.parse(Gdx.files.internal("data/tuning.json").readString("UTF-8"));
                Models models = new Models();
                fbo = new FrameBuffer(Pixmap.Format.RGBA8888, W, H, true);
                new FileHandle(OUT + "/cache").mkdirs();
                String tag = (Atmos.ENABLED ? "on" : "off") + "_" + W + "x" + H + (QUALITY != 2 ? "_q" + QUALITY : "") + (REDUCED ? "_rm" : "");
                System.out.println("ATMOSSHOT atmos=" + Atmos.ENABLED + " size=" + W + "x" + H + " backbuffer=" + Gdx.graphics.getBackBufferWidth() + "x" + Gdx.graphics.getBackBufferHeight());
                for (String hs : HEIGHTS.split(",")) {
                    int height = Integer.parseInt(hs.trim());
                    int k = (int) Math.floor(height / t.chunkHeight);
                    Course course = cached(t, k);
                    // a slice generated on its own starts at y = 0: draw it as if it stood at the asked height (the renderer's floating origin), with the
                    // elements re-labelled to that height's world so they wear its skins (snow, hanging rocks, space decks). The simulation never reads
                    // an element's zone, so the climb itself is the real slice's.
                    float base = (float) Math.floor(course.get(0).y);
                    for (int i = 0; i < course.size(); i++) course.get(i).y -= base;
                    for (Element e : course.hazards) e.y -= base;
                    int world = (int) ((height / t.zoneHeight) % t.zoneCount);
                    for (int i = 0; i < course.size(); i++) course.get(i).zone = world;
                    for (Element e : course.hazards) e.zone = world;
                    base = height - 8f;
                    WorldRenderer wr = new WorldRenderer(t, course, models, QUALITY, 0); wr.setOrigin(base, 0);
                    wr.reducedMotion = REDUCED;
                    wr.resize(W, H);
                    int from = Math.min(course.size() - 1, Integer.getInteger("climb.from", 3)); while (from > 0 && !course.get(from).isPlatform()) from--;
                    Sim sim = Sim.startOn(course, t, from); sim.setRange(0, course.size() - 1);
                    Autopilot.Driver driver = new Autopilot.Driver(sim);
                    InputState in = new InputState(); float time = 0f;
                    wr.snapCamera(sim); for (int q = 0; q < 45; q++) { render(wr, sim, time); time += Sim.DT; }
                    for (int f = 0; f < RUN; f++) {
                        in.clear(); driver.drive(sim, in); sim.step(in); int ev = sim.consumeEvents(); time += Sim.DT;
                        wr.heroEvents(ev, sim.landSpeed); render(wr, sim, time);
                        if (driver.failed) { sim.respawn(); sim.consumeEvents(); driver = new Autopilot.Driver(sim); }
                    }
                    String label = "h" + height + "_" + tag;
                    capture(wr, sim, time, label);
                    System.out.printf("  %s slice=%d start=%d player y=%.1f (abs %.0f m)%n", label, k, from, sim.y, sim.y + base);
                    wr.dispose();
                }
                fbo.dispose(); models.dispose();
            }
            Course cached(Tuning t, int k) {
                if (CHAIN) {          // -Dclimb.chain=true: slice k as the game builds it, on top of slices 0..k-1 (its real height and difficulty)
                    Course prev = null;
                    for (int q = 0; q <= k; q++) {
                        FileHandle fq = new FileHandle(OUT + "/cache/seed" + SEED + "_chain_k" + q + ".slice");
                        if (!fq.exists()) fq.writeBytes(SliceCodec.encode(new CourseGenerator.Framed(CourseGenerator.chunk(SEED, q, prev, t), 0.0, 0.0)), false);
                        prev = SliceCodec.decode(fq.readBytes()).c;
                    }
                    return prev;
                }
                FileHandle fh = new FileHandle(OUT + "/cache/seed" + SEED + "_k" + k + ".slice");
                if (!fh.exists()) fh.writeBytes(SliceCodec.encode(new CourseGenerator.Framed(CourseGenerator.chunk(SEED, k, null, t), 0.0, 0.0)), false);
                return SliceCodec.decode(fh.readBytes()).c;
            }
            void render(WorldRenderer wr, Sim sim, float time) {
                fbo.begin(); Gdx.gl.glViewport(0, 0, W, H); Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT); wr.render(sim, 1f, Sim.DT, time, true); fbo.end();
            }
            void capture(WorldRenderer wr, Sim sim, float time, String label) {
                fbo.begin();
                Gdx.gl.glViewport(0, 0, W, H);
                Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
                wr.render(sim, 1f, Sim.DT, time, true);
                Pixmap pm = Pixmap.createFromFrameBuffer(0, 0, W, H);
                fbo.end();
                int w = pm.getWidth(), hh = pm.getHeight(), stride = w * 4; ByteBuffer buf = pm.getPixels(); byte[] a = new byte[stride], b = new byte[stride];
                for (int y = 0; y < hh / 2; y++) { int top = y * stride, bot = (hh - 1 - y) * stride; buf.position(top); buf.get(a); buf.position(bot); buf.get(b); buf.position(top); buf.put(b); buf.position(bot); buf.put(a); }
                buf.position(0);
                for (int i = 3; i < buf.limit(); i += 4) buf.put(i, (byte) 255);            // opaque PNG (the FBO's alpha is whatever the last blend left)
                PixmapIO.writePNG(new FileHandle(OUT + "/" + label + ".png"), pm); pm.dispose();
            }
        }, c);
        System.out.println("ATMOSSHOT_DONE");
        System.exit(0);
    }
}

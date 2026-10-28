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
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Build 71: screenshots of the creatures through the REAL WorldRenderer while the autopilot plays their decks: the skull hovering, winding up and
 * dashing (mist), the green blob wobbling, its shadow, the landing, and the homing bee. Locally: xvfb-run -a -s "-screen 0 320x240x24" ./gradlew :desktop:creatureShot
 */
public final class CreatureShot {
    private static final int W = 1280, H = 720;
    private static final long SEED = Long.getLong("climb.seed", 7L);
    private static final boolean CABLE = Boolean.getBoolean("climb.shotCable");
    private static final String OUT = System.getProperty("climb.shotDir", "build/creatureshot");

    public static void main(String[] args) {
        Lwjgl3ApplicationConfiguration c = new Lwjgl3ApplicationConfiguration();
        c.setTitle("creatureshot"); c.setWindowedMode(W, H); c.useVsync(false); c.disableAudio(true);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override public void create() {
                try { run(); } catch (Throwable e) { System.out.println("CREATURESHOT ERROR " + e); e.printStackTrace(); }
                Gdx.app.exit();
            }
            void run() {
                Tuning t = Tuning.parse(Gdx.files.internal("data/tuning.json").readString("UTF-8"));
                Models models = new Models();
                FrameBuffer fbo = new FrameBuffer(Pixmap.Format.RGBA8888, W, H, true);
                new FileHandle(OUT).mkdirs();
                Set<String> done = new LinkedHashSet<>();
                if (CABLE) { cables(t, models, fbo, done); System.out.println("CREATURESHOT captured=" + done); fbo.dispose(); models.dispose(); return; }
                Course prev = null;
                for (int k = 0; k < 60 && done.size() < 10; k++) {
                    Course course = CourseGenerator.chunk(SEED, k, prev, t); prev = course;
                    int target = -1; Element.Type kind = null;
                    for (int i = 0; i < course.hazards.size(); i++) {
                        Element h = course.hazards.get(i);
                        if ((h.type == Element.Type.SKULL && !done.contains("skull_dash")) || (h.type == Element.Type.BLOB && !done.contains("blob_land"))
                                || (h.type == Element.Type.BEE && !done.contains("bee_dive")) || (h.type == Element.Type.CRAB && !done.contains("crab_near"))) { target = i; kind = h.type; break; }
                    }
                    if (target < 0) continue;
                    Element h = course.hazards.get(target);
                    float base = (float) Math.floor(course.get(0).y);          // like the game's floating origin: the slice near y = 0, its true height as the renderer's origin
                    Course shifted = shiftDown(course, base);
                    WorldRenderer wr = new WorldRenderer(t, shifted, models, 2, 0); wr.setOrigin(base, 0);
                    course = shifted; h = course.hazards.get(target);
                    int from = Math.max(0, h.anchor - 1);
                    Sim sim = Sim.startOn(course, t, from); sim.setRange(0, course.size() - 1);
                    Autopilot.Driver driver = new Autopilot.Driver(sim);
                    InputState in = new InputState(); float time = 0f;
                    wr.snapCamera(sim); for (int q = 0; q < 90; q++) render(wr, sim, time);       // let the camera settle on the hero
                    for (int f = 0; f < 1500; f++) {
                        in.clear(); driver.drive(sim, in); sim.step(in); int ev = sim.consumeEvents(); time += Sim.DT;
                        wr.heroEvents(ev, sim.landSpeed); render(wr, sim, time);        // every frame, as the game does: the camera and the rigs follow
                        String label = null;
                        if (kind == Element.Type.SKULL) {
                            int st = sim.mobState[target];
                            if (st == 1 && sim.mobSeen[target] > 0.2f) label = "skull_hover";
                            else if (st == 2 && sim.mobT[target] > 0.4f) label = "skull_windup";
                            else if (st == 3 && sim.mobT[target] > 0.12f) label = "skull_dash";
                            else if (st == 4) label = "skull_turn";
                        } else if (kind == Element.Type.BLOB) {
                            int st = sim.mobState[target];
                            if (st == 2 && sim.mobT[target] > 0.3f) label = "blob_wobble";
                            else if (sim.blobShadow(target) >= 0f && sim.blobShadow(target) < 0.2f) label = "blob_shadow";
                            else if (st == 4 && sim.mobT[target] < 0.12f) label = "blob_land";
                        } else if (kind == Element.Type.BEE && h.beeDive(sim.time) > 0.6f) label = "bee_dive";
                        else if (kind == Element.Type.CRAB && sim.onElem == h.anchor) label = "crab_near";      // Build 72: the crab's and the hero's shadows side by side
                        if (label != null && !done.contains(label)) {
                            for (int q = 0; q < 3; q++) render(wr, sim, time);          // let particles and rigs catch up
                            capture(wr, sim, time, fbo, label); done.add(label);
                            System.out.printf("  %s at slice %d hazard %d s=%.2f y=%.2f player s=%.2f y=%.2f mode=%s%n", label, k, target, sim.mobS[target], sim.mobY[target], sim.s, sim.y, sim.mode);
                        }
                        if (driver.failed) { sim.respawn(); sim.consumeEvents(); driver = new Autopilot.Driver(sim); }
                        if (sim.onElem > h.anchor + 2 && kind != Element.Type.BEE) break;
                    }
                    wr.dispose();
                }
                System.out.println("CREATURESHOT captured=" + done);
                fbo.dispose(); models.dispose();
            }
            /** Build 71: the bunny hanging and shimmying along overhead cables in BOTH directions (eyes stay on the face; the head tips back toward the camera). */
            void cables(Tuning t, Models models, FrameBuffer fbo, Set<String> done) {
                Course prev = null; int crossings = 0;
                for (int k = 0; k < 80 && crossings < Integer.getInteger("climb.shotCrossings", 3); k++) {
                    Course course = CourseGenerator.chunk(SEED, k, prev, t); prev = course;
                    int cab = -1;
                    for (int i = 1; i < course.size(); i++) if (course.get(i).type == Element.Type.CABLE) { cab = i; break; }
                    if (cab < 0) continue;
                    float base = (float) Math.floor(course.get(0).y);
                    Course shifted = shiftDown(course, base);
                    WorldRenderer wr = new WorldRenderer(t, shifted, models, 2, Integer.getInteger("climb.shotChar", 0)); wr.setOrigin(base, 0);
                    course = shifted;
                    Sim sim = Sim.startOn(course, t, cab - 1); sim.setRange(0, course.size() - 1);
                    Autopilot.Driver driver = new Autopilot.Driver(sim);
                    InputState in = new InputState(); float time = 0f; int onCable = 0, off = -1; String dir = null;
                    wr.snapCamera(sim); for (int q = 0; q < 90; q++) render(wr, sim, time);
                    for (int f = 0; f < 1500; f++) {
                        in.clear(); driver.drive(sim, in);
                        boolean back = sim.mode == Sim.Mode.CABLE && onCable >= 50 && onCable < 130;     // shimmy back LEFT for a while (cables are generated left to right), then the autopilot carries on
                        if (back) { in.moveX = -1f; in.jumpHeld = false; in.jumpPressed = false; }
                        sim.step(in); int ev = sim.consumeEvents(); time += Sim.DT;
                        wr.heroEvents(ev, sim.landSpeed); render(wr, sim, time);
                        if (back && onCable == 100) shot(wr, sim, time, fbo, "cable" + crossings + "_L_mid", done);
                        if (back && onCable == 125) shot(wr, sim, time, fbo, "cable" + crossings + "_L_late", done);
                        if (sim.mode == Sim.Mode.CABLE) {
                            onCable++;
                            float d = course.dsWrap(sim.s, sim.ps0);
                            if (dir == null && Math.abs(d) > 0.004f) dir = d > 0 ? "R" : "L";
                            String tag = "cable" + crossings + "_" + (dir == null ? "hang" : dir);
                            if (onCable == 4) shot(wr, sim, time, fbo, tag + "_blendin", done);
                            if (onCable == 40) shot(wr, sim, time, fbo, tag + "_mid", done);
                            if (onCable == 48) shot(wr, sim, time, fbo, tag + "_late", done);
                        } else if (onCable > 0 && off < 0) off = f;
                        if (off >= 0 && f == off + 4) { shot(wr, sim, time, fbo, "cable" + crossings + "_" + dir + "_blendout", done); break; }
                        if (driver.failed) break;
                    }
                    if (onCable > 0) crossings++;
                    wr.dispose();
                }
            }
            void shot(WorldRenderer wr, Sim sim, float time, FrameBuffer fbo, String label, Set<String> done) {
                capture(wr, sim, time, fbo, label); done.add(label);
                System.out.printf("  %s look=%.2f yaw=%.1f facing=%d%n", label, wr.heroRig().cableLook(), wr.heroRig().yaw, sim.facing);
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
        System.out.println("CREATURESHOT_DONE");
        System.exit(0);
    }
}

package com.hotatticgames.climbup.desktop;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.model.Node;
import com.badlogic.gdx.graphics.g3d.model.NodePart;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.hotatticgames.climbup.render.Characters;
import com.hotatticgames.climbup.sim.CheckpointGem;
import com.hotatticgames.climbup.render.Models;
import com.hotatticgames.climbup.render.WorldRenderer;
import com.hotatticgames.climbup.sim.*;
import java.nio.ByteBuffer;

/**
 * Build 71 (owner, "final checkpoint gem height rule"): measures, in the REAL renderer, every character's standing head height (the head bone, at the
 * base of the face) and the checkpoint gem's actual drawn bottom and top (its mesh vertices through the transform the frame used) over a whole bob
 * cycle, and shoots the hero walking under the gem from both sides at the gem's lowest, middle and highest bob. Prints GEMSHOT lines; PNGs in
 * build/gemshot. Locally: xvfb-run -a -s "-screen 0 1280x720x24" ./gradlew :desktop:gemShot
 */
public final class GemShot {
    private static final int W = 1280, H = 720;
    private static final String OUT = System.getProperty("climb.shotDir", "build/gemshot");

    public static void main(String[] args) {
        Lwjgl3ApplicationConfiguration c = new Lwjgl3ApplicationConfiguration();
        c.setTitle("gemshot"); c.setWindowedMode(W, H); c.useVsync(false); c.disableAudio(true);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override public void create() {
                try { run(); } catch (Throwable e) { System.out.println("GEMSHOT ERROR " + e); e.printStackTrace(); }
                Gdx.app.exit();
            }

            /** Three flush platforms; the middle one is the halfway gem platform (a run across it needs no jump). */
            Course course(Tuning t) {
                Course c = new Course(1, t.circumference()); c.gemCheckpoints = true;
                Element a = new Element(Element.Type.STATIC, 10f, 0f, 8f); a.checkpoint = true; c.add(a);
                Element g = new Element(Element.Type.STATIC, 18f, 0f, 8f); g.checkpoint = true; g.skin = 3; c.add(g);
                Element b = new Element(Element.Type.STATIC, 26f, 0f, 8f); c.add(b);
                return c;
            }

            void run() {
                Tuning t = Tuning.parse(Gdx.files.internal("data/tuning.json").readString("UTF-8"));
                Models models = new Models();
                FrameBuffer fbo = new FrameBuffer(Pixmap.Format.RGBA8888, W, H, true);
                new FileHandle(OUT).mkdirs();
                float minHead = 1e9f;
                for (int ch = 0; ch < Characters.COUNT; ch++) {
                    Course course = course(t);
                    WorldRenderer wr = new WorldRenderer(t, course, models, 2, ch);
                    Sim sim = Sim.startOn(course, t, 0); sim.setRange(0, course.size() - 1); sim.s = 12f;      // standing on the platform before the gem's (it spans 14-22), the gem still waiting
                    float time = 0f; InputState in = new InputState();
                    wr.snapCamera(sim);
                    for (int q = 0; q < 120; q++) { in.clear(); sim.step(in); sim.consumeEvents(); time += Sim.DT; render(wr, sim, time); }     // settle into the idle pose
                    Vector3 hd = new Vector3(); wr.heroRig().headWorld(hd);
                    float head = hd.y - sim.y;
                    minHead = Math.min(minHead, head);
                    System.out.printf("GEMSHOT char %d %s headAboveFeet=%.3f%n", ch, Characters.name(ch), head);
                    if (ch == 0) gemCycle(wr, sim, time, course);
                    wr.dispose();
                }
                System.out.printf("GEMSHOT minHead=%.3f%n", minHead);
                shots(t, models, fbo);
                fbo.dispose(); models.dispose();
            }

            /** The gem's real bottom and top over two bob cycles (it waits unreached: the checkpoint is behind the hero). */
            void gemCycle(WorldRenderer wr, Sim sim, float time0, Course course) {
                float deck = course.get(1).y, lo = 1e9f, hi = -1e9f, topMax = -1e9f, botMin = 1e9f;
                for (int f = 0; f < 360; f++) {
                    float time = time0 + f * (1f / 60f);
                    render(wr, sim, time);
                    float[] b = extentY(wr.gemDiag());
                    lo = Math.min(lo, b[0] - deck); hi = Math.max(hi, b[0] - deck);
                    botMin = Math.min(botMin, b[0] - deck); topMax = Math.max(topMax, b[1] - deck);
                }
                System.out.printf("GEMSHOT gem bottom above deck: lowest %.3f, highest %.3f; top highest %.3f%n", lo, hi, topMax);
            }

            /** Exact min/max world y of the gem's mesh vertices under the instance transform the last frame used. */
            float[] extentY(ModelInstance inst) {
                float mn = 1e9f, mx = -1e9f; Vector3 v = new Vector3(); Matrix4 m = new Matrix4();
                for (Node n : inst.nodes) for (NodePart np : n.parts) {
                    Mesh mesh = np.meshPart.mesh; VertexAttribute pos = mesh.getVertexAttributes().findByUsage(VertexAttributes.Usage.Position);
                    int stride = mesh.getVertexSize() / 4, po = pos.offset / 4; float[] vs = new float[mesh.getNumVertices() * stride]; mesh.getVertices(vs);
                    m.set(inst.transform).mul(n.globalTransform);
                    for (int i = 0; i < mesh.getNumVertices(); i++) { v.set(vs[i * stride + po], vs[i * stride + po + 1], vs[i * stride + po + 2]).mul(m); mn = Math.min(mn, v.y); mx = Math.max(mx, v.y); }
                }
                return new float[]{mn, mx};
            }

            /**
             * Walking (0.45 stick) and running (full stick) at the gem from the left and from the right, timed so the first touch of the pickup volume comes at the
             * gem's lowest, middle and highest bob; shoots the frame just before the touch (the waiting gem beside the hero) and checks the checkpoint fires.
             */
            void shots(Tuning t, Models models, FrameBuffer fbo) {
                String[] want = {"low", "mid", "high"}; float[] target = {-1f, 0f, 1f};
                int ok = 0, n = 0;
                for (int ch : new int[]{0, 1, 2}) for (int dir : new int[]{1, -1}) for (float stick : new float[]{0.45f, 1f}) for (int k = 0; k < 3; k++) {
                    Course course = course(t);
                    // frames from setting off to the first touch, found on a copy
                    Sim probe = start(course, t, dir); int steps = 0; InputState in = new InputState();
                    while (steps < 2000) { in.clear(); in.moveX = dir * stick; probe.step(in); steps++; if ((probe.consumeEvents() & Sim.EV_CHECKPOINT) != 0) break; }
                    float ph = 1 * 1.7f;                                                           // gem index 1: drawGem bobs with sin(time * 2.1 + i * 1.7)
                    int wait = 0; float best = 9f;
                    for (int w = 0; w < 200; w++) { float b = (float) Math.sin((w + steps) * Sim.DT * 2.1f + ph); float e = Math.abs(b - target[k]); if (e < best) { best = e; wait = w; } }
                    WorldRenderer wr = new WorldRenderer(t, course, models, 2, ch);
                    Sim sim = start(course, t, dir); float time = 0f; boolean got = false; float bobAtTouch = 0f;
                    wr.snapCamera(sim);
                    Sim before = null; float tBefore = 0f;
                    for (int f = 0; f < wait + steps + 30; f++) {
                        in.clear(); if (f >= wait) in.moveX = dir * stick;
                        Sim prev = sim.copy(); float tPrev = time;
                        sim.step(in); int ev = sim.consumeEvents(); time += Sim.DT;
                        if (!got && (ev & Sim.EV_CHECKPOINT) != 0) { got = true; bobAtTouch = (float) Math.sin(time * 2.1f + ph); before = prev; tBefore = tPrev; }
                        if (!got) render(wr, sim, time);
                    }
                    String tag = "c" + ch + (dir > 0 ? "_fromLeft_" : "_fromRight_") + (stick < 1f ? "walk_" : "run_") + want[k];
                    if (before != null) { for (int q = 0; q < 3; q++) render(wr, before, tBefore); capture(wr, before, tBefore, fbo, tag); }
                    System.out.printf("GEMSHOT %s: %s, bob at the touch %.2f (wanted %.0f), gem bottom then %.3f m above the deck%n", tag, got ? "collected" : "NOT collected",
                            bobAtTouch, target[k], CheckpointGem.bottomAt(bobAtTouch, 0f));
                    n++; if (got) ok++;
                    wr.dispose();
                }
                System.out.printf("GEMSHOT collected %d of %d approaches%n", ok, n);
                beside(t, models, fbo);
            }

            /** Visual check of the rule: each character standing beside the WAITING gem (the checkpoint held unreached for the shot) at its lowest, middle and highest bob, both sides. */
            void beside(Tuning t, Models models, FrameBuffer fbo) {
                String[] want = {"low", "mid", "high"}; float[] target = {-1f, 0f, 1f}; float ph = 1 * 1.7f;
                for (int ch = 0; ch < Characters.COUNT; ch++) for (int side : new int[]{-1, 1}) {
                    Course course = course(t);
                    WorldRenderer wr = new WorldRenderer(t, course, models, 2, ch);
                    Sim sim = Sim.startOn(course, t, 1); sim.setRange(0, course.size() - 1); sim.s = course.get(1).s + side * 0.75f; sim.facing = -side;
                    InputState in = new InputState(); float time = 0f;
                    wr.snapCamera(sim);
                    for (int k = 0; k < 3; k++) {
                        int f0 = 0; float best = 9f;      // the frame whose bob is closest to the wanted phase, after settling
                        for (int f = 90; f < 400; f++) { float e = Math.abs((float) Math.sin(f * Sim.DT * 2.1f + ph) - target[k]); if (e < best) { best = e; f0 = f; } }
                        sim = Sim.startOn(course, t, 1); sim.setRange(0, course.size() - 1); sim.s = course.get(1).s + side * 0.75f; sim.facing = -side;
                        time = 0f;
                        for (int f = 0; f <= f0; f++) { in.clear(); sim.step(in); sim.consumeEvents(); sim.checkpoint = 0; time += Sim.DT; render(wr, sim, time); }
                        capture(wr, sim, time, fbo, "beside_c" + ch + (side < 0 ? "_left_" : "_right_") + want[k]);
                        float[] b = extentY(wr.gemDiag());
                        Vector3 hd = new Vector3(); wr.heroRig().headWorld(hd);
                        System.out.printf("GEMSHOT beside c%d %s %s: gem bottom %.3f, head bone %.3f above the feet -> %s%n", ch, side < 0 ? "left" : "right", want[k],
                                b[0] - sim.y, hd.y - sim.y, b[0] - sim.y <= hd.y - sim.y + 1e-3f ? "bottom at or below the face" : "TOO HIGH");
                    }
                    wr.dispose();
                }
            }

            /** Standing on the platform before the gem's (from the left) or after it (from the right), the checkpoint behind. */
            Sim start(Course course, Tuning t, int dir) {
                Sim sim = Sim.startOn(course, t, dir > 0 ? 0 : 2); sim.setRange(0, course.size() - 1);
                sim.checkpoint = 0;
                return sim;
            }

            FrameBuffer scratch;
            FrameBuffer fbo() { if (scratch == null) scratch = new FrameBuffer(Pixmap.Format.RGBA8888, W, H, true); return scratch; }
            void render(WorldRenderer wr, Sim sim, float time) {
                fbo().begin(); Gdx.gl.glViewport(0, 0, W, H); Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT); wr.render(sim, 1f, Sim.DT, time, true); fbo().end();
            }
            void capture(WorldRenderer wr, Sim sim, float time, FrameBuffer fbo, String label) {
                fbo.begin(); Gdx.gl.glViewport(0, 0, W, H); Gdx.gl.glClearColor(0.6f, 0.78f, 0.95f, 1f); Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
                wr.render(sim, 1f, Sim.DT, time, true);
                Pixmap pm = Pixmap.createFromFrameBuffer(0, 0, W, H); fbo.end();
                int w = pm.getWidth(), hh = pm.getHeight(), stride = w * 4; ByteBuffer buf = pm.getPixels(); byte[] a = new byte[stride], b = new byte[stride];
                for (int y = 0; y < hh / 2; y++) { int top = y * stride, bot = (hh - 1 - y) * stride; buf.position(top); buf.get(a); buf.position(bot); buf.get(b); buf.position(top); buf.put(b); buf.position(bot); buf.put(a); }
                buf.position(0);
                PixmapIO.writePNG(new FileHandle(OUT + "/" + label + ".png"), pm); pm.dispose();
                System.out.println("  shot " + label);
            }
        }, c);
        System.out.println("GEMSHOT_DONE");
        System.exit(0);
    }
}

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
import com.hotatticgames.climbup.sim.Course;
import com.hotatticgames.climbup.sim.Element;
import com.hotatticgames.climbup.sim.InputState;
import com.hotatticgames.climbup.sim.Sim;
import com.hotatticgames.climbup.sim.Tuning;

/**
 * Visual check of the horizontal traverse bar's colour (owner 2026-10-27): a platform, a traverse bar and a rope side by side, the climber hanging from
 * the bar, rendered at the heights of each world's sky (meadow morning, frost, dusk, night, space). One PNG per world in {@code climb.shotDir}.
 * Run from assets/ under xvfb with the desktop test classpath (as GameplayShot).
 */
public final class CableShot {
    public static void main(String[] args) {
        final int W = Integer.getInteger("climb.w", 2400), H = Integer.getInteger("climb.h", 1080);
        final String out = System.getProperty("climb.shotDir", "build/cableshot");
        Lwjgl3ApplicationConfiguration c = new Lwjgl3ApplicationConfiguration();
        c.setTitle("cableshot"); c.setWindowedMode(W, H); c.setResizable(false); c.useVsync(false); c.disableAudio(true);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override public void create() {
                try {
                    new FileHandle(out).mkdirs();
                    Tuning t = Tuning.parse(Gdx.files.internal("data/tuning.json").readString("UTF-8"));
                    Models models = new Models();
                    FrameBuffer fbo = new FrameBuffer(Pixmap.Format.RGBA8888, W, H, true);
                    fbo.begin();
                    String[] names = {"meadow", "frost", "dusk", "night", "space"};
                    for (int z = 0; z < names.length; z++) {
                        Course course = new Course(0, t.circumference());
                        Element a = new Element(Element.Type.STATIC, 6f, 0f, 4f); a.checkpoint = true; course.add(a);
                        Element cable = new Element(Element.Type.CABLE, 12.5f, 2.3f, 6f); course.add(cable);
                        Element b = new Element(Element.Type.STATIC, 19f, 0.4f, 4f); course.add(b);
                        Element rope = new Element(Element.Type.ROPE, 22f, 5.2f, 0.3f); rope.len = 4.5f; course.add(rope);
                        WorldRenderer wr = new WorldRenderer(t, course, models, 2, 0);
                        wr.resize(W, H);
                        wr.setOrigin(z * t.zoneHeight + t.zoneHeight * 0.55, 0.0);          // the middle of world z's sky
                        Sim sim = Sim.startOn(course, t, 1); sim.setRange(0, course.size() - 1);
                        wr.snapCamera(sim);
                        InputState in = new InputState();
                        float time = 0f;
                        for (int f = 0; f < 45; f++) {
                            in.clear(); sim.step(in); sim.consumeEvents(); time += Sim.DT;
                            Gdx.gl.glClearColor(0f, 0f, 0f, 1f); Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
                            wr.render(sim, 1f, Sim.DT, time, true);
                        }
                        Pixmap p = Pixmap.createFromFrameBuffer(0, 0, W, H);
                        Pixmap flipped = new Pixmap(W, H, p.getFormat());
                        for (int y = 0; y < H; y++) flipped.drawPixmap(p, 0, y, W, 1, 0, H - 1 - y, W, 1);
                        PixmapIO.writePNG(new FileHandle(out + "/cable_" + z + "_" + names[z] + ".png"), flipped);
                        p.dispose(); flipped.dispose(); wr.dispose();
                        System.out.println("CABLESHOT " + names[z] + " mode=" + sim.mode);
                    }
                    fbo.end();
                } catch (Throwable e) { System.out.println("CABLESHOT ERROR " + e); e.printStackTrace(); }
                Gdx.app.exit();
            }
        }, c);
    }
}

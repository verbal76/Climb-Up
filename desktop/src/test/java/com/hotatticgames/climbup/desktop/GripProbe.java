package com.hotatticgames.climbup.desktop;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.hotatticgames.climbup.render.HeroRig;
import com.hotatticgames.climbup.sim.Autopilot;
import com.hotatticgames.climbup.sim.Course;
import com.hotatticgames.climbup.sim.CourseGenerator;
import com.hotatticgames.climbup.sim.InputState;
import com.hotatticgames.climbup.sim.Sim;
import com.hotatticgames.climbup.sim.Tuning;
import java.util.EnumMap;
import java.util.Map;

/**
 * Drives the REAL player rig (every selectable character) through a full autopilot playthrough of many generated slices -
 * jumps, ledge grabs, rope climbs, cable shimmies, pull-ups, hits - with a real GL context, feeding the sim's events to
 * the rig exactly as PlayScreen does, and reports the smallest arm/leg bone scale reached. This is the end-to-end
 * regression guard for the "arms/legs/ankles shrink over play" bug: the grip poser aims the limb bones through
 * HeroRig.commitBone(), which used to leak scale and drive the limbs toward zero across the state transitions of a real
 * run. A healthy run holds every limb near 1 (the hamster's arms higher, from its deliberate stretch).
 *
 * Prints one GRIP_PROBE line per character (with the anim-state tally proving grips were exercised) and a final
 * GRIP_PROBE_RESULT PASS/FAIL; exits non-zero if any limb shrank below HEALTHY.
 */
public final class GripProbe {
    private static final int CHARACTERS = Integer.getInteger("climb.probeChars", 6);
    private static final int SLICES = 10;           // play ten different generated slices back to back
    private static final int FRAMES_PER_SLICE = 1400;
    private static final int WARMUP = 20;
    private static final float HEALTHY = 0.70f;     // a limb below this has visibly shrunk (healthy climb pose sits ~0.80)
    private static final long SEED = Long.getLong("climb.seed", 11L);

    public static void main(String[] args) {
        Lwjgl3ApplicationConfiguration c = new Lwjgl3ApplicationConfiguration();
        c.setTitle("Upwardly grip probe");
        c.setWindowedMode(320, 240);
        c.useVsync(false);
        c.disableAudio(true);
        final int[] failures = {0};
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override public void create() {
                try {
                    Tuning t = Tuning.parse(Gdx.files.internal("data/tuning.json").readString("UTF-8"));
                    for (int ch = 0; ch < CHARACTERS; ch++) {
                        HeroRig rig = new HeroRig(ch);
                        float min = Float.MAX_VALUE, time = 0f, dt = Sim.DT;
                        int worstSlice = -1;
                        Map<HeroRig.Anim, Integer> tally = new EnumMap<>(HeroRig.Anim.class);
                        InputState in = new InputState();
                        int dumps = 0;
                        for (int k = 0; k < SLICES; k++) {
                            Course course = CourseGenerator.chunk(SEED, k, null, t);
                            Sim sim = Sim.startOn(course, t, 0);
                            sim.setRange(0, course.size() - 1);
                            Autopilot.Driver driver = new Autopilot.Driver(sim);
                            for (int f = 0; f < FRAMES_PER_SLICE; f++) {
                                in.clear();
                                driver.drive(sim, in);
                                sim.step(in);
                                int ev = sim.consumeEvents();
                                rig.events(ev, Math.abs(sim.vy));
                                rig.update(sim, dt, time, 0f, sim.y, 0f, 0f, false);
                                time += dt;
                                tally.merge(rig.anim, 1, Integer::sum);
                                if (f >= WARMUP) {
                                    float m = rig.minLimbScale();
                                    if (m < min) {
                                        min = m; worstSlice = k;
                                        if (m < 0.5f && dumps < 14) { dumps++; System.out.println("  DUMP ch=" + ch + " slice=" + k + " f=" + f + " min=" + String.format("%.3f", m) + " anim=" + rig.anim + " mode=" + sim.mode + " pullT=" + String.format("%.2f", sim.pullT) + " | " + rig.limbScalesDebug()); }
                                    }
                                }
                                if (sim.won) break;
                                if (driver.failed) { sim.respawn(); sim.consumeEvents(); driver = new Autopilot.Driver(sim); }
                            }
                        }
                        boolean ok = min > HEALTHY;
                        if (!ok) failures[0]++;
                        System.out.println("GRIP_PROBE char=" + ch + " minLimbScale=" + String.format("%.4f", min)
                                + " worstSlice=" + worstSlice + " " + (ok ? "OK" : "SHRUNK") + " anim=" + tally);
                        rig.dispose();
                    }
                } catch (Throwable e) {
                    failures[0]++;
                    System.out.println("GRIP_PROBE ERROR " + e);
                    e.printStackTrace();
                }
                Gdx.app.exit();
            }
        }, c);
        System.out.println(failures[0] == 0 ? "GRIP_PROBE_RESULT PASS" : "GRIP_PROBE_RESULT FAIL n=" + failures[0]);
        System.exit(failures[0]);
    }
}

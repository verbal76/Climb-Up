package com.hotatticgames.climbup.module;

import com.badlogic.gdx.Gdx;
import com.hotatticgames.climbup.sim.Autopilot;
import com.hotatticgames.climbup.sim.InputState;
import com.hotatticgames.climbup.sim.Sim;
import com.hotatticgames.climbup.sim.Tower;
import com.hotatticgames.climbup.sim.Tuning;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Validation-only: a deterministic headless climb. The same autopilot, the same seed and the same fixed-step simulation the game uses climb a streaming tower for N steps (with the
 * tower rebuilt around new origins along the way) and every step's state is folded into a SHA-256. Two builds of the same code on the same machine must give the same digest;
 * the digest of the module-loaded game is compared with the packaged game's, and with the JVM's. Request format: {@code digest:seed:steps} (tuning read from the game's assets).
 */
public final class SelfTest {
    private SelfTest() {}

    public static String run(String request) {
        try {
            String[] p = request.split(":");
            if (!p[0].equals("digest")) return null;
            String json = Gdx.files != null ? Gdx.files.internal("data/tuning.json").readString("UTF-8")
                    : new String(Files.readAllBytes(Paths.get(System.getProperty("climb.tuning", "../assets/data/tuning.json"))), StandardCharsets.UTF_8);
            return digest(json, Long.parseLong(p[1]), Integer.parseInt(p[2]));
        } catch (Throwable t) { return "error " + t; }
    }

    public static String digest(String tuningJson, long seed, int steps) throws Exception {
        Tuning t = Tuning.parse(tuningJson);
        Tower a = new Tower(seed, t);
        Sim s = Sim.startOn(a.world, t, 0); s.keysFree = true; s.deferRespawn = true; s.floorOverride = a.floorLocal(); s.setRange(0, a.world.size() - 1);
        InputState in = new InputState(); Autopilot.Driver d = new Autopilot.Driver(s);
        MessageDigest md = MessageDigest.getInstance("SHA-256"); ByteBuffer bb = ByteBuffer.allocate(64);
        int size = a.world.size(), seen = a.rebuilds; double maxAbs = 0;
        for (int step = 0; step < steps; step++) {
            if (step % 6 == 0) {
                while (a.topAbsY() < maxAbs + 150) a.extend();                       // synchronous: no thread timing in the result
                a.maintain(s);
                if (a.rebuilds != seen) { d.rebase(a.lastRemap); seen = a.rebuilds; }
                if (a.world.size() != size) { s.setRange(0, a.world.size() - 1); size = a.world.size(); }
            }
            d.drive(s, in); s.step(in); s.consumeEvents();
            double abs = a.absY(s.y); maxAbs = Math.max(maxAbs, abs);
            Tower.Ref ref = s.onElem >= 0 ? a.refOf(s.onElem) : null;
            bb.clear().putInt(step).putLong(Double.doubleToLongBits(abs)).putFloat(s.s).putFloat(s.vx).putFloat(s.vy).putInt(s.mode.ordinal()).putInt(s.facing)
                    .putInt(ref == null ? -1 : ref.slice).putInt(ref == null ? -1 : ref.local).putInt(s.falls);
            md.update(bb.array(), 0, bb.position());
        }
        StringBuilder hex = new StringBuilder(); for (byte b : md.digest()) hex.append(String.format("%02x", b));
        return String.format("%s steps=%d maxAbsY=%.3f rebuilds=%d falls=%d failed=%b", hex, steps, maxAbs, a.rebuilds, s.falls, d.failed);
    }
}

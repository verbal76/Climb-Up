package com.hotatticgames.climbup.module;

import com.badlogic.gdx.Gdx;
import com.hotatticgames.climbup.sim.Autopilot;
import com.hotatticgames.climbup.sim.Element;
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
 * Every step also folds in the simulation's event mask, hazard hits and checkpoint, and the result line reports what the climb covered (event kinds, element types stood on, element types
 * present in the resident world), so that a green comparison says which parts of the game it actually exercised.
 */
public final class SelfTest {
    private SelfTest() {}

    public static String run(String request) {
        try {
            String[] p = request.split(":");
            if (!p[0].equals("digest") && !p[0].equals("chaos") && !p[0].equals("mixed")) return null;
            String json = Gdx.files != null ? Gdx.files.internal("data/tuning.json").readString("UTF-8")
                    : new String(Files.readAllBytes(Paths.get(System.getProperty("climb.tuning", "../assets/data/tuning.json"))), StandardCharsets.UTF_8);
            return digest(json, Long.parseLong(p[1]), Integer.parseInt(p[2]), p[0].equals("chaos") ? 1 : p[0].equals("mixed") ? 2 : 0);
        } catch (Throwable t) { return "error " + t; }
    }

    public static String digest(String tuningJson, long seed, int steps) throws Exception { return digest(tuningJson, seed, steps, 0); }

    /**
     * {@code mode 0}: the autopilot climbs (it plans around hazards and never gets hit). {@code 1}: a seeded pseudo-random "flailing" player instead (held directions, jumps and swings at random),
     * which falls, respawns at checkpoints and crumbles platforms. {@code 2}: the autopilot climbs and every so often the flailing player takes over for a short burst, so the run reaches high
     * parts of the tower and then gets knocked about by what is there (hazards, crabs, bees). All are exactly repeatable; java.util.Random is specified bit for bit.
     */
    public static String digest(String tuningJson, long seed, int steps, int mode) throws Exception {
        Tuning t = Tuning.parse(tuningJson);
        Tower a = new Tower(seed, t);
        Sim s = Sim.startOn(a.world, t, 0); s.keysFree = true; s.deferRespawn = true; s.floorOverride = a.floorLocal(); s.setRange(0, a.world.size() - 1);
        InputState in = new InputState(); Autopilot.Driver d = new Autopilot.Driver(s);
        java.util.Random rnd = new java.util.Random(seed * 31 + 7); int hold = 0, burst = 900; float mx = 0, my = 0; boolean climbUp = false, flail = mode == 1;
        MessageDigest md = MessageDigest.getInstance("SHA-256"); ByteBuffer bb = ByteBuffer.allocate(64);
        int size = a.world.size(), seen = a.rebuilds; double maxAbs = 0;
        int evAll = 0, onTypes = 0, presentTypes = 0;
        for (int step = 0; step < steps; step++) {
            if (step % 6 == 0) {
                while (a.topAbsY() < maxAbs + 150) a.extend();                       // synchronous: no thread timing in the result
                a.maintain(s);
                if (a.rebuilds != seen) { if (!flail) d.rebase(a.lastRemap); seen = a.rebuilds; }
                if (a.world.size() != size) { s.setRange(0, a.world.size() - 1); size = a.world.size(); }
                for (Element e : a.world.elements) presentTypes |= 1 << e.type.ordinal();
                for (Element e : a.world.hazards) presentTypes |= 1 << e.type.ordinal();
            }
            if (mode == 2 && --burst <= 0) { flail = !flail; burst = flail ? 30 + rnd.nextInt(120) : 600 + rnd.nextInt(1200); if (!flail) { d = new Autopilot.Driver(s); } }
            if (flail) {
                if (--hold <= 0) { hold = 8 + rnd.nextInt(50); mx = Math.max(-1, Math.min(1, rnd.nextInt(5) - 2)); my = rnd.nextInt(4) == 0 ? 1f : (rnd.nextInt(6) == 0 ? -1f : 0f); climbUp = rnd.nextBoolean(); }
                in.clear(); in.moveX = mx; in.moveY = my; in.jumpPressed = rnd.nextInt(18) == 0; in.jumpHeld = in.jumpPressed || (climbUp && rnd.nextInt(3) == 0); in.swingPressed = rnd.nextInt(90) == 0;
            } else d.drive(s, in);
            s.step(in); int ev = s.consumeEvents(); evAll |= ev;
            if (s.onElem >= 0 && s.onElem < a.world.size()) onTypes |= 1 << a.world.get(s.onElem).type.ordinal();
            double abs = a.absY(s.y); maxAbs = Math.max(maxAbs, abs);
            Tower.Ref ref = s.onElem >= 0 ? a.refOf(s.onElem) : null;
            bb.clear().putInt(step).putLong(Double.doubleToLongBits(abs)).putFloat(s.s).putFloat(s.vx).putFloat(s.vy).putInt(s.mode.ordinal()).putInt(s.facing)
                    .putInt(ref == null ? -1 : ref.slice).putInt(ref == null ? -1 : ref.local).putInt(s.falls).putInt(ev).putInt(s.hits).putInt(s.checkpoint);
            md.update(bb.array(), 0, bb.position());
        }
        StringBuilder hex = new StringBuilder(); for (byte b : md.digest()) hex.append(String.format("%02x", b));
        return String.format("%s steps=%d maxAbsY=%.3f rebuilds=%d falls=%d hits=%d failed=%b events=0x%x on=%s present=%s", hex, steps, maxAbs, a.rebuilds, s.falls, s.hits, mode != 1 && d.failed, evAll,
                types(onTypes), types(presentTypes));
    }

    private static String types(int mask) {
        StringBuilder b = new StringBuilder();
        for (Element.Type t : Element.Type.values()) if ((mask & (1 << t.ordinal())) != 0) b.append(b.length() == 0 ? "" : "+").append(t);
        return b.toString();
    }

    /** JVM entry for the CI cross-check: {@code java -Dclimb.tuning=assets/data/tuning.json -cp module.jar:gdx.jar SelfTest digest:12:7200 ...}; prints one {@code request -> result} line each. */
    public static void main(String[] args) {
        for (String r : args) System.out.println(r + " -> " + run(r));
    }
}

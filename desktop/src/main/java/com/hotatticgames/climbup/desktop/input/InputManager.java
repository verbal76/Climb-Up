package com.hotatticgames.climbup.desktop.input;

import com.hotatticgames.climbup.platform.GameInput;
import com.hotatticgames.climbup.sim.InputState;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns keyboard, mouse and controller state into the game's actions, once per frame. Keyboard, mouse and one active controller are always live together;
 * the "device" (for prompts only) follows whichever was meaningfully used last. Gameplay output goes through {@link GameInput} into the unchanged InputState.
 */
public final class InputManager implements GameInput {
    public enum Device { KEYBOARD, CONTROLLER }

    private static final Act[] ACTS = Act.values();
    private static final float REPEAT_FIRST = 0.40f, REPEAT_EVERY = 0.11f, SWITCH_DEBOUNCE = 0.25f, MOUSE_MOVE_PX = 24f, MEANINGFUL_STICK = 0.65f;

    public final DesktopConfig cfg;
    private final Sources src;
    private final boolean[] down = new boolean[ACTS.length], just = new boolean[ACTS.length], rep = new boolean[ACTS.length];
    private final float[] held = new float[ACTS.length], nextRep = new float[ACTS.length];
    private final Map<String, boolean[]> seen = new HashMap<>();      // per controller: which controls were already beyond the "meaningful" threshold
    private final boolean[] prevPad = new boolean[Ctl.values().length];
    private Pad active;
    private Device device = Device.KEYBOARD;
    private float clock, lastSwitch = -10f;
    private int lastMx = Integer.MIN_VALUE, lastMy;
    private boolean jumpLatch, swingLatch;
    private float moveX, moveY;
    private int connectedLast = -1;
    public boolean controllerEvent;       // a controller connected/disconnected since the last poll of this flag (the settings screen refreshes)

    public InputManager(DesktopConfig cfg, Sources src) { this.cfg = cfg; this.src = src; }

    // ------------------------------------------------------------------ per-frame update

    public void update(float dt) {
        clock += dt;
        List<? extends Pad> pads = src.pads();
        int n = 0; for (Pad p : pads) if (p.connected()) n++;
        if (n != connectedLast) { if (connectedLast >= 0) controllerEvent = true; connectedLast = n; }
        selectActivePad(pads);

        boolean kbUsed = false, padUsed = false;
        int any = src.anyJustPressed();
        if (any >= 0) kbUsed = true;
        if (lastMx == Integer.MIN_VALUE) { lastMx = src.mouseX(); lastMy = src.mouseY(); }
        if (Math.abs(src.mouseX() - lastMx) + Math.abs(src.mouseY() - lastMy) >= MOUSE_MOVE_PX) { kbUsed = true; lastMx = src.mouseX(); lastMy = src.mouseY(); }    // measured from where the pointer last counted, so jitter never adds up
        padUsed = padActivity;

        Bindings<Integer> kb = cfg.keyboard;
        Bindings<Ctl> pb = active != null ? cfg.padFor(active.id()) : null;
        float dz = cfg.deadzone;
        for (Act a : ACTS) {
            boolean d = false, j = false;
            for (int k : kb.get(a)) { if (src.down(k)) d = true; if (src.justPressed(k)) j = true; }
            if (!a.game) { for (int k : safetyKeys(a)) { if (src.down(k)) d = true; if (src.justPressed(k)) j = true; } }
            if (pb != null) {
                for (Ctl c : pb.get(a)) { if (isDown(active, c, dz)) d = true; if (justDown(c)) j = true; }
                if (!a.game) for (Ctl c : safetyPad(a)) { if (isDown(active, c, dz)) d = true; if (justDown(c)) j = true; }
            }
            int i = a.ordinal();
            boolean wasDown = down[i];
            down[i] = d; just[i] = j || (d && !wasDown);
            // auto-repeat for menu navigation (held directions step repeatedly)
            rep[i] = false;
            if (just[i]) { rep[i] = true; held[i] = 0f; nextRep[i] = REPEAT_FIRST; }
            else if (d) { held[i] += dt; if (held[i] >= nextRep[i]) { rep[i] = true; nextRep[i] = held[i] + REPEAT_EVERY; } }
            else held[i] = 0f;
        }
        // remember pad state for edge detection next frame
        if (active != null) for (Ctl c : Ctl.values()) prevPad[c.ordinal()] = isDown(active, c, dz);
        if (just[Act.JUMP.ordinal()]) jumpLatch = true;
        if (just[Act.SWING.ordinal()]) swingLatch = true;
        computeMove(kb, pb, dz);
        if (kbUsed) noteDevice(Device.KEYBOARD);
        if (padUsed) noteDevice(Device.CONTROLLER);
        if (connectedLast == 0 && device == Device.CONTROLLER) { device = Device.KEYBOARD; lastSwitch = clock; }
    }

    private boolean padActivity;

    private void selectActivePad(List<? extends Pad> pads) {
        padActivity = false;
        Pad firstConnected = null; boolean activeStillThere = false;
        for (Pad p : pads) {
            if (!p.connected()) continue;
            if (firstConnected == null) firstConnected = p;
            if (p == active) activeStillThere = true;
        }
        if (!activeStillThere) { active = firstConnected; java.util.Arrays.fill(prevPad, false); }
        Pad winner = null;
        for (Pad p : pads) {
            if (!p.connected()) continue;
            boolean[] s = seen.computeIfAbsent(p.id(), k -> new boolean[Ctl.values().length]);
            boolean act = false;
            for (Ctl c : Ctl.values()) {
                boolean now = p.value(c) >= (c.analog() ? Math.max(MEANINGFUL_STICK, cfg.deadzone + 0.25f) : 0.5f);
                if (now && !s[c.ordinal()]) act = true;
                s[c.ordinal()] = now;
            }
            if (act && winner == null) winner = p;
        }
        if (winner != null) { padActivity = true; if (winner != active) { active = winner; java.util.Arrays.fill(prevPad, false); } }
    }

    private void noteDevice(Device d) {
        if (d == device) return;
        if (clock - lastSwitch < SWITCH_DEBOUNCE) return;
        device = d; lastSwitch = clock;
    }

    private static boolean isDown(Pad p, Ctl c, float dz) { return p.value(c) >= (c.analog() ? Math.max(dz, 0.5f) : 0.5f); }
    private boolean justDown(Ctl c) { boolean latched = active.latched(c); return latched || (isDown(active, c, cfg.deadzone) && !prevPad[c.ordinal()]); }

    private static int[] safetyKeys(Act a) {
        switch (a) {
            case MENU_UP: return new int[]{com.badlogic.gdx.Input.Keys.UP};
            case MENU_DOWN: return new int[]{com.badlogic.gdx.Input.Keys.DOWN};
            case MENU_LEFT: return new int[]{com.badlogic.gdx.Input.Keys.LEFT};
            case MENU_RIGHT: return new int[]{com.badlogic.gdx.Input.Keys.RIGHT};
            case CONFIRM: return new int[]{com.badlogic.gdx.Input.Keys.ENTER};
            default: return new int[]{com.badlogic.gdx.Input.Keys.ESCAPE};
        }
    }
    private static Ctl[] safetyPad(Act a) {
        switch (a) {
            case MENU_UP: return new Ctl[]{Ctl.DPAD_UP};
            case MENU_DOWN: return new Ctl[]{Ctl.DPAD_DOWN};
            case MENU_LEFT: return new Ctl[]{Ctl.DPAD_LEFT};
            case MENU_RIGHT: return new Ctl[]{Ctl.DPAD_RIGHT};
            case CONFIRM: return new Ctl[]{Ctl.A};
            default: return new Ctl[]{Ctl.B};
        }
    }

    // ------------------------------------------------------------------ gameplay axes

    private void computeMove(Bindings<Integer> kb, Bindings<Ctl> pb, float dz) {
        float kx = (kbHeld(kb, Act.RIGHT) ? 1 : 0) - (kbHeld(kb, Act.LEFT) ? 1 : 0);
        float ky = (kbHeld(kb, Act.CLIMB_UP) ? 1 : 0) - (kbHeld(kb, Act.CLIMB_DOWN) ? 1 : 0);
        float px = 0, py = 0;
        if (pb != null) { px = axisOf(pb, Act.RIGHT, dz) - axisOf(pb, Act.LEFT, dz); py = digital(pb, Act.CLIMB_UP, dz) - digital(pb, Act.CLIMB_DOWN, dz); }
        moveX = kx != 0 ? kx : px;           // the keyboard wins when both are used, exactly like the touch stick yields to keys in the original
        moveY = ky != 0 ? ky : py;
    }

    private boolean kbHeld(Bindings<Integer> kb, Act a) { for (int k : kb.get(a)) if (src.down(k)) return true; return false; }

    /** Same response curve as the touch stick: nothing inside the dead zone, then 0.35..1 up to a firm push. Digital controls give a full 1. */
    private float axisOf(Bindings<Ctl> pb, Act a, float dz) {
        float best = 0f;
        for (Ctl c : pb.get(a)) {
            float v = active.value(c);
            if (c.analog()) { if (v >= dz) best = Math.max(best, Math.min(1f, (v - dz) / 0.45f + 0.35f)); }
            else if (v >= 0.5f) best = 1f;
        }
        return best;
    }
    private float digital(Bindings<Ctl> pb, Act a, float dz) { for (Ctl c : pb.get(a)) if (isDown(active, c, Math.max(dz, 0.35f))) return 1f; return 0f; }

    // ------------------------------------------------------------------ GameInput

    @Override public void read(InputState in) {
        in.moveX = moveX; in.moveY = moveY;
        in.jumpHeld = down[Act.JUMP.ordinal()];
        in.jumpPressed = jumpLatch;
        in.swingPressed = swingLatch;
    }
    @Override public void stepDone() { jumpLatch = false; swingLatch = false; }
    @Override public boolean pausePressed() { return just[Act.PAUSE.ordinal()]; }
    @Override public boolean backPressed() { return just[Act.BACK.ordinal()]; }
    @Override public void clear() { jumpLatch = false; swingLatch = false; }

    // ------------------------------------------------------------------ queries for menus and prompts

    public boolean menuPressed(Act a) { return rep[a.ordinal()]; }
    public boolean justPressed(Act a) { return just[a.ordinal()]; }
    public boolean held(Act a) { return down[a.ordinal()]; }
    public float moveX() { return moveX; }
    public float moveY() { return moveY; }
    public Pad activePad() { return active; }
    /** Hands the controller role to the next connected controller (Settings > Connected controller). */
    public void cycleActivePad() {
        List<Pad> l = connectedPads();
        if (l.size() < 2) return;
        int i = l.indexOf(active);
        active = l.get((i + 1) % l.size()); java.util.Arrays.fill(prevPad, false);
    }
    public List<Pad> connectedPads() { List<Pad> l = new ArrayList<>(); for (Pad p : src.pads()) if (p.connected()) l.add(p); return l; }
    public Sources sources() { return src; }

    /** The device whose button names are shown. */
    public Device promptDevice() {
        switch (cfg.inputMode) {
            case KEYBOARD: return Device.KEYBOARD;
            case CONTROLLER: return active != null ? Device.CONTROLLER : Device.KEYBOARD;
            default: return device == Device.CONTROLLER && active != null ? Device.CONTROLLER : Device.KEYBOARD;
        }
    }

    public PadFamily family() { return active == null ? PadFamily.GENERIC : PadFamily.of(active.name()); }

    /** The prompt for an action on the current device, e.g. "SPACE" or "CROSS". */
    public String prompt(Act a) {
        if (promptDevice() == Device.CONTROLLER) {
            Ctl c = cfg.padFor(active.id()).first(a);
            return c == null ? "-" : family().label(c);
        }
        Integer k = cfg.keyboard.first(a);
        return k == null ? "-" : KeyCodes.name(k);
    }

    /** The prompt for a pair of directions, e.g. "A/D" or "L STICK". */
    public String promptMove() {
        if (promptDevice() == Device.CONTROLLER) {
            Bindings<Ctl> pb = cfg.padFor(active.id());
            Ctl l = pb.first(Act.LEFT), r = pb.first(Act.RIGHT);
            if (l != null && r != null && l.name().startsWith("LS_") && r.name().startsWith("LS_")) return "L STICK";
            if (l != null && r != null && l.name().startsWith("DPAD") && r.name().startsWith("DPAD")) return "D-PAD";
            return (l == null ? "-" : family().label(l)) + "/" + (r == null ? "-" : family().label(r));
        }
        return prompt(Act.LEFT) + "/" + prompt(Act.RIGHT);
    }

    /** Test/diagnostic helper: forces the clock past the prompt debounce. */
    public float clock() { return clock; }
}

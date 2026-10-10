package com.hotatticgames.climbup.desktop;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.InputMultiplexer;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.ScreenAdapter;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.hotatticgames.climbup.ClimbGame;
import com.hotatticgames.climbup.desktop.input.*;
import com.hotatticgames.climbup.ui.Ui;
import java.util.List;

/** The rebinding interface for the keyboard + mouse or for the active controller. Usable with any device: it is built from the game's own buttons, so the focus ring, mouse and controller all work. */
final class BindingsScreen extends ScreenAdapter {
    private final ClimbGame g; private final DesktopPlatform dp; private final Screen back; private final boolean pad;
    private final Pad target;                        // the controller being edited (null when editing the keyboard)
    private Bindings<Integer> kb; private Bindings<Ctl> pb;
    private final Remapper<Integer> rk; private Remapper<Ctl> rp;
    private final boolean[] prev = new boolean[Ctl.values().length];
    private float clock, armAt;
    private boolean confirmRestore;
    private String status = "";

    BindingsScreen(ClimbGame g, DesktopPlatform dp, Screen back, boolean pad) {
        this.g = g; this.dp = dp; this.back = back; this.pad = pad;
        target = pad ? dp.input.activePad() : null;
        kb = dp.cfg.keyboard;
        pb = pad && target != null ? dp.cfg.editablePad(target.id()) : null;
        rk = new Remapper<>(kb); rp = pb != null ? new Remapper<>(pb) : null;
    }

    @Override public void show() { Gdx.input.setInputProcessor(new InputMultiplexer(dp.sources.scrollTap, g.ui)); dp.screenChanged(); }
    @Override public void resize(int w, int h) { g.ui.resize(w, h); }

    private boolean dialog() { return pad ? rp != null && rp.state != Remapper.State.IDLE : rk.state != Remapper.State.IDLE; }

    private String name(Act a, int slot) {
        if (pad) { List<Ctl> l = pb.get(a); return slot < l.size() ? dp.input.family().label(l.get(slot)) : null; }
        List<Integer> l = kb.get(a); return slot < l.size() ? Keys.name(l.get(slot)) : null;
    }
    private int count(Act a) { return pad ? pb.get(a).size() : kb.get(a).size(); }

    @Override public void render(float dt) {
        clock += dt;
        Gdx.gl.glClearColor(0.06f, 0.08f, 0.16f, 1f); Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        if (pad && (target == null || !target.connected())) { if (rp != null && rp.state != Remapper.State.IDLE) rp.cancel(); status = "CONTROLLER DISCONNECTED. NOTHING WAS CHANGED."; }
        else if (dialog()) capture();
        Ui ui = g.ui; ui.begin();
        float W = ui.w(), H = ui.h();
        ui.textC(pad ? "CONTROLLER BINDINGS" : "KEYBOARD + MOUSE BINDINGS", W / 2, H - 78, 6f, Ui.TEXT);
        if (pad && target != null) ui.textC(DesktopTabs.clip(target.name(), 40), W / 2, H - 118, 2.8f, Ui.DIM);
        boolean usable = !pad || (target != null && target.connected() && pb != null);
        if (!dialog()) {
            if (ui.button("BACK", 40, H - 100, 200, 64, true)) { g.audio.play("click"); dp.save(); g.setScreen(back); ui.end(); return; }
            if (usable) {
                column(ui, W / 2 - 620, H - 190, "GAMEPLAY", true);
                column(ui, W / 2 + 20, H - 190, "MENUS", false);
                if (ui.button(confirmRestore ? "TAP AGAIN TO RESTORE" : "RESTORE DEFAULTS", W / 2 - 230, 70, 460, 64)) {
                    g.audio.play("click");
                    if (!confirmRestore) confirmRestore = true;
                    else {
                        confirmRestore = false;
                        if (pad) { dp.cfg.restorePad(target.id()); pb = dp.cfg.editablePad(target.id()); rp = new Remapper<>(pb); } else dp.cfg.restoreKeyboard();
                        dp.save(); status = "DEFAULTS RESTORED.";
                    }
                }
            } else ui.textC("CONNECT A CONTROLLER TO EDIT ITS BINDINGS.", W / 2, H / 2, 4f, Ui.TEXT);
            if (!status.isEmpty()) ui.textC(status, W / 2, 34, 2.8f, Ui.ACCENT);
        } else drawDialog(ui, W, H);
        ui.end();
    }

    private void column(Ui ui, float x, float top, String head, boolean game) {
        ui.text(head, x, top, 3.6f, Ui.ACCENT);
        float y = top - 70;
        for (Act a : Act.values()) {
            if (a.game != game) continue;
            ui.text(a.label, x, y + 20, 2.8f, Ui.TEXT);
            for (int slot = 0; slot < Bindings.MAX_SLOTS; slot++) {
                String label = name(a, slot);
                int n = count(a);
                if (label == null && slot != n) continue;            // only the next free slot offers "add"
                float bx = x + 210 + slot * 128;
                if (ui.button(label == null ? "+ ADD" : label, bx, y, 120, 54, label != null && slot == 0)) begin(a, slot);
            }
            y -= 64;
        }
    }

    private void begin(Act a, int slot) {
        g.audio.play("click"); status = ""; confirmRestore = false;
        armAt = clock + 0.3f;
        java.util.Arrays.fill(prev, true);               // anything already held cannot be captured
        if (pad) rp.begin(a, slot); else rk.begin(a, slot);
    }

    /** Looks for the next valid input while the dialog waits. */
    private void capture() {
        if (clock < armAt) return;
        if (!pad) {
            if (rk.state != Remapper.State.WAITING) return;
            int k = dp.sources.anyJustPressed();
            if (k < 0 || k == Keys.mouse(com.badlogic.gdx.Input.Buttons.LEFT)) return;      // left click is reserved for pointing at buttons
            if (rk.capture(k, Keys.reserved(k))) g.audio.play("click");
            return;
        }
        if (rp.state != Remapper.State.WAITING) return;
        Ctl best = null; float bv = 0f;
        for (Ctl c : Ctl.values()) {
            float v = target.value(c);
            boolean now = v >= (c.analog() ? 0.7f : 0.5f);
            if (now && !prev[c.ordinal()] && v > bv) { best = c; bv = v; }        // the strongest new push: names the stick axis and its direction
            prev[c.ordinal()] = now;
        }
        if (best != null && rp.capture(best, false)) g.audio.play("click");
    }

    private void drawDialog(Ui ui, float W, float H) {
        ui.rect(0, 0, W, H, new Color(0, 0, 0, 0.6f));
        float pw = 820, ph = 400, x = W / 2 - pw / 2, y = H / 2 - ph / 2;
        ui.panel(x, y, pw, ph);
        Act a = pad ? rp.act : rk.act; Remapper.State st = pad ? rp.state : rk.state;
        ui.textC("REBIND: " + a.label, W / 2, y + ph - 70, 5f, Ui.TEXT);
        String msg = pad ? rp.message : rk.message;
        if (st == Remapper.State.WAITING) {
            ui.textC(pad ? "PRESS A BUTTON OR PUSH A STICK ON YOUR CONTROLLER" : "PRESS A KEY OR A MOUSE BUTTON", W / 2, y + ph - 150, 3.2f, Ui.ACCENT);
            ui.textC("WAITING FOR INPUT...", W / 2, y + ph - 195, 3.2f, Ui.DIM);
            if (!msg.isEmpty()) ui.textC(msg, W / 2, y + ph - 235, 3f, new Color(1f, 0.4f, 0.35f, 1f));
            float bw = 300, by = y + 40;
            boolean canRemove = count(a) > 1 && (pad ? rp.slot < pb.get(a).size() : rk.slot < kb.get(a).size());
            if (ui.button("CANCEL", W / 2 - (canRemove ? bw + 20 : bw / 2), by, bw, 74, true)) cancel();
            if (canRemove && ui.button("REMOVE THIS ONE", W / 2 + 20, by, bw, 74)) remove(a);
        } else {
            String shown = pad ? dp.input.family().label(rp.proposed) : Keys.name(rk.proposed);
            ui.textC("NEW BINDING: " + shown, W / 2, y + ph - 150, 4.2f, Ui.ACCENT);
            boolean conflict = pad ? rp.hasConflict() : rk.hasConflict();
            if (conflict) ui.textC("ALREADY USED FOR " + (pad ? rp.conflicts : rk.conflicts).get(0).label + ". CONFIRM MOVES IT HERE.", W / 2, y + ph - 200, 2.8f, new Color(1f, 0.82f, 0.3f, 1f));
            else ui.textC("NO CONFLICTS.", W / 2, y + ph - 200, 2.8f, Ui.GOOD);
            if (!msg.isEmpty()) ui.textC(msg, W / 2, y + ph - 240, 3f, new Color(1f, 0.4f, 0.35f, 1f));
            float bw = 230, by = y + 40, gap = 20, x0 = W / 2 - (3 * bw + 2 * gap) / 2;
            if (ui.button("CONFIRM", x0, by, bw, 74, true)) confirm(a);
            if (ui.button("TRY AGAIN", x0 + bw + gap, by, bw, 74)) { g.audio.play("click"); if (pad) rp.retry(); else rk.retry(); armAt = clock + 0.3f; java.util.Arrays.fill(prev, true); }
            if (ui.button("CANCEL", x0 + 2 * (bw + gap), by, bw, 74)) cancel();
        }
    }

    private void cancel() { g.audio.play("click"); if (pad) rp.cancel(); else rk.cancel(); status = "CANCELLED. NOTHING WAS CHANGED."; }

    private void confirm(Act a) {
        Bindings.Result r = pad ? rp.confirm() : rk.confirm();
        if (r == Bindings.Result.OK || r == Bindings.Result.SAME) {
            if (pad) dp.cfg.setPadProfile(target.id(), pb);
            dp.save(); g.audio.play("checkpoint", 0.5f, 1.4f); status = "SAVED.";
        }
    }

    private void remove(Act a) {
        int slot = pad ? rp.slot : rk.slot;
        boolean ok = pad ? pb.clearSlot(a, slot) : kb.clearSlot(a, slot);
        if (ok) { if (pad) { dp.cfg.setPadProfile(target.id(), pb); rp.cancel(); } else rk.cancel(); dp.save(); status = "REMOVED."; }
        g.audio.play("click");
    }
}

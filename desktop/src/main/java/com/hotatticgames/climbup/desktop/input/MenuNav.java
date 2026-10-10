package com.hotatticgames.climbup.desktop.input;

import com.badlogic.gdx.math.Vector2;
import com.hotatticgames.climbup.ui.Ui;

/** Keyboard / controller / mouse-hover focus over the game's own buttons. The shared UI calls {@link #button} for every button it draws; nothing else about the UI changes. */
public final class MenuNav implements Ui.Navigator {
    private final InputManager in;
    public final FocusModel fm = new FocusModel();
    public boolean visible;                // focus ring shown (after the first navigation or hover)
    private boolean confirm, back, any;
    private int forced = -1;               // a "-"/"+" button activated by pressing left/right on its row
    private final Vector2 v = new Vector2();
    private float lastPx = Float.NaN, lastPy;
    private boolean pointerMoved;
    private float px, py;
    private float lock; private int prevCount;     // a menu that has just appeared ignores confirm for a moment, so a jump key still held down cannot press its default button

    public MenuNav(InputManager in) { this.in = in; }

    public void reset() { fm.reset(); visible = false; forced = -1; lock = 0.45f; }

    @Override public void beginFrame(Ui ui) {
        v.set(in.sources().mouseX(), in.sources().mouseY()); ui.viewport.unproject(v); px = v.x; py = v.y;
        pointerMoved = !Float.isNaN(lastPx) && Math.abs(px - lastPx) + Math.abs(py - lastPy) > 3f;
        lastPx = px; lastPy = py;
        frame(com.badlogic.gdx.Gdx.graphics.getDeltaTime(), in.sources().takeScroll());
    }

    /** Everything beginFrame does except reading the pointer; tests drive menus through this. */
    public void frame(float dt, float wheel) {
        fm.beginFrame();
        lock = Math.max(0f, lock - dt);
        confirm = in.justPressed(Act.CONFIRM) && lock <= 0f; back = in.justPressed(Act.BACK);
        any = in.justPressed(Act.CONFIRM) || back;
        step(wheel);
    }

    /** Test hook: where the pointer is (menu coordinates) and whether it just moved. */
    public void pointer(float x, float y, boolean moved) { px = x; py = y; pointerMoved = moved; }

    /** Applies this frame's directional presses (and wheel) to the focus; separated so tests can drive it without a window. */
    public void step(float wheel) {
        forced = -1;
        int dir = -1;
        if (in.menuPressed(Act.MENU_UP)) dir = 0; else if (in.menuPressed(Act.MENU_DOWN)) dir = 1;
        else if (in.menuPressed(Act.MENU_LEFT)) dir = 2; else if (in.menuPressed(Act.MENU_RIGHT)) dir = 3;
        else if (wheel <= -1f) dir = 0; else if (wheel >= 1f) dir = 1;
        if (dir >= 0) {
            visible = true; any = true;
            FocusModel.Item f = fm.focused();
            if (f != null && dir >= 2 && (f.label.equals("-") || f.label.equals("+"))) forced = partner(f, dir == 2 ? "-" : "+");   // left/right on a volume-style row adjusts it
            else fm.move(dir);
        }
        if (confirm) visible = true;
    }

    private int partner(FocusModel.Item f, String label) {
        for (int i = 0; i < 64; i++) { FocusModel.Item c = fm.lastItem(i); if (c == null) break; if (c.label.equals(label) && Math.abs(c.y - f.y) < 2f) return i; }
        return -1;
    }

    @Override public int button(String label, float x, float y, float w, float h, boolean accent) {
        int idx = fm.add(label, x, y, w, h, accent);
        int flags = 0;
        if (pointerMoved && px >= x && px <= x + w && py >= y && py <= y + h) { fm.focus = idx; visible = true; }
        boolean focused = idx == fm.focus;
        if (focused && visible) flags |= 1;
        if (focused) flags |= 4;                  // focused, ring visible or not: this one is the orange button
        if (focused && confirm) { flags |= 2; confirm = false; }
        if (back && (label.equals("BACK") || label.equals("CANCEL"))) { flags |= 2; back = false; }
        if (idx == forced) { flags |= 2; forced = -1; }
        return flags;
    }

    @Override public void endFrame() { fm.endFrame(); if (prevCount == 0 && fm.count() > 0) lock = 0.45f; prevCount = fm.count(); }
    @Override public boolean anyPress() { return any; }
}

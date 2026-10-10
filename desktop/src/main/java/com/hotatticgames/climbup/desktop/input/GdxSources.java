package com.hotatticgames.climbup.desktop.input;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.controllers.Controller;
import com.badlogic.gdx.controllers.ControllerAdapter;
import com.badlogic.gdx.controllers.Controllers;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** The real keyboard, mouse and controllers (libGDX Input + gdx-controllers). Controllers are discovered automatically and may come and go at any time. */
public final class GdxSources implements Sources {
    private final Map<Controller, GdxPad> pads = new IdentityHashMap<>();
    private final List<GdxPad> order = new ArrayList<>();
    private final ControllerAdapter listener = new ControllerAdapter() {
        @Override public boolean buttonDown(Controller c, int code) { GdxPad p = pads.get(c); if (p != null) p.buttonDown(code); return false; }
    };
    private float scroll;
    /** Install ahead of whichever processor a screen sets, so the wheel is seen everywhere. */
    public final InputAdapter scrollTap = new InputAdapter() { @Override public boolean scrolled(float ax, float ay) { scroll += ay; return false; } };
    private boolean controllersOk = true;

    public GdxSources() {
        try { Controllers.addListener(listener); } catch (Throwable t) { controllersOk = false; }     // no native controller library (e.g. a stripped runtime): keyboard and mouse still work
        refresh();
    }

    private void refresh() {
        if (!controllersOk) return;
        try {
            com.badlogic.gdx.utils.Array<Controller> now = Controllers.getControllers();
            for (int i = order.size() - 1; i >= 0; i--) if (!now.contains(order.get(i).c, true)) { pads.remove(order.get(i).c); order.remove(i); }
            for (Controller c : now) if (!pads.containsKey(c)) { GdxPad p = new GdxPad(c); pads.put(c, p); order.add(p); }
        } catch (Throwable t) { controllersOk = false; }
    }

    @Override public List<? extends Pad> pads() { refresh(); return order; }

    @Override public boolean down(int code) { return Keys.isMouse(code) ? Gdx.input.isButtonPressed(code - Keys.MOUSE) : Gdx.input.isKeyPressed(code); }
    @Override public boolean justPressed(int code) { return Keys.isMouse(code) ? Gdx.input.isButtonJustPressed(code - Keys.MOUSE) : Gdx.input.isKeyJustPressed(code); }

    @Override public int anyJustPressed() {
        for (int k = 1; k < 256; k++) if (Gdx.input.isKeyJustPressed(k)) return k;
        for (int b = 1; b <= 4; b++) if (Gdx.input.isButtonJustPressed(b)) return Keys.mouse(b);
        if (Gdx.input.isButtonJustPressed(Input.Buttons.LEFT)) return Keys.mouse(Input.Buttons.LEFT);
        return -1;
    }

    @Override public int mouseX() { return Gdx.input.getX(); }
    @Override public int mouseY() { return Gdx.input.getY(); }
    @Override public float takeScroll() { float s = scroll; scroll = 0f; return s; }
}

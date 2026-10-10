package com.hotatticgames.climbup.desktop;

import com.hotatticgames.climbup.ClimbGame;
import com.hotatticgames.climbup.SettingsScreen;
import com.hotatticgames.climbup.desktop.input.*;
import com.hotatticgames.climbup.platform.Platform;
import com.hotatticgames.climbup.ui.Ui;
import java.util.List;
import java.util.Locale;

/** The two extra Settings tabs of the desktop build, drawn with the game's own rows. */
final class DesktopTabs {
    private DesktopTabs() { }

    static String clip(String s, int n) { s = s.toUpperCase(Locale.ROOT); return s.length() > n ? s.substring(0, n - 1) + "." : s; }

    /** DISPLAY MODE and RESOLUTION. */
    static final class WindowTab implements Platform.SettingsTab {
        private final DesktopPlatform dp;
        WindowTab(DesktopPlatform dp) { this.dp = dp; }
        @Override public String name() { return "WINDOW"; }
        @Override public void draw(ClimbGame g, SettingsScreen s) {
            DesktopConfig c = dp.cfg;
            s.row("DISPLAY MODE", c.display == DesktopConfig.DisplayMode.FULLSCREEN ? "FULLSCREEN" : "WINDOWED", null, null, () -> { dp.display.toggle(c); dp.save(); });
            List<int[]> list = dp.display.available();
            int i = DisplayManager.nearest(list, c.width, c.height);
            boolean exact = list.get(i)[0] == c.width && list.get(i)[1] == c.height;
            String shown = c.width + " X " + c.height;
            s.row("RESOLUTION", shown, () -> { int[] r = list.get(Math.max(0, i - (exact ? 1 : 0))); dp.display.setResolution(c, r[0], r[1]); dp.save(); },
                    () -> { int[] r = list.get(Math.min(list.size() - 1, i + (exact ? 1 : 0))); dp.display.setResolution(c, r[0], r[1]); dp.save(); }, null);
            s.skip(8);
            Ui ui = g.ui;
            ui.textC("F11 OR ALT+ENTER SWITCHES BETWEEN WINDOWED AND FULLSCREEN.", ui.w() / 2, s.rowTop() + 30, 2.6f, Ui.DIM);
            s.skip(46);
            ui.textC("THE WINDOW CAN ALSO BE RESIZED BY DRAGGING ITS EDGES. THE GAME KEEPS ITS FRAMING.", ui.w() / 2, s.rowTop() + 30, 2.6f, Ui.DIM);
        }
    }

    /** INPUT DEVICE, CONNECTED CONTROLLER, bindings, dead zone, restore defaults. */
    static final class ControlsTab implements Platform.SettingsTab {
        private final DesktopPlatform dp;
        private boolean confirmRestore;
        ControlsTab(DesktopPlatform dp) { this.dp = dp; }
        @Override public String name() { return "CONTROLS"; }

        @Override public void draw(ClimbGame g, SettingsScreen s) {
            DesktopConfig c = dp.cfg; InputManager in = dp.input;
            DesktopConfig.InputMode[] modes = DesktopConfig.InputMode.values();
            s.row("INPUT DEVICE", c.inputMode.label(), null, null, () -> { c.inputMode = modes[(c.inputMode.ordinal() + 1) % modes.length]; dp.save(); });
            Pad p = in.activePad();
            int n = in.connectedPads().size();
            s.row("CONNECTED CONTROLLER", p == null ? "NONE DETECTED" : clip(p.name(), 24) + (n > 1 ? " (" + n + ")" : ""), null, null, in::cycleActivePad);
            s.row("KEYBOARD + MOUSE BINDINGS", "EDIT", null, null, () -> g.setScreen(new BindingsScreen(g, dp, s, false)));
            s.row("CONTROLLER BINDINGS", p == null ? "CONNECT ONE" : "EDIT", null, null, () -> { if (dp.input.activePad() != null) g.setScreen(new BindingsScreen(g, dp, s, true)); });
            s.row("CONTROLLER DEAD ZONE", Math.round(c.deadzone * 100) + "%", () -> { c.setDeadzone(c.deadzone - 0.05f); dp.save(); }, () -> { c.setDeadzone(c.deadzone + 0.05f); dp.save(); }, null);
            boolean pad = in.promptDevice() == InputManager.Device.CONTROLLER && p != null;
            s.row("RESTORE DEFAULTS", confirmRestore ? "CONFIRM" : pad ? "CONTROLLER" : "KEYBOARD", null, null, () -> {
                if (!confirmRestore) { confirmRestore = true; return; }
                confirmRestore = false;
                if (pad) c.restorePad(p.id()); else c.restoreKeyboard();
                dp.save();
            });
            s.skip(8);
            Ui ui = g.ui;
            ui.textC("KEYBOARD, MOUSE AND CONTROLLER ALL WORK AT ONCE. PROMPTS FOLLOW WHAT YOU USED LAST.", ui.w() / 2, s.rowTop() + 30, 2.6f, Ui.DIM);
        }
    }
}

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

    /** A dim help line under the rows, shrunk to fit the screen width. */
    static void note(Ui ui, SettingsScreen s, String text) {
        float px = Math.min(2.6f, (ui.w() - 60) / Math.max(1, text.length() * 6f));
        ui.textC(text, ui.w() / 2, s.rowTop() + 30, px, Ui.DIM); s.skip(46);
    }

    static String clip(String s, int n) { s = s.toUpperCase(Locale.ROOT); return s.length() > n ? s.substring(0, n - 1) + "." : s; }

    static int index(int[] list, int v) { for (int i = 0; i < list.length; i++) if (list[i] == v) return i; return 0; }
    static String fps(int v) { return v == 0 ? "UNLIMITED" : v + " FPS"; }
    static String level(int v, boolean zeroIsOff) { return v <= (zeroIsOff ? 0 : 1) ? "OFF" : v + "X"; }

    /** DISPLAY MODE, RESOLUTION, PICTURE SIZE, FRAME LIMIT, VSYNC. */
    static final class DisplayTab implements Platform.SettingsTab {
        private final DesktopPlatform dp;
        DisplayTab(DesktopPlatform dp) { this.dp = dp; }
        @Override public String name() { return "DISPLAY"; }
        @Override public void draw(ClimbGame g, SettingsScreen s) {
            DesktopConfig c = dp.cfg; GraphicsManager gm = dp.graphics;
            DesktopConfig.DisplayMode[] modes = DesktopConfig.DisplayMode.values();
            s.row("DISPLAY MODE", c.display.label(), null, null, () -> { dp.display.setMode(c, modes[(c.display.ordinal() + 1) % modes.length]); dp.save(); });
            boolean win = c.display == DesktopConfig.DisplayMode.WINDOWED;
            List<int[]> list = dp.display.available();
            String shown = win ? c.width + " X " + c.height : DisplayManager.pickLabel(c.modeW, c.modeH);
            s.row("RESOLUTION", shown,
                    () -> pick(c, DisplayManager.step(list, !win, win ? c.width : c.modeW, win ? c.height : c.modeH, false)),
                    () -> pick(c, DisplayManager.step(list, !win, win ? c.width : c.modeW, win ? c.height : c.modeH, true)), null);
            int si = index(DesktopConfig.SCALES, c.renderScale), fi = index(DesktopConfig.FRAME_LIMITS, c.frameLimit);
            s.row("PICTURE SIZE", c.renderScale + "%",
                    () -> { c.renderScale = DesktopConfig.SCALES[Math.max(0, si - 1)]; gm.apply(); dp.save(); },
                    () -> { c.renderScale = DesktopConfig.SCALES[Math.min(DesktopConfig.SCALES.length - 1, si + 1)]; gm.apply(); dp.save(); }, null);
            s.row("FRAME LIMIT", fps(c.frameLimit),
                    () -> { c.frameLimit = DesktopConfig.FRAME_LIMITS[Math.max(0, fi - 1)]; gm.apply(); dp.save(); },
                    () -> { c.frameLimit = DesktopConfig.FRAME_LIMITS[Math.min(DesktopConfig.FRAME_LIMITS.length - 1, fi + 1)]; gm.apply(); dp.save(); }, null);
            s.toggle("VSYNC", c.vsync, () -> { c.vsync = !c.vsync; gm.apply(); dp.save(); });
            s.skip(8);
            Ui ui = g.ui;
            int[] n = list.isEmpty() ? new int[]{0, 0} : dp.display.nativeSize();
            note(ui, s, win ? "WINDOWED: RESOLUTION IS THE WINDOW SIZE. F11 OR ALT+ENTER SWITCHES TO FULL SCREEN." : "BORDERLESS FILLS THE SCREEN AND ALT-TABS FAST. FULLSCREEN USES THE MONITOR MODE. THE SCREEN IS " + n[0] + " X " + n[1] + ".");
            note(ui, s, "PICTURE SIZE BELOW 100% DRAWS THE WORLD SMALLER AND STRETCHES IT. TEXT STAYS SHARP.");
        }

        private void pick(DesktopConfig c, int[] r) { dp.display.setResolution(c, r[0], r[1]); dp.save(); }
    }

    /** ANTI-ALIASING, TEXTURE SHARPNESS, SKY EFFECTS, USE THE FASTER GPU. */
    static final class GraphicsTab implements Platform.SettingsTab {
        private final DesktopPlatform dp;
        GraphicsTab(DesktopPlatform dp) { this.dp = dp; }
        @Override public String name() { return "GRAPHICS"; }
        @Override public void draw(ClimbGame g, SettingsScreen s) {
            DesktopConfig c = dp.cfg; GraphicsManager gm = dp.graphics;
            int ai = index(DesktopConfig.AA_LEVELS, c.aa), ti = index(DesktopConfig.SHARPNESS, c.anisotropy);
            s.row("ANTI-ALIASING", level(c.aa, true),
                    () -> { c.aa = DesktopConfig.AA_LEVELS[Math.max(0, ai - 1)]; gm.gfxRestartNeeded = true; dp.save(); },
                    () -> { c.aa = DesktopConfig.AA_LEVELS[Math.min(DesktopConfig.AA_LEVELS.length - 1, ai + 1)]; gm.gfxRestartNeeded = true; dp.save(); }, null);
            s.row("TEXTURE SHARPNESS", level(c.anisotropy, false),
                    () -> { c.anisotropy = DesktopConfig.SHARPNESS[Math.max(0, ti - 1)]; gm.apply(); dp.save(); },
                    () -> { c.anisotropy = DesktopConfig.SHARPNESS[Math.min(DesktopConfig.SHARPNESS.length - 1, ti + 1)]; gm.apply(); dp.save(); }, null);
            s.toggle("SKY EFFECTS", c.skyEffects, () -> { c.skyEffects = !c.skyEffects; gm.apply(); dp.save(); });
            s.toggle("USE THE FASTER GPU", c.highPerfGpu, () -> { c.highPerfGpu = !c.highPerfGpu; dp.save(); gm.applyGpuPreference(true); });
            s.skip(8);
            Ui ui = g.ui;
            if (gm.restartNeeded) note(ui, s, "CLOSE AND START THE GAME AGAIN FOR THE GPU CHOICE TO TAKE EFFECT.");
            else if (gm.gfxRestartNeeded) note(ui, s, "CLOSE AND START THE GAME AGAIN FOR ANTI-ALIASING TO CHANGE.");
            else note(ui, s, "ANTI-ALIASING SMOOTHS JAGGED EDGES. SKY EFFECTS ARE GLOW, LIGHT BEAMS AND FAR CLOUDS.");
            note(ui, s, gm.renderer.isEmpty() ? "GRAPHICS CHIP NOT REPORTED." : "GRAPHICS CHIP: " + clip(gm.renderer, 60) + (gm.activeSamples > 1 ? "  (" + gm.activeSamples + "X AA NOW)" : ""));
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
            note(ui, s, "KEYBOARD, MOUSE AND CONTROLLER ALL WORK AT ONCE. PROMPTS FOLLOW WHAT YOU USED LAST.");
        }
    }
}

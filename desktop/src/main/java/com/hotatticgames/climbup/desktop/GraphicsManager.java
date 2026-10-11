package com.hotatticgames.climbup.desktop;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Graphics;
import com.badlogic.gdx.graphics.GL20;
import com.hotatticgames.climbup.ClimbGame;
import com.hotatticgames.climbup.desktop.input.DesktopConfig;
import com.hotatticgames.climbup.render.GfxHooks;
import java.nio.IntBuffer;
import java.util.Locale;

/** Applies the Graphics tab to the running window and does the first-run auto-detect. Only changes how the picture is paced and drawn, never the simulation. */
public final class GraphicsManager {
    private final DesktopConfig cfg;
    /** What the graphics driver calls itself (shown in the Graphics tab); empty before the window exists. */
    public String renderer = "";
    /** True after the High-performance preference was written during this run: Windows only uses it from the next start. */
    public volatile boolean restartNeeded;
    private int refresh;
    /** Anti-aliasing samples the window really has (0 if unknown) and whether it is fewer than asked. */
    public int activeSamples;
    /** True when anti-aliasing or sharpness was changed this run: the new level only applies from the next start. */
    public volatile boolean gfxRestartNeeded;
    public DisplayManager display;

    public GraphicsManager(DesktopConfig cfg) { this.cfg = cfg; }

    /** Pushes the config into the window and the core drawing switches. Safe to call every time something changes. */
    public void apply() {
        float k = cfg.renderScale / 100f;
        if (cfg.display == DesktopConfig.DisplayMode.BORDERLESS && display != null && Gdx.graphics != null) {      // a smaller picked size in borderless: draw smaller and stretch to the screen
            try { int[] n = display.nativeSize(); k *= DisplayManager.borderlessScale(cfg.modeW, cfg.modeH, n[0], n[1]); } catch (RuntimeException ignored) { }
        }
        GfxHooks.renderScale = k;
        GfxHooks.skyEffects = cfg.skyEffects;
        GfxHooks.mipmaps = true; GfxHooks.anisotropy = cfg.anisotropy;
        try {
            if (Gdx.graphics != null) {
                Gdx.graphics.setVSync(cfg.vsync);
                Gdx.graphics.setForegroundFPS(GraphicsProfile.effectiveFrameLimit(cfg.vsync, cfg.frameLimit, refresh()));
            }
        } catch (RuntimeException e) { DesktopLog.append("graphics: could not apply vsync/frame limit: " + e); }
    }

    private int refresh() {
        if (refresh <= 0) { try { refresh = Gdx.graphics.getDisplayMode().refreshRate; } catch (RuntimeException ignored) { } }
        return refresh;
    }

    /** Called once when the window exists: reads the driver's name, picks defaults on the very first run, applies everything and starts the GPU preference. */
    public void start(ClimbGame game) {
        if (game != null && game.ui != null) game.ui.crisp = true;           // whole-pixel text on the desktop; Android keeps the original path
        try { renderer = String.valueOf(Gdx.gl.glGetString(GL20.GL_RENDERER)); } catch (RuntimeException e) { renderer = ""; }
        if (renderer.equals("null")) renderer = "";
        try { java.nio.IntBuffer b = com.badlogic.gdx.utils.BufferUtils.newIntBuffer(4); Gdx.gl.glGetIntegerv(0x80A9, b); activeSamples = b.get(0); Gdx.gl.glGetError(); } catch (RuntimeException ignored) { }       // GL_SAMPLES
        if (display != null && cfg.display != DesktopConfig.DisplayMode.WINDOWED) { try { DisplayManager.forgetUnlisted(cfg, display.available()); } catch (RuntimeException ignored) { } }
        if (!cfg.graphicsDecided) {
            Graphics.DisplayMode d = Gdx.graphics.getDisplayMode();
            long vram = videoMemoryMb();
            GraphicsProfile.Decision dec = GraphicsProfile.decide(renderer, vram, d.width, d.height, d.refreshRate);
            cfg.renderScale = dec.renderScale; cfg.skyEffects = dec.skyEffects; cfg.frameLimit = dec.frameLimit; cfg.vsync = true; cfg.aa = dec.aa; cfg.anisotropy = dec.anisotropy;
            if (game != null && game.settings != null) { game.settings.quality = dec.quality; try { game.store.saveSettings(game.settings); } catch (RuntimeException ignored) { } }
            cfg.graphicsDecided = true;
            DesktopLog.append("graphics auto-detect (first run): " + dec + "; display " + cfg.display + "; the window itself opened with " + activeSamples + "x anti-aliasing, so a different anti-aliasing level applies from the next start");
        } else DesktopLog.append("graphics: " + renderer + " | scale " + cfg.renderScale + "% | frame limit " + (cfg.frameLimit == 0 ? "none" : cfg.frameLimit) + " | vsync " + (cfg.vsync ? "on" : "off") + " | anti-aliasing " + cfg.aa + "x (window has " + activeSamples + ") | sharpness " + cfg.anisotropy + "x | display " + cfg.display + " " + DisplayManager.pickLabel(cfg.modeW, cfg.modeH) + " | sky effects " + (cfg.skyEffects ? "on" : "off") + " | high-performance GPU " + (cfg.highPerfGpu ? "on" : "off"));
        apply();
        applyGpuPreference(false);
    }

    /** Writes or removes the Windows High-performance preference for Upwardly.exe on a background thread; nothing here can stop the game. */
    public void applyGpuPreference(boolean fromSwitch) {
        final boolean on = cfg.highPerfGpu, windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
        final String exe = GpuPreference.currentExe();
        Thread t = new Thread(() -> {
            try {
                GpuPreference.Result r = GpuPreference.apply(exe, on, windows);
                DesktopLog.append("gpu preference (" + (on ? "high performance" : "windows default") + ") for " + exe + ": " + r);
                if (r == GpuPreference.Result.CHANGED) restartNeeded = true;
            } catch (Throwable ignored) { }
        }, "gpu-preference");
        t.setDaemon(true); t.start();
    }

    /** Video memory in MB when the driver says (NVIDIA and AMD extensions), else -1. */
    private static long videoMemoryMb() {
        try {
            IntBuffer b = com.badlogic.gdx.utils.BufferUtils.newIntBuffer(4);
            if (Gdx.graphics.supportsExtension("GL_NVX_gpu_memory_info")) { b.clear(); Gdx.gl.glGetIntegerv(0x9047, b); long kb = b.get(0); Gdx.gl.glGetError(); if (kb > 0) return kb / 1024; }       // GPU_MEMORY_INFO_DEDICATED_VIDMEM_NVX
            if (Gdx.graphics.supportsExtension("GL_ATI_meminfo")) { b.clear(); Gdx.gl.glGetIntegerv(0x87FB, b); long kb = b.get(0); Gdx.gl.glGetError(); if (kb > 0) return kb / 1024; }               // VBO_FREE_MEMORY_ATI (free, so a lower bound)
        } catch (RuntimeException ignored) { }
        return -1;
    }
}

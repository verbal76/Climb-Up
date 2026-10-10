package com.hotatticgames.climbup.desktop;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Graphics;
import com.hotatticgames.climbup.desktop.input.DesktopConfig;
import java.util.ArrayList;
import java.util.List;

/** Windowed / fullscreen and the resolution list. The simulation never sees any of it: only the window and the viewports change. */
public final class DisplayManager {
    private static final int[][] PRESETS = {{1280, 720}, {1366, 768}, {1600, 900}, {1920, 1080}, {2560, 1440}, {3840, 2160}};

    /** Resolutions to offer: the monitor's own modes plus common windowed sizes, all at least 1280x720 and no larger than the desktop, ascending. Pure so it can be tested. */
    public static List<int[]> resolutions(List<int[]> modes, int desktopW, int desktopH) {
        List<int[]> out = new ArrayList<>();
        for (int[] p : PRESETS) add(out, p[0], p[1], desktopW, desktopH);
        for (int[] m : modes) add(out, m[0], m[1], desktopW, desktopH);
        if (out.isEmpty()) out.add(new int[]{1280, 720});
        out.sort((a, b) -> a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(a[1], b[1]));
        return out;
    }

    private static void add(List<int[]> out, int w, int h, int dw, int dh) {
        if (w < 1280 || h < 720 || w > dw || h > dh) return;
        for (int[] o : out) if (o[0] == w && o[1] == h) return;
        out.add(new int[]{w, h});
    }

    /** Index of the listed resolution closest to w x h. */
    public static int nearest(List<int[]> list, int w, int h) {
        int best = 0; long bd = Long.MAX_VALUE;
        for (int i = 0; i < list.size(); i++) { long dx = list.get(i)[0] - w, dy = list.get(i)[1] - h, d = dx * dx + dy * dy; if (d < bd) { bd = d; best = i; } }
        return best;
    }

    public List<int[]> available() {
        List<int[]> modes = new ArrayList<>();
        for (Graphics.DisplayMode m : Gdx.graphics.getDisplayModes()) modes.add(new int[]{m.width, m.height});
        Graphics.DisplayMode d = Gdx.graphics.getDisplayMode();
        return resolutions(modes, d.width, d.height);
    }

    /** Puts the window into the state the config describes. */
    public void apply(DesktopConfig cfg) {
        if (cfg.display == DesktopConfig.DisplayMode.FULLSCREEN) {
            Graphics.DisplayMode best = null, desktop = Gdx.graphics.getDisplayMode();
            for (Graphics.DisplayMode m : Gdx.graphics.getDisplayModes())
                if (m.width == cfg.width && m.height == cfg.height && (best == null || m.refreshRate > best.refreshRate || (m.refreshRate == best.refreshRate && m.bitsPerPixel > best.bitsPerPixel))) best = m;
            Gdx.graphics.setFullscreenMode(best != null ? best : desktop);
        } else {
            Graphics.DisplayMode d = Gdx.graphics.getDisplayMode();
            Gdx.graphics.setWindowedMode(Math.min(cfg.width, d.width), Math.min(cfg.height, d.height));
        }
    }

    public void toggle(DesktopConfig cfg) {
        if (cfg.display == DesktopConfig.DisplayMode.WINDOWED) {          // remember the window size we are leaving
            if (!Gdx.graphics.isFullscreen()) { cfg.width = Gdx.graphics.getWidth(); cfg.height = Gdx.graphics.getHeight(); }
            cfg.display = DesktopConfig.DisplayMode.FULLSCREEN;
            Graphics.DisplayMode d = Gdx.graphics.getDisplayMode(); cfg.width = d.width; cfg.height = d.height;
        } else {
            cfg.display = DesktopConfig.DisplayMode.WINDOWED;
            if (cfg.width >= Gdx.graphics.getDisplayMode().width) { cfg.width = 1280; cfg.height = 720; }
        }
        apply(cfg);
    }

    public void setResolution(DesktopConfig cfg, int w, int h) { cfg.width = w; cfg.height = h; apply(cfg); }
}

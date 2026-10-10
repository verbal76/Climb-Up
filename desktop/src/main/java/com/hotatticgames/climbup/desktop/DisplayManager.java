package com.hotatticgames.climbup.desktop;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Graphics;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Graphics;
import com.hotatticgames.climbup.desktop.input.DesktopConfig;
import java.util.ArrayList;
import java.util.List;

/**
 * Windowed / full screen / borderless full screen and the resolution list. The simulation never sees any of it: only the window and the viewports change.
 * Modes are int[]{width, height, refreshHz}; a size pick of 0 x 0 means "the monitor's own" (NATIVE, AUTO).
 */
public final class DisplayManager {
    /** Smallest mode offered in the picker. */
    static final int MIN_W = 1024, MIN_H = 576;
    /** Window size used when none is remembered or the remembered one would fill the whole desktop. */
    static final int[] DEFAULT_WINDOW = {1280, 720};

    /** Set by the platform: runs after every display change so vsync, frame cap and drawing size are put back (a mode switch can reset them). */
    public Runnable after;

    // ------------------------------------------------------------------ pure logic (tested)

    /** The picker list from the monitor's real modes: one entry per width x height with its highest refresh rate, largest first. Never empty. */
    public static List<int[]> pickerList(List<int[]> modes) {
        List<int[]> out = new ArrayList<>();
        for (int[] m : modes) {
            if (m[0] < MIN_W || m[1] < MIN_H) continue;
            int at = -1;
            for (int i = 0; i < out.size(); i++) if (out.get(i)[0] == m[0] && out.get(i)[1] == m[1]) at = i;
            if (at < 0) out.add(new int[]{m[0], m[1], m[2]});
            else if (m[2] > out.get(at)[2]) out.get(at)[2] = m[2];
        }
        out.sort((a, b) -> a[0] != b[0] ? Integer.compare(b[0], a[0]) : Integer.compare(b[1], a[1]));
        if (out.isEmpty()) out.add(new int[]{DEFAULT_WINDOW[0], DEFAULT_WINDOW[1], 60});
        return out;
    }

    /** Is this exact size in the list? */
    public static boolean listed(List<int[]> list, int w, int h) { for (int[] m : list) if (m[0] == w && m[1] == h) return true; return false; }

    /** The size to use for full screen: the pick if the monitor still offers it, else the monitor's own. Returns {w, h, hz} (hz 0 = let the system choose). */
    public static int[] fullscreenMode(List<int[]> list, int pickW, int pickH, int[] nativeMode) {
        if (pickW > 0) for (int[] m : list) if (m[0] == pickW && m[1] == pickH) return m.clone();
        return nativeMode.clone();
    }

    /** What the picker shows for the current pick: NATIVE (AUTO) or "1920 X 1080". */
    public static String pickLabel(int w, int h) { return w <= 0 || h <= 0 ? "NATIVE (AUTO)" : w + " X " + h; }

    /**
     * One step in the picker. {@code withAuto}: the first entry is NATIVE (AUTO) (full screen and borderless; a window has no "auto").
     * {@code larger}: toward bigger sizes. Returns the new {w, h}; {0, 0} = auto. A size that is not listed (a window dragged to a custom size) first moves to the nearest listed one.
     */
    public static int[] step(List<int[]> list, boolean withAuto, int curW, int curH, boolean larger) {
        List<int[]> items = new ArrayList<>();
        if (withAuto) items.add(new int[]{0, 0});
        items.addAll(list);
        int i = -1;
        for (int k = 0; k < items.size(); k++) if (items.get(k)[0] == curW && items.get(k)[1] == curH) i = k;
        if (i < 0) i = nearest(items, withAuto ? 1 : 0, curW, curH);
        else i = Math.max(0, Math.min(items.size() - 1, i + (larger ? -1 : 1)));
        return new int[]{items.get(i)[0], items.get(i)[1]};
    }

    private static int nearest(List<int[]> items, int from, int w, int h) {
        int best = from; long bd = Long.MAX_VALUE;
        for (int i = from; i < items.size(); i++) { long dx = items.get(i)[0] - w, dy = items.get(i)[1] - h, d = dx * dx + dy * dy; if (d < bd) { bd = d; best = i; } }
        return best;
    }

    /** The window size to open: the remembered one, never larger than the desktop; one that would cover the whole desktop becomes the default size. */
    public static int[] windowSize(int w, int h, int desktopW, int desktopH) {
        if (w < 640 || h < 360 || w >= desktopW && h >= desktopH) { w = DEFAULT_WINDOW[0]; h = DEFAULT_WINDOW[1]; }
        return new int[]{Math.min(w, desktopW), Math.min(h, desktopH)};
    }

    /** Fraction of the native size the picture is drawn at in borderless mode for a picked size (1 = native or auto). */
    public static float borderlessScale(int pickW, int pickH, int nativeW, int nativeH) {
        if (pickW <= 0 || pickH <= 0 || nativeW <= 0 || nativeH <= 0) return 1f;
        return Math.min(1f, Math.min((float) pickW / nativeW, (float) pickH / nativeH));
    }

    /** The mode F11 switches to: a window goes to the last full-screen kind, anything else back to a window. */
    public static DesktopConfig.DisplayMode toggled(DesktopConfig cfg) {
        return cfg.display == DesktopConfig.DisplayMode.WINDOWED ? (cfg.lastFull == DesktopConfig.DisplayMode.WINDOWED ? DesktopConfig.DisplayMode.FULLSCREEN : cfg.lastFull) : DesktopConfig.DisplayMode.WINDOWED;
    }

    // ------------------------------------------------------------------ the running window

    private static List<int[]> modesOf(Graphics.DisplayMode[] modes) {
        List<int[]> l = new ArrayList<>();
        for (Graphics.DisplayMode m : modes) l.add(new int[]{m.width, m.height, m.refreshRate});
        return l;
    }

    /** Sizes the monitor supports, largest first. */
    public List<int[]> available() {
        try { return pickerList(modesOf(Gdx.graphics.getDisplayModes(Gdx.graphics.getMonitor()))); }
        catch (RuntimeException e) { return pickerList(modesOf(Gdx.graphics.getDisplayModes())); }
    }

    public int[] nativeSize() { Graphics.DisplayMode d = nativeMode(); return new int[]{d.width, d.height}; }

    private Graphics.DisplayMode nativeMode() {
        try { return Gdx.graphics.getDisplayMode(Gdx.graphics.getMonitor()); } catch (RuntimeException e) { return Gdx.graphics.getDisplayMode(); }
    }

    /** Puts the window into the state the config describes; on any failure it falls back to a plain window and says so in desktop.log. */
    public void apply(DesktopConfig cfg) {
        try { doApply(cfg); }
        catch (RuntimeException | LinkageError e) {
            DesktopLog.error("display mode " + cfg.display + " failed; falling back to a window", e instanceof RuntimeException ? e : new RuntimeException(e));
            cfg.display = DesktopConfig.DisplayMode.WINDOWED;
            try { Gdx.graphics.setUndecorated(false); Gdx.graphics.setWindowedMode(DEFAULT_WINDOW[0], DEFAULT_WINDOW[1]); } catch (RuntimeException ignored) { }
        }
        if (after != null) { try { after.run(); } catch (RuntimeException ignored) { } }
    }

    private void doApply(DesktopConfig cfg) {
        Graphics.DisplayMode nat = nativeMode();
        if (cfg.display == DesktopConfig.DisplayMode.FULLSCREEN) {
            List<int[]> list = available();
            forgetUnlisted(cfg, list);
            int[] want = fullscreenMode(list, cfg.modeW, cfg.modeH, new int[]{nat.width, nat.height, nat.refreshRate});
            Graphics.DisplayMode best = null;
            for (Graphics.DisplayMode m : Gdx.graphics.getDisplayModes(Gdx.graphics.getMonitor()))
                if (m.width == want[0] && m.height == want[1] && (want[2] <= 0 || m.refreshRate == want[2]) && (best == null || m.bitsPerPixel > best.bitsPerPixel)) best = m;
            if (!Gdx.graphics.setFullscreenMode(best != null ? best : nat)) throw new IllegalStateException("setFullscreenMode refused");
            DesktopLog.append("display: full screen " + (best != null ? best.width + "x" + best.height + " @" + best.refreshRate : "native"));
        } else if (cfg.display == DesktopConfig.DisplayMode.BORDERLESS) {
            Gdx.graphics.setUndecorated(true);
            Gdx.graphics.setWindowedMode(nat.width, nat.height);
            if (Gdx.graphics instanceof Lwjgl3Graphics) {
                Graphics.Monitor mon = Gdx.graphics.getMonitor();
                ((Lwjgl3Graphics) Gdx.graphics).getWindow().setPosition(mon.virtualX, mon.virtualY);
            }
            forgetUnlisted(cfg, available());
            DesktopLog.append("display: borderless " + nat.width + "x" + nat.height);
        } else {
            int[] sz = windowSize(cfg.width, cfg.height, nat.width, nat.height);
            Gdx.graphics.setUndecorated(false);
            Gdx.graphics.setWindowedMode(sz[0], sz[1]);
            cfg.width = sz[0]; cfg.height = sz[1];
        }
    }

    /** A saved size the monitor no longer offers (another monitor, another cable) goes back to NATIVE (AUTO). */
    static void forgetUnlisted(DesktopConfig cfg, List<int[]> list) {
        if (cfg.modeW > 0 && !listed(list, cfg.modeW, cfg.modeH)) { DesktopLog.append("display: saved size " + cfg.modeW + "x" + cfg.modeH + " is not offered any more; using the monitor's own"); cfg.modeW = 0; cfg.modeH = 0; }
    }

    /** F11 / Alt+Enter. */
    public void toggle(DesktopConfig cfg) {
        if (cfg.display != DesktopConfig.DisplayMode.WINDOWED) cfg.lastFull = cfg.display;
        cfg.display = toggled(cfg);
        apply(cfg);
    }

    public void setMode(DesktopConfig cfg, DesktopConfig.DisplayMode m) {
        if (m == cfg.display) return;
        if (cfg.display != DesktopConfig.DisplayMode.WINDOWED) cfg.lastFull = cfg.display;
        cfg.display = m;
        apply(cfg);
    }

    /** The picker: in a window it sets the window size, in full screen the monitor mode, in borderless the size the picture is drawn at. */
    public void setResolution(DesktopConfig cfg, int w, int h) {
        if (cfg.display == DesktopConfig.DisplayMode.WINDOWED) { cfg.width = w; cfg.height = h; }
        else { cfg.modeW = w; cfg.modeH = h; }
        apply(cfg);
    }

    /** Startup: describes the first window to the launcher configuration (before any window or GL context exists). */
    public static void configureStartup(Lwjgl3ApplicationConfiguration c, DesktopConfig cfg, int devW, int devH) {
        Graphics.DisplayMode nat = Lwjgl3ApplicationConfiguration.getDisplayMode();
        if (devW > 0) { c.setWindowedMode(devW, devH); return; }
        if (cfg.display == DesktopConfig.DisplayMode.FULLSCREEN) {
            List<int[]> list = pickerList(modesOf(Lwjgl3ApplicationConfiguration.getDisplayModes()));
            int[] want = fullscreenMode(list, cfg.modeW, cfg.modeH, new int[]{nat.width, nat.height, nat.refreshRate});
            Graphics.DisplayMode best = nat;
            for (Graphics.DisplayMode m : Lwjgl3ApplicationConfiguration.getDisplayModes())
                if (m.width == want[0] && m.height == want[1] && (want[2] <= 0 || m.refreshRate == want[2])) best = m;
            c.setFullscreenMode(best);
        } else if (cfg.display == DesktopConfig.DisplayMode.BORDERLESS) {
            c.setDecorated(false);
            c.setWindowedMode(nat.width, nat.height);
            Graphics.Monitor mon = Lwjgl3ApplicationConfiguration.getPrimaryMonitor();
            c.setWindowPosition(mon.virtualX, mon.virtualY);
        } else {
            int[] sz = windowSize(cfg.width, cfg.height, nat.width, nat.height);
            c.setWindowedMode(sz[0], sz[1]);
        }
    }
}

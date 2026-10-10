package com.hotatticgames.climbup.desktop;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputMultiplexer;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.graphics.Color;
import com.hotatticgames.climbup.ClimbGame;
import com.hotatticgames.climbup.PlayScreen;
import com.hotatticgames.climbup.desktop.input.*;
import com.hotatticgames.climbup.platform.GameInput;
import com.hotatticgames.climbup.platform.Platform;
import com.hotatticgames.climbup.ui.Ui;
import java.io.File;
import com.badlogic.gdx.InputAdapter;
import java.util.Arrays;
import java.util.function.Supplier;
import java.util.List;

/** The Windows launcher's services: keyboard, mouse and controller input, no touch overlay, no network, the Upwardly name, and the extra Settings tabs. */
public final class DesktopPlatform implements Platform {
    public static final String TITLE = "Upwardly";

    public final File configFile;
    public final DesktopConfig cfg;
    public Sources sources;                       // created in attach(): the controller library needs the running application
    private final Supplier<Sources> sourceFactory;
    private final InputAdapter noScroll = new InputAdapter();
    public InputManager input;
    public final DisplayManager display = new DisplayManager();
    private MenuNav nav;
    private ClimbGame g;
    private float hintT, saveClock;
    private InputManager.Device lastHintDevice = InputManager.Device.KEYBOARD;
    private boolean dirty;
    private int frames; private long startNs = System.nanoTime(), lastNs; private double sumMs, maxMs;
    private final StringBuilder startupLog = new StringBuilder();

    public DesktopPlatform(File dataDir) { this(dataDir, GdxSources::new); }

    /** @param sourceFactory the keyboard/mouse/controller source (tests supply scripted ones). Called from attach(), on the render thread, once the application exists. */
    public DesktopPlatform(File dataDir, Supplier<Sources> sourceFactory) {
        this.sourceFactory = sourceFactory;
        configFile = new File(dataDir, "upwardly-desktop.cfg");
        cfg = DesktopConfig.load(configFile);
    }

    @Override public boolean desktop() { return true; }
    @Override public boolean touchControls() { return false; }
    @Override public boolean networkAllowed() { return false; }
    @Override public String title() { return TITLE; }
    @Override public GameInput gameInput() { return input; }

    @Override public void attach(ClimbGame game) {
        DesktopLog.append("window and GL context created; attaching input");
        g = game; sources = sourceFactory.get(); input = new InputManager(cfg, sources); nav = new MenuNav(input);
        game.ui.nav = nav;
        if (cfg.display == DesktopConfig.DisplayMode.FULLSCREEN) { /* the launcher already opened the window fullscreen */ }
    }

    /** desktop.log in the data folder: startup time and frame pacing of this run (the evidence the packaged build can give without a console). */
    private void trace(long nowNs) {
        frames++;
        if (frames == 1) {
            startupLog.append("first frame ").append((nowNs - startNs) / 1_000_000L).append(" ms after the launcher started\n");
            try { startupLog.append("gpu: ").append(Gdx.gl.glGetString(com.badlogic.gdx.graphics.GL20.GL_RENDERER)).append(" | ").append(Gdx.gl.glGetString(com.badlogic.gdx.graphics.GL20.GL_VERSION)).append(" | backbuffer ").append(Gdx.graphics.getBackBufferWidth()).append("x").append(Gdx.graphics.getBackBufferHeight()).append(Gdx.graphics.isFullscreen() ? " fullscreen" : " windowed").append("\n"); } catch (RuntimeException ignored) { }
        }
        else if (lastNs != 0) {
            double ms = (nowNs - lastNs) / 1e6;
            if (frames > 10) { sumMs += ms; maxMs = Math.max(maxMs, ms); }
            if (frames > 10 && ms > 200 && hitches < 25) { hitches++; DesktopLog.append(String.format(java.util.Locale.ROOT, "hitch %.0f ms at frame %d (%.1f s after start)", ms, frames, (nowNs - startNs) / 1e9)); }      // what a freeze looked like, so it can be traced
        }
        lastNs = nowNs;
        if (frames == 600) { startupLog.append(String.format(java.util.Locale.ROOT, "frames 11..600: avg %.2f ms, max %.2f ms\n", sumMs / 590.0, maxMs)); flushLog(); }
        if (frames == 1) flushLog();
    }
    private int hitches;
    private void flushLog() { DesktopLog.append(startupLog.toString().trim()); startupLog.setLength(0); }

    @Override public void frame(float dt) {
        trace(System.nanoTime());
        if (DesktopLauncher.exitAfter > 0f && (System.nanoTime() - startNs) / 1e9f > DesktopLauncher.exitAfter) { DesktopLauncher.exitAfter = 0f; DesktopLog.append("exit requested by --exit-after"); Gdx.app.exit(); }
        input.update(Math.min(dt, 0.1f));
        if (sources.justPressed(Input.Keys.F11) || (sources.justPressed(Input.Keys.ENTER) && (sources.down(Input.Keys.ALT_LEFT) || sources.down(Input.Keys.ALT_RIGHT)))) { display.toggle(cfg); save(); }
        if (cfg.display == DesktopConfig.DisplayMode.WINDOWED && !Gdx.graphics.isFullscreen()) {
            int w = Gdx.graphics.getWidth(), h = Gdx.graphics.getHeight();
            if (w >= 640 && h >= 360 && (w != cfg.width || h != cfg.height)) { cfg.width = w; cfg.height = h; dirty = true; }
        }
        saveClock += dt;
        if (dirty && saveClock > 2f) { saveClock = 0f; save(); }
        InputManager.Device d = input.promptDevice();
        if (d != lastHintDevice) { lastHintDevice = d; hintT = Math.max(hintT, 6f); }
    }

    /** The mouse-wheel tap to put in front of a screen's own input processor. */
    public InputProcessor scrollTap() { return sources instanceof GdxSources ? ((GdxSources) sources).scrollTap : noScroll; }

    /** Persists the desktop settings now (atomic; a failed write leaves the previous file). */
    public void save() { dirty = false; cfg.save(configFile); }

    @Override public void screenChanged() {
        nav.reset();
        InputProcessor cur = Gdx.input.getInputProcessor();
        Gdx.input.setInputProcessor(new InputMultiplexer(scrollTap(), cur));
        if (g != null && g.getScreen() instanceof PlayScreen) hintT = 14f;
    }

    @Override public String tip(String text) {
        return Prompts.rewrite(text, input.prompt(Act.JUMP), input.prompt(Act.SWING), input.promptMove(), input.prompt(Act.CLIMB_UP), input.prompt(Act.CLIMB_DOWN), input.prompt(Act.LEFT), input.prompt(Act.RIGHT));
    }

    @Override public void drawPlayHints(Ui ui, boolean club, float W, float H) {
        float dt = Gdx.graphics.getDeltaTime();
        hintT = Math.max(0f, hintT - dt);
        if (hintT <= 0f) return;
        StringBuilder sb = new StringBuilder();
        sb.append(input.promptMove()).append(" MOVE    ").append(input.prompt(Act.JUMP)).append(" JUMP");
        if (input.promptDevice() == InputManager.Device.KEYBOARD) sb.append("    ").append(input.prompt(Act.CLIMB_UP)).append('/').append(input.prompt(Act.CLIMB_DOWN)).append(" CLIMB");
        if (club) sb.append("    ").append(input.prompt(Act.SWING)).append(" SWING");
        sb.append("    ").append(input.prompt(Act.PAUSE)).append(" PAUSE");
        String s = sb.toString();
        float px = Math.min(3.2f * Math.min(ui.tm(), 1.3f), (W - 80) / Math.max(1, s.length() * 6f)), w = ui.font.width(s, px) + 40, a = Math.min(1f, hintT / 1.5f);
        ui.rect(W / 2 - w / 2, 22, w, ui.font.height(px) + 28, new Color(0.05f, 0.07f, 0.14f, 0.55f * a));
        ui.text(s, W / 2 - w / 2 + 20, 36, px, new Color(1f, 0.98f, 0.92f, a));
    }

    @Override public boolean drawPauseButton(Ui ui, float x, float y, float size) {
        String k = input.prompt(Act.PAUSE);
        ui.rect(x - 3, y - 3, size + 6, size + 6, Ui.EDGE); ui.rect(x, y, size, size, Ui.PANEL);
        float px = Math.max(2f, Math.min(4.4f, (size - 14) / Math.max(1, k.length() * 6f)));
        ui.textC(k, x + size / 2, y + size / 2 + 4, px, Ui.TEXT);
        ui.textC("PAUSE", x + size / 2, y + 14, 2.4f, Ui.DIM);
        return true;
    }

    @Override public List<SettingsTab> settingsTabs() { return Arrays.<SettingsTab>asList(new DesktopTabs.WindowTab(this), new DesktopTabs.ControlsTab(this)); }

    @Override public void dispose() { save(); }
}

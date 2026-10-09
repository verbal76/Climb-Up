package com.hotatticgames.climbup.synthetic;

import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.GL20;
import com.hotatticgames.climbup.spi.GameModule;
import com.hotatticgames.climbup.spi.HostEnv;
import java.io.InputStream;
import java.util.Properties;

/**
 * Synthetic proof module (NOT Climb Up). It exists only to prove the delivery mechanism: it is loaded from a signed bundle, receives lifecycle and input through the stock libGDX
 * backend, renders, reads a packaged file, and reports what it saw through HostEnv.diag. Behaviour comes from /synthetic.properties inside the bundle so one build yields v1, v2 and
 * deliberately broken variants:
 *   mode=ok            confirms healthy after 120 frames
 *   mode=throwCreate   throws from create() (a module that cannot start)
 *   mode=crashRender   throws from render() after 30 frames and never confirms (a module that starts and then crashes the process)
 */
public final class SyntheticModule implements GameModule {
    @Override public int interfaceVersion() { return INTERFACE_VERSION; }

    @Override public ApplicationListener create(HostEnv env) {
        Properties p = new Properties();
        try (InputStream in = SyntheticModule.class.getResourceAsStream("/synthetic.properties")) { if (in != null) p.load(in); } catch (Exception ignored) { }
        String mode = p.getProperty("mode", "ok");
        if (mode.equals("throwCreate")) throw new IllegalStateException("synthetic module refuses to start");
        return new Game(env, p.getProperty("version", "?"), Float.parseFloat(p.getProperty("hue", "0.2")), mode);
    }

    static final class Game implements ApplicationListener {
        private final HostEnv env; private final String version, mode; private final float hue;
        private int frames; private boolean touched;
        Game(HostEnv env, String version, float hue, String mode) { this.env = env; this.version = version; this.hue = hue; this.mode = mode; }

        @Override public void create() {
            env.diag("module " + version + " create; gdx=" + (Gdx.app != null) + " dataDir=" + env.dataDir().getName());
            env.diag("module " + version + " class loader " + getClass().getClassLoader().getClass().getSimpleName() + ", gdx loader " + Gdx.class.getClassLoader().getClass().getSimpleName());
            if (env.moduleDir() != null) {
                FileHandle h = new FileHandle(new java.io.File(env.moduleDir(), "hello.txt"));
                env.diag("module " + version + " asset: " + (h.exists() ? h.readString("UTF-8").trim() : "MISSING"));
            }
            Gdx.input.setInputProcessor(new com.badlogic.gdx.InputAdapter() {
                @Override public boolean touchDown(int x, int y, int ptr, int btn) { touched = true; env.diag("module " + version + " touch " + x + "," + y); return true; }
            });
        }
        @Override public void resize(int w, int h) { env.diag("module " + version + " resize " + w + "x" + h); }
        @Override public void render() {
            if (frames == 0) env.diag("module " + version + " first frame");
            frames++;
            float c = touched ? 1f : 0.35f;
            Gdx.gl.glClearColor(hue * c, 0.4f * c, (1f - hue) * c, 1f); Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
            if (mode.equals("crashRender") && frames > 30) throw new IllegalStateException("synthetic module crashes while running");
            if (frames == 120) { env.diag("module " + version + " healthy after 120 frames"); env.confirmHealthy(); }
        }
        @Override public void pause() { env.diag("module " + version + " pause"); }
        @Override public void resume() { env.diag("module " + version + " resume"); }
        @Override public void dispose() { env.diag("module " + version + " dispose"); }
    }
}

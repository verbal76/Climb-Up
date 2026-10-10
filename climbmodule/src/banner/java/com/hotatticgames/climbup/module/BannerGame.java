package com.hotatticgames.climbup.module;

import com.badlogic.gdx.graphics.Color;
import com.hotatticgames.climbup.TitleScreen;
import com.hotatticgames.climbup.spi.HostEnv;
import com.hotatticgames.climbup.ui.Ui;

/**
 * The delivered title-screen mark of the live-update test: the module game plus one ribbon drawn over the title screen only. It overrides one public method (the title screen calls
 * autoShot once per frame after its own drawing) and touches no game state: no gameplay, physics, controls, saves or generation. Reversible by publishing the plain module again.
 */
final class BannerGame extends ModuleGame {
    private final String label;
    private final Color band = new Color(0.93f, 0.10f, 0.62f, 1f), line = new Color(1f, 1f, 1f, 1f);

    BannerGame(HostEnv env, String label) { super(env); this.label = label; }

    @Override public void autoShot(String prefix, float dt) {
        if ("title".equals(prefix) && getScreen() instanceof TitleScreen) drawBanner();
        super.autoShot(prefix, dt);
    }

    private void drawBanner() {
        Ui u = ui; if (u == null) return;
        float w = u.w(), h = u.h();
        u.viewport.apply(); u.batch.setProjectionMatrix(u.viewport.getCamera().combined); u.batch.begin();
        u.rect(0, h - 70, w, 70, band); u.rect(0, h - 76, w, 6, line);
        u.textC(label, w / 2f, h - 48, 3.6f, Color.WHITE);
        u.batch.end();
    }
}

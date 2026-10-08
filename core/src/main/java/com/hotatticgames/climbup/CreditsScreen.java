package com.hotatticgames.climbup;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.InputMultiplexer;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.ScreenAdapter;
import com.badlogic.gdx.graphics.GL20;
import com.hotatticgames.climbup.ui.Ui;

/** Credits and licenses (all external assets are CC0 or Apache-2.0; see ASSETS.md and assets/licenses). */
public final class CreditsScreen extends ScreenAdapter {
    private final ClimbGame g; private final Screen back;
    private static final String[] LINES = {
        "CLIMB UP  -  HOT ATTIC GAMES",
        "",
        "STUDIO LOGO: HOT ATTIC GAMES (OWNER SUPPLIED)",
        "ROBOT: CUTE ROBOT BY FOOZLE, COMMISSIONED FROM MAYAKHAN95 (CC0)",
        "BLOCKS AND PROPS: KENNEY PLATFORMER KIT 3.0 (CC0) - KENNEY.NL",
        "ENGINE: LIBGDX (APACHE-2.0)",
        "MUSIC, SOUND EFFECTS, FONT, TITLE ART, ROPES, CABLES, PADS:",
        "ORIGINAL, GENERATED IN CODE FOR THIS GAME",
        "",
        "FULL LICENSE TEXTS ARE SHIPPED IN ASSETS/LICENSES",
    };
    public CreditsScreen(ClimbGame g, Screen back) { this.g = g; this.back = back; }
    @Override public void show() { Gdx.input.setInputProcessor(new InputMultiplexer(g.ui)); }
    @Override public void resize(int w, int h) { g.ui.resize(w, h); }
    @Override public void render(float dt) {
        Gdx.gl.glClearColor(0.06f, 0.08f, 0.16f, 1f); Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        Ui ui = g.ui; ui.begin();
        float W = ui.w(), H = ui.h();
        ui.textC("CREDITS", W / 2, H - 80, 7f, Ui.TEXT);
        float y = H - 170; float px = 3.2f * ui.tm();
        for (String l : LINES) { ui.textC(l, W / 2, y, Math.min(px, (W - 80) / Math.max(1, l.length() * 6f)), l.startsWith("CLIMB") ? Ui.ACCENT : Ui.TEXT); y -= 46 * Math.min(ui.tm(), 1.3f); }
        if (ui.button("BACK", W / 2 - 150, 22, 300, 78, true)) { g.audio.play("click"); g.setScreen(back); }
        ui.end();
        g.autoShot("credits", dt);
    }
}

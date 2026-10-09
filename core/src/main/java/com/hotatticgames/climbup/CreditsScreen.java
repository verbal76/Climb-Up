package com.hotatticgames.climbup;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.InputMultiplexer;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.ScreenAdapter;
import com.badlogic.gdx.graphics.GL20;
import com.hotatticgames.climbup.ui.Ui;

/** Credits and licenses (every outside creator is credited, whether or not the licence requires it; see ASSETS.md and assets/licenses). */
public final class CreditsScreen extends ScreenAdapter {
    private final ClimbGame g; private final Screen back;
    private static final String[] LINES = {
        "CLIMB UP  -  HOT ATTIC GAMES",
        "",
        "GAME DESIGN: KEVIN, WITH HIS OWN GAME DESIGNER SOFTWARE",
        "STUDIO LOGO: HOT ATTIC GAMES",
        "HERO, HAZARDS, CRAB, BEE: ULTIMATE PLATFORMER PACK BY QUATERNIUS (CC0)",
        "ASTRONAUTS, RAMP, PLANETS, ROCKS, SHIPS: ULTIMATE SPACE KIT BY QUATERNIUS (CC0)",
        "BLOCKS AND PROPS: KENNEY PLATFORMER KIT 3.0 BY KENNEY - KENNEY.NL (CC0)",
        "SOUND EFFECTS: RETRO ACTION PLATFORMER SFX BY JEAGERONI (ITCH.IO)",
        "MUSIC: GAMEPLAY TRACKS MADE WITH SUNO",
        "APP ICON ROBOT: FOOZLE, ART BY MAYAKHAN95 (CC0)",
        "HAMSTER HERO AND FALLING SOUND: SUPPLIED BY THE OWNER",
        "ENGINE: LIBGDX (APACHE-2.0)",
        "MENU MUSIC, FONT, TITLE ART, ROPES, CABLES, PADS: ORIGINAL, MADE IN CODE",
        "",
        "THANK YOU TO EVERY CREATOR. LICENSE TEXTS: ASSETS/LICENSES",
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
        float step = Math.min(46 * Math.min(ui.tm(), 1.3f), (H - 170 - 130) / (LINES.length - 1));       // always leave room for the BACK button, whatever the screen shape
        for (String l : LINES) { ui.textC(l, W / 2, y, Math.min(px, (W - 80) / Math.max(1, l.length() * 6f)), l.startsWith("CLIMB") ? Ui.ACCENT : Ui.TEXT); y -= step; }
        if (ui.button("BACK", W / 2 - 150, 22, 300, 78, true)) { g.audio.play("click"); g.setScreen(back); }
        ui.end();
        g.autoShot("credits", dt);
    }
}

package com.hotatticgames.climbup.render;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.TextureRegion;

/**
 * Build 67 (owner): the small picture of each selectable character shown beside the CHARACTER row in settings, so you see who you are choosing.
 * The pictures are {@code ui/portraits/N.png}, shot from the real rig by the desktop {@code PortraitShot} harness ({@code ./gradlew :desktop:portraits});
 * a missing or unreadable file simply shows nothing. Textures load on first use and are managed (reloaded after an Android context loss).
 */
public final class Portraits {
    private Portraits() {}
    private static final TextureRegion[] CACHE = new TextureRegion[Characters.COUNT];
    private static final boolean[] TRIED = new boolean[Characters.COUNT];

    /** The portrait of character {@code c}, or null when there is none. */
    public static TextureRegion of(int c) {
        c = Characters.clamp(c);
        if (!TRIED[c]) {
            TRIED[c] = true;
            try {
                Texture tex = new Texture(Gdx.files.internal("ui/portraits/" + c + ".png"));
                tex.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
                CACHE[c] = new TextureRegion(tex);
            } catch (RuntimeException e) { CACHE[c] = null; }
        }
        return CACHE[c];
    }

    /** Frees the loaded portraits (the game is closing). */
    public static void dispose() {
        for (int i = 0; i < CACHE.length; i++) { if (CACHE[i] != null) CACHE[i].getTexture().dispose(); CACHE[i] = null; TRIED[i] = false; }
    }
}

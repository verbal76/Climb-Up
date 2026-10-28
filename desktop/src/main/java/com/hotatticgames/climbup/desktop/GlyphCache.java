package com.hotatticgames.climbup.desktop;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.utils.Disposable;
import java.util.HashMap;

/** Loads a glyph image on first use (128 px PNGs, drawn with linear filtering so they stay smooth at any window size) and keeps it; a file that cannot be loaded is remembered as missing so the caller falls back to text. */
final class GlyphCache implements Disposable {
    private final HashMap<String, TextureRegion> regions = new HashMap<>();

    /** The image for an asset path, or null when there is none (missing file, or a null path). */
    TextureRegion get(String path) {
        if (path == null) return null;
        if (regions.containsKey(path)) return regions.get(path);
        TextureRegion r = null;
        try {
            Texture t = new Texture(Gdx.files.internal(path));
            t.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
            r = new TextureRegion(t);
        } catch (RuntimeException e) { DesktopLog.append("glyph missing: " + path); }
        regions.put(path, r);
        return r;
    }

    @Override public void dispose() {
        for (TextureRegion r : regions.values()) if (r != null) r.getTexture().dispose();
        regions.clear();
    }
}

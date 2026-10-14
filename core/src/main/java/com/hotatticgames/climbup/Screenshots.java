package com.hotatticgames.climbup;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * The player's screenshots (Build 65): the back buffer exactly as drawn, at its real size, as a PNG with a timestamped name that is never reused.
 * The capture runs on the render thread right after the screen has drawn its frame (the "SCREENSHOT SAVED" line is drawn afterwards, so it is never in
 * the picture); the PNG encoding and the disk write run on a worker thread so the frame never waits for them.
 */
public final class Screenshots {
    private Screenshots() { }
    public static final String PREFIX = "upwardly-";

    /** "upwardly-20261014-153059.png" for the moment given (local time). */
    public static String fileName(long millis) { return PREFIX + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date(millis)) + ".png"; }

    /** The file to write in {@code dir}: {@code name}, or {@code name} with "-2", "-3", ... before ".png" when that already exists. Never an existing file. */
    public static File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        String stem = name.endsWith(".png") ? name.substring(0, name.length() - 4) : name;
        for (int i = 2; ; i++) { File c = new File(dir, stem + "-" + i + ".png"); if (!c.exists()) return c; }
    }

    /** The back buffer with its top row first (the frame buffer is read bottom row first). Render thread only; the caller disposes the result. */
    public static Pixmap captureUpright() {
        int w = Gdx.graphics.getBackBufferWidth(), h = Gdx.graphics.getBackBufferHeight();
        Pixmap raw = Pixmap.createFromFrameBuffer(0, 0, w, h);
        Pixmap pm = new Pixmap(w, h, Pixmap.Format.RGBA8888);
        for (int row = 0; row < h; row++) pm.drawPixmap(raw, 0, row, w, 1, 0, h - 1 - row, w, 1);
        raw.dispose();
        return pm;
    }

    /** The PNG bytes of a pixmap, rows in the pixmap's own order (pure Java: safe off the render thread). */
    public static byte[] encodePng(Pixmap pm) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(pm.getWidth() * pm.getHeight() / 2 + 1024);
        PixmapIO.PNG png = new PixmapIO.PNG((int) (pm.getWidth() * pm.getHeight() * 1.5f));
        try { png.setFlipY(false); png.write(out, pm); } finally { png.dispose(); }
        return out.toByteArray();
    }

    /** Writes the bytes to a new file in {@code dir} (created as needed), never over an existing one. Returns the file written. */
    public static File writeTo(File dir, String name, byte[] png) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) throw new IOException("cannot create " + dir);
        File f = uniqueFile(dir, name);
        try (OutputStream o = new FileOutputStream(f)) { o.write(png); }
        return f;
    }

    /** A folder path as the pixel font can print it (upper case, forward slashes), shortened from the left to {@code max} characters. */
    public static String printable(String path, int max) {
        String p = path.replace('\\', '/').toUpperCase(Locale.ROOT);
        return p.length() <= max ? p : "..." + p.substring(p.length() - (max - 3));
    }
}

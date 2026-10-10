package com.hotatticgames.climbup.host;

import com.badlogic.gdx.Files;
import com.badlogic.gdx.files.FileHandle;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileFilter;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Installed as {@code Gdx.files} by the host when the active module carries asset overrides. The game keeps calling {@code Gdx.files.internal(path)}: a path with a verified override is
 * served from the content store, every other path comes from the APK exactly as before. With no overrides the host does not install it at all, so an un-updated install runs the very
 * same file code as the packaged game.
 * <ul>
 * <li>Audio (.wav/.ogg/.mp3) is never wrapped: an override is the backend's own absolute handle and every other audio file is the backend's own internal handle, because the Android audio classes cast to that type.</li>
 * <li>Everything else is an {@link OverlayHandle} that keeps the LOGICAL path (loaders derive texture/material paths from it) and re-resolves {@code child/sibling/parent} through this overlay,
 *     so a model served from the store still finds the textures that stayed in the APK, and the reverse.</li>
 * </ul>
 */
public final class OverlayFiles implements Files {
    private final Files base; private final Map<String, AssetManifest.Asset> overrides; private final AssetStore store; private final Consumer<String> diag;

    public OverlayFiles(Files base, Map<String, AssetManifest.Asset> overrides, AssetStore store, Consumer<String> diag) { this.base = base; this.overrides = overrides; this.store = store; this.diag = diag; }

    /**
     * Canonical spelling of a request: Windows separators become '/', empty and "." segments vanish, "x/.." cancels. A ".." that would climb above the root is kept, so such a request can
     * never equal an override key (keys are plain names) and is left to the backend exactly as before. Case is NOT folded: the APK's asset lookup is case-sensitive.
     */
    static String norm(String path) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        for (String seg : path.replace('\\', '/').split("/")) {
            if (seg.isEmpty() || seg.equals(".")) continue;
            if (seg.equals("..") && !out.isEmpty() && !out.get(out.size() - 1).equals("..")) out.remove(out.size() - 1); else out.add(seg);
        }
        return String.join("/", out);
    }
    private static boolean isAudio(String p) { String l = p.toLowerCase(java.util.Locale.ROOT); return l.endsWith(".wav") || l.endsWith(".ogg") || l.endsWith(".mp3"); }

    @Override public FileHandle internal(String path) {
        String p = norm(path);
        AssetManifest.Asset a = overrides.get(p);
        File f = a == null ? null : store.openVerified(a);
        if (a != null && f == null) diag.accept("override unusable, using the APK copy: " + p);
        if (isAudio(p)) return f != null ? base.absolute(f.getAbsolutePath()) : base.internal(path);        // the Android audio classes cast to the backend's own handle: audio never gets a wrapper
        return new OverlayHandle(p, f, base.internal(path));
    }
    @Override public FileHandle getFileHandle(String path, FileType type) { return type == FileType.Internal ? internal(path) : base.getFileHandle(path, type); }
    @Override public FileHandle classpath(String path) { return base.classpath(path); }
    @Override public FileHandle external(String path) { return base.external(path); }
    @Override public FileHandle absolute(String path) { return base.absolute(path); }
    @Override public FileHandle local(String path) { return base.local(path); }
    @Override public String getExternalStoragePath() { return base.getExternalStoragePath(); }
    @Override public boolean isExternalStorageAvailable() { return base.isExternalStorageAvailable(); }
    @Override public String getLocalStoragePath() { return base.getLocalStoragePath(); }
    @Override public boolean isLocalStorageAvailable() { return base.isLocalStorageAvailable(); }

    /** An internal-type handle whose content is a store file (if overridden) or the APK's handle, and whose navigation goes back through the overlay. */
    final class OverlayHandle extends FileHandle {
        private final String logical; private final File override; private final FileHandle apk;
        OverlayHandle(String logical, File override, FileHandle apk) { super(logical); this.logical = logical; this.override = override; this.apk = apk; }

        @Override public Files.FileType type() { return Files.FileType.Internal; }
        @Override public File file() { return override != null ? override : apk.file(); }
        @Override public InputStream read() { if (override == null) return apk.read(); try { return new FileInputStream(override); } catch (IOException e) { throw new com.badlogic.gdx.utils.GdxRuntimeException("Error reading file: " + this, e); } }
        @Override public java.nio.ByteBuffer map(java.nio.channels.FileChannel.MapMode mode) { return override != null ? new FileHandle(override).map(mode) : apk.map(mode); }
        @Override public boolean exists() { return override != null || apk.exists(); }
        @Override public long length() { return override != null ? override.length() : apk.length(); }
        @Override public long lastModified() { return override != null ? override.lastModified() : apk.lastModified(); }
        @Override public boolean isDirectory() { return override == null && apk.isDirectory(); }
        @Override public FileHandle child(String name) { return internal(logical.isEmpty() ? name : logical + "/" + name); }
        @Override public FileHandle parent() { int i = logical.lastIndexOf('/'); return internal(i < 0 ? "" : logical.substring(0, i)); }
        @Override public FileHandle sibling(String name) { if (logical.isEmpty()) throw new com.badlogic.gdx.utils.GdxRuntimeException("Cannot get the sibling of the root."); return parent().child(name); }
        @Override public FileHandle[] list() { return wrap(override != null ? new FileHandle[0] : apk.list()); }
        @Override public FileHandle[] list(FileFilter filter) { List<FileHandle> out = new ArrayList<>(); for (FileHandle h : list()) if (filter.accept(h.file())) out.add(h); return out.toArray(new FileHandle[0]); }
        @Override public FileHandle[] list(FilenameFilter filter) { List<FileHandle> out = new ArrayList<>(); for (FileHandle h : list()) if (filter.accept(h.file().getParentFile(), h.name())) out.add(h); return out.toArray(new FileHandle[0]); }
        @Override public FileHandle[] list(String suffix) { List<FileHandle> out = new ArrayList<>(); for (FileHandle h : list()) if (h.name().endsWith(suffix)) out.add(h); return out.toArray(new FileHandle[0]); }
        private FileHandle[] wrap(FileHandle[] kids) { FileHandle[] out = new FileHandle[kids.length]; for (int i = 0; i < kids.length; i++) out[i] = child(kids[i].name()); return out; }
        @Override public boolean equals(Object o) { return o instanceof OverlayHandle && ((OverlayHandle) o).logical.equals(logical); }
        @Override public int hashCode() { return 37 + logical.hashCode(); }
        @Override public String toString() { return logical; }
    }
}

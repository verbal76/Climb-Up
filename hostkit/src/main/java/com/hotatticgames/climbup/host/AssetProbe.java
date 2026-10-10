package com.hotatticgames.climbup.host;

import com.badlogic.gdx.Files;
import com.badlogic.gdx.files.FileHandle;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Opens EVERY game asset through a {@link Files} (on a device: {@code Gdx.files}, i.e. the overlay when game-file overrides are active) and reports what it found, so a scenario can compare an
 * un-updated install, an updated one and the packaged app by one line of text: how many files of each type were read, their total size, a digest over every (path, content hash), and which files
 * (or loaders) failed. The walk, hashing and reporting live here and are unit-testable on the JVM; what needs the real engine (decoding a texture, creating a Sound, parsing a model) is supplied by
 * the launcher as {@link Loader}s keyed by file extension, so this class never touches the Android audio / graphics classes itself.
 */
public final class AssetProbe {
    /** Loads (and discards) one asset the way the game would. Throwing means the game could not use this file. */
    public interface Loader { void load(String path, FileHandle handle) throws Exception; }

    public static final int MAX_FILES = 100_000, MAX_DEPTH = 32;

    public static final class Result {
        public int files, failures; public long bytes; public String digest = "";
        public final Map<String, Integer> byExtension = new TreeMap<>();
        public final Map<String, String> sha256ByPath = new TreeMap<>();
        public final List<String> failed = new ArrayList<>();
        public boolean ok() { return failures == 0 && files > 0; }

        /** One greppable line: ASSETPROBE files=N bytes=B digest=<16 hex> fail=F ext=png:12,g3dj:34 [first=<path: reason>]. */
        public String line() {
            StringBuilder e = new StringBuilder(); for (Map.Entry<String, Integer> x : byExtension.entrySet()) { if (e.length() > 0) e.append(','); e.append(x.getKey()).append(':').append(x.getValue()); }
            return "ASSETPROBE files=" + files + " bytes=" + bytes + " digest=" + digest + " fail=" + failures + " ext=" + (e.length() == 0 ? "-" : e) + (failed.isEmpty() ? "" : " first=" + failed.get(0).replace('\n', ' '));
        }
    }

    private AssetProbe() { }

    /**
     * @param files       where to read from (the overlay on a device)
     * @param extraPaths  paths that exist only as overrides (a delivered file the APK never had), which a directory listing cannot discover; may be null
     * @param loaders     lower-case extension (no dot) to loader; files whose extension has none are only read and hashed; may be null
     */
    public static Result run(Files files, Collection<String> extraPaths, Map<String, Loader> loaders) {
        Result r = new Result(); List<String> paths = new ArrayList<>();
        try { walk(files, "", 0, paths); } catch (RuntimeException e) { fail(r, "<walk>", e); }
        if (extraPaths != null) for (String p : extraPaths) if (!paths.contains(p)) paths.add(p);
        Collections.sort(paths);
        for (String p : paths) {
            if (r.files >= MAX_FILES) { fail(r, p, new IllegalStateException("too many files")); break; }
            try {
                FileHandle h = files.internal(p); byte[] data = h.readBytes();
                r.files++; r.bytes += data.length; r.sha256ByPath.put(p, Hashing.sha256(data));
                String ext = extensionOf(p); r.byExtension.merge(ext.isEmpty() ? "-" : ext, 1, Integer::sum);
                Loader l = loaders == null ? null : loaders.get(ext);
                if (l != null) l.load(p, files.internal(p));                                  // a fresh handle: loaders may consume or wrap it
            } catch (Exception e) { fail(r, p, e); }
        }
        StringBuilder sb = new StringBuilder(); for (Map.Entry<String, String> x : r.sha256ByPath.entrySet()) sb.append(x.getKey()).append('\u0000').append(x.getValue()).append('\n');
        r.digest = Hashing.sha256(sb.toString().getBytes(StandardCharsets.UTF_8)).substring(0, 16);
        return r;
    }

    private static void fail(Result r, String path, Exception e) { r.failures++; r.failed.add(path + ": " + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " " + e.getMessage())); }

    static String extensionOf(String path) { int s = path.lastIndexOf('/'), d = path.lastIndexOf('.'); return d > s + 1 ? path.substring(d + 1).toLowerCase(java.util.Locale.ROOT) : ""; }

    private static void walk(Files files, String dir, int depth, List<String> out) {
        if (depth > MAX_DEPTH) throw new IllegalStateException("directory tree too deep");
        FileHandle[] kids = files.internal(dir).list();
        if (kids == null) return;
        for (FileHandle k : kids) {
            String p = dir.isEmpty() ? k.name() : dir + "/" + k.name();
            if (files.internal(p).isDirectory()) walk(files, p, depth + 1, out); else out.add(p);
            if (out.size() > MAX_FILES) throw new IllegalStateException("too many files");
        }
    }
}

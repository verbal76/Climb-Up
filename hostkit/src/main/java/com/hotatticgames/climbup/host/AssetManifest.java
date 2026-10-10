package com.hotatticgames.climbup.host;

import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The game-owned files a module release serves in place of the ones inside the APK ({@code assets.json}, listed and hashed in the signed module manifest, so it is authenticated
 * by the signature). Overrides only: a path that is not listed is read from the APK exactly as before; a path a newer release stops listing reverts to the APK's copy.
 */
public final class AssetManifest {
    public static final String FILE = "assets.json";
    public static final int SCHEMA = 1, MAX_ASSETS = 20000;
    public static final long MAX_TOTAL_BYTES = 1024L * 1024 * 1024, MAX_ASSET_BYTES = 256L * 1024 * 1024;
    private static final Pattern PATH = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_.\\-]*(/[A-Za-z0-9_][A-Za-z0-9_.\\-]*)*"), HEX = Pattern.compile("[0-9a-f]{64}");

    public static final class Asset { public final String path, sha256; public final long size; Asset(String p, String h, long s) { path = p; sha256 = h; size = s; } }

    public final Map<String, Asset> byPath = new LinkedHashMap<>();

    public static AssetManifest parse(byte[] json) throws IllegalArgumentException {
        JsonValue r;
        try { r = new JsonReader().parse(new String(json, StandardCharsets.UTF_8)); } catch (Exception e) { throw new IllegalArgumentException("assets.json not JSON"); }
        if (r == null || !r.isObject()) throw new IllegalArgumentException("assets.json not an object");
        if (r.getInt("schema", 0) != SCHEMA) throw new IllegalArgumentException("assets.json schema");
        JsonValue arr = r.get("assets");
        if (arr == null || !arr.isArray() || arr.size > MAX_ASSETS) throw new IllegalArgumentException("assets list");
        AssetManifest m = new AssetManifest(); long total = 0; Set<String> seen = new HashSet<>();
        for (JsonValue a = arr.child; a != null; a = a.next) {
            String path = a.getString("path", ""), sha = a.getString("sha256", ""); JsonValue sz = a.get("size");
            if (path.length() > 200 || !PATH.matcher(path).matches() || path.contains("..")) throw new IllegalArgumentException("bad asset path");
            if (!HEX.matcher(sha).matches() || sz == null || !sz.isNumber()) throw new IllegalArgumentException("bad asset entry " + path);
            long size = sz.asLong(); if (size < 0 || size > MAX_ASSET_BYTES) throw new IllegalArgumentException("asset size " + path);
            if (!seen.add(path.toLowerCase(java.util.Locale.ROOT))) throw new IllegalArgumentException("duplicate asset " + path);
            total += size; if (total > MAX_TOTAL_BYTES) throw new IllegalArgumentException("assets too large");
            m.byPath.put(path, new Asset(path, sha, size));
        }
        return m;
    }

    /** The assets a module directory declares (empty if it has none). */
    public static AssetManifest read(File moduleDir) throws java.io.IOException {
        File f = new File(moduleDir, FILE);
        return f.isFile() ? parse(Files.readAllBytes(f.toPath())) : new AssetManifest();
    }

    public List<Asset> all() { return new ArrayList<>(byPath.values()); }
}

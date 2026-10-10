package com.hotatticgames.climbup.host;

import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** The signed description of one game-module release. Parsed strictly: anything missing, malformed or out of range is rejected. */
public final class ModuleManifest {
    public static final int SCHEMA = 1;
    public static final int MAX_BYTES = 64 * 1024, MAX_FILES = 4096;
    public static final long MAX_FILE_BYTES = 256L * 1024 * 1024;
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"), HEX = Pattern.compile("[0-9a-f]{64}"),
            CLASS = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_$]*)+"), SHA = Pattern.compile("[0-9a-f]{7,40}"), ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    public static final class FileEntry { public String name, sha256; public long size; }

    public int schema, moduleVersion, interfaceVersion, hostMin, hostMax, saveSchema, saveMin, generatorRuleset, contentVersion, revokeFloor;
    public String app, channel, keyId, moduleName, sourceSha, entry, dex;
    public final List<FileEntry> files = new ArrayList<>();

    public FileEntry file(String name) { for (FileEntry f : files) if (f.name.equals(name)) return f; return null; }

    /** Reads only the key id (untrusted: used to pick the key; the signature is checked before anything else in the manifest is believed). */
    public static String peekKeyId(byte[] json) {
        try { String k = new JsonReader().parse(new String(json, StandardCharsets.UTF_8)).getString("keyId", ""); return ID.matcher(k).matches() ? k : null; }
        catch (Exception e) { return null; }
    }

    /** Strict JSON objects: a repeated key is refused (parsers disagree about which value wins, and a signed document must mean one thing). */
    static void noDuplicateKeys(JsonValue o) {
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (JsonValue c = o.child; c != null; c = c.next) if (o.isObject() && !seen.add(c.name)) throw new IllegalArgumentException("duplicate key " + c.name);
    }

    public static ModuleManifest parse(byte[] json) throws IllegalArgumentException {
        try { return parse0(json); }
        catch (IllegalArgumentException e) { throw e; }
        catch (RuntimeException e) { throw new IllegalArgumentException("manifest malformed"); }          // the JSON accessors throw other types for mismatched value kinds
    }

    private static ModuleManifest parse0(byte[] json) {
        if (json == null || json.length == 0 || json.length > MAX_BYTES) throw new IllegalArgumentException("manifest size");
        JsonValue r;
        try { r = new JsonReader().parse(new String(json, StandardCharsets.UTF_8)); } catch (Exception e) { throw new IllegalArgumentException("manifest not JSON"); }
        if (r == null || !r.isObject()) throw new IllegalArgumentException("manifest not an object");
        noDuplicateKeys(r);
        ModuleManifest m = new ModuleManifest();
        m.schema = i(r, "schema", 1, 1000); if (m.schema != SCHEMA) throw new IllegalArgumentException("unsupported schema " + m.schema);
        m.app = s(r, "app", ID); m.channel = s(r, "channel", ID); m.keyId = s(r, "keyId", ID); m.moduleName = s(r, "moduleName", ID);
        m.sourceSha = s(r, "sourceSha", SHA); m.entry = s(r, "entry", CLASS); m.dex = s(r, "dex", NAME);
        m.moduleVersion = i(r, "moduleVersion", 1, 1_000_000_000); m.interfaceVersion = i(r, "interfaceVersion", 1, 1000);
        m.hostMin = i(r, "hostMin", 1, 1000); m.hostMax = i(r, "hostMax", 1, 1000); if (m.hostMin > m.hostMax) throw new IllegalArgumentException("hostMin > hostMax");
        m.saveSchema = i(r, "saveSchema", 1, 1000); m.saveMin = i(r, "saveMin", 1, 1000); if (m.saveMin > m.saveSchema) throw new IllegalArgumentException("saveMin > saveSchema");
        m.generatorRuleset = i(r, "generatorRuleset", 1, 1000); m.contentVersion = i(r, "contentVersion", 0, 1_000_000_000);
        m.revokeFloor = i(r, "revokeFloor", 0, 1_000_000_000); if (m.revokeFloor > m.moduleVersion) throw new IllegalArgumentException("revokeFloor above own version");
        JsonValue fs = r.get("files");
        if (fs == null || !fs.isArray() || fs.size == 0 || fs.size > MAX_FILES) throw new IllegalArgumentException("files");
        Set<String> seen = new HashSet<>();
        for (JsonValue f = fs.child; f != null; f = f.next) {
            FileEntry e = new FileEntry();
            if (!f.isObject()) throw new IllegalArgumentException("file entry"); noDuplicateKeys(f);
            e.name = s(f, "name", NAME); e.sha256 = s(f, "sha256", HEX);
            JsonValue sz = f.get("size"); if (sz == null || !sz.isNumber()) throw new IllegalArgumentException("size");
            e.size = sz.asLong(); if (e.size < 1 || e.size > MAX_FILE_BYTES) throw new IllegalArgumentException("size range");
            if (!seen.add(e.name) || e.name.equals("manifest.json") || e.name.equals("manifest.sig")) throw new IllegalArgumentException("file name " + e.name);
            m.files.add(e);
        }
        if (m.file(m.dex) == null) throw new IllegalArgumentException("dex not listed in files");
        return m;
    }

    private static String s(JsonValue o, String k, Pattern p) {
        JsonValue v = o.get(k); if (v == null || !v.isString()) throw new IllegalArgumentException("missing " + k);
        String s = v.asString(); if (!p.matcher(s).matches()) throw new IllegalArgumentException("bad " + k); return s;
    }
    private static int i(JsonValue o, String k, int lo, int hi) {
        JsonValue v = o.get(k); if (v == null || !v.isNumber()) throw new IllegalArgumentException("missing " + k);
        long x = v.asLong(); if (x < lo || x > hi || x != v.asDouble()) throw new IllegalArgumentException("range " + k); return (int) x;
    }
}

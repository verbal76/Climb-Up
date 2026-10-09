package com.hotatticgames.climbup.host;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Test helper: builds signed module bundles from the synthetic proof module jar (the JVM stand-in for module.dex). */
final class Bundles {
    static final String APP = "com.hotatticgames.climbup.otalab", ENTRY = "com.hotatticgames.climbup.synthetic.SyntheticModule";

    static KeyPair newKey() throws Exception { KeyPairGenerator g = KeyPairGenerator.getInstance("EC"); g.initialize(new ECGenParameterSpec("secp256r1")); return g.generateKeyPair(); }

    /** Fields a test may vary. */
    static final class Spec {
        int version = 1, hostMin = 1, hostMax = 1, iface = 1, saveSchema = 6, saveMin = 6, ruleset = 1, revokeFloor = 0;
        String mode = "ok", app = APP, channel = "internal", entry = ENTRY, hello = "hello from v1";
        Spec v(int x) { version = x; hello = "hello from v" + x; return this; }
        Spec mode(String m) { mode = m; return this; }
        Spec ruleset(int r) { ruleset = r; return this; }
        Spec save(int min, int cur) { saveMin = min; saveSchema = cur; return this; }
        Spec floor(int f) { revokeFloor = f; return this; }
    }

    static byte[] patchedJar(Spec s) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (ZipFile in = new ZipFile(System.getProperty("synthetic.jar")); ZipOutputStream out = new ZipOutputStream(bo)) {
            for (Enumeration<? extends ZipEntry> en = in.entries(); en.hasMoreElements(); ) {
                ZipEntry e = en.nextElement(); if (e.isDirectory() || e.getName().equals("synthetic.properties")) continue;
                out.putNextEntry(new ZipEntry(e.getName())); try (InputStream is = in.getInputStream(e)) { is.transferTo(out); } out.closeEntry();
            }
            out.putNextEntry(new ZipEntry("synthetic.properties"));
            out.write(("version=" + s.version + "\nmode=" + s.mode + "\nhue=" + (0.1f * s.version % 1f) + "\n").getBytes(StandardCharsets.UTF_8)); out.closeEntry();
        }
        return bo.toByteArray();
    }

    /** Writes manifest.json, manifest.sig, module.jar and hello.txt into {@code dir}, signed by {@code key} under {@code keyId}. */
    static void write(File dir, Spec s, KeyPair key) throws Exception {
        dir.mkdirs();
        byte[] jar = patchedJar(s), hello = s.hello.getBytes(StandardCharsets.UTF_8);
        Files.write(new File(dir, "module.jar").toPath(), jar); Files.write(new File(dir, "hello.txt").toPath(), hello);
        String json = String.format("{\"schema\":1,\"app\":\"%s\",\"channel\":\"%s\",\"keyId\":\"%s\",\"moduleName\":\"1.0.%d\",\"sourceSha\":\"0123456789abcdef0123456789abcdef01234567\","
                + "\"entry\":\"%s\",\"dex\":\"module.jar\",\"moduleVersion\":%d,\"interfaceVersion\":%d,\"hostMin\":%d,\"hostMax\":%d,\"saveSchema\":%d,\"saveMin\":%d,"
                + "\"generatorRuleset\":%d,\"contentVersion\":1,\"revokeFloor\":%d,\"files\":[{\"name\":\"module.jar\",\"sha256\":\"%s\",\"size\":%d},{\"name\":\"hello.txt\",\"sha256\":\"%s\",\"size\":%d}]}",
                s.app, s.channel, TrustedKeys.keyId(key.getPublic()), s.version, s.entry, s.version, s.iface, s.hostMin, s.hostMax, s.saveSchema, s.saveMin, s.ruleset, s.revokeFloor,
                Hashing.sha256(jar), jar.length, Hashing.sha256(hello), hello.length);
        sign(dir, json.getBytes(StandardCharsets.UTF_8), key);
    }

    static void sign(File dir, byte[] manifest, KeyPair key) throws Exception {
        Signature sg = Signature.getInstance("SHA256withECDSA"); sg.initSign(key.getPrivate()); sg.update(manifest);
        Files.write(new File(dir, "manifest.json").toPath(), manifest);
        Files.write(new File(dir, "manifest.sig").toPath(), Base64.getEncoder().encode(sg.sign()));
    }
}

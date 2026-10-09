package com.hotatticgames.climbup.host;

import java.io.File;
import java.nio.file.Files;

/** Full verification of one module directory (manifest.json + manifest.sig + the listed files). Pure function of the bytes on disk; fails closed with a reason. */
public final class ModuleVerifier {
    private ModuleVerifier() {}

    public static final class Result {
        public final ModuleManifest manifest; public final String reason;
        Result(ModuleManifest m, String r) { manifest = m; reason = r; }
        public boolean ok() { return manifest != null; }
        @Override public String toString() { return ok() ? "ok v" + manifest.moduleVersion : "rejected: " + reason; }
    }

    private static Result no(String why) { return new Result(null, why); }

    /** Signature, identity, compatibility and file hashes. {@code dir} must contain exactly manifest.json, manifest.sig and the listed files. */
    public static Result verify(File dir, TrustedKeys keys, HostInfo host) {
        try {
            File mf = new File(dir, "manifest.json"), sf = new File(dir, "manifest.sig");
            if (!mf.isFile() || !sf.isFile()) return no("manifest or signature missing");
            if (mf.length() > ModuleManifest.MAX_BYTES || sf.length() > 1024) return no("manifest or signature too large");
            byte[] mb = Files.readAllBytes(mf.toPath()), sb = Files.readAllBytes(sf.toPath());
            String kid = ModuleManifest.peekKeyId(mb);
            if (kid == null) return no("no key id");
            if (!keys.trusts(kid)) return no("key " + kid + " not trusted");
            if (!keys.verify(kid, mb, sb)) return no("bad signature");
            ModuleManifest m;
            try { m = ModuleManifest.parse(mb); } catch (IllegalArgumentException e) { return no("manifest: " + e.getMessage()); }
            if (!m.app.equals(host.app)) return no("built for another app (" + m.app + ")");
            if (!m.channel.equals(host.channel)) return no("channel " + m.channel + " is not " + host.channel);
            if (m.interfaceVersion != host.interfaceVersion) return no("interface " + m.interfaceVersion + " != host " + host.interfaceVersion);
            if (host.hostLevel < m.hostMin || host.hostLevel > m.hostMax) return no("host level " + host.hostLevel + " outside " + m.hostMin + ".." + m.hostMax);
            String[] present = dir.list(); int expected = m.files.size() + 2;
            if (present == null || present.length != expected) return no("unexpected files in module directory");
            for (ModuleManifest.FileEntry e : m.files) {
                File f = new File(dir, e.name);
                if (!f.isFile() || !f.getCanonicalFile().getParentFile().equals(dir.getCanonicalFile())) return no("file missing: " + e.name);
                if (f.length() != e.size) return no("size mismatch: " + e.name);
                if (!Hashing.sha256(f).equals(e.sha256)) return no("hash mismatch: " + e.name);
            }
            return new Result(m, "");
        } catch (Exception e) { return no("io: " + e.getMessage()); }
    }
}

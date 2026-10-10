package com.hotatticgames.climbup.host;

import java.io.File;
import java.nio.file.Files;

/** Release/CI gate: runs a built bundle through the host's own verifier against a public key. usage: VerifyBundleCli DIR PUBKEY_B64_FILE APP CHANNEL [HOST_LEVEL]; exit 0 = the host would accept it. */
public final class VerifyBundleCli {
    public static void main(String[] args) throws Exception {
        if (args.length < 4) { System.err.println("usage: VerifyBundleCli DIR PUBKEY_B64_FILE APP CHANNEL [HOST_LEVEL]"); System.exit(2); }
        TrustedKeys keys = new TrustedKeys().add(new String(Files.readAllBytes(new File(args[1]).toPath())).trim());
        File view = java.nio.file.Files.createTempDirectory("bundle").toFile();                       // the served folder may also hold assets/<sha256> blobs: verify the module files only
        for (File f : new File(args[0]).listFiles()) if (f.isFile()) java.nio.file.Files.copy(f.toPath(), new File(view, f.getName()).toPath());
        ModuleVerifier.Result r = ModuleVerifier.verify(view, keys, new HostInfo(args[2], args.length > 4 ? Integer.parseInt(args[4]) : HostInfo.HOST_LEVEL, args[3]));
        System.out.println(r);
        System.exit(r.ok() ? 0 : 1);
    }
}

package com.hotatticgames.climbup;

import com.hotatticgames.climbup.sim.Tower;
import com.hotatticgames.climbup.sim.Tuning;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;

/**
 * Build 73 (owner: the same seed must build the same course on Android and on the desktop): prints the canonical generation fingerprint of several seeds
 * (the stored slice blobs, which hold every platform, rope, pad, castle and hazard with its phase, hashed). Run on the desktop JVM and, dexed, on Android's
 * runtime (ART) by .github/workflows/determinism.yml; the two outputs must be identical.
 * <p>
 * Usage: GenPrint &lt;tuning.json&gt; [slices per seed] [seed...]
 */
public final class GenPrint {
    public static void main(String[] args) throws Exception {
        Tuning t = Tuning.parse(new String(Files.readAllBytes(Paths.get(args[0])), "UTF-8"));
        int slices = args.length > 1 ? Integer.parseInt(args[1]) : 12;
        long[] seeds = args.length > 2 ? new long[args.length - 2] : new long[]{1L, 7L, 12L, 21L, 99L, 130L, 178L, 2024L};
        for (int i = 2; i < args.length; i++) seeds[i - 2] = Long.parseLong(args[i]);
        MessageDigest all = MessageDigest.getInstance("SHA-256");
        for (long seed : seeds) {
            Tower tw = new Tower(seed, t);
            while (tw.sliceCount() < slices) tw.extend();
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (int k = 0; k < slices; k++) { md.update(tw.blob(k)); all.update(tw.blob(k)); }
            System.out.println("GENPRINT seed=" + seed + " slices=" + slices + " " + hex(md.digest()));
        }
        System.out.println("GENPRINT all " + hex(all.digest()));
    }

    private static String hex(byte[] d) { StringBuilder sb = new StringBuilder(); for (byte b : d) sb.append(String.format("%02x", b)); return sb.toString(); }
}

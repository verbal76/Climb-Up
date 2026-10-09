package com.hotatticgames.climbup.host;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The public keys this host trusts, by key id. Rotation: ship a host that pins old+new keys, sign new releases with the new key, later drop the old one in a newer host.
 * Revocation: a key id in {@code revoked} is never accepted, even if it is still pinned (so a compromised key can be cut off by a signed-release-free host update only;
 * a signed manifest's {@code revokeFloor} additionally invalidates older module versions without touching the host). Private keys never appear here.
 */
public final class TrustedKeys {
    private final Map<String, PublicKey> keys = new HashMap<>();
    private final Set<String> revoked = new HashSet<>();

    public TrustedKeys add(String b64X509) throws GeneralSecurityException {
        PublicKey k = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(b64X509.trim())));
        keys.put(keyId(k), k); return this;
    }
    public TrustedKeys add(PublicKey k) { keys.put(keyId(k), k); return this; }
    public TrustedKeys revoke(String keyId) { revoked.add(keyId); return this; }
    public boolean trusts(String keyId) { return keys.containsKey(keyId) && !revoked.contains(keyId); }

    public static String keyId(PublicKey key) {
        try { byte[] d = MessageDigest.getInstance("SHA-256").digest(key.getEncoded()); StringBuilder sb = new StringBuilder(); for (int i = 0; i < 8; i++) sb.append(String.format("%02x", d[i])); return sb.toString(); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    /** True only if {@code sigText} (base64 DER ECDSA P-256/SHA-256) is a valid signature of exactly {@code data} by the pinned, unrevoked key {@code keyId}. Fails closed. */
    public boolean verify(String keyId, byte[] data, byte[] sigText) {
        if (!trusts(keyId)) return false;
        try {
            byte[] sig = Base64.getMimeDecoder().decode(new String(sigText, StandardCharsets.US_ASCII).trim());
            if (sig.length < 8 || sig.length > 140) return false;
            Signature s = Signature.getInstance("SHA256withECDSA"); s.initVerify(keys.get(keyId)); s.update(data);
            return s.verify(sig);
        } catch (Exception e) { return false; }
    }
}

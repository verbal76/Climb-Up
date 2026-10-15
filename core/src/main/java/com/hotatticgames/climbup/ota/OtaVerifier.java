package com.hotatticgames.climbup.ota;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.KeyFactory;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/** Signature check for manifests: ECDSA over P-256 with SHA-256 (DER signature, base64 text), verified against a pinned X.509 public key. Fails closed. */
public final class OtaVerifier {
    private OtaVerifier() {}

    public static PublicKey parseKey(String b64) throws GeneralSecurityException {
        return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(b64.trim())));
    }

    /** First 8 bytes (16 hex) of the SHA-256 of the key's encoding: the manifest names the key it was signed with. */
    public static String keyId(PublicKey key) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(key.getEncoded());
            StringBuilder sb = new StringBuilder(); for (int i = 0; i < 8; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    /** True only if {@code signatureText} (base64 of a DER ECDSA signature) is a valid signature of exactly {@code data} by {@code key}. Any problem is false. */
    public static boolean verify(byte[] data, byte[] signatureText, PublicKey key) {
        try {
            byte[] sig = Base64.getMimeDecoder().decode(new String(signatureText, StandardCharsets.US_ASCII).trim());
            if (sig.length < 8 || sig.length > 140) return false;
            Signature s = Signature.getInstance("SHA256withECDSA");
            s.initVerify(key); s.update(data);
            return s.verify(sig);
        } catch (Exception e) { return false; }
    }
}

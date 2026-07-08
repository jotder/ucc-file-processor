package com.gamma.control;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;

/**
 * HMAC-signed, expiring share tokens (BI-6 public dashboard sharing). A token is
 * {@code base64url(type/name/expiryEpochSec) + "." + base64url(HMAC-SHA256(payload, secret))} — verifiable
 * statelessly, revocable in bulk by rotating the secret, and <b>fail-closed</b>: with no
 * {@code -Dbi.share.secret} configured, issuing is a 503 and verification always fails, so the public
 * surface simply does not exist by default.
 */
final class ShareTokens {

    private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DEC = Base64.getUrlDecoder();

    private ShareTokens() {}

    /** The verified scope a token grants: read access to one named resource until {@code expiresEpochSec}. */
    record Scope(String type, String name, long expiresEpochSec) {}

    /** Whether sharing is enabled at all ({@code -Dbi.share.secret} configured, ≥ 16 chars). */
    static boolean enabled() {
        return secret() != null;
    }

    /** Issue a token for {@code type/name} expiring at {@code expiresEpochSec}; empty when sharing is disabled. */
    static Optional<String> issue(String type, String name, long expiresEpochSec) {
        String secret = secret();
        if (secret == null) return Optional.empty();
        if (name.contains("/")) throw new IllegalArgumentException("unshareable name '" + name + "'");
        String payload = type + "/" + name + "/" + expiresEpochSec;
        return Optional.of(ENC.encodeToString(payload.getBytes(StandardCharsets.UTF_8))
                + "." + ENC.encodeToString(hmac(payload, secret)));
    }

    /** Verify signature + expiry; empty on any mismatch, expiry, malformation, or disabled sharing. */
    static Optional<Scope> verify(String token) {
        String secret = secret();
        if (secret == null || token == null) return Optional.empty();
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1) return Optional.empty();
        try {
            String payload = new String(DEC.decode(token.substring(0, dot)), StandardCharsets.UTF_8);
            byte[] givenMac = DEC.decode(token.substring(dot + 1));
            if (!MessageDigest.isEqual(hmac(payload, secret), givenMac)) return Optional.empty();
            String[] parts = payload.split("/", 3);
            if (parts.length != 3) return Optional.empty();
            long exp = Long.parseLong(parts[2]);
            if (exp * 1000L < System.currentTimeMillis()) return Optional.empty();
            return Optional.of(new Scope(parts[0], parts[1], exp));
        } catch (RuntimeException malformed) {
            return Optional.empty();
        }
    }

    private static String secret() {
        String s = System.getProperty("bi.share.secret");
        return (s == null || s.trim().length() < 16) ? null : s.trim();   // refuse weak/blank secrets
    }

    private static byte[] hmac(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);   // cannot happen on a JDK
        }
    }
}

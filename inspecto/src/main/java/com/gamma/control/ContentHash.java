package com.gamma.control;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Content fingerprint mirroring the UI's {@code transfer/content-hash.ts}: SHA-256 (lowercase hex)
 * over <b>canonical JSON</b> — object keys sorted recursively, arrays preserved, no incidental
 * whitespace. This is the one hash the design gives four jobs (§4/§7): the {@link ETags} value for
 * HTTP caching + optimistic locking (W3), and — when the backend gains bundle export — the
 * Metadata Bundle v2 provenance {@code contentHash} for drift detection + idempotent re-promotion.
 *
 * <p><b>Parity note.</b> Identical to the UI hash for the JSON value types that appear in config
 * content (string / boolean / integer / nested object / array / null) — pinned by
 * {@code ContentHashTest}. Exact cross-implementation parity for floating-point values (JS emits the
 * shortest round-trip form; Java may differ, e.g. {@code 1.0} vs {@code 1}) is deliberately left to
 * a conformance test that lands with the backend bundle endpoints; for W3 the hash is used purely
 * backend-side, where self-consistency (same content ⇒ same hash across reads) is all that matters.
 */
final class ContentHash {

    private ContentHash() {}

    /** Recursive map-key sorting + compact output ⇒ the same canonical form as the UI's canonicalJson(). */
    private static final ObjectMapper CANONICAL =
            new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    /** Canonical JSON of {@code content} (recursively key-sorted, compact). */
    static String canonicalJson(Object content) {
        try {
            return CANONICAL.writeValueAsString(content);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("content is not JSON-serialisable", e);
        }
    }

    /** SHA-256 (lowercase hex) of {@code content}'s canonical JSON. */
    static String of(Object content) {
        return sha256Hex(canonicalJson(content));
    }

    static String sha256Hex(String s) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest)
                sb.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);   // never on a conformant JDK
        }
    }
}

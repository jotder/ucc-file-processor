package com.gamma.config.io;

import dev.toonformat.jtoon.DecodeOptions;
import dev.toonformat.jtoon.JToon;

import java.util.Map;

/**
 * The canonical {@code .toon} encode/decode for configuration maps.
 *
 * <p>Centralising this matters because of the leniency the rest of the system relies on (footgun G5):
 * shipped configs carry {@code #} comment lines, and {@link JToon#decode(String) default decode} is
 * <b>lenient</b> enough to tolerate them, while a strict consumer may not. {@code JToon.encode} never
 * emits comments and always quotes values that need it, so anything this codec <em>produces</em> is a
 * canonical, comment-free form that is guaranteed strict-decodable — regardless of how commented the
 * on-disk source was. The contract here:
 *
 * <ul>
 *   <li>{@link #toMap(String)} — lenient decode, for reading whatever is on disk today (comments ok);
 *   <li>{@link #toToon(Object)} — canonical, comment-free, strict-decodable encode;
 *   <li>{@link #toMapStrict(String)} — strict decode, used to <em>assert</em> a produced string is canonical.
 * </ul>
 *
 * <p>The JSON wire form needed by the Control API is produced by the API's Jackson mapper directly
 * on the decoded {@code Map} / the spec records, so it is not duplicated here.
 */
public final class ConfigCodec {

    private static final DecodeOptions STRICT = DecodeOptions.withStrict(true);

    private ConfigCodec() {}

    /** Lenient decode (tolerates {@code #} comments) — for reading existing on-disk configs. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> toMap(String toon) {
        return (Map<String, Object>) JToon.decode(toon);
    }

    /** Strict decode — rejects comments / non-canonical input; use to verify a string is canonical. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> toMapStrict(String toon) {
        return (Map<String, Object>) JToon.decode(toon, STRICT);
    }

    /** Canonical, comment-free, strict-decodable encode of a config map (or any JToon-encodable value). */
    public static String toToon(Object value) {
        return JToon.encode(value);
    }

    /** Whether {@code toon} decodes under strict rules (no comments, canonical form). */
    public static boolean isStrictDecodable(String toon) {
        try {
            JToon.decode(toon, STRICT);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}

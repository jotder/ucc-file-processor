package com.gamma.config.io;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the canonical {@code .toon} codec (P1): the re-encoded form is always strict-decodable
 * (comment-free, canonical) — the G5 guarantee that anything the codec produces can be re-parsed
 * under strict rules even if the original input carried {@code #} comments.
 *
 * <p>The companion contract test that walks every SHIPPED sample config lives in the core module
 * ({@code ShippedExamplesRoundTripTest}) with the {@code examples/} fixture tree it validates —
 * it could not move here in the S5 split (surefire's working directory is the module root).
 */
class ConfigCodecTest {

    @Test
    void colonAndSlashBearingValuesSurviveStrictRoundTrip() {
        // mirrors the events_meta.toon refs ("events/CALL") and grain strings ("a, b") that broke
        // tabular parsing before — they must encode quoted and strict-decode back intact
        Map<String, Object> src = Map.of(
                "name", "x",
                "kpis", Map.of("arpu", Map.of(
                        "inputs", List.of("events/CALL", "EVENTS_DAILY_KPI"),
                        "grain", "msisdn, month")));
        String toon = ConfigCodec.toToon(src);
        assertTrue(ConfigCodec.isStrictDecodable(toon));
        assertEquals(src, ConfigCodec.toMapStrict(toon));
    }

    @Test
    void encodeNormalisesCommentBearingInputToStrictCleanForm() {
        // lenient decode reads a comment-bearing draft; the canonical re-encode drops the comments
        // and is strict-decodable — the guarantee that matters, independent of JToon's own
        // (version-dependent) tolerance of comments under strict mode.
        String commented = "name: x\n# a comment line\nversion: 1\n";
        Map<String, Object> m = ConfigCodec.toMap(commented);
        assertEquals("x", m.get("name"));

        String canonical = ConfigCodec.toToon(m);
        assertFalse(canonical.contains("#"), "canonical form carries no comments");
        assertTrue(ConfigCodec.isStrictDecodable(canonical));
        assertEquals(m, ConfigCodec.toMapStrict(canonical));
    }
}

package com.gamma.config.io;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the canonical {@code .toon} codec (P1): every shipped config round-trips through
 * {@code decode → encode → strict-decode} with an equal map, and the re-encoded form is always
 * strict-decodable (comment-free, canonical) — the G5 guarantee that anything the codec produces
 * can be re-parsed under strict rules even if the original on-disk file carried {@code #} comments.
 */
class ConfigCodecTest {

    private static final Path CONFIG = Path.of("config");

    @Test
    void everyShippedConfigRoundTripsToStrictCanonicalForm() throws Exception {
        List<Path> toons;
        try (Stream<Path> w = Files.walk(CONFIG)) {
            toons = w.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".toon"))
                    .sorted()
                    .toList();
        }
        assertFalse(toons.isEmpty(), "expected shipped .toon samples under " + CONFIG.toAbsolutePath());

        for (Path p : toons) {
            String text = Files.readString(p, StandardCharsets.UTF_8);
            Map<String, Object> map1 = ConfigCodec.toMap(text);                 // lenient: tolerates comments
            String canonical = ConfigCodec.toToon(map1);                        // canonical encode

            assertTrue(ConfigCodec.isStrictDecodable(canonical),
                    "re-encoded form of " + p + " must be strict-decodable (comment-free, canonical)");
            assertFalse(canonical.contains("\n#"), "canonical form of " + p + " must carry no comments");

            Map<String, Object> map2 = ConfigCodec.toMapStrict(canonical);      // strict re-decode
            assertEquals(map1, map2, "round-trip map mismatch for " + p);
        }
    }

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

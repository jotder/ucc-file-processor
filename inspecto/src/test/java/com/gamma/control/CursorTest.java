package com.gamma.control;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** {@link Cursor} — the opaque keyset pagination token: round-trips the key parts, is URL-safe, and
 *  decodes total (absent/blank/malformed → empty = "from the top"), never throwing. */
class CursorTest {

    @Test
    void roundTripsKeyParts() {
        String c = Cursor.encode(List.of("2026-06-17 10:03:00", "run-42"));
        assertNotNull(c);
        assertEquals(List.of("2026-06-17 10:03:00", "run-42"), Cursor.decode(c));
    }

    @Test
    void absentOrMalformedDecodesToEmpty() {
        assertTrue(Cursor.decode(null).isEmpty());
        assertTrue(Cursor.decode("").isEmpty());
        assertTrue(Cursor.decode("   ").isEmpty());
        assertTrue(Cursor.decode("!!!not-base64!!!").isEmpty());
        assertTrue(Cursor.decode("YWJj").isEmpty(), "valid base64 but not a JSON array → empty");
    }

    @Test
    void tokenIsUrlSafeAndUnpadded() {
        String c = Cursor.encode(List.of("a/b+c=d", "x y"));
        assertFalse(c.contains("/") || c.contains("+") || c.contains("="), "url-safe alphabet, no padding");
        assertEquals(List.of("a/b+c=d", "x y"), Cursor.decode(c), "still round-trips values with reserved chars");
    }
}

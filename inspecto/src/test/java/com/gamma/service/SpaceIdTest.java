package com.gamma.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The filesystem-safe space-id guard: lower-case alnum + hyphen only, so an id can never escape {@code spaces/}. */
class SpaceIdTest {

    @Test
    void acceptsValidIds() {
        assertEquals("default", SpaceId.of("default").value());
        assertEquals("space-a", SpaceId.of("space-a").value());
        assertEquals("acme123", SpaceId.of("acme123").value());
        assertTrue(SpaceId.isValid("a"));
        assertTrue(SpaceId.isValid("a".repeat(63)));
    }

    @Test
    void rejectsUnsafeIds() {
        for (String bad : new String[]{null, "", "-leading", "Upper", "has space", "dot.id",
                "../escape", "a/b", "a\\b", "a".repeat(64)}) {
            assertFalse(SpaceId.isValid(bad), "should reject: " + bad);
            assertThrows(IllegalArgumentException.class, () -> SpaceId.of(bad), "should throw for: " + bad);
        }
    }
}

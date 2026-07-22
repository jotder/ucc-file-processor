package com.gamma.acquire;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link PostAction#resolveTemplate} — the {@code archive_path} date-template resolver. Relocated
 * out of {@code etl.PhaseFConfigTest} when {@code etl} was extracted into its own module (WS-D
 * increment 2): this test exercises {@code acquire.PostAction} directly, not anything etl-specific.
 */
class PostActionTest {

    @Test
    void resolvesArchiveDateTemplate() {
        java.time.ZonedDateTime when = java.time.ZonedDateTime.of(2026, 6, 14, 9, 5, 3, 0,
                java.time.ZoneOffset.UTC);
        assertEquals("archive/2026/06/14",
                PostAction.resolveTemplate("archive/yyyy/MM/dd", when));
        assertEquals("a/2026/06/14/09",
                PostAction.resolveTemplate("a/yyyy/MM/dd/HH", when));
        assertNull(PostAction.resolveTemplate(null, when));
    }
}

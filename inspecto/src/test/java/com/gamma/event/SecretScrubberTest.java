package com.gamma.event;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for the secret-redaction pipeline that guards the append-only event store. */
class SecretScrubberTest {

    @Test
    void redactsKeyValueSecretsInFreeText() {
        assertTrue(SecretScrubber.scrub("connecting password=hunter2 ok").contains(SecretScrubber.REDACTED));
        assertFalse(SecretScrubber.scrub("connecting password=hunter2 ok").contains("hunter2"));
        assertTrue(SecretScrubber.scrub("token: abc123DEF").contains(SecretScrubber.REDACTED));
        assertFalse(SecretScrubber.scrub("api_key=AKIA1234567890").contains("AKIA1234567890"));
        assertFalse(SecretScrubber.scrub("Authorization: Bearer eyJhbGci.payload.sig").contains("eyJhbGci"));
    }

    @Test
    void leavesCleanTextUntouchedAndSameReference() {
        String clean = "batch committed: 1200 rows in 3 partitions";
        assertSame(clean, SecretScrubber.scrub(clean), "clean text returns same reference (no alloc)");
        assertNull(SecretScrubber.scrub((String) null));
    }

    @Test
    void redactsAttributeValuesBySensitiveKey() {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("user", "alice");
        attrs.put("api_key", "AKIA-RAW-NO-HINT-WORD");   // key names a secret → value redacted whole
        attrs.put("rows", "42");
        Event e = new Event(null, 1L, EventLevel.INFO, "X", "src", null, null, "ok", attrs);

        Event scrubbed = SecretScrubber.scrub(e);
        assertEquals("alice", scrubbed.attributes().get("user"));
        assertEquals("42", scrubbed.attributes().get("rows"));
        assertEquals(SecretScrubber.REDACTED, scrubbed.attributes().get("api_key"));
    }

    @Test
    void cleanEventReturnsSameInstance() {
        Event e = Event.builder("BATCH_COMMITTED").message("1200 rows").attr("rows", "1200").build();
        assertSame(e, SecretScrubber.scrub(e), "nothing sensitive → no rebuild");
    }

    @Test
    void scrubsSecretInsideEventMessage() {
        Event e = Event.builder("LOG").message("auth failed for token=SECRETVALUE99").build();
        Event scrubbed = SecretScrubber.scrub(e);
        assertNotSame(e, scrubbed);
        assertFalse(scrubbed.message().contains("SECRETVALUE99"));
        assertTrue(scrubbed.message().contains(SecretScrubber.REDACTED));
    }
}

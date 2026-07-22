package com.gamma.acquire;

import org.junit.jupiter.api.Test;

import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The incremental high-watermark (Data Acquisition roadmap Phase C4) is <em>derived</em> from the fingerprint
 * ledger — {@code MAX(last_modified)} over a source's recorded rows — rather than stored separately. These tests
 * pin that derivation for both ledger backends and the safe default.
 */
class AcquisitionLedgerWatermarkTest {

    private static LedgerEntry entry(String src, String rel, long mtime) {
        return LedgerEntry.metadata(src, rel, rel, 1L, mtime, mtime);
    }

    @Test
    void inMemoryWatermarkIsMaxLastModifiedPerSource() {
        InMemoryAcquisitionLedger l = new InMemoryAcquisitionLedger();
        assertTrue(l.highWatermark("S").isEmpty(), "no rows ⇒ empty");
        l.record(entry("S", "a", 100));
        l.record(entry("S", "b", 300));
        l.record(entry("S", "c", 200));
        l.record(entry("OTHER", "z", 999));   // a different source must not leak in
        assertEquals(OptionalLong.of(300), l.highWatermark("S"));
        assertEquals(OptionalLong.of(999), l.highWatermark("OTHER"));
        assertTrue(l.highWatermark("UNKNOWN").isEmpty());
    }

    @Test
    void inMemoryWatermarkTracksUpsertedState() {
        InMemoryAcquisitionLedger l = new InMemoryAcquisitionLedger();
        l.record(entry("S", "a", 500));
        assertEquals(OptionalLong.of(500), l.highWatermark("S"));
        // the ledger upserts by (source, path): re-recording the same key replaces the row, so the derived
        // watermark reflects current state rather than a monotonic high-water store.
        l.record(entry("S", "a", 200));
        assertEquals(OptionalLong.of(200), l.highWatermark("S"));
    }

    @Test
    void dbWatermarkIsMaxLastModifiedPerSource() throws Exception {
        try (DbAcquisitionLedger l = DbAcquisitionLedger.open("jdbc:duckdb:", null, null)) {
            assertTrue(l.highWatermark("S").isEmpty(), "empty table ⇒ NULL MAX ⇒ empty (not 0)");
            l.record(entry("S", "a", 100));
            l.record(entry("S", "b", 400));
            l.record(entry("T", "a", 999));
            assertEquals(OptionalLong.of(400), l.highWatermark("S"));
            assertEquals(OptionalLong.of(999), l.highWatermark("T"));
            assertTrue(l.highWatermark("none").isEmpty());
        }
    }

    @Test
    void defaultLedgerInterfaceDisablesWatermarkSafely() {
        // a custom ledger that doesn't override highWatermark gets the empty default ⇒ no skipping.
        AcquisitionLedger custom = new AcquisitionLedger() {
            public java.util.Optional<LedgerEntry> find(String s, String r) { return java.util.Optional.empty(); }
            public void record(LedgerEntry e) {}
        };
        assertTrue(custom.highWatermark("anything").isEmpty());
    }
}

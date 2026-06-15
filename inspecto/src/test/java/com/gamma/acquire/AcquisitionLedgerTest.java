package com.gamma.acquire;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** The fingerprint ledger contract — find / record / upsert — for both the in-memory and DuckDB backends. */
class AcquisitionLedgerTest {

    /** The shared contract every {@link AcquisitionLedger} must satisfy. */
    private static void contract(AcquisitionLedger ledger) {
        assertTrue(ledger.find("S", "a/b.csv").isEmpty(), "unknown file ⇒ empty");

        ledger.record(new LedgerEntry("S", "a/b.csv", "b.csv", 100, "cs1", 1000L, 5000L, LedgerEntry.PROCESSED));
        Optional<LedgerEntry> got = ledger.find("S", "a/b.csv");
        assertTrue(got.isPresent());
        assertEquals(100, got.get().size());
        assertEquals("cs1", got.get().checksum());

        // upsert: a new fingerprint for the same key replaces the prior one
        ledger.record(new LedgerEntry("S", "a/b.csv", "b.csv", 250, "cs2", 2000L, 6000L, LedgerEntry.PROCESSED));
        assertEquals(250, ledger.find("S", "a/b.csv").orElseThrow().size());
        assertEquals("cs2", ledger.find("S", "a/b.csv").orElseThrow().checksum());

        // keyed by (sourceId, relativePath): a different source does not collide
        assertTrue(ledger.find("OTHER", "a/b.csv").isEmpty());

        dbWatermarkContract(ledger);
    }

    /** The row-level DB-export watermark contract: empty until recorded, then read-back + upsert, keyed per source. */
    private static void dbWatermarkContract(AcquisitionLedger ledger) {
        assertTrue(ledger.dbWatermark("db-src").isEmpty(), "unknown source ⇒ no watermark");

        ledger.recordDbWatermark("db-src", "2020-04-03 00:00:00");
        assertEquals("2020-04-03 00:00:00", ledger.dbWatermark("db-src").orElseThrow());

        // upsert: a newer watermark replaces the prior one for the same source key
        ledger.recordDbWatermark("db-src", "2020-04-04 00:00:00");
        assertEquals("2020-04-04 00:00:00", ledger.dbWatermark("db-src").orElseThrow());

        // keyed per source: a different source key is independent
        assertTrue(ledger.dbWatermark("other-src").isEmpty());
    }

    @Test
    void inMemory() {
        try (AcquisitionLedger ledger = new InMemoryAcquisitionLedger()) {
            contract(ledger);
        }
    }

    @Test
    void duckDb(@TempDir Path dir) throws Exception {
        String url = "jdbc:duckdb:" + dir.resolve("ledger.db").toString().replace('\\', '/');
        try (AcquisitionLedger ledger = DbAcquisitionLedger.open(url, null, null)) {
            contract(ledger);
        }
    }
}

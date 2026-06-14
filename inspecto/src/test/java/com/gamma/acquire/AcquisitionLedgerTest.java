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

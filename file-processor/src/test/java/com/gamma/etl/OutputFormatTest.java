package com.gamma.etl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Locks the {@link OutputFormat} enum-strategy to the historical {@code PartitionWriter}
 * behaviour: PARQUET only for the canonical {@code "PARQUET"} token, every other value
 * (including {@code null}) falling back to CSV, with the format-specific facts the writer
 * relies on.
 */
class OutputFormatTest {

    @Test
    void parquetFacts() {
        OutputFormat f = OutputFormat.resolve("PARQUET");
        assertEquals(OutputFormat.PARQUET, f);
        assertEquals(".parquet", f.extension());
        assertEquals("PARQUET", f.copyToken());
        assertTrue(f.supportsCompression());
    }

    @Test
    void csvFacts() {
        OutputFormat f = OutputFormat.resolve("CSV");
        assertEquals(OutputFormat.CSV, f);
        assertEquals(".csv", f.extension());
        assertEquals("CSV", f.copyToken());
        assertFalse(f.supportsCompression());
    }

    @Test
    void unknownNullAndNonCanonicalResolveToCsv() {
        // Mirrors the original `"PARQUET".equals(outputFormat)` rule: anything that is
        // not exactly "PARQUET" — an unknown format, lower-case, or null — is CSV.
        assertEquals(OutputFormat.CSV, OutputFormat.resolve("ORC"));
        assertEquals(OutputFormat.CSV, OutputFormat.resolve("parquet"));
        assertEquals(OutputFormat.CSV, OutputFormat.resolve(null));
    }
}

package com.gamma.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsvLedgerTest {

    record Row(String id, long n, String note) {}

    private static CsvLedger<Row> ledger(Path file) {
        return new CsvLedger<>(file.toString(), "id,n,note",
                r -> String.format("%s,%d,\"%s\"", r.id(), r.n(), CsvLedger.q(r.note())));
    }

    @Test
    void writesHeaderOnFirstCreateOnly(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("ledger.csv");
        CsvLedger<Row> l = ledger(file);
        l.append(new Row("a", 1, "first"));
        l.append(new Row("b", 2, "second"));
        List<String> lines = Files.readAllLines(file);
        assertEquals(List.of("id,n,note", "a,1,\"first\"", "b,2,\"second\""), lines);
    }

    @Test
    void appendAllWritesContiguouslyUnderOneOpen(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("ledger.csv");
        ledger(file).appendAll(List.of(new Row("a", 1, "x"), new Row("b", 2, "y")));
        assertEquals(3, Files.readAllLines(file).size());   // header + 2 rows
    }

    @Test
    void appendAllOnEmptyIterableStillCreatesHeaderFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("ledger.csv");
        ledger(file).appendAll(List.of());
        assertEquals(List.of("id,n,note"), Files.readAllLines(file));
    }

    @Test
    void survivesProcessRestartByAppending(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("ledger.csv");
        ledger(file).append(new Row("a", 1, "x"));
        ledger(file).append(new Row("b", 2, "y"));   // a NEW ledger over the same file
        List<String> lines = Files.readAllLines(file);
        assertEquals(3, lines.size());
        assertEquals("id,n,note", lines.get(0));     // header written exactly once
    }

    @Test
    void qReplacesDoubleQuotesAndMapsNullToEmpty() {
        assertEquals("he said 'hi'", CsvLedger.q("he said \"hi\""));
        assertEquals("", CsvLedger.q(null));
    }

    @Test
    void quotedFieldsSurviveARoundTripThroughCsvReadInto(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("ledger.csv");
        // Backslash-bearing Windows path in a quoted field — the historical regression case.
        ledger(file).append(new Row("a", 1, "C:\\db\\out.csv"));
        var rows = new java.util.ArrayList<java.util.Map<String, String>>();
        Csv.readInto(file, rows);
        assertEquals(1, rows.size());
        assertEquals("C:\\db\\out.csv", rows.get(0).get("note"));
        assertTrue(rows.get(0).keySet().containsAll(List.of("id", "n", "note")));
    }
}

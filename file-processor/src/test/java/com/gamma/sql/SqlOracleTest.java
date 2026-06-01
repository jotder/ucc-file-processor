package com.gamma.sql;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the {@code kpi-to-sql} validation oracle against real seeded partitions (M6, gap G4). It must:
 * derive the authoritative column list, surface preview rows only on request, feed back the engine's
 * verbatim error for an unplannable query, and refuse a file-reading query lexically — before any
 * connection is opened.
 */
class SqlOracleTest {

    private static final SqlSandboxPolicy POLICY = SqlSandboxPolicy.withCaps("512MB", 1, 10);

    /** Seed a small CSV "partition" and return a ViewSpec named {@code input} over it. */
    private static SqlOracle.ViewSpec seedInput(Path dir) throws IOException {
        Path csv = dir.resolve("events.csv");
        Files.writeString(csv, "day,cell,dur\n2026-01-01,A,10\n2026-01-01,A,20\n2026-01-01,B,30\n");
        return new SqlOracle.ViewSpec("input", "CSV",
                csv.toAbsolutePath().toString().replace('\\', '/'), false);
    }

    @Test
    void validSelectDerivesColumnsAndNoSampleByDefault(@TempDir Path dir) throws Exception {
        SqlOracle.Result r = new SqlOracle(POLICY).validate(new SqlOracle.Request(
                "SELECT cell, COUNT(*) AS calls FROM input GROUP BY cell",
                List.of(seedInput(dir)), false));

        assertTrue(r.ok(), "a valid aggregate over the seeded partition plans: " + r.error());
        assertEquals(List.of("cell", "calls"), r.columnsProduced(),
                "columns are authoritative, from ResultSetMetaData");
        assertTrue(r.sampleRows().isEmpty(), "no preview rows unless requested");
    }

    @Test
    void sampleRowsReturnedOnlyWhenRequested(@TempDir Path dir) throws Exception {
        SqlOracle.ViewSpec input = seedInput(dir);
        SqlOracle oracle = new SqlOracle(POLICY);

        SqlOracle.Result with = oracle.validate(new SqlOracle.Request(
                "SELECT cell, COUNT(*) AS calls FROM input GROUP BY cell ORDER BY cell",
                List.of(input), true));
        assertTrue(with.ok(), with.error());
        assertFalse(with.sampleRows().isEmpty(), "preview rows present when requested");
        assertTrue(with.sampleRows().size() <= 5, "preview is bounded");
        Map<String, Object> first = with.sampleRows().get(0);
        assertEquals("A", String.valueOf(first.get("cell")));
        assertEquals(2L, ((Number) first.get("calls")).longValue(), "cell A has two calls");
    }

    @Test
    void joinAcrossTwoRegisteredInputsPlans(@TempDir Path dir) throws Exception {
        SqlOracle.ViewSpec input = seedInput(dir);
        Path ref = dir.resolve("cells.csv");
        Files.writeString(ref, "cell,region\nA,north\nB,south\n");
        SqlOracle.ViewSpec cells = new SqlOracle.ViewSpec("cells", "CSV",
                ref.toAbsolutePath().toString().replace('\\', '/'), false);

        SqlOracle.Result r = new SqlOracle(POLICY).validate(new SqlOracle.Request(
                "SELECT c.region, COUNT(*) AS calls FROM input i JOIN cells c ON i.cell = c.cell "
                        + "GROUP BY c.region", List.of(input, cells), false));

        assertTrue(r.ok(), "a join across two registered inputs plans: " + r.error());
        assertEquals(List.of("region", "calls"), r.columnsProduced());
    }

    @Test
    void unknownColumnFailsWithEngineError(@TempDir Path dir) throws Exception {
        SqlOracle.Result r = new SqlOracle(POLICY).validate(new SqlOracle.Request(
                "SELECT no_such_column FROM input", List.of(seedInput(dir)), false));

        assertFalse(r.ok(), "a query referencing a missing column must not validate");
        assertNotNull(r.error());
        assertTrue(r.error().toLowerCase().contains("no_such_column")
                        || r.error().toLowerCase().contains("not found")
                        || r.error().toLowerCase().contains("column"),
                "the verbatim engine error is surfaced for the repair loop: " + r.error());
    }

    @Test
    void unknownTableFailsWithEngineError(@TempDir Path dir) throws Exception {
        SqlOracle.Result r = new SqlOracle(POLICY).validate(new SqlOracle.Request(
                "SELECT * FROM not_registered", List.of(seedInput(dir)), false));
        assertFalse(r.ok(), "referencing an unregistered table is an engine error");
        assertNotNull(r.error());
    }

    @Test
    void fileReadingQueryIsRejectedLexically(@TempDir Path dir) throws Exception {
        SqlOracle.Result r = new SqlOracle(POLICY).validate(new SqlOracle.Request(
                "SELECT * FROM read_csv('/etc/passwd')", List.of(seedInput(dir)), false));

        assertFalse(r.ok(), "a file-reading query must be refused");
        assertFalse(r.findings().isEmpty(), "rejection is lexical (guard), before any connection");
        assertTrue(r.error().contains("read_csv"));
    }

    @Test
    void multiStatementIsRejectedLexically(@TempDir Path dir) throws Exception {
        SqlOracle.Result r = new SqlOracle(POLICY).validate(new SqlOracle.Request(
                "SELECT 1; DROP TABLE input", List.of(seedInput(dir)), false));
        assertFalse(r.ok());
        assertFalse(r.findings().isEmpty(), "guard catches the second statement before execution");
    }
}

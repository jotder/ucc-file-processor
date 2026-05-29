package com.gamma.etl;

import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Characterization tests for {@link CsvIngester}'s skip / junk / tail / error
 * branches — the trickiest, least-covered logic in the codebase. These pin the
 * current behaviour so the Java path can be refactored safely later, and they
 * document exactly what each {@code csv_settings} knob does.
 */
class CsvIngesterSkipLogicTest {

    private static final String SCHEMA = PipelineConfigBatchTest.miniSchema(); // 3 cols: ID, AMT, EVENT_DATE

    private IngestResult ingest(PipelineConfig cfg, File csv) throws Exception {
        File db = DuckDbUtil.tempDbFile("ci_");
        try (Connection conn = DuckDbUtil.openConnection(db)) {
            return CsvIngester.ingest(csv, conn, cfg.schemas().single(), cfg, "raw_f0");
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    /** skip_tail_lines drops the last N lines even when they are otherwise valid rows. */
    @Test
    void skipTailLinesDropsValidTrailingRows(@TempDir Path dir) throws Exception {
        File csv = dir.resolve("tail.csv").toFile();
        Files.writeString(csv.toPath(), """
                ID,AMT,EVENT_DATE
                1,10,2020-01-01
                2,20,2020-01-02
                3,30,2020-01-03
                98,0,2020-12-30
                99,0,2020-12-31
                """);
        PipelineConfig cfg = TestConfigs.csv(dir, SCHEMA).skipTail(2).load();
        IngestResult r = ingest(cfg, csv);
        assertEquals(3, r.parsedRows(), "last 2 (valid) rows must be dropped as footer");
    }

    /** skip_tail_columns trims phantom trailing columns before binding selectors. */
    @Test
    void skipTailColumnsTrimsExtras(@TempDir Path dir) throws Exception {
        File csv = dir.resolve("cols.csv").toFile();
        // 5 physical columns; the schema declares 3 (max selector 2) + 2 phantom.
        Files.writeString(csv.toPath(), """
                ID,AMT,EVENT_DATE,X,Y
                1,10,2020-01-01,junkA,junkB
                2,20,2020-01-02,junkA,junkB
                """);
        PipelineConfig cfg = TestConfigs.csv(dir, SCHEMA).skipTailCols(2).load();

        File db = DuckDbUtil.tempDbFile("ci2_");
        try (Connection conn = DuckDbUtil.openConnection(db)) {
            IngestResult r = CsvIngester.ingest(csv, conn, cfg.schemas().single(), cfg, "raw_f0");
            assertEquals(2, r.parsedRows());
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT EVENT_DATE FROM raw_f0 ORDER BY ID")) {
                assertTrue(rs.next());
                assertEquals("2020-01-01", rs.getString(1), "selector 2 must still bind correctly after trim");
            }
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    /** Adaptive junk detection skips leading banner lines with too few columns. */
    @Test
    void junkScanSkipsBannerLines(@TempDir Path dir) throws Exception {
        File csv = dir.resolve("junk.csv").toFile();
        Files.writeString(csv.toPath(), """
                ID,AMT,EVENT_DATE
                SQL*Plus banner line
                Connected to Oracle
                1,10,2020-01-01
                2,20,2020-01-02
                """);
        PipelineConfig cfg = TestConfigs.csv(dir, SCHEMA).skipJunk(5).load();
        IngestResult r = ingest(cfg, csv);
        assertEquals(2, r.parsedRows(), "two banner lines skipped, two data rows parsed");
        assertTrue(r.junkCandidateRows() >= 1, "banner lines counted as junk candidates");
    }

    /** A line echoing the header (>=50% column match) is treated as junk, not data. */
    @Test
    void junkScanSkipsEchoedHeader(@TempDir Path dir) throws Exception {
        File csv = dir.resolve("echo.csv").toFile();
        Files.writeString(csv.toPath(), """
                ID,AMT,EVENT_DATE
                ID,AMT,EVENT_DATE
                1,10,2020-01-01
                2,20,2020-01-02
                """);
        PipelineConfig cfg = TestConfigs.csv(dir, SCHEMA).skipJunk(5).load();
        IngestResult r = ingest(cfg, csv);
        assertEquals(2, r.parsedRows(), "echoed header line must be skipped, not parsed as data");
    }

    /** Rows with too few columns are rejected and written to the error CSV. */
    @Test
    void shortRowsRejectedToErrorCsv(@TempDir Path dir) throws Exception {
        File csv = dir.resolve("bad.csv").toFile();
        Files.writeString(csv.toPath(), """
                ID,AMT,EVENT_DATE
                1,10,2020-01-01
                BAD
                3,30,2020-01-03
                """);
        PipelineConfig cfg = TestConfigs.csv(dir, SCHEMA).load();
        IngestResult r = ingest(cfg, csv);
        assertEquals(2, r.parsedRows());
        assertEquals(1, r.errorRows(), "the 1-column line must be rejected");

        Path errCsv = dir.resolve("errors").resolve("bad_errors.csv");
        assertTrue(Files.exists(errCsv), "error CSV should be created");
        assertTrue(Files.readString(errCsv).contains("Insufficient columns"),
                "error CSV should record the rejection reason");
    }

    /** has_header=false treats the first line as data, not a column-name row. */
    @Test
    void headerlessTreatsFirstLineAsData(@TempDir Path dir) throws Exception {
        File csv = dir.resolve("noheader.csv").toFile();
        Files.writeString(csv.toPath(), """
                1,10,2020-01-01
                2,20,2020-01-02
                """);
        PipelineConfig cfg = TestConfigs.csv(dir, SCHEMA).hasHeader(false).load();
        IngestResult r = ingest(cfg, csv);
        assertEquals(2, r.parsedRows(), "no header row consumed → both lines are data");
    }

    /** No errors → no error CSV is created (lazy creation). */
    @Test
    void cleanFileWritesNoErrorCsv(@TempDir Path dir) throws Exception {
        File csv = dir.resolve("clean.csv").toFile();
        Files.writeString(csv.toPath(), """
                ID,AMT,EVENT_DATE
                1,10,2020-01-01
                """);
        PipelineConfig cfg = TestConfigs.csv(dir, SCHEMA).load();
        IngestResult r = ingest(cfg, csv);
        assertEquals(1, r.parsedRows());
        assertEquals(0, r.errorRows());
        assertFalse(Files.exists(dir.resolve("errors").resolve("clean_errors.csv")),
                "no error CSV when there are no rejects");
    }
}

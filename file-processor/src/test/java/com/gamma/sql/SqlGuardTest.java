package com.gamma.sql;

import com.gamma.config.spec.Finding;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial tests for the SQL allow-list — the lexical half of the M6 sandbox (gap G4). The cases
 * are the attacks an LLM-generated (or prompt-injected) query could carry: file reads, writes, multi-
 * statements, extension loads, config verbs, and comment-smuggled tokens. A plain read-only SELECT /
 * WITH over catalog views must pass clean. A false reject only costs a repair round; a false accept is
 * a security hole — so the bias is toward rejection.
 */
class SqlGuardTest {

    private static boolean rejects(String sql) {
        return !SqlGuard.check(sql).isEmpty();
    }

    private static String firstMessage(String sql) {
        List<Finding> f = SqlGuard.check(sql);
        return f.isEmpty() ? "" : f.get(0).message();
    }

    @Test
    void plainSelectPasses() {
        assertTrue(SqlGuard.isReadOnly("SELECT cell, COUNT(*) AS calls FROM input GROUP BY cell"),
                "a plain aggregate over a view is the happy path");
    }

    @Test
    void withCtePasses() {
        assertTrue(SqlGuard.isReadOnly(
                "WITH daily AS (SELECT day, cell, COUNT(*) c FROM input GROUP BY day, cell) "
                        + "SELECT day, SUM(c) FROM daily GROUP BY day"),
                "a single WITH … SELECT is read-only");
    }

    @Test
    void trailingSemicolonIsTolerated() {
        assertTrue(SqlGuard.isReadOnly("SELECT 1 AS x;"), "one trailing ';' is fine");
    }

    @Test
    void multiStatementIsRejected() {
        assertTrue(rejects("SELECT 1; DROP TABLE input"), "a second statement is forbidden");
        assertTrue(rejects("SELECT 1; SELECT 2"), "even two SELECTs are two statements");
    }

    @Test
    void readCsvIsRejected() {
        assertTrue(rejects("SELECT * FROM read_csv('/etc/passwd')"), "file reads are forbidden");
        assertTrue(firstMessage("SELECT * FROM read_csv('/etc/passwd')").contains("read_csv"));
    }

    @Test
    void readParquetAndScansAreRejected() {
        assertTrue(rejects("SELECT * FROM read_parquet('/data/secret/*.parquet')"));
        assertTrue(rejects("SELECT * FROM parquet_scan('/data/x.parquet')"));
    }

    @Test
    void copyToIsRejected() {
        assertTrue(rejects("COPY (SELECT * FROM input) TO '/tmp/exfil.csv'"),
                "COPY … TO writes a file — forbidden (and not a SELECT)");
    }

    @Test
    void installLoadAttachAreRejected() {
        assertTrue(rejects("INSTALL httpfs"));
        assertTrue(rejects("LOAD httpfs"));
        assertTrue(rejects("ATTACH 'remote.db' AS r"));
    }

    @Test
    void getenvAndPragmaAndSetAreRejected() {
        assertTrue(rejects("SELECT getenv('PATH')"));
        assertTrue(rejects("PRAGMA database_list"));
        assertTrue(rejects("SET enable_external_access=true"));
    }

    @Test
    void ddlAndDmlAreRejected() {
        assertTrue(rejects("CREATE TABLE x AS SELECT 1"));
        assertTrue(rejects("INSERT INTO input VALUES (1)"));
        assertTrue(rejects("UPDATE input SET cell='x'"));
        assertTrue(rejects("DELETE FROM input"));
    }

    @Test
    void commentSmuggledTokenIsRejected() {
        // Block comments are removed to empty before scanning, so the split token re-joins and is caught.
        assertTrue(rejects("SELECT * FROM read/**/_csv('/etc/passwd')"),
                "a comment cannot be used to split a blocked function name");
        assertTrue(rejects("SELECT 1; /* hide */ DROP TABLE input"),
                "a comment cannot hide a second statement");
    }

    @Test
    void unterminatedBlockCommentIsRejected() {
        assertTrue(rejects("SELECT 1 /* never closed"), "an unterminated block comment is suspicious");
        assertTrue(firstMessage("SELECT 1 /* never closed").contains("unterminated"));
    }

    @Test
    void lineCommentDoesNotFalselyTrip() {
        assertTrue(SqlGuard.isReadOnly("SELECT cell -- the serving cell\nFROM input"),
                "a benign line comment is stripped, not flagged");
    }

    @Test
    void stringLiteralWithCommentMarkersPasses() {
        assertTrue(SqlGuard.isReadOnly("SELECT '/* not a comment */' AS label FROM input"),
                "comment markers inside a string literal are data, not comments");
    }

    @Test
    void emptyOrBlankIsRejected() {
        assertTrue(rejects(""));
        assertTrue(rejects("   "));
        assertTrue(rejects(null));
        assertTrue(rejects("/* only a comment */"));
    }
}

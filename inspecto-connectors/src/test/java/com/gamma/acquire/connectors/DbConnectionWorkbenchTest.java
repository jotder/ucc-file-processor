package com.gamma.acquire.connectors;

import com.gamma.acquire.ConnectionProber;
import com.gamma.acquire.ConnectionProfile;
import com.gamma.acquire.ConnectionWorkbench;
import com.gamma.acquire.ConnectionWorkbench.CheckOutcome;
import com.gamma.acquire.ConnectionWorkbench.ProbeCheck;
import com.gamma.acquire.ConnectionWorkbench.ResourceNode;
import com.gamma.acquire.ConnectionWorkbench.SampleResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DB connection workbench — exercises {@link DbConnectionWorkbench} against an in-process DuckDB JDBC database
 * (driver already on the test classpath; the workbench is JDBC-generic). Covers the schema→table→column
 * explore tree, bounded sampling, the graded probe (with the read-only write skip), honest 404s, and the
 * ServiceLoader factory wiring.
 */
class DbConnectionWorkbenchTest {

    /** Create a DuckDB file, run the DDL, close so the workbench opens its own reader connection. */
    private static String seedDuckDb(Path dir, String ddl) throws Exception {
        Path db = dir.resolve("wb_src.duckdb");
        String url = "jdbc:duckdb:" + db.toString().replace("\\", "/");
        try (Connection c = DriverManager.getConnection(url); Statement st = c.createStatement()) {
            for (String sql : ddl.split(";")) if (!sql.isBlank()) st.execute(sql);
        }
        return url;
    }

    private static ConnectionProfile dbProfile(String url) {
        return new ConnectionProfile("wb", "db", null, 0, null, null, null, null, Map.of("jdbc_url", url), null);
    }

    @Test
    void exploreWalksSchemaTableColumnTree(@TempDir Path dir) throws Exception {
        String url = seedDuckDb(dir, """
            CREATE TABLE cdr(ID INTEGER, AMT DOUBLE, EVENT_DATE DATE);
            INSERT INTO cdr VALUES (1, 1.5, DATE '2020-04-03');
            """);
        try (ConnectionWorkbench wb = new DbConnectionWorkbench(dbProfile(url))) {
            List<ResourceNode> schemas = wb.explore("");
            assertTrue(schemas.stream().anyMatch(n -> n.name().equals("main")
                            && n.kind() == ResourceNode.Kind.SCHEMA && n.hasChildren()),
                    "root explore lists schemas including 'main'");

            List<ResourceNode> tables = wb.explore("main");
            assertTrue(tables.stream().anyMatch(n -> n.name().equals("cdr")
                            && n.kind() == ResourceNode.Kind.TABLE && n.path().equals("main/cdr")),
                    "schema explore lists tables");

            List<ResourceNode> cols = wb.explore("main/cdr");
            assertEquals(List.of("ID", "AMT", "EVENT_DATE"), cols.stream().map(ResourceNode::name).toList(),
                    "table explore lists columns in ordinal (not alphabetical) order");
            assertTrue(cols.stream().allMatch(n -> n.kind() == ResourceNode.Kind.COLUMN && !n.hasChildren()),
                    "columns are leaf nodes");
        }
    }

    @Test
    void sampleReturnsBoundedRowsAndFlagsTruncation(@TempDir Path dir) throws Exception {
        String url = seedDuckDb(dir, """
            CREATE TABLE t(a INTEGER, b VARCHAR);
            INSERT INTO t VALUES (1,'x'),(2,'y'),(3,'z');
            """);
        try (ConnectionWorkbench wb = new DbConnectionWorkbench(dbProfile(url))) {
            SampleResult capped = wb.sample("main/t", 2);
            assertEquals("main/t", capped.path());
            assertEquals(List.of("a", "b"), capped.columns());
            assertEquals(2, capped.rows().size(), "row cap honoured");
            assertTrue(capped.truncated(), "more rows exist beyond the cap");

            SampleResult all = wb.sample("main/t", 10);
            assertEquals(3, all.rows().size());
            assertFalse(all.truncated(), "whole table fits under the cap");
        }
    }

    @Test
    void probeGradesChecksAndSkipsWrite(@TempDir Path dir) throws Exception {
        String url = seedDuckDb(dir, "CREATE TABLE t(a INTEGER); INSERT INTO t VALUES (1);");
        try (ConnectionWorkbench wb = new DbConnectionWorkbench(dbProfile(url))) {
            assertTrue(wb.check(ProbeCheck.AUTHENTICATE, 25).ok(), "connection opens");
            assertTrue(wb.check(ProbeCheck.READ, 25).ok(), "schema catalog readable");
            CheckOutcome write = wb.check(ProbeCheck.WRITE, 25);
            assertTrue(write.skipped(), "write must be skipped — a workbench never mutates a database");
            assertFalse(write.ok(), "a skipped check is not an ok check");
            assertTrue(wb.check(ProbeCheck.LIST, 25).ok(), "tables listable");
        }
    }

    @Test
    void unknownPathsRefuseHonestly(@TempDir Path dir) throws Exception {
        String url = seedDuckDb(dir, "CREATE TABLE t(a INTEGER);");
        try (ConnectionWorkbench wb = new DbConnectionWorkbench(dbProfile(url))) {
            assertThrows(ConnectionWorkbench.NoSuchPath.class, () -> wb.explore("nope"), "unknown schema");
            assertThrows(ConnectionWorkbench.NoSuchPath.class, () -> wb.explore("main/nope"), "unknown table");
            assertThrows(ConnectionWorkbench.NoSuchPath.class, () -> wb.explore("main/t/a/b"), "path too deep");
            assertThrows(ConnectionWorkbench.NoSuchPath.class, () -> wb.sample("main", 10), "sample needs schema/table");
            assertThrows(ConnectionWorkbench.NoSuchPath.class, () -> wb.sample("main/nope", 10), "unknown table sample");
        }
    }

    @Test
    void proberResolvesDbWorkbenchViaServiceLoader(@TempDir Path dir) throws Exception {
        String url = seedDuckDb(dir, "CREATE TABLE t(a INTEGER);");
        try (ConnectionWorkbench wb = ConnectionProber.workbenchFor(dbProfile(url))) {
            assertNotNull(wb, "the 'db' factory contributes a workbench via ServiceLoader");
            assertInstanceOf(DbConnectionWorkbench.class, wb);
        }
    }
}

package com.gamma.sql;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the hardened DuckDB connection — the native half of the M6 sandbox (gap G4). The point is the
 * {@code seal()} boundary: before it, the trusted oracle may read real partition files to register
 * views; after it, the untrusted candidate runs with no file access and a frozen configuration it
 * cannot re-open. So a sealed connection must reject both a config change and a file read.
 */
class SqlSandboxTest {

    private static final SqlSandboxPolicy POLICY = SqlSandboxPolicy.withCaps("512MB", 1, 10);

    @Test
    void runsTrustedQueryBeforeSeal() throws Exception {
        try (SqlSandbox sb = SqlSandbox.open(POLICY); Statement st = sb.statement()) {
            try (ResultSet rs = st.executeQuery("SELECT 21 + 21 AS answer")) {
                assertTrue(rs.next());
                assertEquals(42, rs.getInt("answer"));
            }
        }
    }

    @Test
    void readsRealFileBeforeSealButNotAfter(@TempDir Path dir) throws Exception {
        Path csv = dir.resolve("data.csv");
        Files.writeString(csv, "id,amt\n1,10\n2,20\n");
        String glob = csv.toAbsolutePath().toString().replace('\\', '/');

        try (SqlSandbox sb = SqlSandbox.open(POLICY)) {
            // Trusted phase: external access is on, the oracle can read the legitimate partition file.
            try (Statement st = sb.statement();
                 ResultSet rs = st.executeQuery(
                         "SELECT COUNT(*) AS n FROM read_csv('" + glob + "', header=true)")) {
                assertTrue(rs.next());
                assertEquals(2, rs.getInt("n"), "external access works before seal");
            }

            sb.seal();

            // Sealed: the untrusted candidate cannot reach the filesystem at all.
            try (Statement st = sb.statement()) {
                assertThrows(SQLException.class,
                        () -> st.executeQuery("SELECT * FROM read_csv('" + glob + "', header=true)"),
                        "external access must be denied after seal");
            }
        }
    }

    @Test
    void sealLocksConfiguration() throws Exception {
        try (SqlSandbox sb = SqlSandbox.open(POLICY)) {
            sb.seal();
            assertTrue(sb.isSealed());
            try (Statement st = sb.statement()) {
                assertThrows(SQLException.class,
                        () -> st.execute("SET enable_external_access=true"),
                        "configuration is locked — the candidate cannot re-open access");
                assertThrows(SQLException.class,
                        () -> st.execute("SET memory_limit='8GB'"),
                        "configuration is locked — the candidate cannot raise its own caps");
            }
        }
    }

    @Test
    void sealIsIdempotent() throws Exception {
        try (SqlSandbox sb = SqlSandbox.open(POLICY)) {
            sb.seal();
            sb.seal(); // must not throw
            assertTrue(sb.isSealed());
        }
    }

    @Test
    void closeClosesTheConnection() throws Exception {
        SqlSandbox sb = SqlSandbox.open(POLICY);
        var conn = sb.connection();
        sb.close();
        assertTrue(conn.isClosed(), "close() releases the underlying connection");
    }
}

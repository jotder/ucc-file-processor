package com.gamma.inspector;

import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reference Phase-2 P1 write-side verify: {@link BatchIngestStrategy#stampReferenceVersions} appends
 * the §2.1 system columns ({@code __key_hash}/{@code __valid_from}/{@code __op}/{@code __batch_id}) and
 * folds out within-batch key duplicates (one version per key per batch).
 */
class ReferenceVersionStampTest {

    @Test
    void stampsSystemColumnsAndFoldsWithinBatchDuplicates() throws Exception {
        File db = DuckDbUtil.tempDbFile("stamp_");
        try (Connection c = DuckDbUtil.openConnection(db); Statement st = c.createStatement()) {
            // A batch delivering C1 twice (a within-batch duplicate) plus C2 once.
            st.execute("CREATE TABLE transformed AS SELECT * FROM (VALUES "
                    + "('C1','NA'),('C1','NA'),('C2','EU')) t(customer_id, region)");

            BatchIngestStrategy.stampReferenceVersions(c, "transformed", "__ref_versioned",
                    List.of("customer_id"), "batch-42");

            // within-batch dedup: C1 collapses to one version → two rows total
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM __ref_versioned")) {
                assertTrue(rs.next());
                assertEquals(2L, rs.getLong(1), "within-batch duplicate key folded to one version");
            }
            // one row per distinct key, each stamped upsert with this batch id
            try (ResultSet rs = st.executeQuery(
                    "SELECT COUNT(DISTINCT __key_hash) AS keys, "
                    + "COUNT(*) FILTER (WHERE __op='upsert') AS upserts, "
                    + "COUNT(*) FILTER (WHERE __batch_id='batch-42') AS tagged, "
                    + "COUNT(*) FILTER (WHERE __valid_from IS NOT NULL) AS stamped "
                    + "FROM __ref_versioned")) {
                assertTrue(rs.next());
                assertEquals(2L, rs.getLong("keys"), "distinct __key_hash per key");
                assertEquals(2L, rs.getLong("upserts"), "ingest path stamps __op = upsert");
                assertEquals(2L, rs.getLong("tagged"), "every row carries the batch id");
                assertEquals(2L, rs.getLong("stamped"), "every row carries a __valid_from");
            }
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    @Test
    void emptyKeyIsRejected() throws Exception {
        File db = DuckDbUtil.tempDbFile("stamp_");
        try (Connection c = DuckDbUtil.openConnection(db); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE transformed AS SELECT * FROM (VALUES ('C1')) t(customer_id)");
            assertThrows(IllegalStateException.class, () ->
                    BatchIngestStrategy.stampReferenceVersions(c, "transformed", "__ref_versioned",
                            List.of(), "batch-1"));
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }
}

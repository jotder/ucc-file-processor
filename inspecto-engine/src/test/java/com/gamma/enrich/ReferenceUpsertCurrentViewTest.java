package com.gamma.enrich;

import com.gamma.etl.PipelineConfig;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reference Phase-2 P1 read-side verify: an {@code upsert} reference store is append-only (one version
 * row per key per batch, stamped with the system columns from §2.1). When an enrichment binds it
 * {@code by-name}, {@link EnrichmentEngine} must resolve the <b>current view</b> — latest
 * {@code __valid_from} wins per {@code __key_hash}, {@code __op = 'delete'} tombstones drop the key,
 * system columns are stripped — not the raw append log.
 *
 * <p>Two batches are staged exactly as the write path lays them down (see
 * {@code BatchIngestStrategy.stampReferenceVersions}): batch 2 <em>changes</em> one key, re-delivers
 * one <em>unchanged</em>, adds a <em>new</em> key, and <em>tombstones</em> a fourth.
 */
class ReferenceUpsertCurrentViewTest {

    /** The canonical key hash the write path stamps: {@code md5(concat_ws(chr(31), COALESCE(CAST(key AS VARCHAR),'')))}. */
    private static String keyHash(String customerId) {
        return "md5(concat_ws(chr(31), COALESCE(CAST('" + customerId + "' AS VARCHAR), '')))";
    }

    /** Append one version file to an upsert reference store, stamped with the system columns. */
    private static void appendBatch(Connection c, java.nio.file.Path refdb, String fileStem,
                                    String batchId, String validFrom,
                                    List<String[]> rows /* {customer_id, region, __op} */) throws Exception {
        StringBuilder values = new StringBuilder();
        for (int i = 0; i < rows.size(); i++) {
            String[] r = rows.get(i);
            if (i > 0) values.append(", ");
            values.append("('").append(r[0]).append("', '").append(r[1]).append("', ")
                  .append(keyHash(r[0])).append(", TIMESTAMP '").append(validFrom).append("', '")
                  .append(r[2]).append("', '").append(batchId).append("')");
        }
        String target = refdb.resolve(fileStem + ".parquet").toString().replace("\\", "/");
        try (Statement st = c.createStatement()) {
            st.execute("COPY (SELECT * FROM (VALUES " + values + ") "
                    + "t(customer_id, region, __key_hash, __valid_from, __op, __batch_id)) "
                    + "TO '" + target + "' (FORMAT PARQUET)");
        }
    }

    /** A {@code produces: reference}, {@code load: upsert} pipeline whose output tree is {@code refdb}. */
    private static PipelineConfig upsertReferenceProducer(java.nio.file.Path dir, java.nio.file.Path refdb) throws Exception {
        return PipelineConfig.fromMap(Map.of(
                "name", "CUSTOMER_DIM",
                "produces", "reference",
                "reference", Map.of("load", "upsert", "key", List.of("customer_id")),
                "dirs", Map.of("poll", dir.resolve("ref_in").toString(), "database", refdb.toString()),
                "output", Map.of("format", "PARQUET"),
                "processing", Map.of("threads", 1)));
    }

    @Test
    void currentViewResolvesLatestPerKeyDropsTombstonesAndKeepsNewAndUnchanged(@TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Path refdb = dir.resolve("refdb");
        java.nio.file.Files.createDirectories(refdb);

        File db = DuckDbUtil.tempDbFile("refseed_");
        try (Connection c = DuckDbUtil.openConnection(db)) {
            // batch 1: C1→NA, C2→EU, C3→NA (all upserts)
            appendBatch(c, refdb, "b1", "b1", "2026-07-24 10:00:00", List.of(
                    new String[]{"C1", "NA", "upsert"},
                    new String[]{"C2", "EU", "upsert"},
                    new String[]{"C3", "NA", "upsert"}));
            // batch 2: C1 CHANGED→APAC, C2 UNCHANGED (re-delivered), C4 NEW→SA, C3 DELETE tombstone
            appendBatch(c, refdb, "b2", "b2", "2026-07-24 11:00:00", List.of(
                    new String[]{"C1", "APAC", "upsert"},
                    new String[]{"C2", "EU", "upsert"},
                    new String[]{"C4", "SA", "upsert"},
                    new String[]{"C3", "", "delete"}));
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }

        // A tiny enrichment that simply surfaces the by-name reference's current view.
        java.nio.file.Path in = dir.resolve("in"), out = dir.resolve("out");
        seedTrivialInput(in);
        EnrichmentConfig cfg = new EnrichmentConfig(
                "CUSTOMER_REGIONS",
                new EnrichmentConfig.Input(in.toString().replace("\\", "/"), "PARQUET", List.of("p")),  // input unused by the transform

                List.of(new EnrichmentConfig.Reference("customer_dim", null, null, "customer_dim")),
                new EnrichmentConfig.Output(out.toString().replace("\\", "/"), "PARQUET", "snappy", List.of("region")),
                "SELECT customer_id, region FROM customer_dim");

        EnrichmentEngine.runResult(cfg, null, List.of(upsertReferenceProducer(dir, refdb)));

        Map<String, String> current = readCurrentRegions(out);
        assertEquals(Map.of("C1", "APAC", "C2", "EU", "C4", "SA"), current,
                "current view = latest-per-key, tombstoned C3 dropped, unchanged C2 kept, new C4 added");
        assertFalse(current.containsKey("C3"), "delete tombstone removes the key from the current view");
    }

    /** Minimal Stage-1-style input tree (unused by the transform, but the engine builds the input view). */
    private static void seedTrivialInput(java.nio.file.Path root) throws Exception {
        File db = DuckDbUtil.tempDbFile("seed_");
        try (Connection c = DuckDbUtil.openConnection(db); Statement st = c.createStatement()) {
            st.execute("COPY (SELECT * FROM (VALUES ('x', 1)) t(p, n)) TO '"
                    + root.toString().replace("\\", "/")
                    + "' (FORMAT PARQUET, PARTITION_BY (p), OVERWRITE_OR_IGNORE 1)");
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    private static Map<String, String> readCurrentRegions(java.nio.file.Path outRoot) throws Exception {
        Map<String, String> m = new HashMap<>();
        File db = DuckDbUtil.tempDbFile("verify_");
        try (Connection c = DuckDbUtil.openConnection(db); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT customer_id, region FROM read_parquet('" + outRoot.toString().replace("\\", "/")
                     + "/**/*.parquet', hive_partitioning=true, hive_types_autocast=0)")) {
            while (rs.next()) m.put(rs.getString("customer_id"), rs.getString("region"));
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
        return m;
    }
}

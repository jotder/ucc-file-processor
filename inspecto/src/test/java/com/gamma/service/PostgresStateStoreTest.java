package com.gamma.service;

import com.gamma.acquire.DbAcquisitionLedger;
import com.gamma.acquire.LedgerEntry;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.job.DbJobRunStore;
import com.gamma.job.JobRun;
import com.gamma.ops.DbObjectStore;
import com.gamma.ops.ObjectQuery;
import com.gamma.ops.ObjectType;
import com.gamma.ops.OperationalObject;
import com.gamma.ops.link.DbLinkStore;
import com.gamma.ops.link.ObjectLink;
import com.gamma.ops.note.DbNoteStore;
import com.gamma.ops.note.ObjectNote;
import com.gamma.ops.note.NoteKind;
import com.gamma.pipeline.exec.DbProvenanceStore;
import com.gamma.pipeline.exec.ProvenanceRow;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DAT-6 — proves the seven JDBC-backed stores actually run on <b>real PostgreSQL</b>, not just the
 * bundled DuckDB. A single embedded Postgres (io.zonky, test-scope only — never shipped) is booted
 * once and every store opens against it, runs {@code initSchema}, and does a write→read round-trip. The
 * stores use distinct table names, so they coexist in one database without collision.
 *
 * <p>The critical case is {@link DbJobRunStore#metrics} (p50/p95): those percentiles are the one piece of
 * non-portable SQL — DuckDB's {@code quantile_cont} vs Postgres's {@code percentile_cont(..) WITHIN GROUP}
 * — and this test exercises them against the real engine to pin the dialect fix.
 */
class PostgresStateStoreTest {

    private static EmbeddedPostgres pg;
    private static String url;

    @BeforeAll
    static void startPostgres() throws Exception {
        pg = EmbeddedPostgres.builder().start();
        // URL carries user=postgres, so the no-credential open(url) factories (jobs/provenance) work too.
        url = pg.getJdbcUrl("postgres", "postgres");
    }

    @AfterAll
    static void stopPostgres() throws Exception {
        if (pg != null) pg.close();
    }

    @Test
    void jobRunStore_metricsUsePostgresPercentile() throws Exception {
        try (DbJobRunStore store = DbJobRunStore.open(url)) {
            for (long d : new long[] {100, 200, 300, 400, 500}) {
                store.record(new JobRun("run-" + d, "nightly", "PIPELINE", "SCHEDULE",
                        "2026-07-07T00:00:00Z", "2026-07-07T00:01:00Z", "SUCCESS", d, "ok"));
            }
            store.record(new JobRun("run-fail", "nightly", "PIPELINE", "SCHEDULE",
                    "2026-07-07T00:00:00Z", "2026-07-07T00:01:00Z", "FAILED", 999, "boom"));

            Map<String, Object> m = store.metrics("nightly");
            assertEquals(6L, m.get("total"), "all runs counted");
            assertEquals(5L, m.get("success"), "success FILTER worked on Postgres");
            assertEquals(1L, m.get("failed"), "failed FILTER worked on Postgres");
            // percentile_cont(0.5) WITHIN GROUP over {100,200,300,400,500,999} = 350.0
            assertEquals(350.0, (double) m.get("p50Ms"), 0.001, "Postgres percentile_cont p50");
            double p95 = (double) m.get("p95Ms");
            assertTrue(p95 > 500.0 && p95 <= 999.0, "p95 lands in the upper tail: " + p95);

            assertFalse(store.recentRuns(10, "nightly").isEmpty(), "recent runs read back");
            assertFalse(store.failureTrend(7).isEmpty(), "failure trend groups by day on Postgres");
        }
    }

    @Test
    void objectStore_createUpdateQueryRoundTrip() throws Exception {
        try (DbObjectStore store = DbObjectStore.open(url, null, null)) {
            OperationalObject obj = OperationalObject.builder(ObjectType.ALERT)
                    .id("PG-ALERT-1").title("disk full").status("OPEN").severity("HIGH")
                    .owner("ops").attr("rule", "disk>90").build();
            store.create(obj);

            Optional<OperationalObject> got = store.get("PG-ALERT-1");
            assertTrue(got.isPresent(), "object read back from Postgres");
            assertEquals("disk full", got.get().title());
            assertEquals("disk>90", got.get().attributes().get("rule"), "JSON attributes survived");

            store.update(got.get().withStatus("RESOLVED", System.currentTimeMillis(), true));
            assertEquals("RESOLVED", store.get("PG-ALERT-1").orElseThrow().status());

            List<OperationalObject> hits = store.query(
                    new ObjectQuery(ObjectType.ALERT, "RESOLVED", null, null, null, null, null, 10, 0));
            assertTrue(hits.stream().anyMatch(o -> o.id().equals("PG-ALERT-1")), "query filter matched");
        }
    }

    @Test
    void linkStore_appendAndReadRoundTrip() throws Exception {
        try (DbLinkStore store = DbLinkStore.open(url, null, null)) {
            store.add(ObjectLink.of("CASE-1", ObjectType.CASE, "INC-1", ObjectType.INCIDENT, "CONTAINS"));
            store.add(ObjectLink.of("INC-1", ObjectType.INCIDENT, "ALERT-1", ObjectType.ALERT, "ESCALATED_FROM"));

            List<ObjectLink> incident = store.incident("INC-1");
            assertEquals(2, incident.size(), "both edges touching INC-1 read back from Postgres");
            assertTrue(store.all(10).size() >= 2);
        }
    }

    @Test
    void noteStore_appendAndReadRoundTrip() throws Exception {
        try (DbNoteStore store = DbNoteStore.open(url, null, null)) {
            store.add(ObjectNote.comment("PG-ALERT-1", "alice", "looking into it"));
            store.add(ObjectNote.attachment("PG-ALERT-1", "bob", "log.txt", "text/plain", "s3://x", "the log"));

            List<ObjectNote> all = store.forObject("PG-ALERT-1", null);
            assertEquals(2, all.size(), "both notes read back from Postgres");
            List<ObjectNote> comments = store.forObject("PG-ALERT-1", NoteKind.COMMENT);
            assertEquals(1, comments.size(), "kind filter worked");
            assertEquals("the log", store.forObject("PG-ALERT-1", NoteKind.ATTACHMENT).get(0).body());
        }
    }

    @Test
    void provenanceStore_recordAndQueryRoundTrip() throws Exception {
        try (DbProvenanceStore store = DbProvenanceStore.open(url)) {
            store.record(List.of(
                    new ProvenanceRow("flow-1", "batch-1", "parse", "data", 100, "2026-07-07T00:00:00Z"),
                    new ProvenanceRow("flow-1", "batch-1", "parse", "invalid", 5, "2026-07-07T00:00:00Z")));

            List<Map<String, Object>> rows = store.query("flow-1", "batch-1");
            assertEquals(2, rows.size(), "both provenance cells read back from Postgres");
            assertFalse(store.batches("flow-1", 10).isEmpty(), "batch summary (max/sum/group by) works on Postgres");
        }
    }

    @Test
    void acquisitionLedger_recordFindAndWatermarkRoundTrip() throws Exception {
        try (DbAcquisitionLedger ledger = DbAcquisitionLedger.open(url, null, null)) {
            ledger.record(new LedgerEntry("sftp-src", "2026/07/data.csv", "data.csv", 4096,
                    "sha256:abc", "etag-1", "v3", 1_000L, 2_000L, LedgerEntry.PROCESSED));

            Optional<LedgerEntry> got = ledger.find("sftp-src", "2026/07/data.csv");
            assertTrue(got.isPresent(), "ledger entry read back from Postgres");
            assertEquals(4096, got.get().size(), "size round-tripped");
            assertEquals("etag-1", got.get().etag(), "etag round-tripped");
            assertEquals("v3", got.get().version(), "object_version round-tripped (reserved-word-safe column)");

            OptionalLong hw = ledger.highWatermark("sftp-src");
            assertTrue(hw.isPresent() && hw.getAsLong() == 1_000L, "MAX(last_modified) watermark on Postgres");
            assertTrue(ledger.highWatermark("nobody").isEmpty(), "MAX over an empty set is SQL NULL → empty");

            ledger.recordDbWatermark("jdbc-src", "2026-07-17T00:00:00Z");
            assertEquals(Optional.of("2026-07-17T00:00:00Z"), ledger.dbWatermark("jdbc-src"),
                    "db-watermark upsert round-tripped through Postgres");
        }
    }

    @Test
    void statusStore_syncAndReadRoundTrip(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(
                TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write().toString());

        // A tiny in-memory source: DbStatusStore.sync projects these rows into Postgres.
        StatusStore source = new StatusStore() {
            @Override public Set<String> committedBatches(PipelineConfig c) { return Set.of("b1", "b2"); }
            @Override public List<Map<String, String>> batches(PipelineConfig c) {
                return List.of(Map.of("batch_id", "b1", "status", "SUCCESS"),
                               Map.of("batch_id", "b2", "status", "SUCCESS"));
            }
            @Override public List<Map<String, String>> files(PipelineConfig c) {
                return List.of(Map.of("file", "data.csv"));
            }
            @Override public List<Map<String, String>> lineage(PipelineConfig c, String batchId) {
                return List.of(Map.of("batch_id", "b1", "rows", "3"));
            }
            @Override public List<Map<String, String>> quarantine(PipelineConfig c) { return List.of(); }
        };

        try (DbStatusStore db = DbStatusStore.open(url, null, null)) {
            db.sync(source, List.of(cfg));
            assertEquals(Set.of("b1", "b2"), db.committedBatches(cfg), "commits projected to Postgres");
            assertEquals(2, db.batches(cfg).size(), "batch rows round-tripped through Postgres");
            assertEquals(1, db.files(cfg).size());
            assertEquals("b1", db.lineage(cfg, "b1").get(0).get("batch_id"), "lineage filter by batch on Postgres");
            assertTrue(db.quarantine(cfg).isEmpty());
        }
    }
}

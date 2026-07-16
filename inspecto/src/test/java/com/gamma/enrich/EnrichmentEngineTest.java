package com.gamma.enrich;

import com.gamma.etl.PartitionOutput;
import com.gamma.etl.PipelineConfig;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the Stage-2 {@link EnrichmentEngine} core — reads a Stage-1-style
 * Hive-partitioned Parquet tree, applies a columnar transform, and writes
 * partitioned output. Covers full + incremental recompute, idempotency, a
 * reference join, and CLI partition parsing.
 */
class EnrichmentEngineTest {

    /** Write a Stage-1-like input tree: event_type/year/month/day partitions of (id). */
    private static void seedInput(Path root) throws Exception {
        File db = DuckDbUtil.tempDbFile("seed_");
        try (Connection c = DuckDbUtil.openConnection(db); Statement st = c.createStatement()) {
            st.execute("COPY (SELECT * FROM (VALUES " +
                    "('CALL','2020','04','03','C1')," +
                    "('CALL','2020','04','03','C2')," +
                    "('CALL','2020','04','04','C3')," +
                    "('SMS','2020','04','03','S1')) " +
                    "t(event_type,year,month,day,id)) " +
                    "TO '" + root.toString().replace("\\", "/") + "' " +
                    "(FORMAT PARQUET, PARTITION_BY (event_type,year,month,day), OVERWRITE_OR_IGNORE 1)");
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    private static String configToon(Path inRoot, Path outRoot, String transform, String referencesBlock) {
        return ("""
                name: EVENTS_DAILY_KPI
                version: 1
                input:
                  database: %s
                  format: PARQUET
                  partitions[4]: event_type, year, month, day
                %soutput:
                  database: %s
                  format: PARQUET
                  compression: snappy
                  partitions[4]: event_type, year, month, day
                transform: "%s"
                """).formatted(
                inRoot.toString().replace("\\", "/"),
                referencesBlock,
                outRoot.toString().replace("\\", "/"),
                transform);
    }

    private static EnrichmentConfig load(Path dir, Path inRoot, Path outRoot,
                                         String transform, String referencesBlock) throws Exception {
        Path toon = dir.resolve("enrich.toon");
        Files.writeString(toon, configToon(inRoot, outRoot, transform, referencesBlock));
        return EnrichmentConfig.load(toon.toString());
    }

    /** Read the output tree into {event_type|day → event_count}. */
    private static Map<String, Long> readOutputCounts(Path outRoot) throws Exception {
        Map<String, Long> m = new HashMap<>();
        File db = DuckDbUtil.tempDbFile("verify_");
        try (Connection c = DuckDbUtil.openConnection(db); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT event_type, day, event_count FROM read_parquet('"
                     + outRoot.toString().replace("\\", "/")
                     + "/**/*.parquet', hive_partitioning=true, hive_types_autocast=0)")) {
            while (rs.next())
                m.put(rs.getString("event_type") + "|" + rs.getString("day"), rs.getLong("event_count"));
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
        return m;
    }

    private static final String DAILY_COUNT =
            "SELECT event_type, year, month, day, COUNT(*) AS event_count FROM input GROUP BY event_type, year, month, day";

    @Test
    void fullRecomputeProducesPerPartitionReports(@TempDir Path dir) throws Exception {
        Path in = dir.resolve("in"), out = dir.resolve("out");
        seedInput(in);
        EnrichmentConfig cfg = load(dir, in, out, DAILY_COUNT, "");

        List<PartitionOutput> outputs = EnrichmentEngine.run(cfg);

        assertEquals(3, outputs.size(), "CALL/03, CALL/04, SMS/03");
        Map<String, Long> counts = readOutputCounts(out);
        assertEquals(2L, counts.get("CALL|03"));
        assertEquals(1L, counts.get("CALL|04"));
        assertEquals(1L, counts.get("SMS|03"));
    }

    @Test
    void incrementalRecomputesOnlyTheGivenPartition(@TempDir Path dir) throws Exception {
        Path in = dir.resolve("in"), out = dir.resolve("out");
        seedInput(in);
        EnrichmentConfig cfg = load(dir, in, out, DAILY_COUNT, "");

        List<PartitionOutput> outputs = EnrichmentEngine.run(cfg, List.of(
                Map.of("event_type", "CALL", "year", "2020", "month", "04", "day", "03")));

        assertEquals(1, outputs.size(), "only CALL/2020/04/03 recomputed");
        Map<String, Long> counts = readOutputCounts(out);
        assertEquals(Map.of("CALL|03", 2L), counts, "only the filtered partition is written");
    }

    @Test
    void reRunIsIdempotent(@TempDir Path dir) throws Exception {
        Path in = dir.resolve("in"), out = dir.resolve("out");
        seedInput(in);
        EnrichmentConfig cfg = load(dir, in, out, DAILY_COUNT, "");

        EnrichmentEngine.run(cfg);
        Map<String, Long> first = readOutputCounts(out);
        EnrichmentEngine.run(cfg);                       // overwrite, no duplication
        Map<String, Long> second = readOutputCounts(out);

        assertEquals(first, second, "re-running full recompute is idempotent");
        assertEquals(2L, second.get("CALL|03"));
    }

    @Test
    void joinsAgainstReferenceTable(@TempDir Path dir) throws Exception {
        Path in = dir.resolve("in"), out = dir.resolve("out");
        seedInput(in);

        // reference: event_type → region
        Path ref = dir.resolve("region_dim.parquet");
        File db = DuckDbUtil.tempDbFile("ref_");
        try (Connection c = DuckDbUtil.openConnection(db); Statement st = c.createStatement()) {
            st.execute("COPY (SELECT * FROM (VALUES ('CALL','NA'),('SMS','EU')) t(event_type,region)) TO '"
                    + ref.toString().replace("\\", "/") + "' (FORMAT PARQUET)");
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }

        String transform = "SELECT i.event_type, i.year, i.month, i.day, COUNT(*) AS event_count, "
                + "ANY_VALUE(r.region) AS region FROM input i LEFT JOIN region_dim r "
                + "ON i.event_type = r.event_type GROUP BY i.event_type, i.year, i.month, i.day";
        // Built programmatically — the toon parsing of references is covered by
        // EnrichmentConfigTest; here we exercise the engine's reference-join behaviour.
        EnrichmentConfig cfg = new EnrichmentConfig(
                "EVENTS_DAILY_KPI",
                new EnrichmentConfig.Input(in.toString().replace("\\", "/"), "PARQUET",
                        List.of("event_type", "year", "month", "day")),
                List.of(new EnrichmentConfig.Reference("region_dim",
                        ref.toString().replace("\\", "/"), "PARQUET")),
                new EnrichmentConfig.Output(out.toString().replace("\\", "/"), "PARQUET", "snappy",
                        List.of("event_type", "year", "month", "day")),
                transform);

        EnrichmentEngine.run(cfg);

        File vdb = DuckDbUtil.tempDbFile("vfy_");
        try (Connection c = DuckDbUtil.openConnection(vdb); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT event_type, region FROM read_parquet('" + out.toString().replace("\\", "/")
                     + "/**/*.parquet', hive_partitioning=true, hive_types_autocast=0) "
                     + "WHERE event_type='CALL' LIMIT 1")) {
            assertTrue(rs.next());
            assertEquals("NA", rs.getString("region"), "reference join enriched the report");
        } finally {
            DuckDbUtil.deleteTempDb(vdb);
        }
    }

    /** A minimal {@code produces: reference} pipeline whose output tree is {@code refdb}. */
    private static PipelineConfig referenceProducer(Path dir, Path refdb, String format) throws Exception {
        return PipelineConfig.fromMap(Map.of(
                "name", "REGION_DIM",
                "produces", "reference",
                "dirs", Map.of("poll", dir.resolve("ref_in").toString(), "database", refdb.toString()),
                "output", Map.of("format", format),
                "processing", Map.of("threads", 1)));
    }

    @Test
    void joinsAgainstPipelineProducedReferenceByName(@TempDir Path dir) throws Exception {
        Path in = dir.resolve("in"), out = dir.resolve("out"), refdb = dir.resolve("refdb");
        seedInput(in);

        // the "produced" Reference Dataset: a Hive-partitioned tree, as a reference pipeline writes it
        File db = DuckDbUtil.tempDbFile("refseed_");
        try (Connection c = DuckDbUtil.openConnection(db); Statement st = c.createStatement()) {
            st.execute("COPY (SELECT * FROM (VALUES ('CALL','NA'),('SMS','EU')) t(event_type,region)) TO '"
                    + refdb.toString().replace("\\", "/")
                    + "' (FORMAT PARQUET, PARTITION_BY (region), OVERWRITE_OR_IGNORE 1)");
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }

        String transform = "SELECT i.event_type, i.year, i.month, i.day, COUNT(*) AS event_count, "
                + "ANY_VALUE(r.region) AS region FROM input i LEFT JOIN region_dim r "
                + "ON i.event_type = r.event_type GROUP BY i.event_type, i.year, i.month, i.day";
        EnrichmentConfig cfg = new EnrichmentConfig(
                "EVENTS_DAILY_KPI",
                new EnrichmentConfig.Input(in.toString().replace("\\", "/"), "PARQUET",
                        List.of("event_type", "year", "month", "day")),
                List.of(new EnrichmentConfig.Reference("region_dim", null, null, "region_dim")),
                new EnrichmentConfig.Output(out.toString().replace("\\", "/"), "PARQUET", "snappy",
                        List.of("event_type", "year", "month", "day")),
                transform);

        EnrichmentEngine.runResult(cfg, null, List.of(referenceProducer(dir, refdb, "PARQUET")));

        File vdb2 = DuckDbUtil.tempDbFile("vfy2_");
        try (Connection c = DuckDbUtil.openConnection(vdb2); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT event_type, region FROM read_parquet('" + out.toString().replace("\\", "/")
                     + "/**/*.parquet', hive_partitioning=true, hive_types_autocast=0) "
                     + "WHERE event_type='CALL' LIMIT 1")) {
            assertTrue(rs.next());
            assertEquals("NA", rs.getString("region"), "by-name reference resolved and joined");
        } finally {
            DuckDbUtil.deleteTempDb(vdb2);
        }
    }

    @Test
    void byNameReferenceWithoutPipelineContextFailsClearly(@TempDir Path dir) {
        EnrichmentConfig cfg = new EnrichmentConfig("X",
                new EnrichmentConfig.Input(dir.resolve("in").toString(), "PARQUET", List.of("day")),
                List.of(new EnrichmentConfig.Reference("region_dim", null, null, "region_dim")),
                new EnrichmentConfig.Output(dir.resolve("out").toString(), "PARQUET", null, List.of("day")),
                "SELECT 1");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> EnrichmentEngine.run(cfg));
        assertTrue(ex.getMessage().contains("no such pipeline is loaded"), ex.getMessage());
    }

    @Test
    void byNameReferenceToANonReferencePipelineFails(@TempDir Path dir) throws Exception {
        PipelineConfig plainStream = PipelineConfig.fromMap(Map.of(
                "name", "REGION_DIM",
                "dirs", Map.of("poll", dir.resolve("ref_in").toString(),
                        "database", dir.resolve("refdb").toString()),
                "processing", Map.of("threads", 1)));
        EnrichmentConfig cfg = new EnrichmentConfig("X",
                new EnrichmentConfig.Input(dir.resolve("in").toString(), "PARQUET", List.of("day")),
                List.of(new EnrichmentConfig.Reference("region_dim", null, null, "region_dim")),
                new EnrichmentConfig.Output(dir.resolve("out").toString(), "PARQUET", null, List.of("day")),
                "SELECT 1");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> EnrichmentEngine.runResult(cfg, null, List.of(plainStream)));
        assertTrue(ex.getMessage().contains("does not declare 'produces: reference'"), ex.getMessage());
    }

    @Test
    void runResultReportsTotalOutputRows(@TempDir Path dir) throws Exception {
        Path in = dir.resolve("in"), out = dir.resolve("out");
        seedInput(in);
        EnrichmentConfig cfg = load(dir, in, out, DAILY_COUNT, "");

        EnrichmentEngine.Result res = EnrichmentEngine.runResult(cfg, null);
        assertEquals(3, res.outputs().size(), "CALL/03, CALL/04, SMS/03");
        assertEquals(3L, res.totalRows(), "three daily groups materialised");
    }

    @Test
    void cliRunWritesAuditLineageAndCommitLog(@TempDir Path dir) throws Exception {
        Path in = dir.resolve("in"), out = dir.resolve("out");
        seedInput(in);
        Path toon = dir.resolve("enrich.toon");
        Files.writeString(toon, configToon(in, out, DAILY_COUNT, ""));

        EnrichmentProcessor.main(new String[]{ toon.toString() });   // full recompute via CLI

        Path auditDir = Path.of(out + "_audit");
        Path runs    = auditDir.resolve("events_daily_kpi_enrich_runs.csv");
        Path lineage = auditDir.resolve("events_daily_kpi_enrich_lineage.csv");
        Path commits = auditDir.resolve("events_daily_kpi_enrich_commits.log");
        assertTrue(Files.exists(runs),    "run audit written");
        assertTrue(Files.exists(lineage), "lineage written");
        assertTrue(Files.exists(commits), "durable commit log written");

        List<String> runLines = Files.readAllLines(runs);
        assertEquals(2, runLines.size(), "header + one run row");
        assertTrue(runLines.get(1).contains("EVENTS_DAILY_KPI"));
        assertTrue(runLines.get(1).contains("cli"),     "CLI trigger recorded");
        assertTrue(runLines.get(1).contains("SUCCESS"));
        assertTrue(runLines.get(1).contains("full"),    "scope recorded");

        assertEquals(4, Files.readAllLines(lineage).size(), "header + 3 output partitions");
        assertEquals(2, Files.readAllLines(commits).size(), "commit-log header + one SUCCESS line");
    }

    @Test
    void cliPartitionParsing() {
        List<Map<String, String>> parsed = EnrichmentProcessor.parsePartitions(
                "event_type=CALL/year=2020/month=04/day=03;event_type=SMS/year=2020/month=04/day=03");
        assertEquals(2, parsed.size());
        assertEquals("CALL", parsed.get(0).get("event_type"));
        assertEquals("03", parsed.get(0).get("day"));
        assertEquals("SMS", parsed.get(1).get("event_type"));
    }
}

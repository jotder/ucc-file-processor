package com.gamma.intelligence.pack;

import com.eoiagent.core.RunId;
import com.eoiagent.core.ToolCall;
import com.eoiagent.core.ToolResult;
import com.eoiagent.tool.Tool;
import com.gamma.etl.BatchAuditWriter;
import com.gamma.etl.PipelineConfig;
import com.gamma.pipeline.ComponentStore;
import com.gamma.service.CollectorService;
import com.gamma.signal.Ref;
import com.gamma.signal.Severity;
import com.gamma.signal.Signal;
import com.gamma.util.BrowsableStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Direct (no-LLM) tests of the S5 signal tools: they read the canonical ledger through
 * {@code CollectorService.events()} — the same store {@code EventLog.emit} writes to — so a seeded
 * failure chain must come back filtered ({@code signals_query}) and causation-ordered
 * ({@code signal_timeline}), with signal ids usable as citations.
 *
 * <p>The default-space {@link CollectorService} rides {@code EventLog.global()}, a JVM-wide ledger
 * other tests also emit onto, so every query here is scoped by a unique {@code correlationId} —
 * that isolates the assertions from whatever else the reactor emitted.
 */
class InspectoToolsTest {

    private static final Instant T0 = Instant.parse("2026-07-19T10:00:00Z");
    private static final int WIDE_WINDOW = 1_000_000; // minutes — sidesteps the default 60-min/24-h floors

    private static CollectorService seeded(Signal... signals) {
        CollectorService svc = new CollectorService(List.of(), 3600, 1);
        for (Signal s : signals) svc.events().append(s.toEvent());
        return svc;
    }

    private static Signal sig(String id, String type, Severity sev, String corr, String causedBy, Instant at) {
        return new Signal(id, type, at, sev, Ref.of("pipeline", "p"), Ref.of("pipeline", "p"),
                corr, causedBy, null, null, "msg-" + id, Map.of(), 1);
    }

    private static Tool tool(CollectorService svc, String name) {
        return InspectoTools.tools(svc).stream()
                .filter(t -> t.spec().name().equals(name)).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> invoke(CollectorService svc, String name, Map<String, Object> args) {
        ToolResult r = tool(svc, name).invoke(new ToolCall(name, args, new RunId("t")));
        assertTrue(r.ok(), () -> "tool errored: " + r.error());
        return (Map<String, Object>) r.value();
    }

    @Test
    void signalsQueryFiltersByTypeGlobAndSeverityFloor() {
        String corr = "s5q-filter";
        CollectorService svc = seeded(
                sig("s1", "s5q.batch.failed", Severity.ERROR, corr, null, T0),
                sig("s2", "s5q.job.failed", Severity.ERROR, corr, "s1", T0.plusSeconds(1)),
                sig("s3", "s5q.batch.ok", Severity.INFO, corr, null, T0.plusSeconds(2)));

        Map<String, Object> byType = invoke(svc, "signals_query",
                Map.of("type", "s5q.batch.*", "correlationId", corr, "sinceMinutes", WIDE_WINDOW));
        assertEquals(2, byType.get("count"), "both s5q.batch.* signals, either severity");

        Map<String, Object> bySeverity = invoke(svc, "signals_query",
                Map.of("minSeverity", "error", "correlationId", corr, "sinceMinutes", WIDE_WINDOW));
        assertEquals(2, bySeverity.get("count"), "the two ERROR signals only — the INFO commit is below the floor");

        Map<String, Object> job = invoke(svc, "signals_query",
                Map.of("type", "s5q.job.*", "correlationId", corr, "sinceMinutes", WIDE_WINDOW));
        assertEquals(1, job.get("count"));
    }

    @Test
    void signalsQueryReportsAnUnknownSeverityAsAnErrorResultNotAThrow() {
        ToolResult r = tool(seeded(), "signals_query")
                .invoke(new ToolCall("signals_query", Map.of("minSeverity", "nonsense"), new RunId("t")));
        assertFalse(r.ok());
        assertTrue(r.error().contains("severity"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void signalTimelineReturnsTheCausationOrderedChainWithCitableIds() {
        String corr = "s5tl-chain";
        CollectorService svc = seeded(
                // deliberately appended child-first to prove ordering is by causation, not insertion
                sig("s2", "s5q.job.failed", Severity.ERROR, corr, "s1", T0.plusSeconds(1)),
                sig("s1", "s5q.batch.failed", Severity.ERROR, corr, null, T0),
                sig("other", "s5q.batch.ok", Severity.INFO, "s5tl-other", null, T0));

        Map<String, Object> out = invoke(svc, "signal_timeline",
                Map.of("correlationId", corr, "sinceMinutes", WIDE_WINDOW));
        assertEquals(corr, out.get("correlationId"));
        List<Map<String, Object>> timeline = (List<Map<String, Object>>) out.get("timeline");
        assertEquals(2, timeline.size(), "only this correlationId's signals");
        assertEquals("s1", timeline.get(0).get("signalId"), "root (no causation) first");
        assertNull(timeline.get(0).get("causedBy"));
        assertEquals("s2", timeline.get(1).get("signalId"), "the caused signal follows its cause");
        assertEquals("s1", timeline.get(1).get("causedBy"), "causedBy is a citable parent signal id");
    }

    @Test
    void signalTimelineNarratesAnAgentRunFromItsOwnFacts() {
        String cap = "s5tl-cap-42";
        CollectorService svc = seeded(
                sig("a2", "agent.run.completed", Severity.INFO, cap, "a1", T0.plusSeconds(3)),
                sig("a1", "agent.run.started", Severity.INFO, cap, null, T0));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> timeline = (List<Map<String, Object>>)
                invoke(svc, "signal_timeline", Map.of("correlationId", cap, "sinceMinutes", WIDE_WINDOW)).get("timeline");
        assertEquals(List.of("agent.run.started", "agent.run.completed"),
                timeline.stream().map(e -> e.get("type")).toList());
    }

    @Test
    void signalTimelineWithNoMatchesIsAnErrorResult() {
        ToolResult r = tool(seeded(), "signal_timeline").invoke(new ToolCall("signal_timeline",
                Map.of("correlationId", "s5tl-absent-xyz", "sinceMinutes", WIDE_WINDOW), new RunId("t")));
        assertFalse(r.ok());
    }

    // ── AGT-5 P1 slice A: analysis tools ────────────────────────────────────────
    // These use the package-private tools(service, components, browseStores) seam so the component
    // registry and browse stores are seeded temp/in-memory instances, not live JVM-wide state.

    private static Tool tool(List<Tool> belt, String name) {
        return belt.stream().filter(t -> t.spec().name().equals(name)).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> invoke(Tool t, Map<String, Object> args) {
        ToolResult r = t.invoke(new ToolCall(t.spec().name(), args, new RunId("t")));
        assertTrue(r.ok(), () -> "tool errored: " + r.error());
        return (Map<String, Object>) r.value();
    }

    @Test
    @SuppressWarnings("unchecked")
    void timelineBuildMergesSignalsAndConfigSavesInTimeOrder(@TempDir Path dir) throws Exception {
        String sigId = "tba-sig-1";
        CollectorService svc = seeded(
                sig(sigId, "tba.batch.failed", Severity.ERROR, "tba-corr", null,
                        Instant.now().minusSeconds(300)));   // 5 min before the config saves below
        ComponentStore components = new ComponentStore(dir.resolve("registry"));
        components.write("query", "tba-q1", Map.of("sql", "select 1"));
        components.write("query", "tba-q1", Map.of("sql", "select 2"));   // archives v1, resaves live

        Tool build = tool(InspectoTools.tools(svc, components, List::of), "timeline_build");
        Map<String, Object> out = invoke(build, Map.of("sinceMinutes", WIDE_WINDOW));
        assertEquals(false, out.get("truncated"));
        List<Map<String, Object>> timeline = (List<Map<String, Object>>) out.get("timeline");
        // the global ledger is JVM-wide, so assert on our own entries' presence and relative order
        int idxSignal = -1;
        int idxConfig = -1;
        for (int i = 0; i < timeline.size(); i++) {
            Map<String, Object> e = timeline.get(i);
            if (sigId.equals(e.get("ref"))) idxSignal = i;
            if ("component:query/tba-q1".equals(e.get("ref")) && idxConfig < 0) idxConfig = i;
        }
        assertTrue(idxSignal >= 0, "the seeded signal appears");
        assertTrue(idxConfig >= 0, "the component save appears as a config-change");
        assertEquals("signal", timeline.get(idxSignal).get("kind"));
        assertEquals("config-change", timeline.get(idxConfig).get("kind"));
        assertEquals("error", timeline.get(idxSignal).get("severity"));
        assertTrue(idxSignal < idxConfig, "the 5-min-older signal sorts before the just-saved component");

        Map<String, Object> focused = invoke(build, Map.of("sinceMinutes", WIDE_WINDOW, "focus", "tba-q1"));
        List<Map<String, Object>> ft = (List<Map<String, Object>>) focused.get("timeline");
        assertFalse(ft.isEmpty());
        assertTrue(ft.stream().allMatch(e -> "config-change".equals(e.get("kind"))),
                "focus text keeps only the matching component entries");
    }

    @Test
    void timelineBuildRequiresSinceMinutes() {
        Tool build = tool(InspectoTools.tools(seeded(), null, List::of), "timeline_build");
        ToolResult r = build.invoke(new ToolCall("timeline_build", Map.of(), new RunId("t")));
        assertFalse(r.ok());
        assertTrue(r.error().contains("sinceMinutes"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void diffBatchesComparesTwoLedgerEntriesAndComputesTheDelta(@TempDir Path dir) throws Exception {
        CollectorService svc = new CollectorService(List.of(writeMiniPipeline(dir)), 3600, 1);
        // Pipeline ids register lowercased (in-file 'MINI_ETL' -> 'mini_etl'); the tool takes the id
        // exactly as status_get/signals hand it to the agent, so query with the registered form.
        PipelineConfig cfg = svc.configFor("mini_etl").orElseThrow();
        Path statusDir = Path.of(cfg.dirs().statusFilePath()).toAbsolutePath().getParent();
        Files.createDirectories(statusDir);
        String name = cfg.identity().pipelineName();   // reader globs <name>_batches_*.csv
        BatchAuditWriter w = new BatchAuditWriter(
                statusDir.resolve(name + "_status_TEST.csv").toString(),
                statusDir.resolve(name + "_batches_TEST.csv").toString(),
                statusDir.resolve(name + "_lineage_TEST.csv").toString());
        w.flush(new BatchAuditWriter.BatchRow("B1", name, "mini", "",
                "2026-06-09 08:00:00", "2026-06-09 08:00:02", "SUCCESS",
                1, 0, 100, 100, 1, 120L, 2000, ""), List.of(), List.of());
        w.flush(new BatchAuditWriter.BatchRow("B2", name, "mini", "",
                "2026-06-09 09:00:00", "2026-06-09 09:00:05", "FAILED",
                1, 0, 100, 40, 1, 120L, 5000, "boom"), List.of(), List.of());

        Tool diff = tool(InspectoTools.tools(svc, null, List::of), "diff_batches");
        Map<String, Object> out = invoke(diff,
                Map.of("pipeline", "mini_etl", "batchA", "B1", "batchB", "B2"));
        Map<String, Object> a = (Map<String, Object>) out.get("batchA");
        assertEquals("SUCCESS", a.get("status"));
        assertEquals(100L, a.get("rowCount"));
        assertEquals("2026-06-09 08:00:00", a.get("startedAt"));
        Map<String, Object> delta = (Map<String, Object>) out.get("delta");
        assertEquals(-60L, delta.get("rowCount"));
        assertEquals(3000L, delta.get("durationMs"));
        assertEquals("SUCCESS -> FAILED", delta.get("status"));

        ToolResult missing = diff.invoke(new ToolCall("diff_batches",
                Map.of("pipeline", "mini_etl", "batchA", "B1", "batchB", "B9"), new RunId("t")));
        assertFalse(missing.ok());
        assertTrue(missing.error().contains("B9"));
    }

    @Test
    void diffBatchesUnknownPipelineIsAnErrorResult() {
        Tool diff = tool(InspectoTools.tools(seeded(), null, List::of), "diff_batches");
        ToolResult r = diff.invoke(new ToolCall("diff_batches",
                Map.of("pipeline", "nope", "batchA", "B1", "batchB", "B2"), new RunId("t")));
        assertFalse(r.ok());
        assertTrue(r.error().contains("unknown pipeline"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void configVersionsDiffDefaultsToTheLatestTwoArchivedVersions(@TempDir Path dir) throws Exception {
        ComponentStore components = new ComponentStore(dir.resolve("registry"));
        components.write("query", "q1", Map.of("sql", "select 1", "note", "a"));
        components.write("query", "q1", Map.of("sql", "select 2"));            // archives v1
        components.write("query", "q1", Map.of("sql", "select 2", "extra", "x")); // archives v2

        Tool diff = tool(InspectoTools.tools(seeded(), components, List::of), "config_versions_diff");
        Map<String, Object> out = invoke(diff, Map.of("type", "query", "id", "q1"));
        assertEquals(1, out.get("fromVersion"));
        assertEquals(2, out.get("toVersion"));
        Map<String, Object> changed = (Map<String, Object>) out.get("changed");
        Map<String, Object> sql = (Map<String, Object>) changed.get("sql");
        assertEquals("select 1", sql.get("from"));
        assertEquals("select 2", sql.get("to"));
        assertEquals(List.of("note"), out.get("removed"));
        assertEquals(Map.of(), out.get("added"));
    }

    @Test
    void configVersionsDiffWithoutEnoughHistoryIsAnErrorResult(@TempDir Path dir) {
        ComponentStore components = new ComponentStore(dir.resolve("registry"));
        Tool diff = tool(InspectoTools.tools(seeded(), components, List::of), "config_versions_diff");
        ToolResult r = diff.invoke(new ToolCall("config_versions_diff",
                Map.of("type", "query", "id", "absent"), new RunId("t")));
        assertFalse(r.ok());
        assertTrue(r.error().contains("fewer than two"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void anomalyScanFlagsTheOutlierByZscoreAndByThreshold() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:")) {
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE TABLE metrics(id INTEGER, v DOUBLE)");
                st.execute("INSERT INTO metrics VALUES (1,10),(2,10),(3,10),(4,10),(5,10),"
                        + "(6,10),(7,10),(8,10),(9,10),(10,1000)");
            }
            BrowsableStore store = new BrowsableStore() {
                @Override public String browseId() { return "test"; }
                @Override public String browseLabel() { return "Test"; }
                @Override public List<String> browseTables() { return List.of("metrics"); }
                @Override public Connection browseConnection() { return conn; }
            };
            Tool scan = tool(InspectoTools.tools(seeded(), null, () -> List.of(store)), "anomaly_scan");

            // z of the outlier is exactly 3.0 (mean 109, stddev_pop 297) — cutoff 2.5 flags it alone
            Map<String, Object> out = invoke(scan,
                    Map.of("table", "metrics", "column", "v", "threshold", 2.5));
            assertEquals(109.0, (Double) out.get("mean"), 1e-9);
            assertEquals(297.0, (Double) out.get("stddev"), 1e-9);
            assertEquals(10L, out.get("scanned"));
            assertEquals(1, out.get("flagged"));
            List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("rows");
            assertEquals(1000.0, rows.get(0).get("value"));
            assertEquals(10, rows.get(0).get("rowIdentifier"), "first table column identifies the row");
            assertEquals(3.0, (Double) rows.get(0).get("zscore"), 1e-9);

            Map<String, Object> bounded = invoke(scan, Map.of("table", "metrics", "column", "v",
                    "method", "threshold", "threshold", 500));
            assertEquals(1, bounded.get("flagged"));
        }
    }

    @Test
    void anomalyScanUnknownTableIsAnErrorResult() {
        Tool scan = tool(InspectoTools.tools(seeded(), null, List::of), "anomaly_scan");
        ToolResult r = scan.invoke(new ToolCall("anomaly_scan",
                Map.of("table", "nope", "column", "v"), new RunId("t")));
        assertFalse(r.ok());
        assertTrue(r.error().contains("unknown table"));
    }

    /** Minimal valid pipeline + schema toon — mirrors inspecto's test-scope
     *  {@code PipelineConfigBatchTest.writePipeline}, which isn't on this module's classpath. */
    private static Path writeMiniPipeline(Path dir) throws Exception {
        Path schema = dir.resolve("mini_schema.toon");
        Files.writeString(schema, """
            partitionKey: EVENT_DATE
            raw:
              name: mini
              format: CSV
              fields[3]{name,selector,type}:
                ID,"0",VARCHAR
                AMT,"1",DOUBLE
                EVENT_DATE,"2",DATE
            mapping:
              canonicalName: mini
              rawName: mini
              rules[3]{targetColumn,sourceExpression,transformType}:
                ID,ID,DIRECT
                AMT,AMT,DIRECT
                EVENT_DATE,EVENT_DATE,DIRECT
            """);
        String toon = """
            name: MINI_ETL
            active: true
            version: 1
            dirs:
              poll: %s/inbox
              database: %s/db
              backup: %s/backup
              temp: %s/temp
              errors: %s/errors
              quarantine: %s/quarantine
              markers: %s/markers
              status_dir: %s/status
              log_dir: %s/logs
            output:
              format: CSV
            processing:
              threads: 2
              file_pattern: "glob:**/*.{csv,csv.gz}"
              duplicate_check:
                enabled: true
                marker_extension: .processed
              schema_file: "%s"
              csv_settings:
                delimiter: ","
                skip_header_lines: 0
                skip_junk_lines: 0
                skip_tail_lines: 0
                date_formats[1]: "%%Y-%%m-%%d"
                timestamp_formats[1]: "%%Y-%%m-%%d"
            """.formatted(dir, dir, dir, dir, dir, dir, dir, dir, dir,
                          schema.toString().replace("\\", "/"));
        Path p = dir.resolve("mini_pipeline.toon");
        Files.writeString(p, toon);
        return p;
    }
}

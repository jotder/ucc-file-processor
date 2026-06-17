package com.gamma.flow;

import com.gamma.config.io.ConfigCodec;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.inspector.MultiSourceProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>T5b — execution-through-lift parity.</b> A pipeline run <em>directly</em> by the engine must produce
 * the same <b>data output</b> as the same pipeline run after a {@code lift → FlowCompiler.toConfigMap →
 * PipelineConfig.fromMap} round-trip — proving the lift carries everything execution needs, not merely
 * that recovered objects equal the originals (that is T5a).
 *
 * <p>Scope (incremental): the <b>single-schema</b> shape. The selector / segments / fixed-width / row-filter
 * shapes are follow-ons as {@code toConfigMap} grows. Parity is asserted over the {@code database/} partition
 * files; the run-timestamped status / audit CSVs are excluded (the rebuilt config disables status, which does
 * not affect the data output).
 */
class FlowExecutionParityTest {

    private static final String CSV = "ID,AMT,EVENT_DATE\n1,10,2020-01-01\n2,20,2020-02-02\n";

    @Test
    void singleSchemaRoundTripProducesIdenticalDataOutput(@TempDir Path root) throws Exception {
        // ── direct run (dirA) ──
        Path dirA = root.resolve("a");
        Path toonA = TestConfigs.csv(dirA, PipelineConfigBatchTest.miniSchema()).write();
        seedInbox(dirA, CSV);
        MultiSourceProcessor.runAll(List.of(toonA), 1);
        Map<String, String> outA = readDb(dirA.resolve("db"));
        assertFalse(outA.isEmpty(), "the direct run produced data output");

        // ── through-lift run (dirB): lift → toConfigMap → fromMap → run ──
        Path dirB = root.resolve("b");
        Files.createDirectories(dirB);
        FlowGraph g = PipelineLift.lift(PipelineConfig.load(toonA.toString()));
        Map<String, Object> rebuilt = FlowCompiler.toConfigMap(g, dirB.resolve("schemas"));
        relocateDirs(rebuilt, posix(dirA), posix(dirB));     // run the reconstructed pipeline in a fresh sandbox
        // operational dirs the IR doesn't model (don't affect the data output we compare) — supply for a clean run
        @SuppressWarnings("unchecked")
        Map<String, Object> dirsB = (Map<String, Object>) rebuilt.get("dirs");
        dirsB.put("status_dir", posix(dirB.resolve("status")));
        dirsB.put("log_dir", posix(dirB.resolve("logs")));
        Path toonB = dirB.resolve("rebuilt_pipeline.toon");
        Files.writeString(toonB, ConfigCodec.toToon(rebuilt));
        seedInbox(dirB, CSV);
        MultiSourceProcessor.RunResult rB = MultiSourceProcessor.runAll(List.of(toonB), 1);
        assertEquals(0, rB.failed(), "the rebuilt pipeline must run cleanly");
        Map<String, String> outB = readDb(dirB.resolve("db"));

        // ── parity: identical relative partition files + byte-identical content ──
        assertEquals(outA.keySet(), outB.keySet(), "same output partition files (relative to db/)");
        for (Map.Entry<String, String> e : outA.entrySet())
            assertEquals(e.getValue(), outB.get(e.getKey()), "content of " + e.getKey());
    }

    @Test
    void selectorMultiSchemaRoundTripProducesIdenticalDataOutput(@TempDir Path root) throws Exception {
        // ── direct run (dirA): a 2-schema selector dispatching by column count (3 vs 4) ──
        Path dirA = root.resolve("a");
        Path toonA = writeSelectorPipeline(dirA);
        seedSelectorInbox(dirA);
        MultiSourceProcessor.runAll(List.of(toonA), 1);
        Map<String, String> outA = readDb(dirA.resolve("db"));
        assertTrue(outA.keySet().stream().anyMatch(k -> k.startsWith("three/")), "3-col file routed to 'three'");
        assertTrue(outA.keySet().stream().anyMatch(k -> k.startsWith("four/")), "4-col file routed to 'four'");

        // ── through-lift run (dirB) ──
        Path dirB = root.resolve("b");
        Files.createDirectories(dirB);
        FlowGraph g = PipelineLift.lift(PipelineConfig.load(toonA.toString()));
        Map<String, Object> rebuilt = FlowCompiler.toConfigMap(g, dirB.resolve("schemas"));
        relocateDirs(rebuilt, posix(dirA), posix(dirB));
        @SuppressWarnings("unchecked")
        Map<String, Object> dirsB = (Map<String, Object>) rebuilt.get("dirs");
        dirsB.put("status_dir", posix(dirB.resolve("status")));
        dirsB.put("log_dir", posix(dirB.resolve("logs")));
        Path toonB = dirB.resolve("rebuilt_pipeline.toon");
        Files.writeString(toonB, ConfigCodec.toToon(rebuilt));
        seedSelectorInbox(dirB);
        MultiSourceProcessor.RunResult rB = MultiSourceProcessor.runAll(List.of(toonB), 1);
        assertEquals(0, rB.failed(), "the rebuilt selector pipeline must run cleanly");
        Map<String, String> outB = readDb(dirB.resolve("db"));

        // ── parity over both schema tables ──
        assertEquals(outA.keySet(), outB.keySet(), "same output partition files across both schema tables");
        for (Map.Entry<String, String> e : outA.entrySet())
            assertEquals(e.getValue(), outB.get(e.getKey()), "content of " + e.getKey());
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private static String posix(Path p) {
        return p.toString().replace('\\', '/');
    }

    private static void seedInbox(Path dir, String csv) throws Exception {
        Path inbox = dir.resolve("inbox");
        Files.createDirectories(inbox);
        Files.writeString(inbox.resolve("data.csv"), csv);
    }

    @SuppressWarnings("unchecked")
    private static void relocateDirs(Map<String, Object> raw, String fromPrefix, String toPrefix) {
        Map<String, Object> dirs = (Map<String, Object>) raw.get("dirs");
        // normalise to posix first — TestConfigs writes mixed separators (backslash base + /suffix)
        dirs.replaceAll((k, v) -> v instanceof String s ? s.replace('\\', '/').replace(fromPrefix, toPrefix) : v);
    }

    /** Write a 2-schema selector pipeline (3-col → 'three', 4-col → 'four'; column-count dispatch). */
    private static Path writeSelectorPipeline(Path dir) throws Exception {
        Files.createDirectories(dir);
        Path s3 = dir.resolve("schema3.toon");
        Path s4 = dir.resolve("schema4.toon");
        Files.writeString(s3, """
                partitionKey: EVENT_DATE
                raw:
                  name: three
                  format: CSV
                  fields[3]{name,selector,type}:
                    ID,"0",VARCHAR
                    AMT,"1",DOUBLE
                    EVENT_DATE,"2",DATE
                mapping:
                  canonicalName: three
                  rawName: three
                  rules[3]{targetColumn,sourceExpression,transformType}:
                    ID,ID,DIRECT
                    AMT,AMT,DIRECT
                    EVENT_DATE,EVENT_DATE,DIRECT
                """);
        Files.writeString(s4, """
                partitionKey: EVENT_DATE
                raw:
                  name: four
                  format: CSV
                  fields[4]{name,selector,type}:
                    ID,"0",VARCHAR
                    AMT,"1",DOUBLE
                    EVENT_DATE,"2",DATE
                    REGION,"3",VARCHAR
                mapping:
                  canonicalName: four
                  rawName: four
                  rules[4]{targetColumn,sourceExpression,transformType}:
                    ID,ID,DIRECT
                    AMT,AMT,DIRECT
                    EVENT_DATE,EVENT_DATE,DIRECT
                    REGION,REGION,DIRECT
                """);
        String toon = """
                name: SEL_PARITY
                active: true
                dirs:
                  poll: %1$s/inbox
                  database: %1$s/db
                  backup: %1$s/backup
                  temp: %1$s/temp
                  quarantine: %1$s/quarantine
                  markers: %1$s/markers
                  status_dir: %1$s/status
                  log_dir: %1$s/logs
                output:
                  format: CSV
                processing:
                  threads: 1
                  file_pattern: "glob:**/*.csv"
                  duplicate_check:
                    enabled: true
                    marker_extension: .processed
                  schemas[2]{column_count,schema_file,table}:
                    3, "%2$s", three
                    4, "%3$s", four
                  csv_settings:
                    delimiter: ","
                    has_header: true
                    skip_header_lines: 0
                    skip_junk_lines: 0
                    skip_tail_lines: 0
                    skip_tail_columns: 0
                    date_formats[1]: "%%Y-%%m-%%d"
                    timestamp_formats[1]: "%%Y-%%m-%%d"
                """.formatted(posix(dir), posix(s3), posix(s4));
        Path p = dir.resolve("sel_pipeline.toon");
        Files.writeString(p, toon);
        return p;
    }

    private static void seedSelectorInbox(Path dir) throws Exception {
        Path inbox = dir.resolve("inbox");
        Files.createDirectories(inbox);
        Files.writeString(inbox.resolve("file_3.csv"), "ID,AMT,EVENT_DATE\n1,10,2020-01-01\n");
        Files.writeString(inbox.resolve("file_4.csv"), "ID,AMT,EVENT_DATE,REGION\n2,20,2020-02-02,EU\n");
    }

    /** Every regular file under {@code db}, keyed by its path relative to {@code db} (sorted), → content. */
    private static Map<String, String> readDb(Path db) throws Exception {
        Map<String, String> out = new TreeMap<>();
        if (!Files.exists(db)) return out;
        try (Stream<Path> s = Files.walk(db)) {
            for (Path p : (Iterable<Path>) s.filter(Files::isRegularFile)::iterator)
                out.put(db.relativize(p).toString().replace('\\', '/'), Files.readString(p));
        }
        return out;
    }

    // ── T5b: row-filter, fixed-width, segments shapes ─────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void rowFilterRoundTripProducesIdenticalDataOutput(@TempDir Path root) throws Exception {
        // Rows whose ID (column 0) starts with "SKIP" are excluded via exclude_prefixes
        String csv = "ID,AMT,EVENT_DATE\n1,10,2020-01-01\nSKIP,99,2020-03-03\n2,20,2020-02-02\n";
        Path dirA = root.resolve("a");
        Path toonA = writeRowFilterPipeline(dirA);
        seedInbox(dirA, csv);
        MultiSourceProcessor.RunResult rA = MultiSourceProcessor.runAll(List.of(toonA), 1);
        assertEquals(0, rA.failed(), "direct row-filter run must succeed");
        Map<String, String> outA = readDb(dirA.resolve("db"));
        assertFalse(outA.isEmpty(), "direct run produced data output");

        Path dirB = root.resolve("b");
        Files.createDirectories(dirB);
        FlowGraph g = PipelineLift.lift(PipelineConfig.load(toonA.toString()));
        Map<String, Object> rebuilt = FlowCompiler.toConfigMap(g, dirB.resolve("schemas"));
        relocateDirs(rebuilt, posix(dirA), posix(dirB));
        Map<String, Object> dirsB = (Map<String, Object>) rebuilt.get("dirs");
        dirsB.put("status_dir", posix(dirB.resolve("status")));
        dirsB.put("log_dir", posix(dirB.resolve("logs")));
        Path toonB = dirB.resolve("rebuilt_rf.toon");
        Files.writeString(toonB, ConfigCodec.toToon(rebuilt));
        seedInbox(dirB, csv);
        MultiSourceProcessor.RunResult rB = MultiSourceProcessor.runAll(List.of(toonB), 1);
        assertEquals(0, rB.failed(), "rebuilt row-filter pipeline must run cleanly");
        Map<String, String> outB = readDb(dirB.resolve("db"));

        assertEquals(outA.keySet(), outB.keySet(), "same output partition files");
        for (Map.Entry<String, String> e : outA.entrySet())
            assertEquals(e.getValue(), outB.get(e.getKey()), "content of " + e.getKey());
    }

    @Test
    @SuppressWarnings("unchecked")
    void fixedWidthToConfigMapEmitsFrontendAndSlices(@TempDir Path dir) throws Exception {
        // Write a schema with slice-index selectors (0 → ID, 1 → DATE_COL)
        Path schemaFile = dir.resolve("fw_schema.toon");
        Files.writeString(schemaFile, """
                partitionKey: ID
                raw:
                  name: fw_table
                  format: CSV
                  fields[2]{name,selector,type}:
                    ID, "0", VARCHAR
                    DATE_COL, "1", VARCHAR
                mapping:
                  canonicalName: fw_table
                  rawName: fw_table
                  rules[2]{targetColumn,sourceExpression,transformType}:
                    ID, ID, DIRECT
                    DATE_COL, DATE_COL, DIRECT
                """);
        String toon = """
                name: FW_SHAPE
                active: true
                dirs:
                  poll: %1$s/inbox
                  database: %1$s/db
                  backup: %1$s/backup
                  temp: %1$s/temp
                  status_dir: %1$s/status
                  log_dir: %1$s/logs
                output:
                  format: CSV
                processing:
                  threads: 1
                  schema_file: %2$s
                  csv_settings:
                    has_header: false
                    frontend: fixedwidth
                    fixedwidth:
                      record: line
                      trim: both
                      min_record_length: 8
                      fields[2]{name,start,length}:
                        ID, 0, 3
                        DATE_COL, 3, 5
                """.formatted(posix(dir), posix(schemaFile));
        Path toonFile = dir.resolve("fw_pipeline.toon");
        Files.writeString(toonFile, toon);

        FlowGraph g = PipelineLift.lift(PipelineConfig.load(toonFile.toString()));
        Map<String, Object> map = FlowCompiler.toConfigMap(g, dir.resolve("schemas"));

        Map<String, Object> proc = (Map<String, Object>) map.get("processing");
        Map<String, Object> csvSettings = (Map<String, Object>) proc.get("csv_settings");
        assertEquals("fixedwidth", csvSettings.get("frontend"), "frontend must be fixedwidth");
        Map<String, Object> fw = (Map<String, Object>) csvSettings.get("fixedwidth");
        assertNotNull(fw, "fixedwidth block must be present in csv_settings");
        assertEquals("line", fw.get("record"));
        List<Map<String, Object>> fields = (List<Map<String, Object>>) fw.get("fields");
        assertEquals(2, fields.size(), "two slices round-trip");
        assertEquals("ID", fields.get(0).get("name"));
        assertEquals(0, fields.get(0).get("start"));
        assertEquals(3, fields.get(0).get("length"));
        assertEquals("DATE_COL", fields.get(1).get("name"));
        assertEquals(3, fields.get(1).get("start"));
        assertEquals(5, fields.get(1).get("length"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void segmentsToConfigMapWritesSchemaFilesAndBuildSegmentsMap(@TempDir Path dir) throws Exception {
        // Two segment schema files — written as inline TOON (ConfigCodec.toToon doesn't emit
        // tabular-array format so the TOON parser would fail to re-read the serialized arrays)
        String segSchema = """
                partitionKey: ID
                raw:
                  name: seg_data
                  format: CSV
                  fields[2]{name,selector,type}:
                    ID, "0", VARCHAR
                    VAL, "1", VARCHAR
                mapping:
                  canonicalName: seg_data
                  rawName: seg_data
                  rules[2]{targetColumn,sourceExpression,transformType}:
                    ID, ID, DIRECT
                    VAL, VAL, DIRECT
                """;
        Path seg1 = dir.resolve("seg1.toon");
        Path seg2 = dir.resolve("seg2.toon");
        Files.writeString(seg1, segSchema);
        Files.writeString(seg2, segSchema);
        String toon = """
                name: SEG_SHAPE
                active: true
                dirs:
                  poll: %1$s/inbox
                  database: %1$s/db
                  backup: %1$s/backup
                  temp: %1$s/temp
                  status_dir: %1$s/status
                  log_dir: %1$s/logs
                output:
                  format: CSV
                processing:
                  threads: 1
                  ingester: com.example.FakeIngester
                  segments:
                    alpha: "%2$s"
                    beta: "%3$s"
                  csv_settings:
                    has_header: false
                """.formatted(posix(dir), posix(seg1), posix(seg2));
        Path toonFile = dir.resolve("seg_pipeline.toon");
        Files.writeString(toonFile, toon);

        FlowGraph g = PipelineLift.lift(PipelineConfig.load(toonFile.toString()));
        Path schemaDir = dir.resolve("schemas");
        Map<String, Object> map = FlowCompiler.toConfigMap(g, schemaDir);

        Map<String, Object> proc = (Map<String, Object>) map.get("processing");
        assertEquals("com.example.FakeIngester", proc.get("ingester"), "ingester class preserved");
        Map<String, String> segments = (Map<String, String>) proc.get("segments");
        assertNotNull(segments, "segments map must be present");
        assertEquals(Set.of("alpha", "beta"), segments.keySet(), "both segment names preserved");
        for (String path : segments.values())
            assertTrue(Files.exists(Path.of(path)), "segment toon file must be written: " + path);
    }

    private static Path writeRowFilterPipeline(Path dir) throws Exception {
        Files.createDirectories(dir);
        Path sf = dir.resolve("schema.toon");
        // Write inline TOON — ConfigCodec.toToon doesn't emit tabular-array format and the
        // TOON parser would reject it with "Array length mismatch" when re-reading fields/rules.
        Files.writeString(sf, """
                partitionKey: EVENT_DATE
                raw:
                  name: rf_data
                  format: CSV
                  fields[3]{name,selector,type}:
                    ID, "0", VARCHAR
                    AMT, "1", DOUBLE
                    EVENT_DATE, "2", DATE
                mapping:
                  canonicalName: rf_data
                  rawName: rf_data
                  rules[3]{targetColumn,sourceExpression,transformType}:
                    ID, ID, DIRECT
                    AMT, AMT, DIRECT
                    EVENT_DATE, EVENT_DATE, DIRECT
                """);
        String toon = """
                name: RF_PARITY
                active: true
                dirs:
                  poll: %1$s/inbox
                  database: %1$s/db
                  backup: %1$s/backup
                  temp: %1$s/temp
                  markers: %1$s/markers
                  status_dir: %1$s/status
                  log_dir: %1$s/logs
                output:
                  format: CSV
                processing:
                  threads: 1
                  duplicate_check:
                    enabled: true
                    marker_extension: .processed
                  schema_file: %2$s
                  csv_settings:
                    delimiter: ","
                    has_header: true
                    skip_header_lines: 0
                    skip_junk_lines: 0
                    skip_tail_lines: 0
                    skip_tail_columns: 0
                    date_formats[1]: "%%Y-%%m-%%d"
                    timestamp_formats[1]: "%%Y-%%m-%%d"
                    filter_target_column: 1
                    exclude_prefixes[1]: "SKIP"
                """.formatted(posix(dir), posix(sf));
        Path p = dir.resolve("rf_pipeline.toon");
        Files.writeString(p, toon);
        return p;
    }
}

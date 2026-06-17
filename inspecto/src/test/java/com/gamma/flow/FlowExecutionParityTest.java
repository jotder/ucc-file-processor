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
}

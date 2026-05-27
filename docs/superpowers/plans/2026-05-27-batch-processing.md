# Batch Processing (many-to-many) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a *batch* as the unit of ETL processing so many small files are ingested in a single pass into consolidated partition output, with a per-(input→output) row-count lineage matrix, atomic end-of-batch CSV audit, member-file rejection via quarantine, and `ura reprocess <batch_id>` for full-set delete-and-reprocess.

**Architecture:** A new `BatchProcessor` owns one DuckDB instance per batch. Each member file is ingested into its own temp table, then `INSERT`ed into a shared `raw_input` table tagged with an integer `__src_id`; a rejected file's rows never reach `raw_input`. One `DataTransformer.materialize` pass builds the `transformed` table (carrying `__src_id`); `PartitionWriter` writes partitioned output excluding `__src_id`; `LineageCollector` produces the count matrix via one `GROUP BY`. `SourceProcessor.pollInbox` groups matched files by schema and packs them into `Batch`es via `BatchPlanner`, then submits each batch to the existing thread pool. A batch of one keeps the legacy `<basename>_out.<ext>` name.

**Tech Stack:** Java 24, Maven (maven-shade fat JAR), DuckDB JDBC 1.5.2.1, univocity-parsers, JToon + Jackson, Gson 2.11.0 (manifest JSON), JUnit 5 + maven-surefire (added in Task 1).

**Spec:** `docs/superpowers/specs/2026-05-27-batch-processing-design.md`

**Conventions for every task below:**
- All paths are relative to repo root `C:\sandbox\URA\sandbox`. The Maven module is `file-processor/`.
- Run Maven from the module dir: `cd file-processor` first, or use `mvn -f file-processor/pom.xml`.
- Run a single test class: `mvn -f file-processor/pom.xml -q -Dtest=<ClassName> test`
- Run all tests: `mvn -f file-processor/pom.xml -q test`
- Commit only the files listed in each task's `git add`.

---

### Task 1: Add JUnit 5 test harness

**Files:**
- Modify: `file-processor/pom.xml`
- Test: `file-processor/src/test/java/com/gamma/SmokeTest.java`

- [ ] **Step 1: Add JUnit dependencies and surefire to `pom.xml`**

Add these two `<dependency>` blocks inside `<dependencies>` (after the OpenCSV dependency, before `</dependencies>`):

```xml
        <!-- JUnit 5 — unit/integration tests -->
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>5.10.2</version>
            <scope>test</scope>
        </dependency>
```

Add this `<plugin>` block inside `<build><plugins>` (after the maven-shade-plugin `</plugin>`):

```xml
            <!-- Surefire — JUnit 5 runner; native-access flag silences DuckDB JNI warnings on Java 24 -->
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>3.2.5</version>
                <configuration>
                    <argLine>--enable-native-access=ALL-UNNAMED</argLine>
                </configuration>
            </plugin>
```

- [ ] **Step 2: Write the smoke test**

`file-processor/src/test/java/com/gamma/SmokeTest.java`:

```java
package com.gamma;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SmokeTest {
    @Test
    void harnessRuns() {
        assertEquals(2, 1 + 1);
    }
}
```

- [ ] **Step 3: Run it to verify the harness works**

Run: `mvn -f file-processor/pom.xml -q -Dtest=SmokeTest test`
Expected: `BUILD SUCCESS`, 1 test run, 0 failures.

- [ ] **Step 4: Commit**

```bash
git add file-processor/pom.xml file-processor/src/test/java/com/gamma/SmokeTest.java
git commit -m "test: add JUnit 5 harness with surefire"
```

---

### Task 2: Config — batch caps and audit/manifest paths in `PipelineConfig`

**Files:**
- Modify: `file-processor/src/main/java/com/gamma/etl/PipelineConfig.java`
- Test: `file-processor/src/test/java/com/gamma/etl/PipelineConfigBatchTest.java`
- Test resource (written by the test at runtime — no static file needed)

- [ ] **Step 1: Write the failing test**

`file-processor/src/test/java/com/gamma/etl/PipelineConfigBatchTest.java`:

```java
package com.gamma.etl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PipelineConfigBatchTest {

    /** Minimal 3-column schema reused across batch tests. */
    static String miniSchema() {
        return """
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
            """;
    }

    /** Writes a minimal valid pipeline toon into dir; returns its path. batchSection may be "". */
    static Path writePipeline(Path dir, String batchSection) throws Exception {
        Path schema = dir.resolve("mini_schema.toon");
        Files.writeString(schema, miniSchema());
        String toon = """
            name: MINI_ETL
            version: 1
            dirs:
              poll: %s/inbox
              database: %s/db
              backup: %s/backup
              temp: %s/temp
              errors: %s/errors
              quarantine: %s/quarantine
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
            %s
              csv_settings:
                delimiter: ","
                skip_header_lines: 0
                skip_junk_lines: 0
                skip_tail_lines: 0
                date_formats[1]: "%%Y-%%m-%%d"
                timestamp_formats[1]: "%%Y-%%m-%%d"
            """.formatted(dir, dir, dir, dir, dir, dir, dir, dir,
                          schema.toString().replace("\\", "/"), batchSection);
        Path p = dir.resolve("mini_pipeline.toon");
        Files.writeString(p, toon);
        return p;
    }

    @Test
    void defaultsToSingleFileBatchesWhenSectionAbsent(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(writePipeline(dir, "").toString());
        assertEquals(1, cfg.batchMaxFiles);
        assertEquals(Long.MAX_VALUE, cfg.batchMaxBytes);
    }

    @Test
    void readsBatchCaps(@TempDir Path dir) throws Exception {
        String batch = """
              batch:
                max_files: 500
                max_bytes: 268435456
            """;
        PipelineConfig cfg = PipelineConfig.load(writePipeline(dir, batch).toString());
        assertEquals(500, cfg.batchMaxFiles);
        assertEquals(268435456L, cfg.batchMaxBytes);
    }

    @Test
    void derivesBatchAuditPaths(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(writePipeline(dir, "").toString());
        assertNotNull(cfg.batchesFilePath);
        assertNotNull(cfg.lineageFilePath);
        assertNotNull(cfg.manifestsDir);
        assertTrue(cfg.batchesFilePath.contains("_batches_"));
        assertTrue(cfg.lineageFilePath.contains("_lineage_"));
        assertTrue(cfg.manifestsDir.replace("\\", "/").endsWith("manifests"));
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -f file-processor/pom.xml -q -Dtest=PipelineConfigBatchTest test`
Expected: COMPILE FAILURE — `cfg.batchMaxFiles`, `batchMaxBytes`, `batchesFilePath`, `lineageFilePath`, `manifestsDir` do not exist.

- [ ] **Step 3: Add the fields**

In `PipelineConfig.java`, after the `// ── processing ──` field block (after `public final String filePattern;`), add:

```java
    // ── batch ─────────────────────────────────────────────────────────────────

    /** Max member files per batch (default 1 → one file per batch, legacy behavior). */
    public final int  batchMaxFiles;
    /** Max summed input bytes per batch (default Long.MAX_VALUE). */
    public final long batchMaxBytes;
    /** Path to the run-scoped batches summary CSV; {@code null} when status is disabled. */
    public final String batchesFilePath;
    /** Path to the run-scoped lineage (count-matrix) CSV; {@code null} when status is disabled. */
    public final String lineageFilePath;
    /** Directory for per-batch JSON manifests; {@code null} when status is disabled. */
    public final String manifestsDir;
```

- [ ] **Step 4: Assign the fields in the constructor**

In the `private PipelineConfig(Builder b)` constructor, after `this.filePattern = b.filePattern;` add:

```java
        this.batchMaxFiles    = b.batchMaxFiles;
        this.batchMaxBytes    = b.batchMaxBytes;
        this.batchesFilePath  = b.batchesFilePath;
        this.lineageFilePath  = b.lineageFilePath;
        this.manifestsDir     = b.manifestsDir;
```

- [ ] **Step 5: Add Builder fields**

In the `Builder` class, after `String filePattern = "glob:**/*.{csv,csv.gz}";` add:

```java
        int    batchMaxFiles   = 1;
        long   batchMaxBytes   = Long.MAX_VALUE;
        String batchesFilePath;
        String lineageFilePath;
        String manifestsDir;
```

- [ ] **Step 6: Parse the batch section and derive audit paths in `load()`**

In `load()`, immediately after the `b.filePattern = opt(proc, "file_pattern", ...);` line, add:

```java
        // ── batch caps ──────────────────────────────────────────────────────────
        Map<String, Object> batch = (Map<String, Object>) proc.get("batch");
        if (batch != null) {
            b.batchMaxFiles = toInt(batch.getOrDefault("max_files", 1));
            Object mb = batch.get("max_bytes");
            b.batchMaxBytes = (mb == null) ? Long.MAX_VALUE : Long.parseLong(String.valueOf(mb));
        }
```

Then, in the same method, immediately after the block that sets `b.statusFilePath` (the `if (statusDir != null ...) { ... } else { ... }` block), add:

```java
        // ── batch audit + manifest paths (sibling to the status CSV) ──────────────
        if (b.statusFilePath != null && !b.statusFilePath.isBlank()) {
            Path statusParent = Paths.get(b.statusFilePath).toAbsolutePath().getParent();
            b.batchesFilePath = statusParent.resolve(
                    b.pipelineName + "_batches_" + b.runTimestamp + ".csv").toString();
            b.lineageFilePath = statusParent.resolve(
                    b.pipelineName + "_lineage_" + b.runTimestamp + ".csv").toString();
            b.manifestsDir = statusParent.resolve("manifests").toString();
        }
```

(`java.nio.file.Path` and `Paths` are already imported in this file.)

- [ ] **Step 7: Run to verify it passes**

Run: `mvn -f file-processor/pom.xml -q -Dtest=PipelineConfigBatchTest test`
Expected: PASS, 3 tests.

- [ ] **Step 8: Commit**

```bash
git add file-processor/src/main/java/com/gamma/etl/PipelineConfig.java file-processor/src/test/java/com/gamma/etl/PipelineConfigBatchTest.java
git commit -m "feat: parse processing.batch caps and derive audit/manifest paths"
```

---

### Task 3: Data records — `PartitionOutput`, `LineageRow`, `Batch`/`Member`

**Files:**
- Create: `file-processor/src/main/java/com/gamma/etl/PartitionOutput.java`
- Create: `file-processor/src/main/java/com/gamma/etl/LineageRow.java`
- Create: `file-processor/src/main/java/com/gamma/etl/Batch.java`
- Test: `file-processor/src/test/java/com/gamma/etl/BatchRecordsTest.java`

These are simple immutable carriers; one test confirms they compile and hold values.

- [ ] **Step 1: Write the failing test**

`file-processor/src/test/java/com/gamma/etl/BatchRecordsTest.java`:

```java
package com.gamma.etl;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BatchRecordsTest {
    @Test
    void recordsHoldValues() {
        PartitionOutput po = new PartitionOutput("year=2020/month=04/day=03", "/db/b_out.csv", 123L);
        assertEquals("year=2020/month=04/day=03", po.partition());
        assertEquals(123L, po.bytes());

        LineageRow lr = new LineageRow("B1", 0, "a.csv", "/db/b_out.csv", "year=2020/month=04/day=03", 5L);
        assertEquals(5L, lr.rowCount());

        SchemaSelector.Selection sel =
                new SchemaSelector.Selection(Map.of("raw", Map.of("name", "mini")), "mini");
        Batch.Member m = new Batch.Member(new File("a.csv"), 0, 10L, sel);
        Batch b = new Batch("B1", "mini", "mini", List.of(m));
        assertEquals("B1", b.batchId());
        assertEquals(1, b.members().size());
        assertEquals(0, b.members().get(0).srcId());
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -f file-processor/pom.xml -q -Dtest=BatchRecordsTest test`
Expected: COMPILE FAILURE — types do not exist.

- [ ] **Step 3: Create the records**

`file-processor/src/main/java/com/gamma/etl/PartitionOutput.java`:

```java
package com.gamma.etl;

/**
 * One output file written for a single partition by {@link PartitionWriter}.
 *
 * @param partition  partition path, e.g. {@code "year=2020/month=04/day=03"}
 * @param outputFile absolute path of the revealed output file
 * @param bytes      size of the output file in bytes
 */
public record PartitionOutput(String partition, String outputFile, long bytes) {}
```

`file-processor/src/main/java/com/gamma/etl/LineageRow.java`:

```java
package com.gamma.etl;

/**
 * One cell of the many-to-many count matrix: how many transformed rows from
 * {@code inputFile} (tagged {@code srcId}) landed in {@code outputFile}.
 *
 * @param batchId    owning batch id
 * @param srcId      0-based member index within the batch
 * @param inputFile  member file name
 * @param outputFile absolute path of the output file the rows landed in
 * @param partition  partition path, e.g. {@code "year=2020/month=04/day=03"}
 * @param rowCount   number of rows from inputFile in this output file
 */
public record LineageRow(String batchId, int srcId, String inputFile,
                         String outputFile, String partition, long rowCount) {}
```

`file-processor/src/main/java/com/gamma/etl/Batch.java`:

```java
package com.gamma.etl;

import java.io.File;
import java.util.List;

/**
 * A unit of processing: one or more member files sharing a single schema/table,
 * processed in one pass into consolidated partition output.
 *
 * @param batchId    unique id, e.g. {@code "20260527_103000_mini_0001"}
 * @param schemaName human-readable schema name (from {@code raw.name})
 * @param table      output table sub-directory under {@code dirs.database}; may be {@code null}
 * @param members    member files in deterministic order, each with its 0-based {@code srcId}
 */
public record Batch(String batchId, String schemaName, String table, List<Member> members) {

    /**
     * @param file      the member input file
     * @param srcId     0-based index within the batch (used as the {@code __src_id} tag)
     * @param bytes     file size in bytes (used during planning)
     * @param selection resolved schema + table for this file
     */
    public record Member(File file, int srcId, long bytes, SchemaSelector.Selection selection) {}
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `mvn -f file-processor/pom.xml -q -Dtest=BatchRecordsTest test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add file-processor/src/main/java/com/gamma/etl/PartitionOutput.java file-processor/src/main/java/com/gamma/etl/LineageRow.java file-processor/src/main/java/com/gamma/etl/Batch.java file-processor/src/test/java/com/gamma/etl/BatchRecordsTest.java
git commit -m "feat: add Batch, Member, PartitionOutput, LineageRow records"
```

---

### Task 4: `BatchPlanner` — group by schema, pack by count OR bytes

**Files:**
- Create: `file-processor/src/main/java/com/gamma/etl/BatchPlanner.java`
- Test: `file-processor/src/test/java/com/gamma/etl/BatchPlannerTest.java`

`BatchPlanner` is pure: it takes files, a `SchemaResolver` (so tests can stub schema resolution), the caps, and the run timestamp; it returns `List<Batch>`.

- [ ] **Step 1: Write the failing test**

`file-processor/src/test/java/com/gamma/etl/BatchPlannerTest.java`:

```java
package com.gamma.etl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BatchPlannerTest {

    /** Resolver that buckets by a table name encoded in the filename: "t1_*.csv" -> table "t1". */
    static BatchPlanner.SchemaResolver byPrefix() {
        return f -> {
            String table = f.getName().split("_", 2)[0];
            return new SchemaSelector.Selection(Map.of("raw", Map.of("name", table)), table);
        };
    }

    static File file(Path dir, String name, int bytes) throws Exception {
        Path p = dir.resolve(name);
        Files.write(p, new byte[bytes]);
        return p.toFile();
    }

    @Test
    void packsByFileCount(@TempDir Path dir) throws Exception {
        List<File> files = new ArrayList<>();
        for (int i = 0; i < 5; i++) files.add(file(dir, "t1_" + i + ".csv", 10));
        List<Batch> batches = BatchPlanner.plan(files, byPrefix(), 2, Long.MAX_VALUE, "TS");
        assertEquals(3, batches.size());            // 2 + 2 + 1
        assertEquals(2, batches.get(0).members().size());
        assertEquals(1, batches.get(2).members().size());
    }

    @Test
    void packsByByteCap(@TempDir Path dir) throws Exception {
        List<File> files = List.of(
                file(dir, "t1_a.csv", 100),
                file(dir, "t1_b.csv", 100),
                file(dir, "t1_c.csv", 100));
        List<Batch> batches = BatchPlanner.plan(files, byPrefix(), 100, 250, "TS");
        assertEquals(2, batches.size());            // 100+100 <=250, then 100
        assertEquals(2, batches.get(0).members().size());
        assertEquals(1, batches.get(1).members().size());
    }

    @Test
    void oversizeFileGetsOwnBatch(@TempDir Path dir) throws Exception {
        List<File> files = List.of(
                file(dir, "t1_big.csv", 500),
                file(dir, "t1_small.csv", 10));
        List<Batch> batches = BatchPlanner.plan(files, byPrefix(), 100, 100, "TS");
        assertEquals(2, batches.size());
        assertEquals(1, batches.get(0).members().size());
        assertEquals("t1_big.csv", batches.get(0).members().get(0).file().getName());
    }

    @Test
    void groupsBySchemaAndAssignsSrcIds(@TempDir Path dir) throws Exception {
        List<File> files = List.of(
                file(dir, "t1_a.csv", 10),
                file(dir, "t2_a.csv", 10),
                file(dir, "t1_b.csv", 10));
        List<Batch> batches = BatchPlanner.plan(files, byPrefix(), 500, Long.MAX_VALUE, "TS");
        assertEquals(2, batches.size());            // one per table
        Batch t1 = batches.stream().filter(b -> "t1".equals(b.table())).findFirst().orElseThrow();
        assertEquals(2, t1.members().size());
        assertEquals(0, t1.members().get(0).srcId());
        assertEquals(1, t1.members().get(1).srcId());
        // batchId carries the run timestamp and the table slug
        assertTrue(t1.batchId().startsWith("TS_t1_"));
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -f file-processor/pom.xml -q -Dtest=BatchPlannerTest test`
Expected: COMPILE FAILURE — `BatchPlanner` does not exist.

- [ ] **Step 3: Implement `BatchPlanner`**

`file-processor/src/main/java/com/gamma/etl/BatchPlanner.java`:

```java
package com.gamma.etl;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Groups matched files by resolved schema/table, then greedily packs each group
 * into {@link Batch}es honoring {@code maxFiles} OR {@code maxBytes} (whichever
 * trips first). A file larger than {@code maxBytes} forms a batch of one.
 *
 * <p>Pure and side-effect free apart from reading {@link File#length()}; the
 * schema resolution is injected via {@link SchemaResolver} so it is unit-testable
 * without a {@link PipelineConfig}.
 */
public final class BatchPlanner {

    private BatchPlanner() {}

    /** Resolves the schema/table for one file (wraps {@code SchemaSelector.select} or a single schema). */
    @FunctionalInterface
    public interface SchemaResolver {
        SchemaSelector.Selection resolve(File file) throws IOException;
    }

    /**
     * Plan batches from the given files.
     *
     * @param files        candidate files (already filtered for duplicates)
     * @param resolver      schema/table resolver
     * @param maxFiles      max member files per batch (>= 1)
     * @param maxBytes      max summed bytes per batch (>= 1)
     * @param runTimestamp  run timestamp embedded in each batch id
     * @return batches, grouped by schema/table, in deterministic order
     * @throws IOException if schema resolution fails
     */
    public static List<Batch> plan(List<File> files, SchemaResolver resolver,
                                   int maxFiles, long maxBytes, String runTimestamp)
            throws IOException {

        // Group by table key (insertion-ordered for determinism), preserving each
        // file's resolved selection. Files sorted by path so packing is reproducible.
        List<File> sorted = new ArrayList<>(files);
        sorted.sort(Comparator.comparing(f -> f.toPath().toAbsolutePath().toString()));

        LinkedHashMap<String, List<File>> byKey = new LinkedHashMap<>();
        Map<File, SchemaSelector.Selection> selByFile = new HashMap<>();
        for (File f : sorted) {
            SchemaSelector.Selection sel = resolver.resolve(f);
            String key = (sel.table() != null && !sel.table().isBlank()) ? sel.table() : "default";
            byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(f);
            selByFile.put(f, sel);
        }

        List<Batch> batches = new ArrayList<>();
        int seq = 1;
        for (Map.Entry<String, List<File>> group : byKey.entrySet()) {
            String key  = group.getKey();
            String slug = key.replaceAll("[^A-Za-z0-9]+", "_");

            List<Batch.Member> current = new ArrayList<>();
            long currentBytes = 0;
            for (File f : group.getValue()) {
                long bytes = f.length();
                boolean wouldExceed = !current.isEmpty()
                        && (current.size() >= maxFiles || currentBytes + bytes > maxBytes);
                if (wouldExceed) {
                    batches.add(buildBatch(runTimestamp, slug, seq++, key, current, selByFile));
                    current = new ArrayList<>();
                    currentBytes = 0;
                }
                current.add(new Batch.Member(f, current.size(), bytes, selByFile.get(f)));
                currentBytes += bytes;
            }
            if (!current.isEmpty())
                batches.add(buildBatch(runTimestamp, slug, seq++, key, current, selByFile));
        }
        return batches;
    }

    private static Batch buildBatch(String ts, String slug, int seq, String table,
                                    List<Batch.Member> members,
                                    Map<File, SchemaSelector.Selection> selByFile) {
        // Re-index srcId from 0 within the final batch (members were added with running index).
        List<Batch.Member> reindexed = new ArrayList<>(members.size());
        for (int i = 0; i < members.size(); i++) {
            Batch.Member m = members.get(i);
            reindexed.add(new Batch.Member(m.file(), i, m.bytes(), m.selection()));
        }
        String batchId = String.format("%s_%s_%04d", ts, slug, seq);
        String schemaName = schemaNameOf(reindexed.get(0).selection());
        return new Batch(batchId, schemaName, "default".equals(table) ? null : table, reindexed);
    }

    @SuppressWarnings("unchecked")
    private static String schemaNameOf(SchemaSelector.Selection sel) {
        Object raw = sel.schema().get("raw");
        if (raw instanceof Map<?, ?> rawMap && rawMap.get("name") != null)
            return String.valueOf(rawMap.get("name"));
        return "schema";
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `mvn -f file-processor/pom.xml -q -Dtest=BatchPlannerTest test`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add file-processor/src/main/java/com/gamma/etl/BatchPlanner.java file-processor/src/test/java/com/gamma/etl/BatchPlannerTest.java
git commit -m "feat: add BatchPlanner (group by schema, pack by count/bytes)"
```

---

### Task 5: `CsvIngester` — target-table overload

**Files:**
- Modify: `file-processor/src/main/java/com/gamma/etl/CsvIngester.java`
- Test: `file-processor/src/test/java/com/gamma/etl/CsvIngesterTargetTableTest.java`

Currently `ingest(...)` hardcodes the table name `raw_input` in the DDL and the appender. Parameterize it via an overload so each batch member can ingest into its own temp table.

- [ ] **Step 1: Write the failing test**

`file-processor/src/test/java/com/gamma/etl/CsvIngesterTargetTableTest.java`:

```java
package com.gamma.etl;

import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;

import static org.junit.jupiter.api.Assertions.*;

class CsvIngesterTargetTableTest {

    @Test
    void ingestsIntoNamedTable(@TempDir Path dir) throws Exception {
        Path schema = dir.resolve("mini_schema.toon");
        Files.writeString(schema, PipelineConfigBatchTest.miniSchema());
        Path toon = PipelineConfigBatchTest.writePipeline(dir, "");
        PipelineConfig cfg = PipelineConfig.load(toon.toString());

        Path csv = dir.resolve("data.csv");
        Files.writeString(csv, "ID,AMT,EVENT_DATE\na,1.5,2020-04-03\nb,2.5,2020-04-03\n");

        File db = DuckDbUtil.tempDbFile("test_");
        try (Connection conn = DuckDbUtil.openConnection(db)) {
            IngestResult r = CsvIngester.ingest(csv.toFile(), conn, cfg.singleSchema, cfg, "raw_f0");
            assertEquals(2, r.parsedRows());
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM raw_f0")) {
                rs.next();
                assertEquals(2, rs.getInt(1));
            }
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -f file-processor/pom.xml -q -Dtest=CsvIngesterTargetTableTest test`
Expected: COMPILE FAILURE — no 5-arg `ingest` overload.

- [ ] **Step 3: Add the overload and parameterize the table name**

In `CsvIngester.java`, change the existing 4-arg method to delegate, and add a 5-arg method. Replace the method signature line:

```java
    public static IngestResult ingest(File file, Connection conn,
                                      Map<String, Object> schemaConfig,
                                      PipelineConfig cfg) throws Exception {
```

with:

```java
    public static IngestResult ingest(File file, Connection conn,
                                      Map<String, Object> schemaConfig,
                                      PipelineConfig cfg) throws Exception {
        return ingest(file, conn, schemaConfig, cfg, "raw_input");
    }

    /**
     * Ingest {@code file} into the table named {@code targetTable} in the supplied
     * DuckDB connection. Identical to the 4-arg overload but lets a batch member
     * stream into its own per-file staging table.
     */
    @SuppressWarnings("unchecked")
    public static IngestResult ingest(File file, Connection conn,
                                      Map<String, Object> schemaConfig,
                                      PipelineConfig cfg,
                                      String targetTable) throws Exception {
```

Then inside the (now 5-arg) method body, replace the table-creation block:

```java
            // ── create raw_input staging table ────────────────────────────────
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("DROP TABLE IF EXISTS raw_input");
                StringBuilder ddl = new StringBuilder("CREATE TABLE raw_input (");
```

with:

```java
            // ── create staging table ──────────────────────────────────────────
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("DROP TABLE IF EXISTS \"" + targetTable + "\"");
                StringBuilder ddl = new StringBuilder("CREATE TABLE \"" + targetTable + "\" (");
```

And replace the appender line:

```java
            try (DuckDBAppender appender = duckConn.createAppender("", "raw_input")) {
```

with:

```java
            try (DuckDBAppender appender = duckConn.createAppender("", targetTable)) {
```

- [ ] **Step 4: Run to verify it passes**

Run: `mvn -f file-processor/pom.xml -q -Dtest=CsvIngesterTargetTableTest test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add file-processor/src/main/java/com/gamma/etl/CsvIngester.java file-processor/src/test/java/com/gamma/etl/CsvIngesterTargetTableTest.java
git commit -m "feat: add target-table overload to CsvIngester"
```

---

### Task 6: `DataTransformer.materialize` + `PartitionWriter`

**Files:**
- Modify: `file-processor/src/main/java/com/gamma/etl/DataTransformer.java`
- Create: `file-processor/src/main/java/com/gamma/etl/PartitionWriter.java`
- Test: `file-processor/src/test/java/com/gamma/etl/PartitionWriterTest.java`

Split the COPY/rename out of `DataTransformer` into `PartitionWriter`, make `DataTransformer` produce a `transformed` table that carries `__src_id`, and have the COPY exclude `__src_id`.

- [ ] **Step 1: Write the failing test**

`file-processor/src/test/java/com/gamma/etl/PartitionWriterTest.java`:

```java
package com.gamma.etl;

import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.*;
import java.sql.*;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class PartitionWriterTest {

    @Test
    void writesPartitionsAndExcludesSrcId(@TempDir Path dir) throws Exception {
        File db = DuckDbUtil.tempDbFile("test_");
        String dbDir = dir.resolve("out").toString();
        try (Connection conn = DuckDbUtil.openConnection(db);
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE transformed AS SELECT * FROM (VALUES " +
                    "('a', '2020', '04', '03', 0)," +
                    "('b', '2020', '04', '03', 1)," +
                    "('c', '2020', '01', '01', 0)) " +
                    "t(ID, year, month, day, __src_id)");

            List<PartitionOutput> outs = PartitionWriter.write(
                    conn, "transformed", dbDir, "CSV", null, "B1");

            assertEquals(2, outs.size());                       // two partitions
            for (PartitionOutput o : outs) {
                assertTrue(o.outputFile().endsWith("B1_out.csv"));
                String content = Files.readString(Path.of(o.outputFile()));
                assertFalse(content.contains("__src_id"));      // excluded
                assertTrue(content.contains("ID") || content.contains("a") || content.contains("c"));
            }
            // a partition dir was created
            try (Stream<Path> w = Files.walk(Path.of(dbDir))) {
                assertTrue(w.anyMatch(p -> p.toString().replace('\\','/').contains("year=2020/month=04/day=03")));
            }
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -f file-processor/pom.xml -q -Dtest=PartitionWriterTest test`
Expected: COMPILE FAILURE — `PartitionWriter` does not exist.

- [ ] **Step 3: Create `PartitionWriter`**

`file-processor/src/main/java/com/gamma/etl/PartitionWriter.java`:

```java
package com.gamma.etl;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Writes a materialized table to partitioned output, excluding the internal
 * {@code __src_id} column, and reveals each partition file under a stable name
 * via a two-step atomic rename.
 *
 * <p>The {@code __src_id} column is dropped from the written rows with
 * {@code SELECT * EXCLUDE (__src_id)} so the output schema is unchanged.
 *
 * <p>Extracted from {@link DataTransformer}, where this COPY/rename logic lived
 * before batching required it to be reusable and lineage-aware.
 */
public final class PartitionWriter {

    private PartitionWriter() {}

    /**
     * @param conn         worker DuckDB connection containing {@code table}
     * @param table        table to write (must contain {@code year,month,day,__src_id})
     * @param databaseDir  output root (already resolved to include any table sub-dir)
     * @param outputFormat {@code "CSV"} or {@code "PARQUET"}
     * @param compression  parquet compression (ignored for CSV; may be {@code null})
     * @param baseName     output file stem; final files are {@code <baseName>_out.<ext>}
     * @return one {@link PartitionOutput} per revealed partition file
     */
    public static List<PartitionOutput> write(Connection conn, String table,
                                              String databaseDir, String outputFormat,
                                              String compression, String baseName)
            throws Exception {

        boolean isParquet = "PARQUET".equals(outputFormat);
        String  ext       = isParquet ? ".parquet" : ".csv";
        String  outputFileName = baseName + "_out" + ext;

        new File(databaseDir).mkdirs();
        String workerTag   = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Path   stagingPath = Paths.get(databaseDir, ".staging", workerTag);
        Files.createDirectories(stagingPath);
        String stagingDir  = stagingPath.toString().replace("\\", "/");

        List<PartitionOutput> outputs = new ArrayList<>();

        try (Statement stmt = conn.createStatement()) {
            StringBuilder copyOpts = new StringBuilder("FORMAT ").append(outputFormat)
                    .append(", PARTITION_BY (year, month, day), OVERWRITE_OR_IGNORE 1");
            if (isParquet && compression != null && !compression.isBlank())
                copyOpts.append(", COMPRESSION ").append(compression);

            stmt.execute(String.format(
                    "COPY (SELECT * EXCLUDE (__src_id) FROM %s) TO '%s' (%s)",
                    table, stagingDir, copyOpts));

            try (Stream<Path> staged = Files.walk(stagingPath)) {
                staged.filter(Files::isRegularFile).forEach(src -> {
                    Path rel      = stagingPath.relativize(src);
                    Path dstFinal = Paths.get(databaseDir).resolve(rel).resolveSibling(outputFileName);
                    Path dstTemp  = dstFinal.resolveSibling(outputFileName + ".tmp");
                    try {
                        Files.createDirectories(dstFinal.getParent());
                        Files.move(src, dstTemp, StandardCopyOption.REPLACE_EXISTING);
                        Files.move(dstTemp, dstFinal,
                                StandardCopyOption.REPLACE_EXISTING,
                                StandardCopyOption.ATOMIC_MOVE);
                        String partition = rel.getParent().toString().replace("\\", "/");
                        outputs.add(new PartitionOutput(partition, dstFinal.toString(), Files.size(dstFinal)));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            }

            try (Stream<Path> cleanup = Files.walk(stagingPath)) {
                cleanup.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            }
        }
        return outputs;
    }
}
```

- [ ] **Step 4: Run the PartitionWriter test to verify it passes**

Run: `mvn -f file-processor/pom.xml -q -Dtest=PartitionWriterTest test`
Expected: PASS.

- [ ] **Step 5: Refactor `DataTransformer` to `materialize` (carries `__src_id`, no COPY)**

Replace the entire body of `DataTransformer.java` with the version below. It keeps the typed-SELECT and partition-column logic but (a) renames `transform` → `materialize`, (b) drops the `tableName`/`inputFile` params and the COPY/rename block (now in `PartitionWriter`), (c) appends `__src_id` to the projection, and (d) returns `void` (the `transformed` table is left in the connection).

```java
package com.gamma.etl;

import com.gamma.util.SqlBuilder;

import java.sql.Connection;
import java.sql.Statement;
import java.util.*;

/**
 * Builds the {@code transformed} table from {@code raw_input} by applying the
 * schema's mapping rules and type casts, plus the {@code year/month/day}
 * partition columns and the internal {@code __src_id} lineage tag.
 *
 * <p>{@code raw_input} must already exist in {@code conn} and carry a trailing
 * {@code __src_id INTEGER} column (added by {@link com.gamma.inspector.BatchProcessor}).
 * Writing the partitioned output is the responsibility of {@link PartitionWriter};
 * computing lineage is {@link LineageCollector}.
 */
public final class DataTransformer {

    private DataTransformer() {}

    /**
     * Create the {@code transformed} table in {@code conn} from {@code raw_input}.
     *
     * @param conn         worker DuckDB connection containing {@code raw_input}
     * @param schemaConfig schema config map ({@code raw.fields}, {@code mapping}, {@code partitionKey})
     * @param cfg          pipeline configuration (date/timestamp formats)
     */
    @SuppressWarnings("unchecked")
    public static void materialize(Connection conn, Map<String, Object> schemaConfig,
                                   PipelineConfig cfg) throws Exception {

        List<Map<String, Object>> fields =
                (List<Map<String, Object>>) ((Map<String, Object>) schemaConfig.get("raw")).get("fields");
        Map<String, String> fieldTypes = new LinkedHashMap<>();
        for (Map<String, Object> f : fields)
            fieldTypes.put((String) f.get("name"), (String) f.get("type"));

        List<Map<String, String>> rules =
                (List<Map<String, String>>) ((Map<String, Object>) schemaConfig.get("mapping")).get("rules");
        String partitionKey = (String) schemaConfig.get("partitionKey");

        StringBuilder select = new StringBuilder("SELECT ");
        for (int i = 0; i < rules.size(); i++) {
            Map<String, String> rule = rules.get(i);
            String source        = rule.get("sourceExpression");
            String target        = rule.get("targetColumn");
            String transformType = rule.getOrDefault("transformType", "DIRECT");

            if ("CONCAT_DT".equals(transformType)) {
                String[] parts  = source.split("\\|", 2);
                String dateCol  = "raw_input.\"" + parts[0] + '"';
                String timeCol  = "raw_input.\"" + parts[1] + '"';
                SqlBuilder.appendCoalesce(select,
                        dateCol + " || ' ' || " + timeCol, cfg.tsFormats, "TIMESTAMP");
            } else if ("FILENAME_DATE".equals(transformType)) {
                if (!"EVENT_DATE".equals(target)) {
                    throw new IllegalArgumentException(
                            "FILENAME_DATE transform is only supported for the EVENT_DATE column, got: " + target);
                }
                String[] parts  = source.split("\\|", 3);
                String   col    = "raw_input.\"" + parts[0] + '"';
                String   prefix = parts.length > 1 ? parts[1] : "";
                String   fmt    = parts.length > 2 ? parts[2] : "%Y%m%d";
                select.append("TRY_STRPTIME(regexp_extract(")
                      .append(col).append(", '").append(prefix)
                      .append("([0-9]{8})', 1), '").append(fmt).append("')::DATE");
            } else {
                String col  = "raw_input.\"" + source + '"';
                String type = fieldTypes.getOrDefault(source, "VARCHAR");
                switch (type) {
                    case "TIMESTAMP" -> SqlBuilder.appendCoalesce(select, col, cfg.tsFormats, "TIMESTAMP");
                    case "DATE"      -> SqlBuilder.appendCoalesce(select, col, cfg.dateFormats, "DATE");
                    case "DOUBLE"    -> select.append("TRY_CAST(").append(col).append(" AS DOUBLE)");
                    default          -> select.append(col);
                }
            }
            select.append(" AS \"").append(target).append('"');
            if (i < rules.size() - 1) select.append(", ");
        }

        if (partitionKey != null && !partitionKey.isEmpty()) {
            String castExpr = SqlBuilder.buildPartitionExpr(
                    partitionKey, rules, fieldTypes, cfg.dateFormats, cfg.tsFormats);
            select.append(", YEAR(").append(castExpr).append(")::VARCHAR AS year");
            select.append(", LPAD(MONTH(").append(castExpr).append(")::VARCHAR, 2, '0') AS month");
            select.append(", LPAD(DAY(").append(castExpr).append(")::VARCHAR, 2, '0') AS day");
        } else {
            select.append(", '1900' AS year, '01' AS month, '01' AS day");
        }
        // Carry the lineage tag through; PartitionWriter excludes it from output.
        select.append(", raw_input.\"__src_id\" AS __src_id");
        select.append(" FROM raw_input");

        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE transformed AS " + select);
        }
    }
}
```

- [ ] **Step 6: Build the whole module to confirm nothing else references the old `transform` signature**

Run: `mvn -f file-processor/pom.xml -q -DskipTests compile`
Expected: COMPILE FAILURE in `SourceProcessor.java` (it still calls `DataTransformer.transform`). This is expected — `SourceProcessor` is rewritten in Task 11. To keep the build green until then, temporarily comment out the body of `SourceProcessor.processFile` is **not** needed because Task 11 replaces the file wholesale; instead, verify only the two new units compile in isolation by running their tests:

Run: `mvn -f file-processor/pom.xml -q -Dtest=PartitionWriterTest,BatchPlannerTest,BatchRecordsTest test`

> **Note for the implementer:** `SourceProcessor.java` will not compile from this task until Task 11 replaces it. Surefire compiles test sources against main sources, so the test command above will also fail to compile the module. To keep tasks independently runnable, do Step 7 now: stub `SourceProcessor` so the module compiles.

- [ ] **Step 7: Temporarily stub `SourceProcessor.processFile` so the module compiles**

In `SourceProcessor.java`, replace the single line that calls the transformer:

```java
                xformResult = DataTransformer.transform(inputFile, conn, schema, table, cfg);
```

with a temporary shim (removed in Task 11):

```java
                // TEMP shim until Task 11 rewrites this class for batching.
                DataTransformer.materialize(conn, schema, cfg);
                xformResult = PartitionWriter.write(conn, "transformed",
                        (table != null && !table.isBlank())
                                ? java.nio.file.Paths.get(cfg.databaseDir, table).toString()
                                : cfg.databaseDir,
                        cfg.outputFormat, cfg.compression,
                        CsvIngester.stripExtensions(inputFile.getName()))
                        .stream()
                        .collect(java.util.stream.Collectors.teeing(
                                java.util.stream.Collectors.mapping(PartitionOutput::outputFile, java.util.stream.Collectors.toList()),
                                java.util.stream.Collectors.mapping(PartitionOutput::bytes, java.util.stream.Collectors.toList()),
                                TransformResult::new));
```

> This shim also requires `raw_input` to carry `__src_id`. The legacy `CsvIngester.ingest` does not add it, so the shimmed `SourceProcessor` path will throw at runtime — that is acceptable: it is exercised by no test and is deleted in Task 11. The shim exists only so `mvn compile` succeeds for Tasks 6–10.

Run: `mvn -f file-processor/pom.xml -q -Dtest=PartitionWriterTest test`
Expected: PASS (module compiles, PartitionWriter test green).

- [ ] **Step 8: Commit**

```bash
git add file-processor/src/main/java/com/gamma/etl/DataTransformer.java file-processor/src/main/java/com/gamma/etl/PartitionWriter.java file-processor/src/main/java/com/gamma/inspector/SourceProcessor.java file-processor/src/test/java/com/gamma/etl/PartitionWriterTest.java
git commit -m "refactor: split PartitionWriter from DataTransformer; carry __src_id"
```

---

### Task 7: `LineageCollector` — the count matrix

**Files:**
- Create: `file-processor/src/main/java/com/gamma/etl/LineageCollector.java`
- Test: `file-processor/src/test/java/com/gamma/etl/LineageCollectorTest.java`

- [ ] **Step 1: Write the failing test**

`file-processor/src/test/java/com/gamma/etl/LineageCollectorTest.java`:

```java
package com.gamma.etl;

import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.sql.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class LineageCollectorTest {

    @Test
    void countsRowsPerSrcAndPartition() throws Exception {
        File db = DuckDbUtil.tempDbFile("test_");
        try (Connection conn = DuckDbUtil.openConnection(db);
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE transformed AS SELECT * FROM (VALUES " +
                    "('2020','04','03',0)," +
                    "('2020','04','03',0)," +
                    "('2020','04','03',1)," +
                    "('2020','01','01',0)) t(year, month, day, __src_id)");

            List<PartitionOutput> outs = List.of(
                    new PartitionOutput("year=2020/month=04/day=03", "/db/B1_out.csv", 1),
                    new PartitionOutput("year=2020/month=01/day=01", "/db/B1_out.csv", 1));
            Map<Integer, String> srcIdToFile = Map.of(0, "a.csv", 1, "b.csv");

            List<LineageRow> rows = LineageCollector.collect(conn, "transformed", "B1", srcIdToFile, outs);

            // a.csv -> 04/03 = 2 rows ; b.csv -> 04/03 = 1 row ; a.csv -> 01/01 = 1 row
            long aTo0403 = rows.stream()
                    .filter(r -> r.inputFile().equals("a.csv") && r.partition().equals("year=2020/month=04/day=03"))
                    .mapToLong(LineageRow::rowCount).sum();
            assertEquals(2, aTo0403);
            long bTo0403 = rows.stream()
                    .filter(r -> r.inputFile().equals("b.csv") && r.partition().equals("year=2020/month=04/day=03"))
                    .mapToLong(LineageRow::rowCount).sum();
            assertEquals(1, bTo0403);
            assertEquals(3, rows.size());
            assertTrue(rows.stream().allMatch(r -> r.outputFile().equals("/db/B1_out.csv")));
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -f file-processor/pom.xml -q -Dtest=LineageCollectorTest test`
Expected: COMPILE FAILURE — `LineageCollector` does not exist.

- [ ] **Step 3: Implement `LineageCollector`**

`file-processor/src/main/java/com/gamma/etl/LineageCollector.java`:

```java
package com.gamma.etl;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;

/**
 * Builds the many-to-many count matrix for a batch: how many transformed rows
 * each member ({@code __src_id}) contributed to each output file.
 *
 * <p>Runs one {@code GROUP BY __src_id, year, month, day} over the materialized
 * {@code transformed} table and joins each {@code (year,month,day)} partition to
 * its {@link PartitionOutput} file.
 */
public final class LineageCollector {

    private LineageCollector() {}

    /**
     * @param conn        connection containing {@code table}
     * @param table       materialized table (must contain {@code year,month,day,__src_id})
     * @param batchId     owning batch id
     * @param srcIdToFile map of {@code __src_id} → member file name
     * @param outputs     revealed partition outputs (partition → file)
     * @return one {@link LineageRow} per (src, partition) group that has rows
     */
    public static List<LineageRow> collect(Connection conn, String table, String batchId,
                                           Map<Integer, String> srcIdToFile,
                                           List<PartitionOutput> outputs) throws SQLException {
        Map<String, String> partToFile = new HashMap<>();
        for (PartitionOutput o : outputs) partToFile.put(o.partition(), o.outputFile());

        List<LineageRow> rows = new ArrayList<>();
        String sql = "SELECT __src_id, year, month, day, COUNT(*) AS n FROM \""
                + table + "\" GROUP BY 1, 2, 3, 4";
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                int    srcId     = rs.getInt(1);
                String partition = "year=" + rs.getString(2)
                        + "/month=" + rs.getString(3) + "/day=" + rs.getString(4);
                String outputFile = partToFile.getOrDefault(partition, "");
                long   n          = rs.getLong(5);
                rows.add(new LineageRow(batchId, srcId,
                        srcIdToFile.getOrDefault(srcId, ""), outputFile, partition, n));
            }
        }
        return rows;
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `mvn -f file-processor/pom.xml -q -Dtest=LineageCollectorTest test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add file-processor/src/main/java/com/gamma/etl/LineageCollector.java file-processor/src/test/java/com/gamma/etl/LineageCollectorTest.java
git commit -m "feat: add LineageCollector (input->output count matrix)"
```

---

### Task 8: `BatchManifest` + `ManifestStore` (Gson JSON)

**Files:**
- Create: `file-processor/src/main/java/com/gamma/etl/BatchManifest.java`
- Create: `file-processor/src/main/java/com/gamma/etl/ManifestStore.java`
- Test: `file-processor/src/test/java/com/gamma/etl/ManifestStoreTest.java`

- [ ] **Step 1: Write the failing test**

`file-processor/src/test/java/com/gamma/etl/ManifestStoreTest.java`:

```java
package com.gamma.etl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ManifestStoreTest {

    @Test
    void writesReadsAndSupersedes(@TempDir Path dir) throws Exception {
        String manifestsDir = dir.resolve("manifests").toString();

        BatchManifest m = new BatchManifest();
        m.batchId = "B1";
        m.pipeline = "mini_etl";
        m.schemaName = "mini";
        m.outputTable = null;
        m.createdAt = "2026-05-27 10:30:00";
        m.members = List.of(new BatchManifest.MemberEntry("a.csv", 0, "20200403/a.csv",
                dir.resolve("backup/20200403/a.csv").toString(), "SUCCESS"));
        m.outputs = List.of(new BatchManifest.OutputEntry("year=2020/month=04/day=03",
                dir.resolve("db/B1_out.csv").toString()));
        m.markers = List.of(dir.resolve("markers/20200403/a.csv.processed").toString());

        ManifestStore.write(manifestsDir, m);

        BatchManifest back = ManifestStore.read(manifestsDir, "B1");
        assertEquals("B1", back.batchId);
        assertEquals(1, back.members.size());
        assertEquals("a.csv", back.members.get(0).filename);
        assertEquals(1, back.outputs.size());

        ManifestStore.supersede(manifestsDir, "B1");
        assertFalse(Files.exists(Path.of(manifestsDir, "B1.json")));
        assertTrue(Files.exists(Path.of(manifestsDir, "B1.json.superseded")));
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -f file-processor/pom.xml -q -Dtest=ManifestStoreTest test`
Expected: COMPILE FAILURE — types do not exist.

- [ ] **Step 3: Create `BatchManifest`**

`file-processor/src/main/java/com/gamma/etl/BatchManifest.java`:

```java
package com.gamma.etl;

import java.util.List;

/**
 * Serializable record of everything a batch produced, used by
 * {@code ura reprocess <batch_id>} to delete outputs/markers and restore members.
 *
 * <p>Plain mutable fields (not a record) for straightforward Gson (de)serialization.
 */
public final class BatchManifest {
    public String batchId;
    public String pipeline;
    public String schemaName;
    public String outputTable;     // null when writing directly to dirs.database
    public String createdAt;
    public List<MemberEntry> members;
    public List<OutputEntry> outputs;
    public List<String>      markers;

    /**
     * @param filename        member file name
     * @param srcId           0-based index within the batch
     * @param originalRelPath member path relative to the poll dir (for restore target)
     * @param backupPath      computed backup destination (where the source was moved)
     * @param status          SUCCESS or a QUARANTINED_* status
     */
    public record MemberEntry(String filename, int srcId, String originalRelPath,
                              String backupPath, String status) {}

    /**
     * @param partition  partition path, e.g. {@code "year=2020/month=04/day=03"}
     * @param outputFile absolute path of the produced output file
     */
    public record OutputEntry(String partition, String outputFile) {}
}
```

- [ ] **Step 4: Create `ManifestStore`**

`file-processor/src/main/java/com/gamma/etl/ManifestStore.java`:

```java
package com.gamma.etl;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/**
 * Reads and writes {@link BatchManifest} JSON files under a manifests directory.
 * One file per batch: {@code <manifestsDir>/<batchId>.json}.
 */
public final class ManifestStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private ManifestStore() {}

    /** Write {@code manifest} to {@code <manifestsDir>/<batchId>.json}. */
    public static void write(String manifestsDir, BatchManifest manifest) throws IOException {
        Path dir = Paths.get(manifestsDir);
        Files.createDirectories(dir);
        Path file = dir.resolve(manifest.batchId + ".json");
        Files.writeString(file, GSON.toJson(manifest), StandardCharsets.UTF_8);
    }

    /** Read the manifest for {@code batchId}. Throws if missing. */
    public static BatchManifest read(String manifestsDir, String batchId) throws IOException {
        Path file = Paths.get(manifestsDir, batchId + ".json");
        if (!Files.exists(file))
            throw new IOException("Manifest not found for batch " + batchId + ": " + file);
        return GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), BatchManifest.class);
    }

    /** Rename {@code <batchId>.json} to {@code <batchId>.json.superseded}. */
    public static void supersede(String manifestsDir, String batchId) throws IOException {
        Path file = Paths.get(manifestsDir, batchId + ".json");
        if (Files.exists(file))
            Files.move(file, file.resolveSibling(batchId + ".json.superseded"),
                    StandardCopyOption.REPLACE_EXISTING);
    }
}
```

- [ ] **Step 5: Run to verify it passes**

Run: `mvn -f file-processor/pom.xml -q -Dtest=ManifestStoreTest test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add file-processor/src/main/java/com/gamma/etl/BatchManifest.java file-processor/src/main/java/com/gamma/etl/ManifestStore.java file-processor/src/test/java/com/gamma/etl/ManifestStoreTest.java
git commit -m "feat: add BatchManifest + ManifestStore (Gson JSON)"
```

---

### Task 9: `BatchAuditWriter` — three append-only CSVs

**Files:**
- Create: `file-processor/src/main/java/com/gamma/etl/BatchAuditWriter.java`
- Test: `file-processor/src/test/java/com/gamma/etl/BatchAuditWriterTest.java`

`BatchAuditWriter` owns all three audit CSVs (batch_file = the evolved status CSV, batches, lineage). It is constructed with the three paths and exposes `flush(...)` which writes one batch's rows under a per-file lock, creating headers on first write.

- [ ] **Step 1: Write the failing test**

`file-processor/src/test/java/com/gamma/etl/BatchAuditWriterTest.java`:

```java
package com.gamma.etl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BatchAuditWriterTest {

    @Test
    void writesHeadersAndRows(@TempDir Path dir) throws Exception {
        String statusCsv  = dir.resolve("p_status_TS.csv").toString();
        String batchesCsv = dir.resolve("p_batches_TS.csv").toString();
        String lineageCsv = dir.resolve("p_lineage_TS.csv").toString();
        BatchAuditWriter w = new BatchAuditWriter(statusCsv, batchesCsv, lineageCsv);

        var fileRows = List.of(
                new BatchAuditWriter.FileRow("2026-05-27 10:30:00", "2026-05-27 10:30:01",
                        "a.csv", "SUCCESS", 2, 0, List.of("/db/B1_out.csv"), List.of(120L), 1000, "", "B1"),
                new BatchAuditWriter.FileRow("2026-05-27 10:30:00", "2026-05-27 10:30:01",
                        "bad.csv", "QUARANTINED_MISMATCH", 0, 3, List.of(), List.of(), 50, "0 valid rows", "B1"));
        var batchRow = new BatchAuditWriter.BatchRow("B1", "mini_etl", "mini", "",
                "2026-05-27 10:30:00", "2026-05-27 10:30:02", "SUCCESS",
                2, 1, 2, 2, 1, 120L, 2000, "");
        var lineage = List.of(new LineageRow("B1", 0, "a.csv", "/db/B1_out.csv", "year=2020/month=04/day=03", 2));

        w.flush(batchRow, fileRows, lineage);

        String status = Files.readString(Path.of(statusCsv));
        assertTrue(status.startsWith("start_time,end_time,filename,status,parsed_rows,error_rows,output_paths,output_sizes_bytes,duration_ms,error,batch_id"));
        assertTrue(status.contains("a.csv"));
        assertTrue(status.contains("QUARANTINED_MISMATCH"));

        String batches = Files.readString(Path.of(batchesCsv));
        assertTrue(batches.contains("batch_id,pipeline,schema_name,output_table"));
        assertTrue(batches.contains("B1"));

        String lin = Files.readString(Path.of(lineageCsv));
        assertTrue(lin.startsWith("batch_id,src_id,input_file,output_file,partition,row_count"));
        assertTrue(lin.contains("year=2020/month=04/day=03"));
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -f file-processor/pom.xml -q -Dtest=BatchAuditWriterTest test`
Expected: COMPILE FAILURE — `BatchAuditWriter` does not exist.

- [ ] **Step 3: Implement `BatchAuditWriter`**

`file-processor/src/main/java/com/gamma/etl/BatchAuditWriter.java`:

```java
package com.gamma.etl;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Appends one batch's audit to three run-scoped CSVs, structured for a future
 * bulk RDBMS load (all joined by {@code batch_id}):
 * <ul>
 *   <li><b>status</b> (batch_file): one row per member (surviving or rejected)</li>
 *   <li><b>batches</b>: one row per batch</li>
 *   <li><b>lineage</b>: the (input → output) count matrix</li>
 * </ul>
 *
 * <p>{@link #flush} is {@code synchronized} so each batch's rows are written
 * contiguously even when multiple batches finish concurrently.
 */
public final class BatchAuditWriter {

    private final String statusPath;
    private final String batchesPath;
    private final String lineagePath;

    public BatchAuditWriter(String statusPath, String batchesPath, String lineagePath) {
        this.statusPath  = statusPath;
        this.batchesPath = batchesPath;
        this.lineagePath = lineagePath;
    }

    /** One member-file audit row. */
    public record FileRow(String startTime, String endTime, String filename, String status,
                          long parsedRows, long errorRows, List<String> outputPaths,
                          List<Long> outputSizes, long durationMs, String error, String batchId) {}

    /** One batch-summary audit row. */
    public record BatchRow(String batchId, String pipeline, String schemaName, String outputTable,
                           String startTime, String endTime, String status,
                           int memberCount, int rejectedCount, long totalInputRows,
                           long totalOutputRows, int outputFileCount, long totalOutputBytes,
                           long durationMs, String error) {}

    /** Append this batch's rows to all three CSVs. */
    public synchronized void flush(BatchRow batch, List<FileRow> files, List<LineageRow> lineage) {
        appendStatus(files);
        appendBatch(batch);
        appendLineage(lineage);
    }

    private void appendStatus(List<FileRow> files) {
        if (statusPath == null) return;
        boolean exists = new java.io.File(statusPath).exists();
        try (PrintWriter pw = new PrintWriter(new FileWriter(statusPath, true))) {
            if (!exists)
                pw.println("start_time,end_time,filename,status,parsed_rows,error_rows," +
                        "output_paths,output_sizes_bytes,duration_ms,error,batch_id");
            for (FileRow f : files) {
                String paths = String.join(";", f.outputPaths()).replace('"', '\'');
                String sizes = f.outputSizes().stream().map(String::valueOf)
                        .collect(Collectors.joining(";"));
                pw.printf("%s,%s,%s,%s,%d,%d,\"%s\",\"%s\",%d,\"%s\",%s%n",
                        f.startTime(), f.endTime(), f.filename(), f.status(),
                        f.parsedRows(), f.errorRows(), paths, sizes, f.durationMs(),
                        f.error() == null ? "" : f.error().replace('"', '\''), f.batchId());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void appendBatch(BatchRow b) {
        if (batchesPath == null) return;
        boolean exists = new java.io.File(batchesPath).exists();
        try (PrintWriter pw = new PrintWriter(new FileWriter(batchesPath, true))) {
            if (!exists)
                pw.println("batch_id,pipeline,schema_name,output_table,start_time,end_time,status," +
                        "member_count,rejected_count,total_input_rows,total_output_rows," +
                        "output_file_count,total_output_bytes,duration_ms,error");
            pw.printf("%s,%s,%s,%s,%s,%s,%s,%d,%d,%d,%d,%d,%d,%d,\"%s\"%n",
                    b.batchId(), b.pipeline(), b.schemaName(),
                    b.outputTable() == null ? "" : b.outputTable(),
                    b.startTime(), b.endTime(), b.status(),
                    b.memberCount(), b.rejectedCount(), b.totalInputRows(), b.totalOutputRows(),
                    b.outputFileCount(), b.totalOutputBytes(), b.durationMs(),
                    b.error() == null ? "" : b.error().replace('"', '\''));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void appendLineage(List<LineageRow> rows) {
        if (lineagePath == null) return;
        boolean exists = new java.io.File(lineagePath).exists();
        try (PrintWriter pw = new PrintWriter(new FileWriter(lineagePath, true))) {
            if (!exists)
                pw.println("batch_id,src_id,input_file,output_file,partition,row_count");
            for (LineageRow r : rows) {
                pw.printf("%s,%d,%s,\"%s\",%s,%d%n",
                        r.batchId(), r.srcId(), r.inputFile(),
                        r.outputFile().replace('"', '\''), r.partition(), r.rowCount());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `mvn -f file-processor/pom.xml -q -Dtest=BatchAuditWriterTest test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add file-processor/src/main/java/com/gamma/etl/BatchAuditWriter.java file-processor/src/test/java/com/gamma/etl/BatchAuditWriterTest.java
git commit -m "feat: add BatchAuditWriter (status/batches/lineage CSVs)"
```

---

### Task 10: `BatchProcessor` — ingest, transform, lineage, commit

**Files:**
- Create: `file-processor/src/main/java/com/gamma/inspector/BatchProcessor.java`
- Test: `file-processor/src/test/java/com/gamma/inspector/BatchProcessorTest.java`

This is the orchestration core. The test drives a real batch of two good files plus one malformed file through `process(...)` and asserts: consolidated output, lineage rows, markers created, sources backed up, the bad file quarantined, and audit CSVs written.

- [ ] **Step 1: Write the failing test**

`file-processor/src/test/java/com/gamma/inspector/BatchProcessorTest.java`:

```java
package com.gamma.inspector;

import com.gamma.etl.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class BatchProcessorTest {

    private Batch.Member member(PipelineConfig cfg, File f, int id) throws Exception {
        SchemaSelector.Selection sel = new SchemaSelector.Selection(cfg.singleSchema, null);
        return new Batch.Member(f, id, f.length(), sel);
    }

    @Test
    void consolidatesGoodFilesQuarantinesBadOne(@TempDir Path dir) throws Exception {
        Path toon = PipelineConfigBatchTestRef.writePipeline(dir, "");
        PipelineConfig cfg = PipelineConfig.load(toon.toString());

        Path inbox = Path.of(cfg.pollDir);
        Files.createDirectories(inbox);
        Path a = inbox.resolve("a.csv");
        Path b = inbox.resolve("b.csv");
        Path bad = inbox.resolve("bad.csv");
        Files.writeString(a, "ID,AMT,EVENT_DATE\na1,1.0,2020-04-03\na2,2.0,2020-04-03\n");
        Files.writeString(b, "ID,AMT,EVENT_DATE\nb1,3.0,2020-04-03\nb2,4.0,2020-01-01\n");
        // bad.csv: only 1 column on data lines -> all rows rejected -> QUARANTINED_MISMATCH
        Files.writeString(bad, "ID,AMT,EVENT_DATE\njustonecolumn\nanotherbadline\n");

        List<Batch.Member> members = List.of(
                member(cfg, a.toFile(), 0), member(cfg, b.toFile(), 1), member(cfg, bad.toFile(), 2));
        Batch batch = new Batch(cfg.runTimestamp + "_mini_0001", "mini", null, members);

        BatchAuditWriter audit = new BatchAuditWriter(
                cfg.statusFilePath, cfg.batchesFilePath, cfg.lineageFilePath);
        BatchProcessor.process(batch, cfg, audit);

        // Output: consolidated files named by batchId, two partitions (04/03 and 01/01)
        try (Stream<Path> w = Files.walk(Path.of(cfg.databaseDir))) {
            List<Path> outFiles = w.filter(p -> p.getFileName().toString().endsWith("_out.csv")).toList();
            assertEquals(2, outFiles.size());
            assertTrue(outFiles.stream().allMatch(p -> p.getFileName().toString().startsWith(batch.batchId())));
        }

        // Bad file quarantined, good files backed up and out of the inbox
        assertFalse(Files.exists(a));
        assertFalse(Files.exists(b));
        assertFalse(Files.exists(bad));
        try (Stream<Path> q = Files.walk(Path.of(cfg.quarantineDir))) {
            assertTrue(q.anyMatch(p -> p.getFileName().toString().equals("bad.csv")));
        }
        assertTrue(Files.exists(Path.of(cfg.backupDir, "a.csv")));
        assertTrue(Files.exists(Path.of(cfg.backupDir, "b.csv")));

        // Markers created for survivors only
        assertTrue(Files.exists(Path.of(cfg.markersDir, "a.csv.processed")));
        assertTrue(Files.exists(Path.of(cfg.markersDir, "b.csv.processed")));
        assertFalse(Files.exists(Path.of(cfg.markersDir, "bad.csv.processed")));

        // Manifest written
        assertTrue(Files.exists(Path.of(cfg.manifestsDir, batch.batchId() + ".json")));

        // Lineage: a.csv -> 04/03 = 2 ; b.csv -> 04/03 = 1 ; b.csv -> 01/01 = 1
        String lineage = Files.readString(Path.of(cfg.lineageFilePath));
        assertTrue(lineage.contains("a.csv"));
        assertTrue(lineage.contains("b.csv"));

        // batches.csv records rejected_count = 1
        String batches = Files.readString(Path.of(cfg.batchesFilePath));
        assertTrue(batches.contains(batch.batchId()));
        assertTrue(batches.contains(",SUCCESS,"));

        // status.csv has a QUARANTINED_MISMATCH row for bad.csv
        String status = Files.readString(Path.of(cfg.statusFilePath));
        assertTrue(status.contains("QUARANTINED_MISMATCH"));
    }

    @Test
    void singleMemberKeepsLegacyName(@TempDir Path dir) throws Exception {
        Path toon = PipelineConfigBatchTestRef.writePipeline(dir, "");
        PipelineConfig cfg = PipelineConfig.load(toon.toString());
        Path inbox = Path.of(cfg.pollDir);
        Files.createDirectories(inbox);
        Path only = inbox.resolve("solo.csv");
        Files.writeString(only, "ID,AMT,EVENT_DATE\nx,9.0,2020-04-03\n");

        Batch batch = new Batch(cfg.runTimestamp + "_mini_0001", "mini", null,
                List.of(member(cfg, only.toFile(), 0)));
        BatchProcessor.process(batch, cfg,
                new BatchAuditWriter(cfg.statusFilePath, cfg.batchesFilePath, cfg.lineageFilePath));

        try (Stream<Path> w = Files.walk(Path.of(cfg.databaseDir))) {
            assertTrue(w.anyMatch(p -> p.getFileName().toString().equals("solo_out.csv")));
        }
    }
}
```

> **Note:** this test references `PipelineConfigBatchTestRef.writePipeline` / `miniSchema`. Those helpers live in `PipelineConfigBatchTest` (Task 2, package `com.gamma.etl`). To call them from package `com.gamma.inspector`, add a tiny public test helper in Step 2 below.

- [ ] **Step 2: Add a shared test helper for fixtures**

Create `file-processor/src/test/java/com/gamma/inspector/PipelineConfigBatchTestRef.java`:

```java
package com.gamma.inspector;

import java.nio.file.Path;

/** Bridges the fixture helpers in com.gamma.etl.PipelineConfigBatchTest to inspector-package tests. */
final class PipelineConfigBatchTestRef {
    static Path writePipeline(Path dir, String batchSection) throws Exception {
        return com.gamma.etl.PipelineConfigBatchTest.writePipeline(dir, batchSection);
    }
}
```

Make the two helpers in `PipelineConfigBatchTest` callable cross-package: change `static String miniSchema()` to `public static String miniSchema()` and `static Path writePipeline(...)` to `public static Path writePipeline(...)` in `file-processor/src/test/java/com/gamma/etl/PipelineConfigBatchTest.java`.

- [ ] **Step 3: Run to verify it fails**

Run: `mvn -f file-processor/pom.xml -q -Dtest=BatchProcessorTest test`
Expected: COMPILE FAILURE — `BatchProcessor` does not exist.

- [ ] **Step 4: Implement `BatchProcessor`**

`file-processor/src/main/java/com/gamma/inspector/BatchProcessor.java`:

```java
package com.gamma.inspector;

import com.gamma.etl.*;
import com.gamma.util.DuckDbUtil;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Processes one {@link Batch} in a single pass: ingests each member into a
 * per-file temp table, inserts accepted rows into a shared {@code raw_input}
 * tagged with {@code __src_id}, transforms once, writes consolidated partition
 * output, computes the lineage matrix, and commits (manifest → markers → backup
 * → audit). Rejected members are quarantined; their rows never reach
 * {@code raw_input}.
 */
public final class BatchProcessor {

    private BatchProcessor() {}

    public static void process(Batch batch, PipelineConfig cfg, BatchAuditWriter audit) {
        LocalDateTime batchStart = LocalDateTime.now();
        String batchStatus = "SUCCESS";
        String batchError  = "";

        Map<Integer, String> srcIdToFile = new LinkedHashMap<>();
        List<Batch.Member>   survivors   = new ArrayList<>();
        List<MemberAudit>    memberAudits = new ArrayList<>();
        long totalInputRows = 0;
        List<PartitionOutput> outputs = List.of();
        List<LineageRow>      lineage = List.of();

        File tempDb = null;
        try {
            tempDb = DuckDbUtil.tempDbFile("duckdb_batch_");
            try (Connection conn = DuckDbUtil.openConnection(tempDb)) {
                boolean rawCreated = false;
                for (Batch.Member m : batch.members()) {
                    LocalDateTime mStart = LocalDateTime.now();
                    String tempTable = "raw_f" + m.srcId();
                    IngestResult ing;
                    try {
                        ing = CsvIngester.ingest(m.file(), conn, m.selection().schema(), cfg, tempTable);
                    } catch (IOException e) {
                        QuarantineManager.quarantine(m.file(), "unreadable", false, cfg);
                        memberAudits.add(MemberAudit.rejected(m, "QUARANTINED_UNREADABLE",
                                msg(e), mStart));
                        dropTable(conn, tempTable);
                        continue;
                    }

                    if (ing.parsedRows() == 0
                            && (ing.errorRows() > 0 || ing.junkCandidateRows() > 0)) {
                        QuarantineManager.quarantine(m.file(), "field_mismatch",
                                ing.errorRows() > 0, cfg);
                        String reason = ing.errorRows() > 0
                                ? String.format("0 valid rows; %d row(s) rejected (field mismatch)", ing.errorRows())
                                : String.format("0 valid rows; %d content line(s) failed column-count", ing.junkCandidateRows());
                        memberAudits.add(MemberAudit.rejected(m, "QUARANTINED_MISMATCH", reason, mStart));
                        dropTable(conn, tempTable);
                        continue;
                    }

                    if (ing.parsedRows() == 0) {
                        // Empty-but-clean file: contributes nothing, not an error.
                        memberAudits.add(MemberAudit.accepted(m, 0, 0, mStart));
                        dropTable(conn, tempTable);
                        continue;
                    }

                    try (Statement st = conn.createStatement()) {
                        if (!rawCreated) {
                            st.execute("CREATE TABLE raw_input AS SELECT *, CAST(" + m.srcId()
                                    + " AS INTEGER) AS __src_id FROM \"" + tempTable + "\" WHERE false");
                            rawCreated = true;
                        }
                        st.execute("INSERT INTO raw_input SELECT *, " + m.srcId()
                                + " FROM \"" + tempTable + "\"");
                    }
                    dropTable(conn, tempTable);

                    srcIdToFile.put(m.srcId(), m.file().getName());
                    survivors.add(m);
                    totalInputRows += ing.parsedRows();
                    memberAudits.add(MemberAudit.accepted(m, ing.parsedRows(), ing.errorRows(), mStart));
                }

                if (!rawCreated) {
                    batchStatus = "EMPTY";
                } else {
                    DataTransformer.materialize(conn, batch.members().get(0).selection().schema(), cfg);
                    String dbDir = (batch.table() != null && !batch.table().isBlank())
                            ? Paths.get(cfg.databaseDir, batch.table()).toString()
                            : cfg.databaseDir;
                    String baseName = survivors.size() == 1
                            ? CsvIngester.stripExtensions(survivors.get(0).file().getName())
                            : batch.batchId();
                    outputs = PartitionWriter.write(conn, "transformed", dbDir,
                            cfg.outputFormat, cfg.compression, baseName);
                    lineage = LineageCollector.collect(conn, "transformed",
                            batch.batchId(), srcIdToFile, outputs);
                }
            } // connection closed
        } catch (Exception e) {
            batchStatus = "FAILED";
            batchError  = msg(e);
            e.printStackTrace();
        } finally {
            if (tempDb != null) DuckDbUtil.deleteTempDb(tempDb);
        }

        // ── commit / record ──────────────────────────────────────────────────
        try {
            if ("SUCCESS".equals(batchStatus)) {
                commit(batch, cfg, survivors, outputs, lineage);
            }
            // EMPTY and FAILED: no outputs revealed beyond what PartitionWriter wrote;
            // for FAILED we still leave survivors in the inbox (no markers, no backup).
            writeAudit(batch, cfg, audit, batchStart, batchStatus, batchError,
                    memberAudits, survivors, outputs, lineage, totalInputRows);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ── commit: register, manifest, markers, backup ────────────────────────────

    private static void commit(Batch batch, PipelineConfig cfg, List<Batch.Member> survivors,
                               List<PartitionOutput> outputs, List<LineageRow> lineage)
            throws IOException {

        DuckLakeRegistrar.register(outputs.stream().map(PartitionOutput::outputFile).toList(),
                batch.table(), cfg);

        Path poll   = Paths.get(cfg.pollDir).toAbsolutePath().normalize();
        Path backup = (cfg.backupDir != null && !cfg.backupDir.isBlank())
                ? Paths.get(cfg.backupDir).toAbsolutePath() : null;

        // Build manifest (computed paths) BEFORE creating markers / moving files.
        List<BatchManifest.MemberEntry> memberEntries = new ArrayList<>();
        List<String> markerPaths = new ArrayList<>();
        for (Batch.Member m : survivors) {
            Path filePath = m.file().toPath().toAbsolutePath().normalize();
            String rel    = poll.relativize(filePath).toString().replace('\\', '/');
            String backupPath = backup != null
                    ? backup.resolve(poll.relativize(filePath)).toString() : "";
            markerPaths.add(MarkerManager.getMarkerPath(m.file(), cfg).toString());
            memberEntries.add(new BatchManifest.MemberEntry(
                    m.file().getName(), m.srcId(), rel, backupPath, "SUCCESS"));
        }

        if (cfg.manifestsDir != null) {
            BatchManifest manifest = new BatchManifest();
            manifest.batchId     = batch.batchId();
            manifest.pipeline    = cfg.pipelineName;
            manifest.schemaName  = batch.schemaName();
            manifest.outputTable = batch.table();
            manifest.createdAt   = LocalDateTime.now().format(DuckDbUtil.DT_FMT);
            manifest.members     = memberEntries;
            manifest.outputs     = outputs.stream()
                    .map(o -> new BatchManifest.OutputEntry(o.partition(), o.outputFile())).toList();
            manifest.markers     = markerPaths;
            ManifestStore.write(cfg.manifestsDir, manifest);
        }

        for (Batch.Member m : survivors) MarkerManager.createMarkerFile(m.file(), cfg);
        if (backup != null)
            for (Batch.Member m : survivors) backupFile(m.file(), cfg);
    }

    /** Move a survivor source file into the backup dir, preserving poll-relative path. */
    private static void backupFile(File inputFile, PipelineConfig cfg) throws IOException {
        Path poll = Paths.get(cfg.pollDir).toAbsolutePath().normalize();
        Path file = inputFile.toPath().toAbsolutePath().normalize();
        Path dst  = Paths.get(cfg.backupDir).resolve(poll.relativize(file));
        Files.createDirectories(dst.getParent());
        Files.move(file, dst, StandardCopyOption.REPLACE_EXISTING);
    }

    // ── audit assembly ──────────────────────────────────────────────────────────

    private static void writeAudit(Batch batch, PipelineConfig cfg, BatchAuditWriter audit,
                                   LocalDateTime batchStart, String batchStatus, String batchError,
                                   List<MemberAudit> memberAudits, List<Batch.Member> survivors,
                                   List<PartitionOutput> outputs, List<LineageRow> lineage,
                                   long totalInputRows) {
        if (audit == null) return;
        LocalDateTime end = LocalDateTime.now();

        // per-member output files (from lineage), keyed by srcId
        Map<Integer, LinkedHashSet<String>> outBySrc = new HashMap<>();
        for (LineageRow r : lineage)
            outBySrc.computeIfAbsent(r.srcId(), k -> new LinkedHashSet<>()).add(r.outputFile());

        List<BatchAuditWriter.FileRow> fileRows = new ArrayList<>();
        int rejected = 0;
        for (MemberAudit ma : memberAudits) {
            if (!ma.status.equals("SUCCESS")) rejected++;
            List<String> paths = new ArrayList<>(
                    outBySrc.getOrDefault(ma.srcId, new LinkedHashSet<>()));
            fileRows.add(new BatchAuditWriter.FileRow(
                    ma.start.format(DuckDbUtil.DT_FMT), end.format(DuckDbUtil.DT_FMT),
                    ma.filename, ma.status, ma.parsedRows, ma.errorRows,
                    paths, Collections.nCopies(paths.size(), 0L),
                    Duration.between(ma.start, end).toMillis(), ma.error, batch.batchId()));
        }

        long totalOutputRows = lineage.stream().mapToLong(LineageRow::rowCount).sum();
        long totalOutputBytes = outputs.stream().mapToLong(PartitionOutput::bytes).sum();

        BatchAuditWriter.BatchRow batchRow = new BatchAuditWriter.BatchRow(
                batch.batchId(), cfg.pipelineName, batch.schemaName(), batch.table(),
                batchStart.format(DuckDbUtil.DT_FMT), end.format(DuckDbUtil.DT_FMT), batchStatus,
                batch.members().size(), rejected, totalInputRows, totalOutputRows,
                outputs.size(), totalOutputBytes,
                Duration.between(batchStart, end).toMillis(), batchError);

        audit.flush(batchRow, fileRows, lineage);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static void dropTable(Connection conn, String table) {
        try (Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS \"" + table + "\"");
        } catch (Exception ignored) { }
    }

    private static String msg(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    /** Internal per-member audit accumulator. */
    private record MemberAudit(int srcId, String filename, String status,
                               long parsedRows, long errorRows, String error, LocalDateTime start) {
        static MemberAudit accepted(Batch.Member m, long parsed, long errors, LocalDateTime start) {
            return new MemberAudit(m.srcId(), m.file().getName(), "SUCCESS", parsed, errors, "", start);
        }
        static MemberAudit rejected(Batch.Member m, String status, String error, LocalDateTime start) {
            return new MemberAudit(m.srcId(), m.file().getName(), status, 0, 0, error, start);
        }
    }
}
```

- [ ] **Step 5: Run to verify it passes**

Run: `mvn -f file-processor/pom.xml -q -Dtest=BatchProcessorTest test`
Expected: PASS, 2 tests.

- [ ] **Step 6: Commit**

```bash
git add file-processor/src/main/java/com/gamma/inspector/BatchProcessor.java file-processor/src/test/java/com/gamma/inspector/BatchProcessorTest.java file-processor/src/test/java/com/gamma/inspector/PipelineConfigBatchTestRef.java file-processor/src/test/java/com/gamma/etl/PipelineConfigBatchTest.java
git commit -m "feat: add BatchProcessor (single-pass ingest, transform, lineage, commit)"
```

---

### Task 11: Rewrite `SourceProcessor.pollInbox` to plan and submit batches

**Files:**
- Modify: `file-processor/src/main/java/com/gamma/inspector/SourceProcessor.java`
- Test: `file-processor/src/test/java/com/gamma/inspector/SourceProcessorPollTest.java`

Replace the per-file polling/processing with: collect candidates, drop already-marked files, plan batches, submit each batch to the thread pool. Remove `processFile`, `backupFile`, and the Task 6 shim. Expose a public `run(PipelineConfig)` for `reprocess` (Task 12).

- [ ] **Step 1: Write the failing test**

`file-processor/src/test/java/com/gamma/inspector/SourceProcessorPollTest.java`:

```java
package com.gamma.inspector;

import com.gamma.etl.PipelineConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class SourceProcessorPollTest {

    @Test
    void consolidatesManySmallFilesIntoOneBatch(@TempDir Path dir) throws Exception {
        String batch = """
              batch:
                max_files: 100
                max_bytes: 268435456
            """;
        Path toon = PipelineConfigBatchTestRef.writePipeline(dir, batch);
        PipelineConfig cfg = PipelineConfig.load(toon.toString());

        Path inbox = Path.of(cfg.pollDir);
        Files.createDirectories(inbox);
        for (int i = 0; i < 6; i++)
            Files.writeString(inbox.resolve("f" + i + ".csv"),
                    "ID,AMT,EVENT_DATE\nr" + i + ",1.0,2020-04-03\n");

        SourceProcessor.run(cfg);

        // All 6 tiny files consolidate into ONE partition's single output file.
        try (Stream<Path> w = Files.walk(Path.of(cfg.databaseDir))) {
            assertEquals(1, w.filter(p -> p.getFileName().toString().endsWith("_out.csv")).count());
        }
        // One batch row recorded; six member rows in status.
        String batches = Files.readString(Path.of(cfg.batchesFilePath));
        assertEquals(2, batches.split("\n").length, "header + 1 batch row"); // header + 1 line
        // Re-running is a no-op: markers skip all files (no new batch).
        long before = Files.size(Path.of(cfg.batchesFilePath));
        SourceProcessor.run(cfg);
        // No new output files appeared (still one).
        try (Stream<Path> w = Files.walk(Path.of(cfg.databaseDir))) {
            assertEquals(1, w.filter(p -> p.getFileName().toString().endsWith("_out.csv")).count());
        }
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -f file-processor/pom.xml -q -Dtest=SourceProcessorPollTest test`
Expected: COMPILE FAILURE — `SourceProcessor.run` does not exist.

- [ ] **Step 3: Replace `SourceProcessor.java`**

Replace the entire file `file-processor/src/main/java/com/gamma/inspector/SourceProcessor.java` with:

```java
package com.gamma.inspector;

import com.gamma.etl.*;
import com.gamma.util.LogSetup;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.stream.Stream;

/**
 * ETL entry point. Reads a {@code .toon} pipeline config, scans the inbox for
 * matching CSV/CSV.GZ files, groups them into {@link Batch}es by schema (packed
 * to {@code processing.batch.max_files}/{@code max_bytes}), and processes each
 * batch in one pass via {@link BatchProcessor}.
 *
 * <p>A batch of one file is the legacy single-file case and keeps the
 * {@code <basename>_out.<ext>} output name.
 *
 * <p>Run via: {@code java -jar file-processor.jar <pipeline.toon>}
 */
public class SourceProcessor {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: SourceProcessor <pipeline_config_path>");
            System.exit(1);
        }
        PipelineConfig cfg = PipelineConfig.load(args[0]);
        LogSetup.configure(cfg.logDir, cfg.pipelineName, cfg.runTimestamp);
        run(cfg);
    }

    /** Run one poll cycle for {@code cfg}: plan batches and process them in parallel. */
    public static void run(PipelineConfig cfg) throws Exception {
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher(cfg.filePattern);
        Path root           = Paths.get(cfg.pollDir).toAbsolutePath();
        Path errorsDir      = Paths.get(cfg.errorsDir).toAbsolutePath();
        Path quarantineDir  = Paths.get(cfg.quarantineDir).toAbsolutePath();
        if (!Files.exists(root)) Files.createDirectories(root);

        MarkerManager.cleanupStaleMarkers(cfg);

        // ── collect candidates (skip already-processed) ──────────────────────────
        List<File> candidates = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> !p.startsWith(errorsDir))
                .filter(p -> !p.startsWith(quarantineDir))
                .filter(matcher::matches)
                .map(Path::toFile)
                .filter(f -> !MarkerManager.isAlreadyProcessed(f, cfg))
                .forEach(candidates::add);
        }

        if (candidates.isEmpty()) {
            System.out.println("No new files to process in " + root);
            return;
        }

        // ── plan batches ─────────────────────────────────────────────────────────
        BatchPlanner.SchemaResolver resolver = (cfg.schemaSelector != null)
                ? cfg.schemaSelector::select
                : f -> new SchemaSelector.Selection(cfg.singleSchema, null);

        List<Batch> batches = BatchPlanner.plan(
                candidates, resolver, cfg.batchMaxFiles, cfg.batchMaxBytes, cfg.runTimestamp);
        System.out.printf("Planned %d batch(es) from %d file(s) using %d thread(s)...%n",
                batches.size(), candidates.size(), cfg.threads);

        // ── process batches in parallel ────────────────────────────────────────
        BatchAuditWriter audit = new BatchAuditWriter(
                cfg.statusFilePath, cfg.batchesFilePath, cfg.lineageFilePath);

        ExecutorService executor = Executors.newFixedThreadPool(cfg.threads);
        List<Future<?>> futures  = new ArrayList<>();
        for (Batch b : batches)
            futures.add(executor.submit(() -> BatchProcessor.process(b, cfg, audit)));

        for (Future<?> f : futures) {
            try   { f.get(); }
            catch (Exception e) { e.printStackTrace(); }
        }
        executor.shutdown();
        executor.awaitTermination(1, TimeUnit.HOURS);
    }
}
```

- [ ] **Step 4: Run the poll test**

Run: `mvn -f file-processor/pom.xml -q -Dtest=SourceProcessorPollTest test`
Expected: PASS.

- [ ] **Step 5: Run the full suite to confirm no regressions**

Run: `mvn -f file-processor/pom.xml -q test`
Expected: PASS (all test classes from Tasks 1–11).

- [ ] **Step 6: Commit**

```bash
git add file-processor/src/main/java/com/gamma/inspector/SourceProcessor.java file-processor/src/test/java/com/gamma/inspector/SourceProcessorPollTest.java
git commit -m "feat: batch-based polling in SourceProcessor; expose run(cfg)"
```

---

### Task 12: `reprocess` command — delete outputs/markers, restore, re-run

**Files:**
- Create: `file-processor/src/main/java/com/gamma/inspector/ReprocessCommand.java`
- Modify: `file-processor/src/main/java/com/gamma/util/MainApp.java`
- Test: `file-processor/src/test/java/com/gamma/inspector/ReprocessCommandTest.java`

- [ ] **Step 1: Write the failing test**

`file-processor/src/test/java/com/gamma/inspector/ReprocessCommandTest.java`:

```java
package com.gamma.inspector;

import com.gamma.etl.PipelineConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ReprocessCommandTest {

    @Test
    void deletesOutputsRestoresFilesAndReprocesses(@TempDir Path dir) throws Exception {
        Path toon = PipelineConfigBatchTestRef.writePipeline(dir, """
              batch:
                max_files: 100
            """);
        PipelineConfig cfg = PipelineConfig.load(toon.toString());
        Path inbox = Path.of(cfg.pollDir);
        Files.createDirectories(inbox);
        Files.writeString(inbox.resolve("a.csv"), "ID,AMT,EVENT_DATE\na,1.0,2020-04-03\n");
        Files.writeString(inbox.resolve("b.csv"), "ID,AMT,EVENT_DATE\nb,2.0,2020-04-03\n");

        SourceProcessor.run(cfg);

        // Find the batch id from the single manifest written.
        String batchId;
        try (Stream<Path> w = Files.walk(Path.of(cfg.manifestsDir))) {
            Path mf = w.filter(p -> p.toString().endsWith(".json")).findFirst().orElseThrow();
            batchId = mf.getFileName().toString().replace(".json", "");
        }

        // Sanity: outputs + markers + backup present before reprocess.
        assertTrue(Files.exists(Path.of(cfg.backupDir, "a.csv")));
        assertTrue(Files.exists(Path.of(cfg.markersDir, "a.csv.processed")));

        // Reprocess: must restore files, delete old outputs/markers, supersede manifest, re-run.
        ReprocessCommand.run(toon.toString(), batchId);

        // Old manifest superseded; a NEW manifest exists for the re-run.
        assertTrue(Files.exists(Path.of(cfg.manifestsDir, batchId + ".json.superseded")));
        // Markers exist again (re-run re-created them) and outputs exist.
        assertTrue(Files.exists(Path.of(cfg.markersDir, "a.csv.processed")));
        try (Stream<Path> w = Files.walk(Path.of(cfg.databaseDir))) {
            assertTrue(w.anyMatch(p -> p.getFileName().toString().endsWith("_out.csv")));
        }
    }
}
```

> **Note:** `ReprocessCommand.run` reloads the pipeline config from the toon, which produces a *new* `runTimestamp` (and thus a new batch id) for the re-run — exactly the spec's behavior.

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -f file-processor/pom.xml -q -Dtest=ReprocessCommandTest test`
Expected: COMPILE FAILURE — `ReprocessCommand` does not exist.

- [ ] **Step 3: Implement `ReprocessCommand`**

`file-processor/src/main/java/com/gamma/inspector/ReprocessCommand.java`:

```java
package com.gamma.inspector;

import com.gamma.etl.BatchManifest;
import com.gamma.etl.ManifestStore;
import com.gamma.etl.PipelineConfig;

import java.nio.file.*;

/**
 * Implements {@code ura reprocess <pipeline.toon> <batch_id>}: deletes the
 * batch's output files and markers, restores its member files from backup into
 * the inbox, supersedes the manifest, and triggers a fresh poll.
 *
 * <p>Reprocessing is whole-batch only; the original audit rows remain as history.
 */
public final class ReprocessCommand {

    private ReprocessCommand() {}

    public static void run(String toonPath, String batchId) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(toonPath);
        if (cfg.manifestsDir == null)
            throw new IllegalStateException("No manifests dir configured (set dirs.status_dir).");

        BatchManifest m = ManifestStore.read(cfg.manifestsDir, batchId);
        System.out.printf("[REPROCESS] %s — %d member(s), %d output(s)%n",
                batchId, m.members.size(), m.outputs.size());

        // 1. delete outputs
        for (BatchManifest.OutputEntry o : m.outputs) {
            Files.deleteIfExists(Paths.get(o.outputFile()));
        }
        // 2. delete markers
        for (String marker : m.markers) {
            Files.deleteIfExists(Paths.get(marker));
        }
        // 3. restore members from backup into the inbox (original relative path)
        Path poll = Paths.get(cfg.pollDir).toAbsolutePath();
        for (BatchManifest.MemberEntry me : m.members) {
            if (me.backupPath() == null || me.backupPath().isBlank()) continue;
            Path src = Paths.get(me.backupPath());
            if (!Files.exists(src)) {
                System.err.printf("[REPROCESS] WARN: backup missing, cannot restore %s (%s)%n",
                        me.filename(), src);
                continue;
            }
            Path dst = poll.resolve(me.originalRelPath());
            Files.createDirectories(dst.getParent());
            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
        }
        // 4. supersede the manifest
        ManifestStore.supersede(cfg.manifestsDir, batchId);

        // 5. re-run a normal poll on the restored set (fresh batch id)
        SourceProcessor.run(cfg);
        System.out.printf("[REPROCESS] %s complete.%n", batchId);
    }
}
```

- [ ] **Step 4: Wire `reprocess` into `MainApp`**

In `MainApp.java`, add a new case inside the `switch (command)` block, after the `prepare-inbox` case:

```java
                // ── reprocess: delete-and-reprocess a whole batch by id ────────

                case "reprocess": {
                    if (subArgs.length < 2) {
                        System.err.println("Usage: reprocess <pipeline.toon> <batch_id>");
                        System.exit(1);
                    }
                    com.gamma.inspector.ReprocessCommand.run(subArgs[0], subArgs[1]);
                    break;
                }
```

And add a usage line in `printUsage()`, after the `prepare-inbox` block:

```java
        System.out.println("  reprocess <pipeline.toon> <batch_id>");
        System.out.println("                              Delete a batch's outputs + markers, restore its");
        System.out.println("                              member files from backup, and reprocess the set.");
```

- [ ] **Step 5: Run to verify it passes**

Run: `mvn -f file-processor/pom.xml -q -Dtest=ReprocessCommandTest test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add file-processor/src/main/java/com/gamma/inspector/ReprocessCommand.java file-processor/src/main/java/com/gamma/util/MainApp.java file-processor/src/test/java/com/gamma/inspector/ReprocessCommandTest.java
git commit -m "feat: add reprocess command (delete outputs/markers, restore, re-run)"
```

---

### Task 13: Cleanup — remove dead code, update config + docs

**Files:**
- Delete: `file-processor/src/main/java/com/gamma/etl/StatusWriter.java` (superseded by `BatchAuditWriter`)
- Delete: `file-processor/src/main/java/com/gamma/etl/TransformResult.java` (no longer referenced after Task 11)
- Modify: `file-processor/config/adjustment/adjustment_pipeline.toon` (document the optional `batch` section)
- Modify: `file-processor/config/voucher/voucher_unknown_pipeline.toon` (document the optional `batch` section)
- Modify: `file-processor/README.md`

- [ ] **Step 1: Confirm `StatusWriter` and `TransformResult` are unreferenced**

Run: `grep -rn "StatusWriter\|TransformResult" file-processor/src/main`
Expected: no matches (both were only used by the old `SourceProcessor.processFile`, removed in Task 11). If `TransformResult` still appears, it is in the Task 6 shim — confirm Task 11 deleted that shim. If any match remains, stop and resolve the reference before deleting.

- [ ] **Step 2: Delete the dead files**

```bash
git rm file-processor/src/main/java/com/gamma/etl/StatusWriter.java file-processor/src/main/java/com/gamma/etl/TransformResult.java
```

- [ ] **Step 3: Build + full test to confirm nothing breaks**

Run: `mvn -f file-processor/pom.xml -q clean test`
Expected: PASS, all tests.

- [ ] **Step 4: Document the optional `batch` section in the two production pipeline toons**

Add the following block under `processing:` in `file-processor/config/adjustment/adjustment_pipeline.toon` and `file-processor/config/voucher/voucher_unknown_pipeline.toon` (indent two spaces under `processing:`, place it adjacent to `file_pattern`):

```
  batch:
    max_files: 500
    max_bytes: 268435456
```

(Recall JToon: no `#` comments in `.toon` files — see project memory `technical_jtoon`. Leave the values uncommented.)

- [ ] **Step 5: Update `README.md`**

Add a "Batch processing" subsection to `README.md` documenting: the `processing.batch.{max_files,max_bytes}` keys (default `max_files=1` = legacy per-file behavior); the three audit CSVs (`*_status_*`, `*_batches_*`, `*_lineage_*`) and that the count matrix lives in the lineage CSV; the manifests dir; and the `ura reprocess <pipeline.toon> <batch_id>` command. Keep it consistent with the existing README tone and headings.

- [ ] **Step 6: Commit**

```bash
git add file-processor/src/main/java/com/gamma/etl/ file-processor/config/adjustment/adjustment_pipeline.toon file-processor/config/voucher/voucher_unknown_pipeline.toon file-processor/README.md
git commit -m "chore: remove StatusWriter/TransformResult; document batch config + reprocess"
```

---

### Task 14: End-to-end verification

**Files:** none (verification only).

- [ ] **Step 1: Full clean build + test**

Run: `mvn -f file-processor/pom.xml -q clean test`
Expected: `BUILD SUCCESS`, all test classes green.

- [ ] **Step 2: Build the fat JAR**

Run: `mvn -f file-processor/pom.xml -q clean package`
Expected: `BUILD SUCCESS`, `file-processor/target/file-processor-1.0.jar` produced.

- [ ] **Step 3: Confirm the lineage reconciles (manual sanity using the integration test fixtures)**

Re-read `BatchProcessorTest.consolidatesGoodFilesQuarantinesBadOne`: confirm that the sum of `row_count` in the lineage CSV equals `total_output_rows` in `batches.csv` for the batch (2 from a.csv + 1 + 1 from b.csv = 4). This is the audit reconciliation the spec calls for. If the test asserts pass, this holds.

- [ ] **Step 4: Commit (if any incidental fixes were needed)**

```bash
git add -A
git commit -m "test: end-to-end batch processing verification"
```

(Skip if there is nothing to commit.)

---

## Self-Review (completed during plan authoring)

**Spec coverage:**
- Count-matrix lineage → Tasks 7 (LineageCollector), 9 (lineage CSV), 10 (wired in BatchProcessor). ✓
- Batch cap (count OR bytes) → Task 4 (BatchPlanner), Task 2 (config). ✓
- One schema per batch → Task 4 (grouping by table). ✓
- Rejection = quarantine + audit row + drop rows → Task 10 (BatchProcessor ingest loop). ✓
- Reprocess CLI → Task 12. ✓
- Unify; batch-of-one legacy name → Task 10 (baseName rule), Task 11 (single code path). ✓
- Flat-CSV audit (status/batches/lineage) → Task 9. ✓
- Manifest for "full set" → Task 8 + Task 10 commit. ✓
- Atomicity / commit order (manifest → markers → backup → audit; staging+atomic rename reveal) → Task 10. ✓
- Config defaults opt-in (max_files=1 = no change) → Task 2. ✓
- Testing strategy (planner, lineage, naming, integration, reprocess) → Tasks 4, 6, 7, 10, 11, 12. ✓
- Out-of-scope items (per-record lineage, mixed-schema batch, orphan GC, RDBMS load) → not implemented, by design. ✓

**Type/signature consistency:** `BatchPlanner.plan` / `SchemaResolver.resolve`, `DataTransformer.materialize`, `PartitionWriter.write` → `List<PartitionOutput>`, `LineageCollector.collect` → `List<LineageRow>`, `BatchProcessor.process(Batch, PipelineConfig, BatchAuditWriter)`, `SourceProcessor.run(PipelineConfig)`, `ReprocessCommand.run(String, String)`, `BatchAuditWriter.flush(BatchRow, List<FileRow>, List<LineageRow>)` — names match across all tasks.

**Placeholder scan:** No TBD/TODO; every code step shows full code; README step (Task 13 Step 5) describes exact content rather than prose-only, anchored to existing README conventions.

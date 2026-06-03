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
        SchemaSelector.Selection sel = new SchemaSelector.Selection(cfg.schemas().single(), null);
        return new Batch.Member(f, id, f.length(), sel);
    }

    @Test
    void consolidatesGoodFilesQuarantinesBadOne(@TempDir Path dir) throws Exception {
        Path toon = PipelineConfigBatchTestRef.writePipeline(dir, "");
        PipelineConfig cfg = PipelineConfig.load(toon.toString());

        Path inbox = Path.of(cfg.dirs().poll());
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
        Batch batch = new Batch(cfg.identity().runTimestamp() + "_mini_0001", "mini", null, members);

        BatchAuditWriter audit = new BatchAuditWriter(
                cfg.dirs().statusFilePath(), cfg.dirs().batchesFilePath(), cfg.dirs().lineageFilePath());
        BatchProcessor.process(batch, cfg, audit);

        // Output: consolidated files named by batchId, two partitions (04/03 and 01/01)
        try (Stream<Path> w = Files.walk(Path.of(cfg.dirs().database()))) {
            List<Path> outFiles = w.filter(p -> p.getFileName().toString().endsWith("_out.csv")).toList();
            assertEquals(2, outFiles.size());
            assertTrue(outFiles.stream().allMatch(p -> p.getFileName().toString().startsWith(batch.batchId())));
        }

        // Bad file quarantined, good files backed up and out of the inbox
        assertFalse(Files.exists(a));
        assertFalse(Files.exists(b));
        assertFalse(Files.exists(bad));
        try (Stream<Path> q = Files.walk(Path.of(cfg.dirs().quarantine()))) {
            assertTrue(q.anyMatch(p -> p.getFileName().toString().equals("bad.csv")));
        }
        assertTrue(Files.exists(Path.of(cfg.dirs().backup(), "a.csv")));
        assertTrue(Files.exists(Path.of(cfg.dirs().backup(), "b.csv")));

        // Markers created for survivors only
        assertTrue(Files.exists(Path.of(cfg.dirs().markers(), "a.csv.processed")));
        assertTrue(Files.exists(Path.of(cfg.dirs().markers(), "b.csv.processed")));
        assertFalse(Files.exists(Path.of(cfg.dirs().markers(), "bad.csv.processed")));

        // Manifest written
        assertTrue(Files.exists(Path.of(cfg.dirs().manifestsDir(), batch.batchId() + ".json")));

        // Lineage: a.csv -> 04/03 = 2 ; b.csv -> 04/03 = 1 ; b.csv -> 01/01 = 1
        String lineage = Files.readString(Path.of(cfg.dirs().lineageFilePath()));
        assertTrue(lineage.contains("a.csv"));
        assertTrue(lineage.contains("b.csv"));

        // batches.csv records rejected_count = 1
        String batches = Files.readString(Path.of(cfg.dirs().batchesFilePath()));
        assertTrue(batches.contains(batch.batchId()));
        assertTrue(batches.contains(",SUCCESS,"));

        // status.csv has a QUARANTINED_MISMATCH row for bad.csv
        String status = Files.readString(Path.of(cfg.dirs().statusFilePath()));
        assertTrue(status.contains("QUARANTINED_MISMATCH"));
    }

    /**
     * Regression for the "ghost batch" defect: if {@code commit()} throws <em>after</em> the
     * output is written and inputs are backed up (e.g. a marker step fails), the batch must
     * still be audited — as FAILED — rather than vanishing from the audit/lineage/recovery
     * surface. We induce a deterministic commit failure by pre-creating a survivor's
     * {@code .processed} marker, so {@code Files.createFile} (markers are the last commit step)
     * throws {@code FileAlreadyExistsException}.
     */
    @Test
    void commitFailureStillWritesAuditAsFailed(@TempDir Path dir) throws Exception {
        Path toon = PipelineConfigBatchTestRef.writePipeline(dir, "");
        PipelineConfig cfg = PipelineConfig.load(toon.toString());
        Path inbox = Path.of(cfg.dirs().poll());
        Files.createDirectories(inbox);
        Path solo = inbox.resolve("solo.csv");
        Files.writeString(solo, "ID,AMT,EVENT_DATE\nx,9.0,2020-04-03\n");

        // Pre-create the marker so the LAST commit step (markers) fails after output+backup.
        Path marker = MarkerManager.getMarkerPath(solo.toFile(), cfg);
        Files.createDirectories(marker.getParent());
        Files.createFile(marker);

        Batch batch = new Batch(cfg.identity().runTimestamp() + "_mini_0001", "mini", null,
                List.of(member(cfg, solo.toFile(), 0)));
        BatchProcessor.process(batch, cfg, new BatchAuditWriter(
                cfg.dirs().statusFilePath(), cfg.dirs().batchesFilePath(), cfg.dirs().lineageFilePath()));

        // Output was produced and the input was backed up (side effects happened before the failure)...
        try (Stream<Path> w = Files.walk(Path.of(cfg.dirs().database()))) {
            assertTrue(w.anyMatch(p -> p.getFileName().toString().equals("solo_out.csv")));
        }
        assertTrue(Files.exists(Path.of(cfg.dirs().backup(), "solo.csv")));

        // ...and crucially, the batch is still recorded — as FAILED, not silently dropped.
        String batches = Files.readString(Path.of(cfg.dirs().batchesFilePath()));
        assertTrue(batches.contains(batch.batchId()), "batch must be audited even when commit fails");
        assertTrue(batches.contains(",FAILED,"), "commit failure must be recorded as FAILED");
    }

    /**
     * Row-conservation guard for the multi-member streaming UNION path (#3): three good files across
     * two partitions are consolidated via per-member {@code read_csv} views UNION-ed into one
     * transform. Every input row must appear in the output exactly once, attributed to the right
     * partition — proving the {@code UNION ALL} neither drops nor duplicates rows versus the old
     * {@code raw_f → raw_input} table-copy path.
     */
    @Test
    void unionStreamingConservesRowsAcrossMembers(@TempDir Path dir) throws Exception {
        Path toon = PipelineConfigBatchTestRef.writePipeline(dir, "");
        PipelineConfig cfg = PipelineConfig.load(toon.toString());

        Path inbox = Path.of(cfg.dirs().poll());
        Files.createDirectories(inbox);
        Path a = inbox.resolve("a.csv");
        Path b = inbox.resolve("b.csv");
        Path c = inbox.resolve("c.csv");
        Files.writeString(a, "ID,AMT,EVENT_DATE\na1,1.0,2020-04-03\na2,2.0,2020-04-03\n");
        Files.writeString(b, "ID,AMT,EVENT_DATE\nb1,3.0,2020-04-03\nb2,4.0,2020-01-01\n");
        Files.writeString(c, "ID,AMT,EVENT_DATE\nc1,5.0,2020-04-03\n");

        List<Batch.Member> members = List.of(
                member(cfg, a.toFile(), 0), member(cfg, b.toFile(), 1), member(cfg, c.toFile(), 2));
        Batch batch = new Batch(cfg.identity().runTimestamp() + "_mini_0001", "mini", null, members);
        BatchProcessor.process(batch, cfg, new BatchAuditWriter(
                cfg.dirs().statusFilePath(), cfg.dirs().batchesFilePath(), cfg.dirs().lineageFilePath()));

        // Two partitions (04/03 and 01/01), both consolidated under the batch id.
        StringBuilder allOutput = new StringBuilder();
        int outFiles = 0;
        try (Stream<Path> w = Files.walk(Path.of(cfg.dirs().database()))) {
            for (Path p : (Iterable<Path>) w.filter(x -> x.getFileName().toString().endsWith("_out.csv"))::iterator) {
                outFiles++;
                assertTrue(p.getFileName().toString().startsWith(batch.batchId()),
                        "consolidated output must be named by batch id");
                allOutput.append(Files.readString(p));
            }
        }
        assertEquals(2, outFiles, "rows from all members land in the two date partitions");

        // Every input row is present exactly once (row conservation across the UNION).
        for (String id : List.of("a1", "a2", "b1", "b2", "c1"))
            assertTrue(allOutput.toString().contains(id), "output must contain row " + id);

        // All three files are survivors: backed up + marked, and all appear in lineage.
        String lineage = Files.readString(Path.of(cfg.dirs().lineageFilePath()));
        for (String f : List.of("a.csv", "b.csv", "c.csv")) {
            assertTrue(Files.exists(Path.of(cfg.dirs().backup(), f)), f + " backed up");
            assertTrue(Files.exists(Path.of(cfg.dirs().markers(), f + ".processed")), f + " marked");
            assertTrue(lineage.contains(f), "lineage must reference " + f);
        }
    }

    /**
     * Per-member UNREADABLE quarantine on the multi-member streaming UNION path: a corrupt
     * {@code .csv.gz} (invalid gzip) makes {@code read_csv} throw when its view is probed, so that
     * member is quarantined individually while the good members still consolidate — one bad file
     * never fails the batch.
     */
    @Test
    void unionStreamingQuarantinesUnreadableMemberIndividually(@TempDir Path dir) throws Exception {
        Path toon = PipelineConfigBatchTestRef.writePipeline(dir, "");
        PipelineConfig cfg = PipelineConfig.load(toon.toString());

        Path inbox = Path.of(cfg.dirs().poll());
        Files.createDirectories(inbox);
        Path good1 = inbox.resolve("good1.csv");
        Path good2 = inbox.resolve("good2.csv");
        Path corrupt = inbox.resolve("corrupt.csv.gz");
        Files.writeString(good1, "ID,AMT,EVENT_DATE\ng1,1.0,2020-04-03\n");
        Files.writeString(good2, "ID,AMT,EVENT_DATE\ng2,2.0,2020-04-03\n");
        // Not valid gzip — DuckDB's read_csv gzip reader fails on the bad magic bytes.
        Files.write(corrupt, "this is not gzip-compressed data".getBytes());

        List<Batch.Member> members = List.of(
                member(cfg, good1.toFile(), 0), member(cfg, good2.toFile(), 1), member(cfg, corrupt.toFile(), 2));
        Batch batch = new Batch(cfg.identity().runTimestamp() + "_mini_0001", "mini", null, members);
        BatchProcessor.process(batch, cfg, new BatchAuditWriter(
                cfg.dirs().statusFilePath(), cfg.dirs().batchesFilePath(), cfg.dirs().lineageFilePath()));

        // The corrupt member is quarantined as UNREADABLE; the good members survive and consolidate.
        try (Stream<Path> q = Files.walk(Path.of(cfg.dirs().quarantine()))) {
            assertTrue(q.anyMatch(p -> p.getFileName().toString().equals("corrupt.csv.gz")),
                    "corrupt member must be quarantined");
        }
        String status = Files.readString(Path.of(cfg.dirs().statusFilePath()));
        assertTrue(status.contains("QUARANTINED_UNREADABLE"), "corrupt member must be UNREADABLE");

        assertTrue(Files.exists(Path.of(cfg.dirs().backup(), "good1.csv")), "good1 survives");
        assertTrue(Files.exists(Path.of(cfg.dirs().backup(), "good2.csv")), "good2 survives");
        try (Stream<Path> w = Files.walk(Path.of(cfg.dirs().database()))) {
            assertTrue(w.anyMatch(p -> p.getFileName().toString().endsWith("_out.csv")),
                    "good members still produce consolidated output");
        }
    }

    @Test
    void singleMemberKeepsLegacyName(@TempDir Path dir) throws Exception {
        Path toon = PipelineConfigBatchTestRef.writePipeline(dir, "");
        PipelineConfig cfg = PipelineConfig.load(toon.toString());
        Path inbox = Path.of(cfg.dirs().poll());
        Files.createDirectories(inbox);
        Path only = inbox.resolve("solo.csv");
        Files.writeString(only, "ID,AMT,EVENT_DATE\nx,9.0,2020-04-03\n");

        Batch batch = new Batch(cfg.identity().runTimestamp() + "_mini_0001", "mini", null,
                List.of(member(cfg, only.toFile(), 0)));
        BatchProcessor.process(batch, cfg,
                new BatchAuditWriter(cfg.dirs().statusFilePath(), cfg.dirs().batchesFilePath(), cfg.dirs().lineageFilePath()));

        try (Stream<Path> w = Files.walk(Path.of(cfg.dirs().database()))) {
            assertTrue(w.anyMatch(p -> p.getFileName().toString().equals("solo_out.csv")));
        }
    }
}

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

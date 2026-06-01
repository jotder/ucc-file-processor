package com.gamma.inspector;

import com.gamma.etl.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.*;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end coverage for the large-file handling added in 3.10.0:
 * <ul>
 *   <li><b>streaming single-pass</b> (no {@code raw_f0}/{@code raw_input} copy) for a single-member
 *       native batch — already exercised by the existing suite, asserted here for output parity;</li>
 *   <li><b>auto-chunking</b> ({@code processing.chunking}) of an oversized file into bounded chunks
 *       whose aggregated output conserves every row and whose partitions match the un-chunked run,
 *       with the <em>original</em> file remaining the audit/marker/backup unit.</li>
 * </ul>
 */
class ChunkedStreamingTest {

    private Batch.Member member(PipelineConfig cfg, File f, int id) {
        SchemaSelector.Selection sel = new SchemaSelector.Selection(cfg.schemas().single(), null);
        return new Batch.Member(f, id, f.length(), sel);
    }

    private void process(PipelineConfig cfg, Batch batch) {
        BatchProcessor.process(batch, cfg, new BatchAuditWriter(
                cfg.dirs().statusFilePath(), cfg.dirs().batchesFilePath(), cfg.dirs().lineageFilePath()));
    }

    /** Sum data rows across all CSV output files (each file has a header row to discount). */
    private long outputDataRows(PipelineConfig cfg) throws Exception {
        try (Stream<Path> w = Files.walk(Path.of(cfg.dirs().database()))) {
            return w.filter(p -> p.getFileName().toString().endsWith("_out.csv"))
                    .mapToLong(p -> {
                        try { return Math.max(0, Files.readAllLines(p).size() - 1); }
                        catch (Exception e) { throw new RuntimeException(e); }
                    }).sum();
        }
    }

    private List<Path> outFiles(PipelineConfig cfg) throws Exception {
        try (Stream<Path> w = Files.walk(Path.of(cfg.dirs().database()))) {
            return w.filter(p -> p.getFileName().toString().endsWith("_out.csv")).sorted().toList();
        }
    }

    private static final String DATA = """
            ID,AMT,EVENT_DATE
            a1,1.0,2020-04-03
            a2,2.0,2020-04-03
            a3,3.0,2020-01-01
            a4,4.0,2020-01-01
            a5,5.0,2020-04-03
            a6,6.0,2020-01-01
            """;

    @Test
    void chunkedRunConservesRowsAndPartitionsAndKeepsOriginalAsMember(@TempDir Path dir) throws Exception {
        // Tiny threshold so the 6-row file splits into several chunks.
        String chunking = """
              chunking:
                max_file_bytes: 30
                target_chunk_bytes: 30
            """;
        Path toon = com.gamma.etl.PipelineConfigBatchTest.writePipeline(dir, chunking);
        PipelineConfig cfg = PipelineConfig.load(toon.toString());
        assertTrue(cfg.chunking().appliesTo(1_000), "chunking should be enabled");

        Path inbox = Path.of(cfg.dirs().poll());
        Files.createDirectories(inbox);
        Path solo = inbox.resolve("solo.csv");
        Files.writeString(solo, DATA);
        assertTrue(solo.toFile().length() > 30, "file must exceed the chunk threshold");

        Batch batch = new Batch(cfg.identity().runTimestamp() + "_mini_0001", "mini", null,
                List.of(member(cfg, solo.toFile(), 0)));
        process(cfg, batch);

        // Every one of the 6 data rows survives, summed across all chunk output files.
        assertEquals(6, outputDataRows(cfg));

        // Output spans both date partitions and is named by the chunk stem (solo_cNNNNN_out.csv).
        List<Path> outs = outFiles(cfg);
        assertTrue(outs.size() >= 2, "expected at least one file per partition");
        assertTrue(outs.stream().allMatch(p -> p.getFileName().toString().startsWith("solo_c")),
                "chunked outputs carry the _cNNNNN stem: " + outs);
        assertTrue(outs.stream().anyMatch(p -> p.toString().replace('\\','/').contains("/day=03/")));
        assertTrue(outs.stream().anyMatch(p -> p.toString().replace('\\','/').contains("/day=01/")));

        // The ORIGINAL file is the audit/marker/backup unit — not the transient chunks.
        assertFalse(Files.exists(solo), "original moved to backup");
        assertTrue(Files.exists(Path.of(cfg.dirs().backup(), "solo.csv")));
        assertTrue(Files.exists(Path.of(cfg.dirs().markers(), "solo.csv.processed")));
        String batches = Files.readString(Path.of(cfg.dirs().batchesFilePath()));
        assertTrue(batches.contains(",SUCCESS,"));
        // Lineage attributes rows to the original file, never a chunk name.
        String lineage = Files.readString(Path.of(cfg.dirs().lineageFilePath()));
        assertTrue(lineage.contains("solo.csv"));
        assertFalse(lineage.contains("_chunk_"), "lineage must not leak chunk file names");

        // No chunk scratch left behind under the temp dir.
        try (Stream<Path> t = Files.walk(Path.of(cfg.dirs().temp()))) {
            assertFalse(t.anyMatch(p -> p.getFileName().toString().contains("_chunk_")),
                    "chunk files must be deleted after processing");
        }
    }

    @Test
    void chunkedAndUnchunkedProduceSameRowTotal(@TempDir Path dir) throws Exception {
        // Un-chunked baseline (chunking disabled).
        Path baseDir = dir.resolve("base");
        Files.createDirectories(baseDir);
        Path baseToon = com.gamma.etl.PipelineConfigBatchTest.writePipeline(baseDir, "");
        PipelineConfig base = PipelineConfig.load(baseToon.toString());
        Files.createDirectories(Path.of(base.dirs().poll()));
        Path baseFile = Path.of(base.dirs().poll()).resolve("solo.csv");
        Files.writeString(baseFile, DATA);
        process(base, new Batch(base.identity().runTimestamp() + "_mini_0001", "mini", null,
                List.of(member(base, baseFile.toFile(), 0))));
        long baseRows = outputDataRows(base);

        // Chunked run over identical data.
        Path chDir = dir.resolve("chunked");
        Files.createDirectories(chDir);
        String chunking = """
              chunking:
                max_file_bytes: 25
            """;
        Path chToon = com.gamma.etl.PipelineConfigBatchTest.writePipeline(chDir, chunking);
        PipelineConfig ch = PipelineConfig.load(chToon.toString());
        Files.createDirectories(Path.of(ch.dirs().poll()));
        Path chFile = Path.of(ch.dirs().poll()).resolve("solo.csv");
        Files.writeString(chFile, DATA);
        process(ch, new Batch(ch.identity().runTimestamp() + "_mini_0001", "mini", null,
                List.of(member(ch, chFile.toFile(), 0))));
        long chRows = outputDataRows(ch);

        assertEquals(6, baseRows);
        assertEquals(baseRows, chRows, "chunked output must conserve exactly the un-chunked row total");
    }
}

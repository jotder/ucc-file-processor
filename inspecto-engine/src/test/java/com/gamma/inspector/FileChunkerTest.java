package com.gamma.inspector;

import com.gamma.etl.PipelineConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Unit coverage for {@link FileChunker}: header replication, row conservation, gzip, byte budget. */
class FileChunkerTest {

    private static final String HEADER = "ID,AMT,EVENT_DATE";

    private PipelineConfig cfg(Path dir, long target) throws Exception {
        String chunking = ("""
              chunking:
                max_file_bytes: 10
                target_chunk_bytes: %d
            """).formatted(target);
        return PipelineConfig.load(
                com.gamma.etl.PipelineConfigBatchTest.writePipeline(dir, chunking).toString());
    }

    /** Drain the chunker, returning each chunk's lines; deletes chunks as it goes (caller-owns model). */
    private List<List<String>> drain(FileChunker chunker) throws Exception {
        List<List<String>> chunks = new ArrayList<>();
        while (chunker.hasNext()) {
            File c = chunker.next();
            chunks.add(Files.readAllLines(c.toPath()));
            Files.deleteIfExists(c.toPath());
        }
        return chunks;
    }

    @Test
    void splitsIntoMultipleChunksEachCarryingHeaderAndConservesRows(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = cfg(dir, 20);   // ~20-byte data budget ⇒ roughly one row per chunk
        Path src = dir.resolve("big.csv");
        StringBuilder sb = new StringBuilder(HEADER).append('\n');
        for (int i = 1; i <= 6; i++) sb.append("r").append(i).append(",").append(i).append(".0,2020-04-03\n");
        Files.writeString(src, sb.toString());

        List<List<String>> chunks;
        try (FileChunker chunker = new FileChunker(src.toFile(), cfg, dir.resolve("chunks"))) {
            chunks = drain(chunker);
        }

        assertTrue(chunks.size() > 1, "small budget should yield multiple chunks, got " + chunks.size());
        long dataRows = 0;
        for (List<String> c : chunks) {
            assertEquals(HEADER, c.get(0), "every chunk must replicate the header row");
            assertTrue(c.size() >= 2, "every chunk must carry at least one data row");
            dataRows += c.size() - 1;
        }
        assertEquals(6, dataRows, "no data row may be lost or duplicated across chunks");
    }

    @Test
    void oneChunkWhenUnderBudget(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = cfg(dir, 1_000_000);
        Path src = dir.resolve("small.csv");
        Files.writeString(src, HEADER + "\nr1,1.0,2020-04-03\nr2,2.0,2020-04-03\n");
        try (FileChunker chunker = new FileChunker(src.toFile(), cfg, dir.resolve("chunks"))) {
            List<List<String>> chunks = drain(chunker);
            assertEquals(1, chunks.size());
            assertEquals(3, chunks.get(0).size());   // header + 2 data rows
        }
    }

    @Test
    void decompressesGzInputAndChunksDecompressedText(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = cfg(dir, 20);
        Path src = dir.resolve("big.csv.gz");
        StringBuilder sb = new StringBuilder(HEADER).append('\n');
        for (int i = 1; i <= 5; i++) sb.append("r").append(i).append(",").append(i).append(".0,2020-01-01\n");
        try (OutputStream os = new GZIPOutputStream(Files.newOutputStream(src))) {
            os.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }

        try (FileChunker chunker = new FileChunker(src.toFile(), cfg, dir.resolve("chunks"))) {
            List<List<String>> chunks = drain(chunker);
            long dataRows = chunks.stream().mapToLong(c -> c.size() - 1).sum();
            assertEquals(5, dataRows);
            assertTrue(chunks.stream().allMatch(c -> c.get(0).equals(HEADER)));
        }
    }
}

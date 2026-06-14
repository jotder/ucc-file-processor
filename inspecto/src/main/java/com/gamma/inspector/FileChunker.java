package com.gamma.inspector;

import com.gamma.etl.CsvIngester;
import com.gamma.etl.PipelineConfig;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * Streams a single oversized delimited-text file into bounded, self-contained chunk files so the
 * engine can process a multi-hundred-GB / TB input without ever materialising it as one unit.
 *
 * <p><b>Why:</b> a single huge file would otherwise become a batch-of-one whose DuckDB scratch
 * (transformed table + spill) is proportional to the <em>whole</em> file. Splitting it into
 * ~{@code targetChunkBytes} pieces caps per-chunk scratch and lets chunks be processed (and freed)
 * one after another. The split itself is a single sequential read — far cheaper than the repeated
 * full-size materialisation it avoids.
 *
 * <p><b>Self-contained chunks:</b> each emitted chunk reproduces the source's leading context —
 * the {@code skip_header_lines} preamble plus the header row (when {@code has_header}) — followed by
 * a slice of data lines. So the <em>same</em> pipeline config (delimiter, {@code skip}, selectors)
 * reads a chunk exactly as it reads the original. Chunking is only ever used on the native
 * {@code read_csv} path, where {@code skip_junk_lines}/{@code skip_tail_lines}/{@code skip_tail_columns}
 * are all zero, so no footer/ragged-tail handling is needed here.
 *
 * <p><b>Bounded footprint:</b> this is an {@link java.util.Iterator}-style producer — exactly one
 * chunk file exists on disk at a time; the caller processes it and deletes it before asking for the
 * next. {@code .gz} inputs are decompressed on the fly; chunks are written as plain {@code .csv}.
 *
 * <p>Not thread-safe; one instance per source file. Always use in try-with-resources.
 */
final class FileChunker implements Closeable {

    private final BufferedReader reader;
    private final Path outDir;
    private final String baseName;
    private final long targetBytes;
    private final List<String> prefixLines = new ArrayList<>();
    private String pendingDataLine;   // one-line lookahead; null once data is exhausted
    private int seq = 0;

    /**
     * @param source      the oversized input file (may be {@code .gz})
     * @param cfg         pipeline config (delimiter is irrelevant to splitting; only the
     *                    {@code skip_header_lines}/{@code has_header} prefix is reproduced)
     * @param outDir      directory to write chunk files into (the data-volume scratch dir)
     * @throws IOException if the source cannot be opened/read
     */
    FileChunker(File source, PipelineConfig cfg, Path outDir) throws IOException {
        this.outDir   = outDir;
        this.baseName = CsvIngester.stripExtensions(source.getName());
        long target   = cfg.chunking().effectiveChunkBytes();
        this.targetBytes = target > 0 ? target : Long.MAX_VALUE;
        Files.createDirectories(outDir);

        InputStream in = com.gamma.etl.Compression.decompress(
                source, Files.newInputStream(source.toPath()), 1 << 16);
        this.reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 20);

        // Capture the leading context reproduced on every chunk: skip_header_lines preamble
        // plus the header row (when has_header). Mirrors read_csv's skip = skipHeaderLines + header.
        int prefixCount = cfg.csv().skipHeaderLines() + (cfg.csv().hasHeader() ? 1 : 0);
        for (int i = 0; i < prefixCount; i++) {
            String line = reader.readLine();
            if (line == null) break;
            prefixLines.add(line);
        }
        pendingDataLine = reader.readLine();   // first data line (null ⇒ no data)
    }

    /** True if at least one more data line remains, i.e. another chunk can be produced. */
    boolean hasNext() {
        return pendingDataLine != null;
    }

    /**
     * Write the next chunk to {@code outDir} and return it. Each chunk holds the reproduced prefix
     * plus as many data lines as fit under {@code targetChunkBytes} (always at least one, so a single
     * over-long line still makes progress). Caller owns the returned file and should delete it.
     */
    File next() throws IOException {
        if (pendingDataLine == null)
            throw new IllegalStateException("no more chunks");

        Path chunk = outDir.resolve(baseName + "_chunk_" + String.format("%05d", seq++) + ".csv");
        long dataBytes = 0;
        try (BufferedWriter w = Files.newBufferedWriter(chunk, StandardCharsets.UTF_8)) {
            for (String pre : prefixLines) { w.write(pre); w.write('\n'); }
            // Always emit the pending line first, then keep going until the byte budget trips.
            do {
                w.write(pendingDataLine);
                w.write('\n');
                dataBytes += (long) pendingDataLine.length() + 1;
                pendingDataLine = reader.readLine();
            } while (pendingDataLine != null && dataBytes < targetBytes);
        }
        return chunk.toFile();
    }

    @Override
    public void close() throws IOException {
        reader.close();
    }
}

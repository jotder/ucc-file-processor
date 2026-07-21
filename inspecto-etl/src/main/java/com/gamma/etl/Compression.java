package com.gamma.etl;

import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

/**
 * Transparent input decompression for the streaming (Java/OpenCSV) ingest path (Data Acquisition roadmap
 * Phase E, requirement §9). One place decides, by file extension, how to turn a raw byte stream into the
 * plain CSV bytes the readers expect — so {@link CsvIngester}, {@link SchemaSelector}, {@link BoundaryScanner}
 * and {@link com.gamma.inspector.FileChunker} all agree.
 *
 * <ul>
 *   <li>{@code .gz}  — {@link GZIPInputStream} (JDK).</li>
 *   <li>{@code .bz2} — {@link BZip2CompressorInputStream} (Apache Commons Compress, already a core dependency
 *       for {@code .tar.gz}; <b>zero new dependency</b>).</li>
 *   <li>{@code .zip} — the first non-directory entry via {@link ZipArchiveInputStream}; a single-CSV {@code .zip}
 *       delivery reads as one stream. (Multi-member archives remain the job of the tar/zip inbox preparer.)</li>
 *   <li>anything else — the raw stream, unchanged.</li>
 * </ul>
 *
 * <p>Note: {@code .gz} is also understood natively by DuckDB's {@code read_csv}, so the DuckDB ingest path
 * needs nothing here; {@code .bz2}/{@code .zip} (which DuckDB does not auto-decompress) are served by this
 * streaming Java path.
 */
public final class Compression {

    private Compression() {}

    /** Whether {@code fileName} carries a decompressible extension this helper understands. */
    public static boolean isCompressed(String fileName) {
        String n = fileName.toLowerCase();
        return n.endsWith(".gz") || n.endsWith(".bz2") || n.endsWith(".zip");
    }

    /**
     * Wrap {@code raw} (a freshly opened stream over {@code file}) with the decompressor its extension implies.
     * A plain file's stream is returned unchanged. For a compressed file the compressed bytes are first buffered
     * by {@code bufferBytes} (the read-ahead the caller already tuned for {@code .gz}); pass {@code 0} for the
     * decompressor's default buffering. Closing the returned stream closes {@code raw}.
     */
    public static InputStream decompress(File file, InputStream raw, int bufferBytes) throws IOException {
        String n = file.getName().toLowerCase();
        if (n.endsWith(".gz"))  return new GZIPInputStream(buffer(raw, bufferBytes));
        if (n.endsWith(".bz2")) return new BZip2CompressorInputStream(buffer(raw, bufferBytes));
        if (n.endsWith(".zip")) return firstEntry(file, buffer(raw, bufferBytes));
        return raw;   // plain — unchanged (the caller's BufferedReader provides buffering)
    }

    private static InputStream buffer(InputStream raw, int bytes) {
        return bytes > 0 ? new BufferedInputStream(raw, bytes) : new BufferedInputStream(raw);
    }

    /** Position a zip stream at its first file entry; the reader then consumes that entry's bytes. */
    private static InputStream firstEntry(File file, InputStream buffered) throws IOException {
        ZipArchiveInputStream zis = new ZipArchiveInputStream(buffered);
        ArchiveEntry e;
        while ((e = zis.getNextEntry()) != null) if (!e.isDirectory()) return zis;
        throw new IOException("Empty .zip (no file entry): " + file.getName());
    }
}

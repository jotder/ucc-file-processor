package com.gamma.etl;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Phase-E: transparent {@code .gz}/{@code .bz2}/{@code .zip} decompression on the streaming read path. */
class CompressionTest {

    private static final String BODY = "ID,AMT,EVENT_DATE\nr1,1.0,2020-04-03\nr2,2.0,2020-04-04\n";

    private static String read(Path file) throws Exception {
        try (InputStream raw = Files.newInputStream(file);
             InputStream in = Compression.decompress(file.toFile(), raw, 1 << 16)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void readsGzip(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("feed.csv.gz");
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(f))) {
            out.write(BODY.getBytes(StandardCharsets.UTF_8));
        }
        assertEquals(BODY, read(f));
    }

    @Test
    void readsBzip2(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("feed.csv.bz2");
        try (OutputStream out = new BZip2CompressorOutputStream(Files.newOutputStream(f))) {
            out.write(BODY.getBytes(StandardCharsets.UTF_8));
        }
        assertEquals(BODY, read(f));
    }

    @Test
    void readsFirstEntryOfZip(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("feed.csv.zip");
        try (ZipArchiveOutputStream zos = new ZipArchiveOutputStream(Files.newOutputStream(f))) {
            zos.putArchiveEntry(new ZipArchiveEntry("feed.csv"));
            zos.write(BODY.getBytes(StandardCharsets.UTF_8));
            zos.closeArchiveEntry();
        }
        assertEquals(BODY, read(f));
    }

    @Test
    void plainFileIsUnchanged(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("feed.csv");
        Files.writeString(f, BODY);
        assertEquals(BODY, read(f));
    }

    @Test
    void stripExtensionsHandlesAllCompressionSuffixes() {
        assertEquals("feed", CsvIngester.stripExtensions("feed.csv"));
        assertEquals("feed", CsvIngester.stripExtensions("feed.csv.gz"));
        assertEquals("feed", CsvIngester.stripExtensions("feed.csv.bz2"));
        assertEquals("feed", CsvIngester.stripExtensions("feed.csv.zip"));
        assertTrue(Compression.isCompressed("x.BZ2"), "case-insensitive");
        assertFalse(Compression.isCompressed("x.csv"));
    }
}

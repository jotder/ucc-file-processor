package com.gamma.service;

import com.gamma.util.ToonHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Packages a data-source bundle / whole space into a zip + bundle.toon manifest. */
class BundleExporterTest {

    @Test
    void exportDataSourceZipsBundleFilesRelativeToConfigPlusManifest(@TempDir Path tmp) throws Exception {
        Path config = tmp.resolve("config");
        Path pipeline = write(config.resolve("voucher/voucher_pipeline.toon"), "name: VOUCHER_ETL\n");
        Path conn     = write(config.resolve("connections/voucher_conn_connection.toon"), "connection:\n  id: VOUCHER_CONN\n");
        Path schema   = write(config.resolve("voucher/voucher_schema.toon"), "raw:\n  name: VOUCHER\n");
        Path job      = write(config.resolve("voucher/voucher_job.toon"), "job:\n  name: vh\n");

        DataSourceBundle bundle = new DataSourceBundle("voucher_etl", pipeline, conn, List.of(schema), List.of(job));
        byte[] zip = BundleExporter.exportDataSource(bundle, config, "voucher-space");

        Map<String, byte[]> got = unzip(zip);
        assertTrue(got.containsKey("bundle.toon"), "manifest at zip root");
        assertTrue(got.containsKey("voucher/voucher_pipeline.toon"), "config-relative entry, subdir preserved");
        assertTrue(got.containsKey("connections/voucher_conn_connection.toon"));
        assertTrue(got.containsKey("voucher/voucher_schema.toon"));
        assertTrue(got.containsKey("voucher/voucher_job.toon"));
        assertEquals("name: VOUCHER_ETL\n", new String(got.get("voucher/voucher_pipeline.toon"), StandardCharsets.UTF_8),
                "file bytes preserved verbatim");

        Map<String, Object> manifest = parseManifest(tmp, got.get("bundle.toon"));
        assertEquals("datasource", manifest.get("kind"));
        assertEquals("voucher-space", manifest.get("source_space"));
        assertEquals("voucher_etl", manifest.get("data_source"));
        assertEquals(BundleExporter.SCHEMA_VERSION, ((Number) manifest.get("schema_version")).intValue());
        assertInstanceOf(List.class, manifest.get("artifacts"), "an artifact list is recorded");
    }

    @Test
    void exportSpaceIncludesWholeConfigTreeAndSpaceToon(@TempDir Path tmp) throws Exception {
        Path base   = tmp.resolve("space-a");
        Path config = base.resolve("config");
        write(config.resolve("a_pipeline.toon"), "name: A\n");
        write(config.resolve("sub/b_schema.toon"), "raw:\n");
        Path spaceToon = write(base.resolve("space.toon"), "display_name: \"A\"\n");

        byte[] zip = BundleExporter.exportSpace(config, spaceToon, "space-a");

        Map<String, byte[]> got = unzip(zip);
        assertTrue(got.containsKey("bundle.toon"));
        assertTrue(got.containsKey("space.toon"), "the space manifest rides at the zip root");
        assertTrue(got.containsKey("a_pipeline.toon"));
        assertTrue(got.containsKey("sub/b_schema.toon"), "nested config files preserved");

        Map<String, Object> manifest = parseManifest(tmp, got.get("bundle.toon"));
        assertEquals("space", manifest.get("kind"));
        assertEquals("space-a", manifest.get("source_space"));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────

    private static Path write(Path p, String content) throws Exception {
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
        return p;
    }

    private static Map<String, byte[]> unzip(byte[] zip) throws Exception {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (var e = zis.getNextEntry(); e != null; e = zis.getNextEntry()) {
                out.put(e.getName(), zis.readAllBytes());
            }
        }
        return out;
    }

    private static Map<String, Object> parseManifest(Path tmp, byte[] manifestBytes) throws Exception {
        Path mf = tmp.resolve("manifest-" + System.nanoTime() + ".toon");
        Files.write(mf, manifestBytes);
        return ToonHelper.load(mf.toString());   // proves the emitted bundle.toon is valid, re-parsable TOON
    }
}

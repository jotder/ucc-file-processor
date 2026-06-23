package com.gamma.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Parses a bundle zip and unpacks it into a config dir (jailed against zip-slip). */
class BundleImporterTest {

    @Test
    void parsesManifestAndSplitsOutTheSpaceToon() throws Exception {
        byte[] zip = zip(Map.of(
                "bundle.toon", "kind: space\nschema_version: 1\nsource_space: alpha\n",
                "space.toon", "display_name: \"Alpha\"\n",
                "a_pipeline.toon", "name: A_ETL\n"));

        BundleImporter.Bundle b = BundleImporter.parse(zip);
        assertEquals("space", b.kind());
        assertEquals("alpha", b.manifest().get("source_space"));
        assertNotNull(b.spaceToon(), "space.toon is split out of the config entries");
        assertTrue(b.configEntries().containsKey("a_pipeline.toon"));
        assertFalse(b.configEntries().containsKey("bundle.toon"), "manifest is not a config entry");
        assertFalse(b.configEntries().containsKey("space.toon"), "space.toon is not a config entry");
    }

    @Test
    void pipelineIdsAreTheLowercasedInFileNames() throws Exception {
        byte[] zip = zip(Map.of(
                "bundle.toon", "kind: datasource\n",
                "voucher/voucher_pipeline.toon", "name: VOUCHER_ETL\n",
                "voucher/voucher_schema.toon", "raw:\n  name: V\n"));
        assertEquals(List.of("voucher_etl"), BundleImporter.pipelineIds(BundleImporter.parse(zip)));
    }

    @Test
    void writeConfigUnpacksEntriesUnderConfigDir(@TempDir Path tmp) throws Exception {
        Path config = tmp.resolve("config");
        byte[] zip = zip(Map.of(
                "bundle.toon", "kind: datasource\n",
                "voucher/voucher_pipeline.toon", "name: VOUCHER_ETL\n",
                "voucher/voucher_schema.toon", "raw:\n"));

        List<String> written = BundleImporter.writeConfig(BundleImporter.parse(zip), config);
        assertTrue(written.contains("voucher/voucher_pipeline.toon"));
        assertEquals("name: VOUCHER_ETL\n",
                Files.readString(config.resolve("voucher/voucher_pipeline.toon")), "bytes unpacked verbatim");
        assertTrue(Files.exists(config.resolve("voucher/voucher_schema.toon")));
    }

    @Test
    void writeConfigRejectsZipSlipEntries(@TempDir Path tmp) throws Exception {
        Path config = tmp.resolve("config");
        Files.createDirectories(config);
        byte[] zip = zip(Map.of(
                "bundle.toon", "kind: datasource\n",
                "../evil.toon", "name: EVIL\n"));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> BundleImporter.writeConfig(BundleImporter.parse(zip), config));
        assertTrue(ex.getMessage().contains("escapes"), ex.getMessage());
        assertFalse(Files.exists(tmp.resolve("evil.toon")), "nothing written outside the config dir");
    }

    @Test
    void parseRejectsAZipWithoutAManifest() throws Exception {
        byte[] zip = zip(Map.of("a_pipeline.toon", "name: A\n"));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> BundleImporter.parse(zip));
        assertTrue(ex.getMessage().contains("bundle.toon"), ex.getMessage());
    }

    private static byte[] zip(Map<String, String> entries) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (Map.Entry<String, String> e : new LinkedHashMap<>(entries).entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }
}

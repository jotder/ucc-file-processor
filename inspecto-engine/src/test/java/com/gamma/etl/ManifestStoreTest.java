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
        assertEquals("a.csv", back.members.get(0).filename());
        assertEquals(1, back.outputs.size());

        ManifestStore.supersede(manifestsDir, "B1");
        assertFalse(Files.exists(Path.of(manifestsDir, "B1.json")));
        assertTrue(Files.exists(Path.of(manifestsDir, "B1.json.superseded")));
    }
}

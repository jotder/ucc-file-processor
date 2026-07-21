package com.gamma.query;

import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link DatasetRelation} (W4): view-backed and physicalRef-backed relation resolution + rejections. */
class DatasetRelationTest {

    @Test
    void physicalRefBecomesReadParquetGlob() {
        String sql = DatasetRelation.relationSql(Map.of("physicalRef", "cdr"), Path.of("/data"), null);
        assertTrue(sql.startsWith("SELECT * FROM read_parquet('"), sql);
        assertTrue(sql.replace('\\', '/').contains("cdr/**/*.parquet"), sql);
    }

    @Test
    void viewRefReturnsDerivedSql(@TempDir Path root) throws Exception {
        ViewStore views = new ViewStore(root);
        views.write(new ViewDefinition("sales_view", "flow-x", List.of(),
                "SELECT * FROM (VALUES (1)) AS t(n)", "2026-07-06T00:00:00Z"));
        String sql = DatasetRelation.relationSql(Map.of("view", "sales_view"), null, views);
        assertEquals("SELECT * FROM (VALUES (1)) AS t(n)", sql);
    }

    @Test
    void unknownViewRejected(@TempDir Path root) {
        assertThrows(IllegalArgumentException.class,
                () -> DatasetRelation.relationSql(Map.of("view", "nope"), null, new ViewStore(root)));
    }

    @Test
    void physicalRefWithDatabaseSubtreeReadsMappedOutputOnly(@TempDir Path root) throws Exception {
        // store-layout contract: a pipeline-shaped store (database/ subtree present) reads its mapped
        // output only, so backup/quarantine/nested trees never leak into the dataset
        Files.createDirectories(root.resolve("orders").resolve("database"));
        Files.createDirectories(root.resolve("orders").resolve("backup"));
        String sql = DatasetRelation.relationSql(Map.of("physicalRef", "orders"), root, null);
        assertTrue(sql.replace('\\', '/').contains("orders/database/**/*.parquet"),
                "reads the mapped output, not the whole store tree: " + sql);
        // an explicit deeper ref is honoured as written
        String explicit = DatasetRelation.relationSql(Map.of("physicalRef", "orders/backup"), root, null);
        assertTrue(explicit.replace('\\', '/').contains("orders/backup/**/*.parquet"), explicit);
    }

    @Test
    void unsafePhysicalRefRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> DatasetRelation.relationSql(Map.of("physicalRef", "../etc"), Path.of("/data"), null));
    }

    @Test
    void missingBothRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> DatasetRelation.relationSql(Map.of("name", "x"), Path.of("/data"), null));
    }

    // ── calculated columns (DAT-5) ────────────────────────────────────────────────

    @Test
    void calculatedColumnsWrapTheBaseRelation() {
        String sql = DatasetRelation.relationSql(Map.of(
                "physicalRef", "cdr",
                "calculated", List.of(Map.of("name", "amount_taxed", "expr", "round(amount * 1.2, 2)"))),
                Path.of("/data"), null);
        assertTrue(sql.startsWith("SELECT *, (round(amount * 1.2, 2)) AS \"amount_taxed\" FROM ("), sql);
        assertTrue(sql.endsWith(") AS __base"), sql);
    }

    @Test
    void calculatedColumnFailsClosed() {
        // an unsafe expression makes the whole dataset unusable (422 at the route), never silently degraded
        assertThrows(IllegalArgumentException.class, () -> DatasetRelation.relationSql(Map.of(
                "physicalRef", "cdr",
                "calculated", List.of(Map.of("name", "x", "expr", "(SELECT 1)"))),
                Path.of("/data"), null), "subquery smuggle rejected");
        assertThrows(IllegalArgumentException.class, () -> DatasetRelation.relationSql(Map.of(
                "physicalRef", "cdr",
                "calculated", List.of(Map.of("name", "bad name", "expr", "1"))),
                Path.of("/data"), null), "non-identifier column name rejected");
        assertThrows(IllegalArgumentException.class, () -> DatasetRelation.relationSql(Map.of(
                "physicalRef", "cdr",
                "calculated", List.of(Map.of("name", "x"))),
                Path.of("/data"), null), "missing expr rejected");
    }
}

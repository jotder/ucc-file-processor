package com.gamma.etl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BatchPlannerTest {

    /** Resolver that buckets by a table name encoded in the filename: "t1_*.csv" -> table "t1". */
    static BatchPlanner.SchemaResolver byPrefix() {
        return f -> {
            String table = f.getName().split("_", 2)[0];
            return new SchemaSelector.Selection(Map.of("raw", Map.of("name", table)), table);
        };
    }

    static File file(Path dir, String name, int bytes) throws Exception {
        Path p = dir.resolve(name);
        Files.write(p, new byte[bytes]);
        return p.toFile();
    }

    @Test
    void packsByFileCount(@TempDir Path dir) throws Exception {
        List<File> files = new ArrayList<>();
        for (int i = 0; i < 5; i++) files.add(file(dir, "t1_" + i + ".csv", 10));
        List<Batch> batches = BatchPlanner.plan(files, byPrefix(), 2, Long.MAX_VALUE, "TS");
        assertEquals(3, batches.size());            // 2 + 2 + 1
        assertEquals(2, batches.get(0).members().size());
        assertEquals(1, batches.get(2).members().size());
    }

    @Test
    void packsByByteCap(@TempDir Path dir) throws Exception {
        List<File> files = List.of(
                file(dir, "t1_a.csv", 100),
                file(dir, "t1_b.csv", 100),
                file(dir, "t1_c.csv", 100));
        List<Batch> batches = BatchPlanner.plan(files, byPrefix(), 100, 250, "TS");
        assertEquals(2, batches.size());            // 100+100 <=250, then 100
        assertEquals(2, batches.get(0).members().size());
        assertEquals(1, batches.get(1).members().size());
    }

    @Test
    void oversizeFileGetsOwnBatch(@TempDir Path dir) throws Exception {
        List<File> files = List.of(
                file(dir, "t1_big.csv", 500),
                file(dir, "t1_small.csv", 10));
        List<Batch> batches = BatchPlanner.plan(files, byPrefix(), 100, 100, "TS");
        assertEquals(2, batches.size());
        assertEquals(1, batches.get(0).members().size());
        assertEquals("t1_big.csv", batches.get(0).members().get(0).file().getName());
    }

    @Test
    void groupsBySchemaAndAssignsSrcIds(@TempDir Path dir) throws Exception {
        List<File> files = List.of(
                file(dir, "t1_a.csv", 10),
                file(dir, "t2_a.csv", 10),
                file(dir, "t1_b.csv", 10));
        List<Batch> batches = BatchPlanner.plan(files, byPrefix(), 500, Long.MAX_VALUE, "TS");
        assertEquals(2, batches.size());            // one per table
        Batch t1 = batches.stream().filter(b -> "t1".equals(b.table())).findFirst().orElseThrow();
        assertEquals(2, t1.members().size());
        assertEquals(0, t1.members().get(0).srcId());
        assertEquals(1, t1.members().get(1).srcId());
        // batchId carries the run timestamp and the table slug
        assertTrue(t1.batchId().startsWith("TS_t1_"));
    }
}

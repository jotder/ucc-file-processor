package com.gamma.etl;

import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;

import static org.junit.jupiter.api.Assertions.*;

class CsvIngesterTargetTableTest {

    @Test
    void ingestsIntoNamedTable(@TempDir Path dir) throws Exception {
        Path schema = dir.resolve("mini_schema.toon");
        Files.writeString(schema, PipelineConfigBatchTest.miniSchema());
        Path toon = PipelineConfigBatchTest.writePipeline(dir, "");
        PipelineConfig cfg = PipelineConfig.load(toon.toString());

        Path csv = dir.resolve("data.csv");
        Files.writeString(csv, "ID,AMT,EVENT_DATE\na,1.5,2020-04-03\nb,2.5,2020-04-03\n");

        File db = DuckDbUtil.tempDbFile("test_");
        try (Connection conn = DuckDbUtil.openConnection(db)) {
            IngestResult r = CsvIngester.ingest(csv.toFile(), conn, cfg.singleSchema, cfg, "raw_f0");
            assertEquals(2, r.parsedRows());
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM raw_f0")) {
                rs.next();
                assertEquals(2, rs.getInt(1));
            }
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }
}

package com.gamma.service;

import com.gamma.event.EventLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.MDC;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Resolves a pipeline to its cohesive data-source bundle (pipeline + connection + schema(s) + jobs) by
 * walking the references inside a real, booted space's {@code config/} tree.
 */
class DataSourceBundleResolverTest {

    @Test
    void resolvesPipelineConnectionSchemaAndJobsForOneDataSource(@TempDir Path tmp) throws Exception {
        Path base   = tmp.resolve("ds-space");
        Path config = base.resolve("config");
        Files.createDirectories(config);

        // A remote data source: pipeline → connection VOUCHER_CONN → its own schema, plus a job that targets it.
        Path voucherSchema = config.resolve("voucher_schema.toon");
        Files.writeString(voucherSchema, schema("VOUCHER", "voucher"));
        Files.writeString(config.resolve("voucher_pipeline.toon"),
                pipeline("VOUCHER_ETL", voucherSchema, "VOUCHER_CONN", tmp.resolve("voucher")));
        Files.writeString(config.resolve("voucher_conn_connection.toon"), connection("VOUCHER_CONN"));
        Files.writeString(config.resolve("voucher_job.toon"), job("voucher_heartbeat", "voucher_etl")); // lowercased

        // A second, unrelated local data source — proves the bundle does not bleed across pipelines.
        Path otherSchema = config.resolve("other_schema.toon");
        Files.writeString(otherSchema, schema("OTHER", "other"));
        Files.writeString(config.resolve("other_pipeline.toon"),
                pipeline("OTHER_ETL", otherSchema, null, tmp.resolve("other")));
        Files.writeString(config.resolve("other_job.toon"), job("other_heartbeat", "other_etl"));

        try (SpaceContext ctx = SpaceBootstrap.load(SpaceRoot.under(base))) {
            DataSourceBundleResolver resolver = new DataSourceBundleResolver(ctx.service(), ctx.root().config());

            // The engine lowercases pipeline names (the BatchEvent.pipeline() convention), so the
            // data-source id is the lowercased form of the in-file name.
            assertEquals(java.util.List.of("other_etl", "voucher_etl"), resolver.dataSourceIds(),
                    "both pipelines are listed as data sources");

            DataSourceBundle voucher = resolver.resolve("voucher_etl");
            assertEquals("voucher_etl", voucher.id());
            assertEquals(config.resolve("voucher_pipeline.toon"), voucher.pipeline());
            assertEquals(config.resolve("voucher_conn_connection.toon"), voucher.connection(),
                    "connection matched by in-file id, not filename");
            assertTrue(voucher.schemas().contains(voucherSchema), "the referenced schema is in the bundle");
            assertEquals(java.util.List.of(config.resolve("voucher_job.toon")), voucher.jobs(),
                    "only the job whose on_pipeline targets this pipeline (case-insensitively)");
            assertTrue(voucher.files().contains(config.resolve("voucher_pipeline.toon")));
            assertTrue(voucher.files().contains(config.resolve("voucher_conn_connection.toon")));
            assertFalse(voucher.files().contains(config.resolve("other_job.toon")), "no cross-pipeline bleed");

            DataSourceBundle other = resolver.resolve("other_etl");
            assertNull(other.connection(), "a local source has no connection file");
            assertEquals(java.util.List.of(config.resolve("other_job.toon")), other.jobs());

            assertThrows(NoSuchElementException.class, () -> resolver.resolve("NOPE"),
                    "unknown data source");
        } finally {
            MDC.put(EventLog.SPACE_MDC_KEY, "ds-space");
            try { com.gamma.acquire.AcquisitionLedgers.use(null); }
            finally { MDC.remove(EventLog.SPACE_MDC_KEY); }
        }
    }

    // ── inline TOON builders (shapes mirror the shipped sample configs) ──────────────────────────────

    private static String pipeline(String name, Path schemaFile, String connectionId, Path dataRoot) {
        String fwd = dataRoot.toString().replace("\\", "/");
        String source = connectionId == null ? "" : """
                source:
                  connection: %s
                """.formatted(connectionId);
        return """
                name: %s
                active: true
                version: 1
                dirs:
                  poll: %s/inbox
                  database: %s/db
                  backup: %s/backup
                  temp: %s/temp
                  errors: %s/errors
                  quarantine: %s/quarantine
                  markers: %s/markers
                  status_dir: %s/status
                  log_dir: %s/logs
                output:
                  format: PARQUET
                  compression: snappy
                processing:
                  threads: 1
                  file_pattern: "glob:**/*.csv"
                  duplicate_check:
                    enabled: true
                    marker_extension: .processed
                  schema_file: "%s"
                  csv_settings:
                    delimiter: ","
                    date_formats[1]: "%%Y-%%m-%%d"
                    timestamp_formats[1]: "%%Y-%%m-%%d %%H:%%M:%%S"
                %s""".formatted(name, fwd, fwd, fwd, fwd, fwd, fwd, fwd, fwd, fwd,
                schemaFile.toString().replace("\\", "/"), source);
    }

    private static String schema(String rawName, String canonical) {
        return """
                partitionKey: ID
                raw:
                  name: %s
                  format: CSV
                  fields[2]{name,selector,type,description,unit,classification}:
                    ID,"0",VARCHAR,"id","","INTERNAL"
                    AMOUNT,"1",DOUBLE,"amount","","INTERNAL"
                mapping:
                  canonicalName: %s
                  rawName: %s
                  rules[2]{targetColumn,sourceExpression,transformType}:
                    ID,ID,DIRECT
                    AMOUNT,AMOUNT,DIRECT
                """.formatted(rawName, canonical, rawName);
    }

    private static String connection(String id) {
        return """
                connection:
                  id: %s
                  connector: sftp
                  host: sftp.example.com
                  port: 22
                  base_path: /voucher
                  username: voucheruser
                  password: "${ENV:VOUCHER_PASSWORD}"
                """.formatted(id);
    }

    private static String job(String name, String onPipeline) {
        return """
                job:
                  name: %s
                  type: maintenance
                  task: heartbeat
                  on_pipeline: %s
                """.formatted(name, onPipeline);
    }
}

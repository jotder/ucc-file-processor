package com.gamma.etl;

import com.gamma.util.DuckDbUtil;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Inserts newly written Parquet files into a DuckLake catalog.
 *
 * <p>The DuckLake step is optional and non-fatal: any connectivity or SQL failure
 * is caught and printed to stderr without aborting ETL success for the file.
 *
 * <p>Activation requires {@code output.ducklake.enabled: true} in the pipeline
 * config.  The method is a no-op otherwise.
 *
 * <p>Extracted from {@link com.gamma.inspector.SourceProcessor#registerInDuckLake}.
 */
public final class DuckLakeRegistrar {

    private DuckLakeRegistrar() {}

    /**
     * Register {@code outputPaths} in the DuckLake catalog referenced by the
     * pipeline config's {@code output.ducklake} section.
     *
     * @param outputPaths absolute paths of Parquet files to register
     * @param tableName   target DuckLake table (overrides the toon's {@code table} key)
     * @param cfg         pipeline configuration
     */
    @SuppressWarnings("unchecked")
    public static void register(List<String> outputPaths, String tableName, PipelineConfig cfg) {
        if (outputPaths.isEmpty()) return;
        Map<String, Object> dl = cfg.duckLakeCfg;
        if (dl == null) return;
        if (!Boolean.parseBoolean(String.valueOf(dl.getOrDefault("enabled", false)))) return;

        String catalogUrl = (String) dl.get("catalog_url");
        String dataPath   = (String) dl.get("data_path");
        String schema     = (String) dl.getOrDefault("schema", "main");
        String table      = (tableName != null) ? tableName : (String) dl.get("table");

        System.out.printf("DuckLake: registering %d file(s) into %s.%s ...%n",
                outputPaths.size(), schema, table);
        try {
            java.io.File lakeDb = DuckDbUtil.tempDbFile("duckdb_lake_");
            try (Connection conn = DriverManager.getConnection(DuckDbUtil.jdbcUrl(lakeDb));
                 Statement  stmt = conn.createStatement()) {

                stmt.execute("INSTALL ducklake FROM core");
                stmt.execute("LOAD ducklake");
                stmt.execute(String.format(
                        "ATTACH 'ducklake:%s' AS lake (DATA_PATH '%s')",
                        catalogUrl, dataPath.replace("\\", "/")));
                stmt.execute("CREATE SCHEMA IF NOT EXISTS lake.\"" + schema + '"');

                String firstPath = outputPaths.get(0).replace("\\", "/");
                stmt.execute(String.format(
                        "CREATE TABLE IF NOT EXISTS lake.\"%s\".\"%s\" AS" +
                                " SELECT * FROM read_parquet('%s') LIMIT 0",
                        schema, table, firstPath));

                String pathList = outputPaths.stream()
                        .map(p -> '\'' + p.replace("\\", "/") + '\'')
                        .collect(Collectors.joining(", ", "[", "]"));
                stmt.execute(String.format(
                        "INSERT INTO lake.\"%s\".\"%s\" SELECT * FROM read_parquet(%s)",
                        schema, table, pathList));

                System.out.printf("DuckLake: OK — %d file(s) registered in %s.%s%n",
                        outputPaths.size(), schema, table);
            } finally {
                DuckDbUtil.deleteTempDb(lakeDb);
            }
        } catch (Exception e) {
            System.err.println("DuckLake registration failed (non-fatal): " + e.getMessage());
        }
    }
}

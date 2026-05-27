package com.gamma.etl;

import com.gamma.util.DuckDbUtil;
import com.gamma.util.SqlBuilder;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.sql.Connection;
import java.sql.Statement;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Applies mapping rules and type casts to the {@code raw_input} DuckDB table,
 * writes partitioned output (CSV or Parquet) to the database directory, and
 * returns the final file paths and sizes.
 *
 * <p>A two-stage write strategy avoids DuckDB's AVX2 page-fault crash on Windows:
 * <ol>
 *   <li>Materialise data into a {@code transformed} table inside the worker DuckDB.</li>
 *   <li>{@code COPY TO} a UUID-tagged staging directory.</li>
 *   <li>Rename each staged file to {@code <source_basename>_out.<ext>} in the final
 *       partition directory.</li>
 * </ol>
 *
 * <p>Extracted from {@link com.gamma.inspector.SourceProcessor#executeTransformation}.
 */
public final class DataTransformer {

    private DataTransformer() {}

    /**
     * Transform {@code raw_input} and write partitioned output.
     *
     * @param inputFile   original source file (used to derive the output filename stem)
     * @param conn        worker-local DuckDB connection containing {@code raw_input}
     * @param schemaConfig the schema config map (contains {@code raw.fields}, {@code mapping}, etc.)
     * @param tableName   output table name (drives the sub-directory under {@code dirs.database});
     *                    {@code null} or blank → write directly to {@code dirs.database}
     * @param cfg         pipeline configuration
     * @return paths and byte-sizes of all output files written
     */
    @SuppressWarnings("unchecked")
    public static TransformResult transform(File inputFile, Connection conn,
                                            Map<String, Object> schemaConfig,
                                            String tableName,
                                            PipelineConfig cfg) throws Exception {
        // ── schema / config extraction ────────────────────────────────────────
        List<Map<String, Object>> fields =
                (List<Map<String, Object>>) ((Map<String, Object>) schemaConfig.get("raw")).get("fields");
        Map<String, String> fieldTypes = new LinkedHashMap<>();
        for (Map<String, Object> f : fields)
            fieldTypes.put((String) f.get("name"), (String) f.get("type"));

        List<Map<String, String>> rules =
                (List<Map<String, String>>) ((Map<String, Object>) schemaConfig.get("mapping")).get("rules");
        String partitionKey = (String) schemaConfig.get("partitionKey");

        boolean isParquet   = "PARQUET".equals(cfg.outputFormat);
        String  ext         = isParquet ? ".parquet" : ".csv";
        String  compression = isParquet ? cfg.compression : null;

        // ── output filename ───────────────────────────────────────────────────
        String baseName      = CsvIngester.stripExtensions(inputFile.getName());
        String outputFileName= baseName + "_out" + ext;

        // ── typed SELECT ──────────────────────────────────────────────────────
        StringBuilder select = new StringBuilder("SELECT ");
        for (int i = 0; i < rules.size(); i++) {
            Map<String, String> rule = rules.get(i);
            String source        = rule.get("sourceExpression");
            String target        = rule.get("targetColumn");
            String transformType = rule.getOrDefault("transformType", "DIRECT");

            if ("CONCAT_DT".equals(transformType)) {
                String[] parts  = source.split("\\|", 2);
                String dateCol  = "raw_input.\"" + parts[0] + '"';
                String timeCol  = "raw_input.\"" + parts[1] + '"';
                SqlBuilder.appendCoalesce(select,
                        dateCol + " || ' ' || " + timeCol, cfg.tsFormats, "TIMESTAMP");
            } else if ("FILENAME_DATE".equals(transformType)) {
                // Restricted to EVENT_DATE only — this transform extracts a date from a
                // filename-style column and is intentionally scoped to the voucher CDR
                // (voucher_unknown_537) schema.  Using it on any other target column is a
                // configuration error and will fail fast here.
                if (!"EVENT_DATE".equals(target)) {
                    throw new IllegalArgumentException(
                            "FILENAME_DATE transform is only supported for the EVENT_DATE column, got: " + target);
                }
                // source format: COLUMN_NAME|PREFIX  (optionally |FORMAT, default %Y%m%d)
                // Extracts an 8-digit date appearing after PREFIX in the named column.
                String[] parts  = source.split("\\|", 3);
                String   col    = "raw_input.\"" + parts[0] + '"';
                String   prefix = parts.length > 1 ? parts[1] : "";
                String   fmt    = parts.length > 2 ? parts[2] : "%Y%m%d";
                select.append("TRY_STRPTIME(regexp_extract(")
                      .append(col).append(", '").append(prefix)
                      .append("([0-9]{8})', 1), '").append(fmt).append("')::DATE");
            } else {
                String col  = "raw_input.\"" + source + '"';
                String type = fieldTypes.getOrDefault(source, "VARCHAR");
                switch (type) {
                    case "TIMESTAMP" -> SqlBuilder.appendCoalesce(select, col, cfg.tsFormats, "TIMESTAMP");
                    case "DATE"      -> SqlBuilder.appendCoalesce(select, col, cfg.dateFormats, "DATE");
                    case "DOUBLE"    -> select.append("TRY_CAST(").append(col).append(" AS DOUBLE)");
                    default          -> select.append(col);
                }
            }
            select.append(" AS \"").append(target).append('"');
            if (i < rules.size() - 1) select.append(", ");
        }

        // ── partition columns ─────────────────────────────────────────────────
        if (partitionKey != null && !partitionKey.isEmpty()) {
            String castExpr = SqlBuilder.buildPartitionExpr(
                    partitionKey, rules, fieldTypes, cfg.dateFormats, cfg.tsFormats);
            select.append(", YEAR(").append(castExpr).append(")::VARCHAR AS year");
            select.append(", LPAD(MONTH(").append(castExpr).append(")::VARCHAR, 2, '0') AS month");
            select.append(", LPAD(DAY(").append(castExpr).append(")::VARCHAR, 2, '0') AS day");
        } else {
            select.append(", '1900' AS year, '01' AS month, '01' AS day");
        }
        select.append(" FROM raw_input");

        // ── resolve output database directory ─────────────────────────────────
        String databaseDir = (tableName != null && !tableName.isBlank())
                ? Paths.get(cfg.databaseDir, tableName).toString()
                : cfg.databaseDir;
        new File(databaseDir).mkdirs();

        // ── unique staging subdirectory per worker ────────────────────────────
        String workerTag   = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Path   stagingPath = Paths.get(databaseDir, ".staging", workerTag);
        Files.createDirectories(stagingPath);
        String stagingDir  = stagingPath.toString().replace("\\", "/");

        List<String> outputPaths = new ArrayList<>();
        List<Long>   outputSizes = new ArrayList<>();

        try (Statement stmt = conn.createStatement()) {
            // Materialise first to avoid DuckDB AVX2 page-fault on large files
            stmt.execute("CREATE TABLE transformed AS " + select);

            // Build COPY TO options
            StringBuilder copyOpts = new StringBuilder("FORMAT ").append(cfg.outputFormat)
                    .append(", PARTITION_BY (year, month, day), OVERWRITE_OR_IGNORE 1");
            if (isParquet && compression != null && !compression.isBlank())
                copyOpts.append(", COMPRESSION ").append(compression);

            stmt.execute(String.format("COPY transformed TO '%s' (%s)", stagingDir, copyOpts));

            // Move staged files to final partition directories.
            //
            // Two-step rename to keep partially-visible output away from downstream
            // readers (e.g. pg_duckdb / DuckLake glob scans): the file first lands
            // with a `.tmp` suffix that does not match the `*_out.<ext>` pattern any
            // consumer would scan for, then is atomically renamed to the final name
            // only once the move from the staging tree has completed.  Both renames
            // are on the same filesystem (staging is a subdir of databaseDir), so
            // each is a single rename(2) — the consumer never sees a half-written
            // *_out.<ext> file.
            try (Stream<Path> staged = Files.walk(stagingPath)) {
                staged.filter(Files::isRegularFile).forEach(src -> {
                    Path rel       = stagingPath.relativize(src);
                    Path dstFinal  = Paths.get(databaseDir).resolve(rel).resolveSibling(outputFileName);
                    Path dstTemp   = dstFinal.resolveSibling(outputFileName + ".tmp");
                    try {
                        Files.createDirectories(dstFinal.getParent());
                        // Step 1: land in the final partition dir under a non-matching name.
                        Files.move(src, dstTemp, StandardCopyOption.REPLACE_EXISTING);
                        // Step 2: atomically reveal it under the canonical *_out.<ext> name.
                        Files.move(dstTemp, dstFinal,
                                StandardCopyOption.REPLACE_EXISTING,
                                StandardCopyOption.ATOMIC_MOVE);
                        outputPaths.add(dstFinal.toString());
                        outputSizes.add(Files.size(dstFinal));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            }

            // Clean up empty staging tree
            try (Stream<Path> cleanup = Files.walk(stagingPath)) {
                cleanup.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            }
        }

        return new TransformResult(outputPaths, outputSizes);
    }
}

package com.gamma.etl;

import com.gamma.util.SqlBuilder;

import java.sql.Connection;
import java.sql.Statement;
import java.util.*;

/**
 * Builds the {@code transformed} table from a raw-ingestion table by applying the
 * schema's mapping rules and type casts, plus the partition columns declared in
 * {@code partitions[]} (or synthesised from the legacy {@code partitionKey:} field),
 * and the internal {@code __src_id} lineage tag.
 *
 * <p>The raw-ingestion table must already exist in {@code conn} and carry a trailing
 * {@code __src_id INTEGER} column added by {@link com.gamma.inspector.BatchProcessor}.
 * Writing the partitioned output is the responsibility of {@link PartitionWriter};
 * computing lineage is {@link LineageCollector}.
 *
 * <h3>Partition column SQL generation</h3>
 * <ul>
 *   <li>{@code VARCHAR / DOUBLE / INTEGER} — direct reference or TRY_CAST.</li>
 *   <li>{@code DATE_YEAR / DATE_MONTH / DATE_DAY} — if the source field type is
 *       {@code DATE} or {@code TIMESTAMP} (already typed by the ingester), the column
 *       is referenced directly and wrapped with {@code YEAR(…)}, {@code MONTH(…)},
 *       {@code DAY(…)}.  For {@code VARCHAR} sources, a
 *       {@code COALESCE(TRY_STRPTIME(…))::DATE} chain is applied first.</li>
 * </ul>
 */
public final class DataTransformer {

    private DataTransformer() {}

    // ── public API ────────────────────────────────────────────────────────────

    /**
     * Backward-compatible overload: reads from {@code raw_input}, writes to
     * {@code transformed}.  Used by the existing CSV single-schema path.
     */
    public static void materialize(Connection conn, Map<String, Object> schemaConfig,
                                   PipelineConfig cfg) throws Exception {
        materialize(conn, schemaConfig, cfg, "raw_input", "transformed");
    }

    /**
     * Create {@code destTable} in {@code conn} from {@code sourceTable}.
     *
     * @param conn         worker DuckDB connection containing {@code sourceTable}
     * @param schemaConfig schema config map ({@code raw.fields}, {@code mapping},
     *                     {@code partitions[]} or legacy {@code partitionKey})
     * @param cfg          pipeline configuration (date/timestamp formats)
     * @param sourceTable  DuckDB table to read from (e.g. {@code "raw_CALL"})
     * @param destTable    DuckDB table to create    (e.g. {@code "transformed_CALL"})
     */
    @SuppressWarnings("unchecked")
    public static void materialize(Connection conn, Map<String, Object> schemaConfig,
                                   PipelineConfig cfg,
                                   String sourceTable, String destTable) throws Exception {

        List<Map<String, Object>> fields =
                (List<Map<String, Object>>) ((Map<String, Object>) schemaConfig.get("raw")).get("fields");
        Map<String, String> fieldTypes = new LinkedHashMap<>();
        for (Map<String, Object> f : fields)
            fieldTypes.put((String) f.get("name"), (String) f.get("type"));

        List<Map<String, String>> rules =
                (List<Map<String, String>>) ((Map<String, Object>) schemaConfig.get("mapping")).get("rules");

        StringBuilder select = new StringBuilder("SELECT ");

        // ── mapped data columns ───────────────────────────────────────────────
        for (int i = 0; i < rules.size(); i++) {
            Map<String, String> rule = rules.get(i);
            String source        = rule.get("sourceExpression");
            String target        = rule.get("targetColumn");
            String transformType = rule.getOrDefault("transformType", "DIRECT");

            if ("CONCAT_DT".equals(transformType)) {
                String[] parts  = source.split("\\|", 2);
                String dateCol  = "\"" + sourceTable + "\".\"" + parts[0] + '"';
                String timeCol  = "\"" + sourceTable + "\".\"" + parts[1] + '"';
                SqlBuilder.appendCoalesce(select,
                        dateCol + " || ' ' || " + timeCol, cfg.tsFormats, "TIMESTAMP");
            } else if ("FILENAME_DATE".equals(transformType)) {
                if (!"EVENT_DATE".equals(target)) {
                    throw new IllegalArgumentException(
                            "FILENAME_DATE transform is only supported for the EVENT_DATE column, got: " + target);
                }
                String[] parts  = source.split("\\|", 3);
                String   col    = "\"" + sourceTable + "\".\"" + parts[0] + '"';
                String   prefix = parts.length > 1 ? parts[1] : "";
                String   fmt    = parts.length > 2 ? parts[2] : "%Y%m%d";
                select.append("TRY_STRPTIME(regexp_extract(")
                      .append(col).append(", '").append(prefix)
                      .append("([0-9]{8})', 1), '").append(fmt).append("')::DATE");
            } else {
                String col  = "\"" + sourceTable + "\".\"" + source + '"';
                String type = fieldTypes.getOrDefault(source, "VARCHAR");
                switch (type) {
                    // Cast to VARCHAR first: no-op for raw VARCHAR (CSV path);
                    // converts already-typed DATE/TIMESTAMP (plugin path) to ISO string
                    // so TRY_STRPTIME always receives a string argument.
                    case "TIMESTAMP" -> SqlBuilder.appendCoalesce(select,
                            "CAST(" + col + " AS VARCHAR)", cfg.tsFormats, "TIMESTAMP");
                    case "DATE"      -> SqlBuilder.appendCoalesce(select,
                            "CAST(" + col + " AS VARCHAR)", cfg.dateFormats, "DATE");
                    case "DOUBLE"    -> select.append("TRY_CAST(").append(col).append(" AS DOUBLE)");
                    default          -> select.append(col);
                }
            }
            select.append(" AS \"").append(target).append('"');
            if (i < rules.size() - 1) select.append(", ");
        }

        // ── partition columns ─────────────────────────────────────────────────
        List<PartitionDef> partDefs = PartitionDef.fromSchema(schemaConfig);
        if (partDefs.isEmpty()) {
            // No partition key at all — land everything in the 1900/01/01 sentinel
            select.append(", '1900' AS year, '01' AS month, '01' AS day");
        } else {
            for (PartitionDef pd : partDefs) {
                select.append(", ");
                appendPartitionColumn(select, pd, sourceTable, fieldTypes, cfg);
                select.append(" AS \"").append(pd.column()).append('"');
            }
        }

        // ── lineage tag ───────────────────────────────────────────────────────
        select.append(", \"").append(sourceTable).append("\".\"__src_id\" AS __src_id");
        select.append(" FROM \"").append(sourceTable).append('"');

        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE \"" + destTable + "\" AS " + select);
        }
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * Append the SQL expression for one partition column (without the {@code AS} alias).
     * DATE_YEAR/MONTH/DAY use a direct column reference when the source is already
     * DATE/TIMESTAMP (ingester pre-typed), or a COALESCE(TRY_STRPTIME…) chain for
     * VARCHAR sources (CSV pipeline).
     */
    private static void appendPartitionColumn(StringBuilder sb, PartitionDef pd,
                                               String sourceTable,
                                               Map<String, String> fieldTypes,
                                               PipelineConfig cfg) {
        String col = "\"" + sourceTable + "\".\"" + pd.source() + "\"";

        switch (pd.type()) {
            case VARCHAR -> sb.append(col);
            case DOUBLE  -> sb.append("TRY_CAST(").append(col).append(" AS DOUBLE)");
            case INTEGER -> sb.append("TRY_CAST(").append(col).append(" AS INTEGER)");
            case DATE_YEAR, DATE_MONTH, DATE_DAY -> {
                // Cast to VARCHAR first: no-op for raw VARCHAR (CSV path);
                // converts already-typed DATE/TIMESTAMP (plugin path) to ISO string
                // so TRY_STRPTIME always receives a string argument.
                String varcharExpr = "CAST(" + col + " AS VARCHAR)";
                String dateExpr = SqlBuilder.buildCastExpr(varcharExpr, "DATE",
                        cfg.dateFormats, cfg.tsFormats);
                switch (pd.type()) {
                    case DATE_YEAR  -> sb.append("YEAR(").append(dateExpr).append(")::VARCHAR");
                    case DATE_MONTH -> sb.append("LPAD(MONTH(").append(dateExpr)
                                         .append(")::VARCHAR, 2, '0')");
                    case DATE_DAY   -> sb.append("LPAD(DAY(").append(dateExpr)
                                         .append(")::VARCHAR, 2, '0')");
                    default -> throw new AssertionError();
                }
            }
        }
    }
}

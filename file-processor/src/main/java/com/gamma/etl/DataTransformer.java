package com.gamma.etl;

import com.gamma.util.SqlBuilder;

import java.sql.Connection;
import java.sql.Statement;
import java.util.*;

/**
 * Builds the {@code transformed} table from {@code raw_input} by applying the
 * schema's mapping rules and type casts, plus the {@code year/month/day}
 * partition columns and the internal {@code __src_id} lineage tag.
 *
 * <p>{@code raw_input} must already exist in {@code conn} and carry a trailing
 * {@code __src_id INTEGER} column (added by {@link com.gamma.inspector.BatchProcessor}).
 * Writing the partitioned output is the responsibility of {@link PartitionWriter};
 * computing lineage is {@link LineageCollector}.
 */
public final class DataTransformer {

    private DataTransformer() {}

    /**
     * Create the {@code transformed} table in {@code conn} from {@code raw_input}.
     *
     * @param conn         worker DuckDB connection containing {@code raw_input}
     * @param schemaConfig schema config map ({@code raw.fields}, {@code mapping}, {@code partitionKey})
     * @param cfg          pipeline configuration (date/timestamp formats)
     */
    @SuppressWarnings("unchecked")
    public static void materialize(Connection conn, Map<String, Object> schemaConfig,
                                   PipelineConfig cfg) throws Exception {

        List<Map<String, Object>> fields =
                (List<Map<String, Object>>) ((Map<String, Object>) schemaConfig.get("raw")).get("fields");
        Map<String, String> fieldTypes = new LinkedHashMap<>();
        for (Map<String, Object> f : fields)
            fieldTypes.put((String) f.get("name"), (String) f.get("type"));

        List<Map<String, String>> rules =
                (List<Map<String, String>>) ((Map<String, Object>) schemaConfig.get("mapping")).get("rules");
        String partitionKey = (String) schemaConfig.get("partitionKey");

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
                if (!"EVENT_DATE".equals(target)) {
                    throw new IllegalArgumentException(
                            "FILENAME_DATE transform is only supported for the EVENT_DATE column, got: " + target);
                }
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

        if (partitionKey != null && !partitionKey.isEmpty()) {
            String castExpr = SqlBuilder.buildPartitionExpr(
                    partitionKey, rules, fieldTypes, cfg.dateFormats, cfg.tsFormats);
            select.append(", YEAR(").append(castExpr).append(")::VARCHAR AS year");
            select.append(", LPAD(MONTH(").append(castExpr).append(")::VARCHAR, 2, '0') AS month");
            select.append(", LPAD(DAY(").append(castExpr).append(")::VARCHAR, 2, '0') AS day");
        } else {
            select.append(", '1900' AS year, '01' AS month, '01' AS day");
        }
        // Carry the lineage tag through; PartitionWriter excludes it from output.
        select.append(", raw_input.\"__src_id\" AS __src_id");
        select.append(" FROM raw_input");

        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE transformed AS " + select);
        }
    }
}

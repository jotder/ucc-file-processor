package com.gamma.util;

import java.util.List;
import java.util.Map;

/**
 * Builds DuckDB SQL expression fragments for type casting and partition-key resolution.
 *
 * <p>Extracted from {@link com.gamma.inspector.CollectorProcessor} where these helpers
 * were private static methods unreachable by other classes.  Now shared with
 * {@link com.gamma.etl.DataTransformer}.
 */
public final class SqlBuilder {

    private SqlBuilder() {}

    /**
     * Append a {@code COALESCE(TRY_STRPTIME(col, fmt1), ...)::TYPE} expression to {@code sb}.
     *
     * <p>Used to try multiple date/timestamp format strings in order, returning the
     * first successful parse.  The result is cast to {@code castType} (e.g.
     * {@code "DATE"} or {@code "TIMESTAMP"}).
     *
     * @param sb       builder to append to
     * @param col      the SQL column reference, e.g. {@code raw_input."TRADE_DATE"}
     * @param formats  ordered list of strptime format strings
     * @param castType DuckDB type name for the final {@code ::} cast
     */
    public static void appendCoalesce(StringBuilder sb, String col,
                                      List<String> formats, String castType) {
        sb.append("COALESCE(");
        for (int j = 0; j < formats.size(); j++) {
            sb.append("TRY_STRPTIME(").append(col).append(", '").append(formats.get(j)).append("')");
            if (j < formats.size() - 1) sb.append(", ");
        }
        sb.append(")::").append(castType);
    }

    /**
     * Resolve the raw-input SQL expression for the given {@code partitionKey} by
     * scanning the mapping rules for a matching {@code targetColumn}.
     *
     * <p>Handles both {@code DIRECT} and {@code CONCAT_DT} transform types so that
     * partition columns are derived from the correct raw columns regardless of
     * schema variant.  Falls back to {@code raw_input."partitionKey"} when no
     * matching rule is found.
     *
     * @param partitionKey schema-level partition key name (e.g. {@code "TRADE_DATE"})
     * @param rules        mapping rules from the schema config
     * @param fieldTypes   raw-field name → toon type (e.g. {@code "DATE"})
     * @param dateFormats  strptime formats for DATE columns
     * @param tsFormats    strptime formats for TIMESTAMP columns
     * @return a DuckDB SQL expression suitable for {@code YEAR(…)}, {@code MONTH(…)}, etc.
     */
    @SuppressWarnings("unchecked")
    public static String buildPartitionExpr(String partitionKey,
                                            List<Map<String, String>> rules,
                                            Map<String, String> fieldTypes,
                                            List<String> dateFormats,
                                            List<String> tsFormats) {
        for (Map<String, String> rule : rules) {
            if (!partitionKey.equals(rule.get("targetColumn"))) continue;
            String source        = rule.get("sourceExpression");
            String transformType = rule.getOrDefault("transformType", "DIRECT");

            if ("CONCAT_DT".equals(transformType)) {
                // Two raw columns separated by '|': date-part | time-part
                String[] parts   = source.split("\\|", 2);
                String dateCol   = "raw_input.\"" + parts[0] + '"';
                String timeCol   = "raw_input.\"" + parts[1] + '"';
                StringBuilder sb = new StringBuilder();
                appendCoalesce(sb, dateCol + " || ' ' || " + timeCol, tsFormats, "TIMESTAMP");
                return sb.toString();
            } else if ("FILENAME_DATE".equals(transformType)) {
                // Restricted to EVENT_DATE only — same guard as DataTransformer.
                if (!"EVENT_DATE".equals(partitionKey)) {
                    throw new IllegalArgumentException(
                            "FILENAME_DATE transform is only supported for the EVENT_DATE column, got: "
                            + partitionKey);
                }
                // source format: COLUMN_NAME|PREFIX  (optionally |FORMAT, default %Y%m%d)
                String[] parts  = source.split("\\|", 3);
                String   col    = "raw_input.\"" + parts[0] + '"';
                String   prefix = parts.length > 1 ? parts[1] : "";
                String   fmt    = parts.length > 2 ? parts[2] : "%Y%m%d";
                return "TRY_STRPTIME(regexp_extract(" + col + ", '" + prefix
                        + "([0-9]{8})', 1), '" + fmt + "')::DATE";
            } else {
                String col  = "raw_input.\"" + source + '"';
                String type = fieldTypes.getOrDefault(source, "VARCHAR");
                return buildCastExpr(col, type, dateFormats, tsFormats);
            }
        }
        // Fallback: assume partitionKey is a raw column name (single-schema behaviour)
        String col  = "raw_input.\"" + partitionKey + '"';
        String type = fieldTypes.getOrDefault(partitionKey, "VARCHAR");
        return buildCastExpr(col, type, dateFormats, tsFormats);
    }

    /**
     * Build a typed cast expression for a column reference.
     *
     * <p>DATE and TIMESTAMP columns get a {@code COALESCE(TRY_STRPTIME…)} wrapper;
     * all other types are returned as-is (the caller handles DOUBLE / VARCHAR
     * separately in the SELECT list).
     */
    public static String buildCastExpr(String col, String type,
                                       List<String> dateFormats, List<String> tsFormats) {
        StringBuilder sb = new StringBuilder();
        switch (type) {
            case "TIMESTAMP" -> appendCoalesce(sb, col, tsFormats, "TIMESTAMP");
            case "DATE"      -> appendCoalesce(sb, col, dateFormats, "DATE");
            default          -> sb.append(col);
        }
        return sb.toString();
    }
}

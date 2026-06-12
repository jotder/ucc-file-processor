package com.gamma.etl;

import com.gamma.util.SqlBuilder;

import java.util.Map;

/**
 * Pure SQL-expression compiler for {@link DataTransformer}. Turns a single mapping
 * rule (or partition definition) into the DuckDB scalar expression that produces one
 * output column — <em>without</em> the {@code AS "alias"} suffix, which the caller adds.
 *
 * <h3>Why a separate seam</h3>
 * The transform vocabulary ({@code DIRECT}, {@code CONCAT_DT}, {@code FILENAME_DATE})
 * was previously an inline {@code if/else} chain inside {@code DataTransformer.materialize}.
 * Modelling each transform type as a {@link ColumnRule} function in a lookup
 * {@link #DATA_RULES registry} keeps {@code materialize} a thin SELECT-assembler and
 * makes a new transform type a one-line registry addition (functional injection) rather
 * than another branch in a growing switch.
 *
 * <h3>Behaviour parity</h3>
 * Every method here emits the byte-identical SQL the inline code produced — it reuses the
 * same {@link SqlBuilder} calls in the same order. Note the deliberate asymmetry preserved
 * from the original: data columns wrap DATE/TIMESTAMP sources in {@code CAST(col AS VARCHAR)}
 * before the {@code TRY_STRPTIME} chain (so an already-typed plugin column is re-stringified),
 * whereas partition columns route through {@link SqlBuilder#buildCastExpr}. The two paths are
 * intentionally distinct.
 */
public final class TransformCompiler {

    private TransformCompiler() {}

    /**
     * Compiles one mapping rule into a column expression. Implementations receive the
     * already-extracted {@code sourceExpression} / {@code targetColumn} plus the schema's
     * field-type map and the source table name.
     */
    @FunctionalInterface
    public interface ColumnRule {
        String compile(String source, String target, Map<String, String> fieldTypes,
                       String sourceTable, PipelineConfig cfg);
    }

    /**
     * transformType → expression compiler. {@code DIRECT} (and a blank/omitted type) is handled
     * directly by {@link #dataColumn} via {@link #direct}; any other non-blank value not in this
     * map is rejected.
     */
    private static final Map<String, ColumnRule> DATA_RULES = Map.of(
            "CONCAT_DT",     TransformCompiler::concatDt,
            "FILENAME_DATE", TransformCompiler::filenameDate,
            "EXPR",          TransformCompiler::expr
    );

    // ── data columns ────────────────────────────────────────────────────────────

    /**
     * Build the expression for one mapping rule (no {@code AS} alias). The {@code transformType}
     * is optional and case-insensitive: <b>blank or omitted means {@code DIRECT}</b>. Recognised
     * types are {@code DIRECT}, {@code EXPR}, {@code CONCAT_DT}, {@code FILENAME_DATE}; any other
     * non-blank value is rejected with an {@link IllegalArgumentException} so a typo (e.g.
     * {@code EXPER}) fails fast instead of silently degrading to a pass-through.
     */
    public static String dataColumn(Map<String, String> rule, Map<String, String> fieldTypes,
                                    String sourceTable, PipelineConfig cfg) {
        String source = rule.get("sourceExpression");
        String target = rule.get("targetColumn");
        String type   = rule.get("transformType");
        String norm   = type == null ? "" : type.trim().toUpperCase();

        if (norm.isEmpty() || norm.equals("DIRECT"))   // blank / omitted / DIRECT → pass-through cast
            return direct(source, target, fieldTypes, sourceTable, cfg);

        ColumnRule r = DATA_RULES.get(norm);
        if (r == null)
            throw new IllegalArgumentException(
                    "Unknown transformType '" + type + "' for target column '" + target
                    + "'. Valid: DIRECT (or leave blank), EXPR, CONCAT_DT, FILENAME_DATE.");
        return r.compile(source, target, fieldTypes, sourceTable, cfg);
    }

    private static String direct(String source, String target, Map<String, String> fieldTypes,
                                 String sourceTable, PipelineConfig cfg) {
        String col  = "\"" + sourceTable + "\".\"" + source + '"';
        String type = fieldTypes.getOrDefault(source, "VARCHAR");
        StringBuilder sb = new StringBuilder();
        switch (type) {
            // Cast to VARCHAR first: no-op for raw VARCHAR (CSV path); converts an
            // already-typed DATE/TIMESTAMP (plugin path) to an ISO string so
            // TRY_STRPTIME always receives a string argument.
            case "TIMESTAMP" -> SqlBuilder.appendCoalesce(sb,
                    "CAST(" + col + " AS VARCHAR)", cfg.csv().tsFormats(), "TIMESTAMP");
            case "DATE"      -> SqlBuilder.appendCoalesce(sb,
                    "CAST(" + col + " AS VARCHAR)", cfg.csv().dateFormats(), "DATE");
            case "DOUBLE"    -> sb.append("TRY_CAST(").append(col).append(" AS DOUBLE)");
            default          -> sb.append(col);
        }
        return sb.toString();
    }

    /**
     * EXPR: the {@code sourceExpression} <em>is</em> a DuckDB scalar expression, emitted verbatim.
     * Unqualified column references resolve against the single source table ({@code raw_input}),
     * giving access to DuckDB's full scalar-function library (e.g. {@code UPPER(TRIM(col))},
     * {@code TRY_CAST(amt AS DOUBLE) / 100}, {@code CASE WHEN … END}). The author owns validity and
     * any explicit cast; it must stay a <b>per-row scalar</b> expression — no aggregates or joins,
     * which belong to Stage-2 enrichment. Schema config is operator-authored and trusted (same model
     * as the Stage-2 transform SQL), so the expression is not sandbox-validated.
     */
    private static String expr(String source, String target, Map<String, String> fieldTypes,
                               String sourceTable, PipelineConfig cfg) {
        return source;
    }

    private static String concatDt(String source, String target, Map<String, String> fieldTypes,
                                   String sourceTable, PipelineConfig cfg) {
        String[] parts  = source.split("\\|", 2);
        String dateCol  = "\"" + sourceTable + "\".\"" + parts[0] + '"';
        String timeCol  = "\"" + sourceTable + "\".\"" + parts[1] + '"';
        StringBuilder sb = new StringBuilder();
        SqlBuilder.appendCoalesce(sb,
                dateCol + " || ' ' || " + timeCol, cfg.csv().tsFormats(), "TIMESTAMP");
        return sb.toString();
    }

    private static String filenameDate(String source, String target, Map<String, String> fieldTypes,
                                       String sourceTable, PipelineConfig cfg) {
        if (!"EVENT_DATE".equals(target)) {
            throw new IllegalArgumentException(
                    "FILENAME_DATE transform is only supported for the EVENT_DATE column, got: " + target);
        }
        String[] parts  = source.split("\\|", 3);
        String   col    = "\"" + sourceTable + "\".\"" + parts[0] + '"';
        String   prefix = parts.length > 1 ? parts[1] : "";
        String   fmt    = parts.length > 2 ? parts[2] : "%Y%m%d";
        return "TRY_STRPTIME(regexp_extract(" + col + ", '" + prefix
                + "([0-9]{8})', 1), '" + fmt + "')::DATE";
    }

    // ── partition columns ─────────────────────────────────────────────────────────

    /**
     * Build the expression for one partition column (no {@code AS} alias).
     *
     * <p>{@code DATE_YEAR}/{@code MONTH}/{@code DAY} stringify the source column and parse it with the
     * format list that matches the source field's <em>declared type</em>: a {@code TIMESTAMP} source
     * uses {@code timestamp_formats}, everything else ({@code VARCHAR}/{@code DATE}) uses
     * {@code date_formats}. This matters because a {@code TIMESTAMP} value rendered to text carries a
     * time component that a date-only format cannot match — so a date-only parse would yield {@code NULL}
     * and send every row to the {@code 1900/01/01} sentinel partition. {@code YEAR}/{@code MONTH}/
     * {@code DAY} accept both {@code DATE} and {@code TIMESTAMP}, so the extracted component is correct
     * either way.
     */
    public static String partitionColumn(PartitionDef pd, String sourceTable,
                                         Map<String, String> fieldTypes, PipelineConfig cfg) {
        String col = "\"" + sourceTable + "\".\"" + pd.source() + "\"";
        StringBuilder sb = new StringBuilder();
        switch (pd.type()) {
            case VARCHAR -> sb.append(col);
            case DOUBLE  -> sb.append("TRY_CAST(").append(col).append(" AS DOUBLE)");
            case INTEGER -> sb.append("TRY_CAST(").append(col).append(" AS INTEGER)");
            case DATE_YEAR, DATE_MONTH, DATE_DAY -> {
                // Parse with the format list matching the source's declared type: timestamp_formats
                // for a TIMESTAMP source (its text has a time component), date_formats otherwise.
                String srcType     = fieldTypes.getOrDefault(pd.source(), "VARCHAR");
                String castType    = "TIMESTAMP".equals(srcType) ? "TIMESTAMP" : "DATE";
                String varcharExpr = "CAST(" + col + " AS VARCHAR)";
                String dateExpr = SqlBuilder.buildCastExpr(varcharExpr, castType,
                        cfg.csv().dateFormats(), cfg.csv().tsFormats());
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
        return sb.toString();
    }
}

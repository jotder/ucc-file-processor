package com.gamma.etl;

import java.util.List;
import java.util.Map;

/**
 * One declared partition column: the output Hive-directory segment name, the raw-table
 * source column it is derived from, and how to produce the SQL expression.
 *
 * <p>Read from the {@code partitions[]} list in a schema toon:
 * <pre>
 * partitions:
 *   - column: event_type
 *     source: EVENT_TYPE
 *     type: VARCHAR
 *   - column: year
 *     source: TXN_DATE
 *     type: DATE_YEAR
 *   - column: month
 *     source: TXN_DATE
 *     type: DATE_MONTH
 *   - column: day
 *     source: TXN_DATE
 *     type: DATE_DAY
 * </pre>
 *
 * <p>{@link #fromSchema} is the sole entry point; it handles three cases:
 * <ol>
 *   <li>{@code partitions[]} present — parsed directly.</li>
 *   <li>Legacy {@code partitionKey:} present — synthesised to three DATE_YEAR/MONTH/DAY
 *       defs on the same source column.</li>
 *   <li>Neither present — returns an empty list (caller emits the {@code 1900/01/01}
 *       fallback partition).</li>
 * </ol>
 *
 * @param column  output partition-directory segment name, e.g. {@code "year"}
 * @param source  raw-table column the expression is derived from, e.g. {@code "TXN_DATE"}
 * @param type    how to produce the SQL expression from the source column
 */
public record PartitionDef(String column, String source, Type type) {

    public enum Type {
        /** Direct VARCHAR reference — {@code sourceTable."SOURCE"} */
        VARCHAR,
        /** {@code TRY_CAST(sourceTable."SOURCE" AS DOUBLE)} */
        DOUBLE,
        /** {@code TRY_CAST(sourceTable."SOURCE" AS INTEGER)} */
        INTEGER,
        /**
         * {@code YEAR(dateExpr)::VARCHAR} where {@code dateExpr} is
         * {@code COALESCE(TRY_STRPTIME(…))} for VARCHAR sources or a direct
         * column reference for DATE/TIMESTAMP sources.
         */
        DATE_YEAR,
        /** {@code LPAD(MONTH(dateExpr)::VARCHAR, 2, '0')} — same dateExpr rules as DATE_YEAR. */
        DATE_MONTH,
        /** {@code LPAD(DAY(dateExpr)::VARCHAR, 2, '0')} — same dateExpr rules as DATE_YEAR. */
        DATE_DAY
    }

    /**
     * Parse {@code partitions[]} from a schema config map, falling back to the
     * legacy {@code partitionKey:} field, or returning an empty list when neither
     * is present.
     *
     * @param schemaConfig the loaded schema toon map
     * @return ordered list of partition defs; empty list means "no partitioning"
     */
    @SuppressWarnings("unchecked")
    public static List<PartitionDef> fromSchema(Map<String, Object> schemaConfig) {
        Object raw = schemaConfig.get("partitions");
        if (raw instanceof List<?> list && !list.isEmpty()) {
            return list.stream()
                    .map(e -> (Map<String, Object>) e)
                    .map(m -> new PartitionDef(
                            (String) m.get("column"),
                            (String) m.get("source"),
                            Type.valueOf(((String) m.get("type")).toUpperCase().replace('-', '_'))))
                    .toList();
        }
        // Legacy fallback: single partitionKey → three date components
        String pk = (String) schemaConfig.get("partitionKey");
        if (pk != null && !pk.isBlank()) {
            return List.of(
                    new PartitionDef("year",  pk, Type.DATE_YEAR),
                    new PartitionDef("month", pk, Type.DATE_MONTH),
                    new PartitionDef("day",   pk, Type.DATE_DAY));
        }
        return List.of();
    }

    /** Extract just the column names in declaration order. */
    public static List<String> columnNames(List<PartitionDef> defs) {
        return defs.stream().map(PartitionDef::column).toList();
    }
}

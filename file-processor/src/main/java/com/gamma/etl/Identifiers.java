package com.gamma.etl;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Validates SQL identifier values (column names, table names) sourced from
 * pipeline / schema configuration files.
 *
 * <p>The ETL framework interpolates these names directly into DDL and DML
 * (`CREATE TABLE "<name>" (...)`, `COPY ... PARTITION_BY (col1, col2)`, etc.).
 * That is the right call for an internal tool — schema configs are operator-curated,
 * not user-supplied — but a stray character in a name silently breaks SQL parsing
 * at write time, far from the misconfigured field.  This validator catches the
 * problem at config-load time with a clear message.
 *
 * <p>Identifiers must match {@code ^[A-Za-z_][A-Za-z0-9_]*$}: a leading letter or
 * underscore, followed by letters / digits / underscores.  Any name violating
 * this rule fails the load.
 *
 * <p>Apply once at {@link PipelineConfig#load} — runtime callers can assume
 * identifiers are safe to interpolate.
 */
public final class Identifiers {

    private Identifiers() {}

    private static final Pattern VALID = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    /**
     * Throw {@link IllegalArgumentException} if {@code name} is null, blank, or
     * fails the {@code [A-Za-z_][A-Za-z0-9_]*} pattern.  {@code where} is included
     * in the error message so operators can locate the offending config entry.
     */
    public static void validate(String name, String where) {
        if (name == null || name.isBlank())
            throw new IllegalArgumentException(
                    "SQL identifier at " + where + " is null or blank");
        if (!VALID.matcher(name).matches())
            throw new IllegalArgumentException(
                    "SQL identifier at " + where + " is invalid: '" + name +
                    "'. Must match [A-Za-z_][A-Za-z0-9_]*  (no spaces, dots, quotes, hyphens, or operators).");
    }

    /**
     * Validate every name a schema config exposes to SQL: {@code raw.fields[].name},
     * {@code mapping.rules[].targetColumn}, and {@code partitions[].column / source}.
     * Called from {@link PipelineConfig#load} for the single-schema, multi-schema,
     * and plugin-segment paths alike.
     *
     * @param schema   the loaded schema config map
     * @param origin   short identifier of the schema's source (for error messages)
     */
    @SuppressWarnings("unchecked")
    public static void validateSchema(Map<String, Object> schema, String origin) {
        // raw.fields[].name
        Map<String, Object> rawSection = (Map<String, Object>) schema.get("raw");
        if (rawSection != null) {
            Object rawFields = rawSection.get("fields");
            if (rawFields instanceof List<?> fieldsList) {
                for (Object f : fieldsList) {
                    if (f instanceof Map<?, ?> fm)
                        validate((String) fm.get("name"), origin + ".raw.fields[].name");
                }
            }
        }
        // mapping.rules[].targetColumn
        Map<String, Object> mapping = (Map<String, Object>) schema.get("mapping");
        if (mapping != null) {
            Object rules = mapping.get("rules");
            if (rules instanceof List<?> rulesList) {
                for (Object r : rulesList) {
                    if (r instanceof Map<?, ?> rm)
                        validate((String) rm.get("targetColumn"), origin + ".mapping.rules[].targetColumn");
                }
            }
        }
        // partitions[].column and partitions[].source
        Object partitions = schema.get("partitions");
        if (partitions instanceof List<?> partsList) {
            for (Object p : partsList) {
                if (p instanceof Map<?, ?> pm) {
                    validate((String) pm.get("column"), origin + ".partitions[].column");
                    validate((String) pm.get("source"), origin + ".partitions[].source");
                }
            }
        }
        // Legacy partitionKey
        Object pk = schema.get("partitionKey");
        if (pk instanceof String pkStr && !pkStr.isBlank())
            validate(pkStr, origin + ".partitionKey");
    }
}

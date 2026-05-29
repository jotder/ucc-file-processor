package com.gamma.enrich;

import com.gamma.etl.Identifiers;
import com.gamma.util.ToonHelper;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Configuration for one <b>Stage-2 enrichment</b> job — the separate engine that
 * turns the multiplexer's partitioned output into reports / KPIs via joins and
 * aggregation (the things the Stage-1 multiplexer deliberately does not do).
 *
 * <p>Loaded from a {@code .toon} file in the same style as the pipeline config:
 * <pre>
 * name: EVENTS_DAILY_KPI
 * version: 1
 * input:
 *   database: database/events          # root of the Stage-1 Hive-partitioned output
 *   format: PARQUET                     # PARQUET | CSV
 *   partitions[4]: event_type, year, month, day   # hive partition columns present
 * references:                           # optional join/lookup tables (registered as views by name)
 *   region_dim:
 *     path: ref/region_dim.parquet
 *     format: PARQUET
 * output:
 *   database: reports/events_daily
 *   format: PARQUET
 *   compression: snappy
 *   partitions[4]: event_type, year, month, day   # OUTPUT grain (may differ from input)
 * transform: "SELECT event_type, year, month, day, COUNT(*) AS event_count FROM input GROUP BY 1,2,3,4"
 * # or, for longer SQL:
 * # transform_file: config/events/events_daily_kpi.sql
 * </pre>
 *
 * <p>The {@code transform} SQL reads from a view named {@code input} (the selected
 * partitions of the Stage-1 output) and from any {@code references} by name, and
 * must project the {@code output.partitions} columns plus the report columns.
 */
public record EnrichmentConfig(String name,
                               Input input,
                               List<Reference> references,
                               Output output,
                               String transformSql) {

    /** Stage-1 output to read. */
    public record Input(String database, String format, List<String> partitions) {}

    /** A reference/dimension source registered as a view named {@code name}. */
    public record Reference(String name, String path, String format) {}

    /** Where and how enriched output is written, and at what partition grain. */
    public record Output(String database, String format, String compression, List<String> partitions) {}

    // ── factory ────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public static EnrichmentConfig load(String configPath) throws IOException {
        Map<String, Object> raw = ToonHelper.load(configPath);
        String name = String.valueOf(raw.get("name"));

        Map<String, Object> in = ToonHelper.requireSection(raw, "input");
        Input input = new Input(
                req(in, "database", "input.database"),
                String.valueOf(in.getOrDefault("format", "PARQUET")).toUpperCase(),
                strList(in.get("partitions"), "input.partitions"));
        input.partitions().forEach(c -> Identifiers.validate(c, "input.partitions"));

        Map<String, Object> out = ToonHelper.requireSection(raw, "output");
        Output output = new Output(
                req(out, "database", "output.database"),
                String.valueOf(out.getOrDefault("format", "PARQUET")).toUpperCase(),
                (String) out.get("compression"),
                strList(out.get("partitions"), "output.partitions"));
        output.partitions().forEach(c -> Identifiers.validate(c, "output.partitions"));

        // references is a map of name → {path, format}. A map (not a tabular array)
        // because reference paths contain ':' (Windows drive letters), which JToon's
        // tabular-row parser mis-tokenises; a `path:` scalar handles colons fine.
        List<Reference> refs = new ArrayList<>();
        Object refsRaw = raw.get("references");
        if (refsRaw instanceof Map<?, ?> refMap) {
            for (Map.Entry<?, ?> e : refMap.entrySet()) {
                String rname = (String) e.getKey();
                Identifiers.validate(rname, "references.<name>");
                if (e.getValue() instanceof Map<?, ?> rv) {
                    Object fmt = rv.get("format");
                    refs.add(new Reference(rname, (String) rv.get("path"),
                            (fmt == null ? "PARQUET" : fmt.toString()).toUpperCase()));
                }
            }
        }

        String transform = (String) raw.get("transform");
        String transformFile = (String) raw.get("transform_file");
        if ((transform == null || transform.isBlank()) && transformFile != null) {
            if (!Files.exists(Paths.get(transformFile)))
                throw new FileNotFoundException("transform_file not found: " + transformFile);
            transform = Files.readString(Paths.get(transformFile), StandardCharsets.UTF_8);
        }
        if (transform == null || transform.isBlank())
            throw new IllegalArgumentException("Enrichment config needs 'transform' or 'transform_file'");

        return new EnrichmentConfig(name, input, refs, output, transform.trim());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static String req(Map<String, Object> m, String key, String where) {
        Object v = m.get(key);
        if (v == null || v.toString().isBlank())
            throw new IllegalArgumentException("Missing required " + where);
        return v.toString();
    }

    @SuppressWarnings("unchecked")
    private static List<String> strList(Object v, String where) {
        if (v instanceof List<?> l) return (List<String>) l;
        if (v instanceof String s && !s.isBlank()) {
            List<String> out = new ArrayList<>();
            for (String p : s.split(",")) out.add(p.trim());
            return out;
        }
        throw new IllegalArgumentException("Missing or invalid list at " + where);
    }
}

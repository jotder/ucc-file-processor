package com.gamma.flow;

import com.gamma.api.PublicApi;
import com.gamma.config.io.ConfigCodec;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.SchemaSelector;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Compile-back: recovers, from a lifted {@link FlowGraph}, the execution inputs the existing engine
 * consumes — the inverse of {@link PipelineLift}. Because the lift is lossless (it carries the typed
 * {@code PipelineConfig} sub-records / schema maps / {@code SchemaSelector} verbatim as node config
 * values), {@code compile} simply groups the nodes back by role; every original input is recoverable
 * unchanged. A round-trip ({@code lift → compile}) that returns the same inputs is the <b>Phase-1
 * parity gate</b> (the IR loses nothing).
 *
 * <p><b>Scope (Phase 1):</b> this recovers the inputs; it does not yet <em>invoke</em>
 * {@code SourceProcessor} from them — driving the engine from a {@code FlowGraph} (so the existing
 * suite literally runs through the lifted path) needs the branch-aware executor scheduled for Phase 3
 * (doc §13 R3 / §14 T12). Until then the lossless round-trip below is the gate.
 */
@PublicApi(since = "4.3.0")
public final class FlowCompiler {

    private FlowCompiler() {}

    /**
     * The execution inputs recovered from a {@link FlowGraph}, grouped by role.
     *
     * @param name        the pipeline id ({@link FlowGraph#name()})
     * @param active      the poll gate ({@link FlowGraph#active()})
     * @param acquisition the single entry {@code acquisition} node (the engine's {@code source:} + {@code dirs.poll})
     * @param parser      the single {@code parser} node (csv/grammar/fixedwidth + schema(s)/selector/segments)
     * @param dedups      the dedup nodes ({@code marker} and/or {@code fingerprint}), in chain order
     * @param sinks       every {@code sink} node (per-schema outputs + any quarantine)
     * @param gap         the optional {@code gap} reporting node
     */
    public record Compiled(String name, boolean active,
                           Optional<FlowNode> acquisition, Optional<FlowNode> parser,
                           List<FlowNode> dedups, List<FlowNode> sinks, Optional<FlowNode> gap) {}

    /** Recover the engine inputs from {@code g} by grouping its nodes by role. */
    public static Compiled compile(FlowGraph g) {
        FlowNode acq = null, parser = null, gap = null;
        List<FlowNode> dedups = new ArrayList<>();
        List<FlowNode> sinks = new ArrayList<>();
        for (FlowNode n : g.nodes()) {
            String t = n.type();
            if (BuiltinNodeType.ACQUISITION.type().equals(t)) acq = n;
            else if (BuiltinNodeType.PARSER.type().equals(t)) parser = n;
            else if (BuiltinNodeType.GAP.type().equals(t)) gap = n;
            else if (FlowNodeTypes.isCategory(t, NodeCategory.SINK)) sinks.add(n);   // any sink subtype
            else if (BuiltinNodeType.TRANSFORM_DEDUP_MARKER.type().equals(t)
                    || BuiltinNodeType.TRANSFORM_DEDUP_FINGERPRINT.type().equals(t)) dedups.add(n);
        }
        return new Compiled(g.name(), g.active(),
                Optional.ofNullable(acq), Optional.ofNullable(parser),
                List.copyOf(dedups), List.copyOf(sinks), Optional.ofNullable(gap));
    }

    /**
     * <b>T5b — compile a lifted graph back to a runnable {@code PipelineConfig.fromMap}-shaped map.</b>
     * Reconstructs the raw config map from a lifted {@link FlowGraph}, writing the lift's stored schema
     * map to {@code schemaDir} as a {@code .toon} file ({@code fromMap} re-reads schema files from disk).
     * Round-tripping {@code lift → toConfigMap → fromMap → run} reproduces today's <b>data output</b> —
     * the execution-through-lift parity gate (proven for single-schema in {@code FlowExecutionParityTest}).
     *
     * <p>Scope: <b>single-schema</b> today; selector / segments / fixed-width are incremental follow-ons
     * (this throws {@link UnsupportedOperationException} for them). Operational, non-data knobs the IR
     * does not model ({@code status_dir}/{@code errors}/{@code log_dir}) are intentionally omitted —
     * status is simply disabled in the rebuilt config, which does not affect the data output.
     */
    public static Map<String, Object> toConfigMap(FlowGraph g, Path schemaDir) throws IOException {
        Compiled c = compile(g);
        FlowNode acq = c.acquisition().orElseThrow(() -> new IllegalArgumentException("graph has no acquisition node"));
        FlowNode parser = c.parser().orElseThrow(() -> new IllegalArgumentException("graph has no parser node"));
        FlowNode sink = c.sinks().stream().filter(s -> s.cfg("database") != null).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("graph has no persistent sink with a database dir"));

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("name", g.name());
        raw.put("active", g.active());

        // ── dirs (data-relevant only; status_dir/errors/log_dir intentionally omitted) ──
        Map<String, Object> dirs = new LinkedHashMap<>();
        putIfPresent(dirs, "poll", acq.cfg("poll"));
        putIfPresent(dirs, "database", sink.cfg("database"));
        putIfPresent(dirs, "backup", sink.cfg("backup"));
        putIfPresent(dirs, "temp", sink.cfg("temp"));
        c.dedups().stream().filter(d -> BuiltinNodeType.TRANSFORM_DEDUP_MARKER.type().equals(d.type())).findFirst()
                .ifPresent(d -> putIfPresent(dirs, "markers", d.cfg("markers_dir")));
        c.sinks().stream().filter(s -> s.cfg("dir") != null).findFirst()
                .ifPresent(qs -> putIfPresent(dirs, "quarantine", qs.cfg("dir")));
        raw.put("dirs", dirs);

        // ── output ──
        Map<String, Object> output = new LinkedHashMap<>();
        putIfPresent(output, "format", sink.cfg("format"));
        putIfPresent(output, "compression", sink.cfg("compression"));
        putIfPresent(output, "ducklake", sink.cfg("ducklake"));
        raw.put("output", output);

        // ── processing ──
        Map<String, Object> proc = new LinkedHashMap<>();
        putIfPresent(proc, "threads", sink.cfg("threads"));
        putIfPresent(proc, "duckdb_threads", sink.cfg("duckdb_threads"));
        putIfPresent(proc, "file_pattern", acq.cfg("file_pattern"));   // present once the lift carries it; else default glob
        c.dedups().stream().filter(d -> BuiltinNodeType.TRANSFORM_DEDUP_MARKER.type().equals(d.type())).findFirst()
                .ifPresent(d -> {
                    Map<String, Object> dc = new LinkedHashMap<>();
                    dc.put("enabled", true);
                    putIfPresent(dc, "marker_extension", d.cfg("marker_extension"));
                    putIfPresent(dc, "retention_days", d.cfg("retention_days"));
                    proc.put("duplicate_check", dc);
                });
        if (parser.cfg("csv") instanceof PipelineConfig.CsvSettings csv) proc.put("csv_settings", csvSettingsToMap(csv));

        Files.createDirectories(schemaDir);
        if (parser.cfg("selector") instanceof SchemaSelector selector && selector.hasSchemas()) {
            // multi-schema selector → processing.schemas[] (column-count dispatch; one schema file per entry)
            List<Map<String, Object>> schemas = new ArrayList<>();
            int i = 0;
            for (SchemaSelector.Descriptor d : selector.descriptors()) {
                Path sf = schemaDir.resolve(g.name() + "_schema_" + (i++) + ".toon");
                Files.writeString(sf, ConfigCodec.toToon(d.schema()));
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("column_count", d.columnCount());
                entry.put("schema_file", sf.toString().replace('\\', '/'));
                if (d.table() != null && !d.table().isBlank()) entry.put("table", d.table());
                schemas.add(entry);
            }
            proc.put("schemas", schemas);
        } else if (parser.cfg("schema") instanceof Map<?, ?> schemaMap) {
            // single legacy schema → processing.schema_file
            Path sf = schemaDir.resolve(g.name() + "_schema.toon");
            Files.writeString(sf, ConfigCodec.toToon(schemaMap));
            proc.put("schema_file", sf.toString().replace('\\', '/'));
        } else {
            throw new UnsupportedOperationException(
                    "toConfigMap supports single-schema + selector shapes; segments/fixed-width are follow-ons");
        }
        raw.put("processing", proc);
        return raw;
    }

    private static Map<String, Object> csvSettingsToMap(PipelineConfig.CsvSettings c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("delimiter", c.delimiter());
        m.put("has_header", c.hasHeader());
        m.put("skip_header_lines", c.skipHeaderLines());
        m.put("skip_junk_lines", c.skipJunkLines());
        m.put("skip_tail_lines", c.skipTailLines());
        m.put("skip_tail_columns", c.skipTailCols());
        if (c.engine() != null && !"auto".equalsIgnoreCase(c.engine())) m.put("engine", c.engine());
        if (!c.dateFormats().isEmpty()) m.put("date_formats", c.dateFormats());
        if (!c.tsFormats().isEmpty()) m.put("timestamp_formats", c.tsFormats());
        if (c.encoding() != null) m.put("encoding", c.encoding());
        if (c.inputCompression() != null) m.put("compression", c.inputCompression());
        if (c.strictMode() != null) m.put("strict_mode", c.strictMode());
        if (!c.nullStrings().isEmpty()) m.put("null_strings", c.nullStrings());
        if (!c.includePrefixes().isEmpty()) m.put("include_prefixes", c.includePrefixes());
        if (!c.includeRegex().isEmpty()) m.put("include_regex", c.includeRegex());
        if (!c.excludePrefixes().isEmpty()) m.put("exclude_prefixes", c.excludePrefixes());
        if (!c.excludeRegex().isEmpty()) m.put("exclude_regex", c.excludeRegex());
        if (c.filterTargetColumn() != 0) m.put("filter_target_column", c.filterTargetColumn());
        return m;
    }

    private static void putIfPresent(Map<String, Object> m, String key, Object v) {
        if (v != null) m.put(key, v);
    }
}

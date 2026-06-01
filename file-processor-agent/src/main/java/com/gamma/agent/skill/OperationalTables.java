package com.gamma.agent.skill;

import com.gamma.catalog.ConfigSource;
import com.gamma.enrich.EnrichmentAuditReader;
import com.gamma.enrich.EnrichmentConfig;
import com.gamma.etl.PipelineConfig;
import com.gamma.service.StatusStore;
import com.gamma.sql.SqlOracle;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves a pipeline/job <em>name</em> into the in-memory {@link SqlOracle.TableData}s that
 * {@code report-sql} (M8 / v3.8.0) queries — the platform's own operational audit/status ledgers,
 * read through the backend-agnostic {@link StatusStore} and {@link EnrichmentAuditReader} seams
 * (works identically over the file- and DB-backed stores).
 *
 * <h3>Canonical schemas (always present, even with no rows)</h3>
 * Each table's column list is fixed to the audit ledger's header contract (mirroring
 * {@code BatchAuditWriter} / {@code EnrichmentAuditWriter}) rather than inferred from whatever rows
 * happen to exist. That guarantees a freshly-started pipeline (zero batches) still presents a
 * queryable, correctly-shaped table — so the model's SQL plans, and the prompt can list exact
 * columns. Cells are pulled positionally by column key from the row maps (absent → {@code NULL}).
 * All columns are {@code VARCHAR}; the candidate SQL {@code CAST}s numeric columns as needed.
 */
final class OperationalTables {

    private OperationalTables() {}

    // ── canonical ledger schemas (the audit-writer header contracts) ─────────────────────

    static final List<String> BATCHES = List.of(
            "batch_id", "pipeline", "schema_name", "output_table", "start_time", "end_time", "status",
            "member_count", "rejected_count", "total_input_rows", "total_output_rows",
            "output_file_count", "total_output_bytes", "duration_ms", "error");

    static final List<String> FILES = List.of(
            "start_time", "end_time", "filename", "status", "parsed_rows", "error_rows",
            "output_paths", "output_sizes_bytes", "duration_ms", "error", "batch_id");

    static final List<String> LINEAGE = List.of(
            "batch_id", "src_id", "input_file", "output_file", "partition", "row_count");

    static final List<String> QUARANTINE = List.of("file", "reason", "path", "size_bytes");

    static final List<String> ENRICH_RUNS = List.of(
            "run_id", "job", "trigger", "reason", "scope", "input_partition_count",
            "start_time", "end_time", "status", "output_partition_count", "output_file_count",
            "total_output_rows", "total_output_bytes", "duration_ms", "error");

    static final List<String> ENRICH_LINEAGE = List.of(
            "run_id", "job", "partition", "output_file", "bytes");

    /** The Stage-1 (pipeline) operational tables. */
    static final List<String> STAGE1_NAMES = List.of("batches", "files", "lineage", "quarantine");
    /** The Stage-2 (enrichment) operational tables. */
    static final List<String> STAGE2_NAMES = List.of("enrich_runs", "enrich_lineage");

    // ── name → config resolution (case-insensitive, by display or normalised name) ───────

    static Optional<PipelineConfig> pipeline(ConfigSource configs, String name) {
        if (configs == null || name == null || name.isBlank()) return Optional.empty();
        String n = name.trim();
        for (PipelineConfig c : configs.pipelines()) {
            if (n.equalsIgnoreCase(c.identity().name())
                    || n.equalsIgnoreCase(c.identity().pipelineName())) {
                return Optional.of(c);
            }
        }
        return Optional.empty();
    }

    static Optional<EnrichmentConfig> enrichment(ConfigSource configs, String name) {
        if (configs == null || name == null || name.isBlank()) return Optional.empty();
        String n = name.trim();
        for (EnrichmentConfig c : configs.enrichments()) {
            if (n.equalsIgnoreCase(c.name())) return Optional.of(c);
        }
        return Optional.empty();
    }

    // ── config → operational tables ──────────────────────────────────────────────────────

    static List<SqlOracle.TableData> stage1(StatusStore store, PipelineConfig cfg) {
        List<SqlOracle.TableData> out = new ArrayList<>(4);
        out.add(toTable("batches", BATCHES, store.batches(cfg)));
        out.add(toTable("files", FILES, store.files(cfg)));
        out.add(toTable("lineage", LINEAGE, store.lineage(cfg, null)));
        out.add(toTable("quarantine", QUARANTINE, store.quarantine(cfg)));
        return out;
    }

    static List<SqlOracle.TableData> stage2(EnrichmentConfig cfg) {
        EnrichmentAuditReader r = EnrichmentAuditReader.forConfig(cfg);
        List<SqlOracle.TableData> out = new ArrayList<>(2);
        out.add(toTable("enrich_runs", ENRICH_RUNS, r.runs()));
        out.add(toTable("enrich_lineage", ENRICH_LINEAGE, r.lineage(null)));
        return out;
    }

    /** Build a fixed-schema table from header→value row maps, pulling each canonical column by key. */
    static SqlOracle.TableData toTable(String name, List<String> columns, List<Map<String, String>> rows) {
        List<List<String>> data = new ArrayList<>(rows.size());
        for (Map<String, String> r : rows) {
            List<String> row = new ArrayList<>(columns.size());
            for (String col : columns) row.add(r.get(col));   // null when the ledger omits it
            data.add(row);
        }
        return new SqlOracle.TableData(name, columns, data);
    }

    /** A short, prompt-ready description of a table: {@code name(col1, col2, …)}. */
    static String describe(SqlOracle.TableData t) {
        return t.name() + "(" + String.join(", ", t.columns()) + ")";
    }
}

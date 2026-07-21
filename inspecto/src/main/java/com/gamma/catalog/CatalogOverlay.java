package com.gamma.catalog;

import com.gamma.etl.PipelineConfig;
import com.gamma.etl.StatusStore;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * The lazy operational overlay for the metadata graph, projected from the audit stores the
 * platform already writes — reusing the very reads the Control API exposes, never re-parsing CSVs.
 *
 * <ul>
 *   <li><b>SOURCE / RAW_SCHEMA / TABLE / COLUMN</b> → Stage-1 {@link StatusStore}: latest
 *       batch status + cumulative output rows/bytes, parsed/error rows (completeness/error), and
 *       distinct output partitions (lineage). No committed batch ⇒ {@link OperationalOverlay#NO_DATA}.</li>
 *   <li><b>DERIVED_TABLE</b> → Stage-2 enrichment run/lineage audit.</li>
 *   <li><b>REFERENCE_DATASET / KPI / REPORT</b> → {@link OperationalOverlay#NONE} (config/semantic
 *       artifacts with no runtime footprint).</li>
 * </ul>
 *
 * <p>Dependencies are taken as seams (a pipeline→config lookup, the {@link StatusStore}, and a
 * Stage-2 read seam) so the overlay is unit-testable in isolation and so the M1 {@code ConfigRegistry}
 * can supply the config lookup later with no change here.
 */
public final class CatalogOverlay implements MetadataGraphService.OverlaySource {

    /** Read seam over Stage-2 enrichment audit (satisfied by {@code EnrichmentService}). */
    public interface Stage2Reads {
        boolean hosts(String job);
        List<Map<String, String>> runs(String job);
        List<Map<String, String>> lineage(String job, String runId);
    }

    private static final int MAX_LINEAGE_REFS = 100;

    private final Function<String, Optional<PipelineConfig>> configByPipeline;
    private final StatusStore statusStore;
    private final Stage2Reads stage2;   // nullable

    public CatalogOverlay(Function<String, Optional<PipelineConfig>> configByPipeline,
                          StatusStore statusStore, Stage2Reads stage2) {
        this.configByPipeline = configByPipeline;
        this.statusStore = statusStore;
        this.stage2 = stage2;
    }

    @Override
    public OperationalOverlay overlayFor(MetadataNode node) {
        if (node == null) return OperationalOverlay.NONE;
        return switch (node.kind()) {
            case STREAM, RAW_SCHEMA, TABLE, COLUMN -> stage1(node);
            case DERIVED_TABLE -> stage2(node);
            case REFERENCE_DATASET, KPI, REPORT -> OperationalOverlay.NONE;
        };
    }

    // ── Stage-1 ────────────────────────────────────────────────────────────────────

    private OperationalOverlay stage1(MetadataNode node) {
        Optional<PipelineConfig> cfgOpt = configByPipeline.apply(pipelineOf(node.id()));
        if (cfgOpt.isEmpty()) return OperationalOverlay.NONE;
        PipelineConfig cfg = cfgOpt.get();

        if (statusStore.committedBatches(cfg).isEmpty()) return OperationalOverlay.NO_DATA;

        List<Map<String, String>> batches = statusStore.batches(cfg);
        String status = "UNKNOWN", endTime = "", lastError = "";
        long outRows = 0, outBytes = 0;
        for (Map<String, String> b : batches) {
            outRows += parseLong(b.get("total_output_rows"));
            outBytes += parseLong(b.get("total_output_bytes"));
        }
        if (!batches.isEmpty()) {
            Map<String, String> latest = batches.get(batches.size() - 1);   // newest run last
            status = latest.getOrDefault("status", "UNKNOWN");
            endTime = latest.getOrDefault("end_time", "");
            lastError = latest.getOrDefault("error", "");
        }

        long parsed = 0, errored = 0;
        for (Map<String, String> f : statusStore.files(cfg)) {
            parsed += parseLong(f.get("parsed_rows"));
            errored += parseLong(f.get("error_rows"));
        }

        String eventType = node.kind() == NodeKind.TABLE
                ? str(node.attrs().get("eventType")) : "";
        List<String> lineage = distinctPartitions(statusStore.lineage(cfg, null), eventType);

        return new OperationalOverlay(status, endTime, outRows, outBytes,
                parsed, errored, lastError, lineage, true);
    }

    /**
     * Distinct partition paths from lineage rows. When {@code eventType} is set (a plugin segment
     * event table) and at least one partition is scoped to it ({@code event_type=<key>}), only
     * those are returned; otherwise (CSV sources have no {@code event_type} partition) all are.
     */
    private static List<String> distinctPartitions(List<Map<String, String>> rows, String eventType) {
        Set<String> all = new LinkedHashSet<>();
        Set<String> scoped = new LinkedHashSet<>();
        String marker = "event_type=" + eventType;
        for (Map<String, String> r : rows) {
            String p = r.getOrDefault("partition", "");
            if (p.isBlank()) continue;
            all.add(p);
            if (!eventType.isBlank() && p.contains(marker)) scoped.add(p);
        }
        Set<String> chosen = scoped.isEmpty() ? all : scoped;
        List<String> out = new ArrayList<>(chosen);
        return out.size() > MAX_LINEAGE_REFS ? out.subList(0, MAX_LINEAGE_REFS) : out;
    }

    // ── Stage-2 ────────────────────────────────────────────────────────────────────

    private OperationalOverlay stage2(MetadataNode node) {
        if (stage2 == null) return OperationalOverlay.NONE;
        String job = node.id().startsWith("xform:") ? node.id().substring("xform:".length()) : node.label();
        if (!stage2.hosts(job)) return OperationalOverlay.NONE;

        List<Map<String, String>> runs = stage2.runs(job);
        if (runs.isEmpty()) return OperationalOverlay.NO_DATA;

        String status = "UNKNOWN", endTime = "", lastError = "";
        long outRows = 0, outBytes = 0;
        for (Map<String, String> r : runs) {
            outRows += parseLong(r.get("total_output_rows"));
            outBytes += parseLong(r.get("total_output_bytes"));
        }
        Map<String, String> latest = runs.get(runs.size() - 1);
        status = latest.getOrDefault("status", "UNKNOWN");
        endTime = latest.getOrDefault("end_time", latest.getOrDefault("start_time", ""));
        lastError = latest.getOrDefault("error", "");

        List<String> lineage = distinctPartitions(stage2.lineage(job, null), "");
        return new OperationalOverlay(status, endTime, outRows, outBytes, 0, 0, lastError, lineage, true);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    /** Pipeline name from any Stage-1 node id ({@code source:p}, {@code schema:p/..}, {@code col:p/..}). */
    private static String pipelineOf(String id) {
        int colon = id.indexOf(':');
        String rest = colon < 0 ? id : id.substring(colon + 1);
        int slash = rest.indexOf('/');
        return slash < 0 ? rest : rest.substring(0, slash);
    }

    private static long parseLong(String s) {
        if (s == null || s.isBlank()) return 0;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }
}

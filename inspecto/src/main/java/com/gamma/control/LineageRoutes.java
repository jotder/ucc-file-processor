package com.gamma.control;

import com.gamma.pipeline.PipelineGraph;
import com.gamma.pipeline.PipelineStore;
import com.gamma.pipeline.PipelineStores;
import com.gamma.util.Csv;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>Cross-engine data lineage, stitched at the STORE (§11).</b> The two provenance halves of the platform
 * never knew about each other:
 * <ul>
 *   <li>the <b>ingest pipeline</b> records which input <i>file</i>'s rows landed in which output store+partition
 *       — the per-{@code (inputFile, partition)} count matrix ({@link com.gamma.etl.LineageRow}, written to the
 *       {@code lineage}/{@code batches} audit CSVs, joined by {@code batch_id});</li>
 *   <li>an <b>authored flow</b> declares the {@code source_store}(s) it reads (and emits per-{@code (node, rel)}
 *       counts to {@link com.gamma.pipeline.exec.DbProvenanceStore}, painted as the {@code /provenance} Sankey).</li>
 * </ul>
 * Neither half carries the other's dimension (the flow has no file; the ingest matrix has no node), and they do
 * <b>not</b> share a {@code batch_id} — they are distinct execution engines. The bridge between them is the
 * <b>store name</b>: ingest <i>writes</i> a store (the {@code batches.output_table}); a flow <i>reads</i> it as a
 * {@code source_store}. {@code GET /lineage?store=<store>} returns both ends so a consumer can trace
 * <i>file → store → flow → sink</i>:
 * <pre>
 *   { "store": "...",
 *     "upstream":   [ { pipeline, batchId, inputFile, partition, rowCount } … ],   // ingest: files → this store
 *     "downstream": [ { flow, sinks:[…] } … ] }                                    // flows reading this store
 * </pre>
 * Independent of {@code -Dprovenance.backend}: the upstream half reads the ingest audit CSVs and the downstream
 * half reads the authored-flow store. Both degrade to {@code []} (never a 500) when their inputs are absent.
 */
final class LineageRoutes implements RouteModule {

    @Override
    public void register(ApiContext api) {
        api.get("/lineage", (e, m) -> storeLineage(api, ApiContext.query(e, "store")));
    }

    /** {@code GET /lineage?store=} — the file→store (ingest) and store→flow (authored) lineage around one store. */
    private Object storeLineage(ApiContext api, String store) {
        if (store == null || store.isBlank())
            throw new ApiException(400, "the 'store' query param is required");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("store", store);
        out.put("upstream", upstream(api, store));
        out.put("downstream", downstream(api, store));
        return out;
    }

    /**
     * The ingest {@code (inputFile, partition) → rowCount} rows that landed in {@code store}, unioned across every
     * registered pipeline. Per pipeline, joins its {@code lineage} CSV to its {@code batches} CSV on {@code batch_id}
     * and keeps only batches whose {@code output_table} is this store.
     */
    private List<Map<String, Object>> upstream(ApiContext api, String store) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (var pv : api.service().pipelines()) {
            var cfg = api.service().configFor(pv.name());
            if (cfg.isEmpty()) continue;
            var dirs = cfg.get().dirs();
            if (dirs.batchesFilePath() == null || dirs.lineageFilePath() == null) continue;
            Path batches = Path.of(dirs.batchesFilePath());
            Path lineage = Path.of(dirs.lineageFilePath());
            if (!Files.exists(batches) || !Files.exists(lineage)) continue;
            rows.addAll(stitchUpstream(pv.name(), store, read(batches), read(lineage)));
        }
        return rows;
    }

    /**
     * Pure join (no I/O — directly unit-testable): the {@code lineage} rows whose batch wrote to {@code store},
     * shaped for the wire. {@code batchesRows}/{@code lineageRows} are header→value maps from the audit CSVs.
     */
    static List<Map<String, Object>> stitchUpstream(String pipeline, String store,
                                                    List<Map<String, String>> batchesRows,
                                                    List<Map<String, String>> lineageRows) {
        Map<String, String> batchStore = new LinkedHashMap<>();
        for (Map<String, String> b : batchesRows) batchStore.put(b.get("batch_id"), b.get("output_table"));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, String> r : lineageRows) {
            if (!store.equals(batchStore.get(r.get("batch_id")))) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("pipeline", pipeline);
            row.put("batchId", r.get("batch_id"));
            row.put("inputFile", r.get("input_file"));
            row.put("partition", r.get("partition"));
            row.put("rowCount", parseLong(r.get("row_count")));
            out.add(row);
        }
        return out;
    }

    /**
     * The authored flows that read {@code store} as a {@code source_store}, with the sink stores they produce —
     * the downstream end of the bridge. Empty when filesystem writes are disabled (authored flows live under
     * {@code <write-root>/flows}; {@code writeRoot()} is null without {@code -Dassist.write.root}).
     */
    private List<Map<String, Object>> downstream(ApiContext api, String store) {
        List<Map<String, Object>> flows = new ArrayList<>();
        Path writeRoot = api.writeRoot();
        if (writeRoot == null) return flows;
        for (PipelineGraph g : new PipelineStore(writeRoot.resolve("flows")).list()) {
            if (!PipelineStores.consumed(g).contains(store)) continue;
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("flow", g.name());
            f.put("sinks", new ArrayList<>(PipelineStores.produced(g)));
            flows.add(f);
        }
        return flows;
    }

    /** Best-effort CSV read: a partially-written/locked audit file must not 500 the lineage read. */
    private static List<Map<String, String>> read(Path csv) {
        List<Map<String, String>> out = new ArrayList<>();
        try {
            Csv.readInto(csv, out);
        } catch (Exception ignored) {
            // tolerate a missing/locked/partial audit CSV — return what parsed
        }
        return out;
    }

    private static long parseLong(String s) {
        try {
            return s == null || s.isBlank() ? 0L : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}

package com.gamma.flow;

import com.gamma.api.PublicApi;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>T32 Phase C — the durable definition of a logical {@code sink.view} store.</b> A {@code sink.view}
 * (§3.1) persists no bytes; it is a logical store a job / KPI / report / alert API binds to and the engine
 * <em>concretises on demand</em> (re-deriving it by running its producing flow). A flow job therefore does
 * not write Parquet for such a sink — instead it records this definition under {@code <write-root>/views/}
 * so the binding side can discover the view, its lineage (which {@code source_store}s feed it, via which
 * flow), and — when expressible — the SQL that derives it.
 *
 * @param store        the produced logical store name (the view's identity)
 * @param flow         the authored flow id that produces it (run to concretise the view)
 * @param sourceStores the {@code source_store}s the producing flow consumes (lineage)
 * @param derivedSql   the SELECT that derives the view, when expressible as a single statement; else {@code null}
 *                     (the multi-statement transform chain is re-run via {@code flow} instead)
 * @param definedAt    ISO-8601 timestamp the definition was last written
 */
@PublicApi(since = "4.3.0")
public record ViewDefinition(String store, String flow, List<String> sourceStores,
                             String derivedSql, String definedAt) {

    public ViewDefinition {
        sourceStores = sourceStores == null ? List.of() : List.copyOf(sourceStores);
    }

    /** Lossless map form for {@code ConfigCodec.toToon} persistence. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("store", store);
        m.put("flow", flow);
        m.put("source_store", sourceStores);
        if (derivedSql != null && !derivedSql.isBlank()) m.put("derived_sql", derivedSql);
        m.put("defined_at", definedAt);
        return m;
    }

    public static ViewDefinition fromMap(Map<String, Object> m) {
        Object ss = m.get("source_store");
        List<String> sources = ss instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
        return new ViewDefinition(
                str(m.get("store")), str(m.get("flow")), sources,
                str(m.get("derived_sql")), str(m.get("defined_at")));
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }
}

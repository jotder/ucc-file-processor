package com.gamma.catalog;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Projects a loaded schema config map (the {@code raw}/{@code mapping} structure parsed from a
 * schema {@code .toon}) into the metadata-graph's column view. This is the only new reader of the
 * additive {@code description}/{@code unit}/{@code classification} field columns — the ETL data
 * path ignores them entirely, which is what keeps the 4th+ columns backward-compatible.
 *
 * <p>A non-blank {@code description} read from a schema file is treated as {@link Provenance#MANUAL}
 * (operator-authored prose), so it outranks any later AI/heuristic suggestion.
 */
public final class SchemaProjection {

    private SchemaProjection() {}

    /** One projected column with its domain metadata. */
    public record Column(String name, String type, Description description,
                         String unit, String classification) {}

    /** Project the {@code raw.fields[]} of a schema into columns (empty if absent/malformed). */
    public static List<Column> columns(Map<String, Object> schema) {
        List<Column> out = new ArrayList<>();
        if (schema == null || !(schema.get("raw") instanceof Map<?, ?> raw)
                || !(raw.get("fields") instanceof List<?> fields)) {
            return out;
        }
        for (Object o : fields) {
            if (!(o instanceof Map<?, ?> f)) continue;
            String name = str(f.get("name"));
            if (name.isBlank()) continue;
            String desc = str(f.get("description"));
            Description d = desc.isBlank() ? Description.EMPTY : Description.manual(desc);
            out.add(new Column(name, str(f.get("type")), d,
                    str(f.get("unit")), str(f.get("classification"))));
        }
        return out;
    }

    /**
     * The natural table name a schema declares ({@code mapping.canonicalName}, then
     * {@code mapping.rawName}, then {@code raw.name}); {@code null} if none is present. Used to
     * label single-schema / multi-schema event tables when no explicit table is registered.
     */
    public static String canonicalName(Map<String, Object> schema) {
        if (schema == null) return null;
        if (schema.get("mapping") instanceof Map<?, ?> m) {
            String c = str(m.get("canonicalName"));
            if (!c.isBlank()) return c;
            String r = str(m.get("rawName"));
            if (!r.isBlank()) return r;
        }
        if (schema.get("raw") instanceof Map<?, ?> raw) {
            String n = str(raw.get("name"));
            if (!n.isBlank()) return n;
        }
        return null;
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }
}

package com.gamma.catalog;

import com.gamma.etl.Identifiers;
import com.gamma.util.ToonHelper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The semantic / domain layer for a set of data sources, loaded from a {@code *_meta.toon}.
 *
 * <p>This holds the cross-table knowledge the per-schema files cannot express: table-level
 * descriptions, a <b>KPI catalog</b> (named KPI → definition / grain / inputs / join keys), report
 * metadata, and free-form <b>domain notes</b> (currency, time-zone, caveats). It is the layer that
 * lets an assist agent relate a KPI or report requirement back to the right tables and columns.
 *
 * <p>References inside the file ({@code tables} keys, KPI {@code inputs}, report {@code uses}) name
 * other catalog artifacts. They may be plain refs (e.g. {@code events/CALL}, {@code events_daily})
 * or fully-prefixed node ids (e.g. {@code "xform:events_daily"}) — the latter <b>must be quoted</b>
 * in {@code .toon} inline arrays because they contain a colon. {@link MetadataGraphService} resolves
 * each ref to a concrete node id when it assembles the graph; this loader keeps them verbatim.
 *
 * <pre>
 * name: events_semantics
 * tables:
 *   events/CALL:
 *     description: One row per voice call detail record
 *     grain: event_type, year, month, day
 * kpis:
 *   arpu:
 *     definition: Average revenue per user per month
 *     grain: subscriber_id, month
 *     inputs[2]: "events/CALL", "xform:events_daily"
 *     join_keys[1]: subscriber_id
 * reports:
 *   events_daily:
 *     description: Daily KPI rollup per event type
 *     uses[1]: "kpi:event_count"
 * domain:
 *   currency: USD
 *   timezone: UTC
 *   notes[1]: "revenue excludes tax"
 * </pre>
 */
public record SemanticModel(String name,
                            Map<String, TableMeta> tables,
                            Map<String, KpiMeta> kpis,
                            Map<String, ReportMeta> reports,
                            DomainNotes domain) {

    /** Table-level domain description, keyed in {@link #tables} by the author's table ref. */
    public record TableMeta(String ref, String description, String grain) {}

    /** A named KPI: what it means, at what grain, and which inputs/keys compute it. */
    public record KpiMeta(String name, String definition, String grain,
                          List<String> inputs, List<String> joinKeys) {
        public KpiMeta {
            inputs = inputs == null ? List.of() : List.copyOf(inputs);
            joinKeys = joinKeys == null ? List.of() : List.copyOf(joinKeys);
        }
    }

    /** A named report and the KPIs/tables it uses. */
    public record ReportMeta(String name, String description, List<String> uses) {
        public ReportMeta {
            uses = uses == null ? List.of() : List.copyOf(uses);
        }
    }

    /** Free-form domain notes that apply across tables. */
    public record DomainNotes(String currency, String timezone, List<String> notes) {
        public static final DomainNotes EMPTY = new DomainNotes("", "", List.of());

        public DomainNotes {
            currency = currency == null ? "" : currency;
            timezone = timezone == null ? "" : timezone;
            notes = notes == null ? List.of() : List.copyOf(notes);
        }
    }

    public SemanticModel {
        tables = tables == null ? Map.of() : Map.copyOf(tables);
        kpis = kpis == null ? Map.of() : Map.copyOf(kpis);
        reports = reports == null ? Map.of() : Map.copyOf(reports);
        domain = domain == null ? DomainNotes.EMPTY : domain;
    }

    // ── factory ──────────────────────────────────────────────────────────────────

    /** Load a {@code *_meta.toon} into a {@code SemanticModel}. */
    public static SemanticModel load(String path) throws IOException {
        Map<String, Object> raw = ToonHelper.load(path);
        String name = str(raw.getOrDefault("name", ""));

        Map<String, TableMeta> tables = new LinkedHashMap<>();
        if (raw.get("tables") instanceof Map<?, ?> tm) {
            for (Map.Entry<?, ?> e : tm.entrySet()) {
                String ref = String.valueOf(e.getKey());
                if (e.getValue() instanceof Map<?, ?> v) {
                    tables.put(ref, new TableMeta(ref, str(v.get("description")), str(v.get("grain"))));
                }
            }
        }

        Map<String, KpiMeta> kpis = new LinkedHashMap<>();
        if (raw.get("kpis") instanceof Map<?, ?> km) {
            for (Map.Entry<?, ?> e : km.entrySet()) {
                String kname = String.valueOf(e.getKey());
                Identifiers.validate(kname, "kpis.<name>");
                if (e.getValue() instanceof Map<?, ?> v) {
                    kpis.put(kname, new KpiMeta(kname, str(v.get("definition")), str(v.get("grain")),
                            strList(v.get("inputs")), strList(v.get("join_keys"))));
                }
            }
        }

        Map<String, ReportMeta> reports = new LinkedHashMap<>();
        if (raw.get("reports") instanceof Map<?, ?> rm) {
            for (Map.Entry<?, ?> e : rm.entrySet()) {
                String rname = String.valueOf(e.getKey());
                Identifiers.validate(rname, "reports.<name>");
                if (e.getValue() instanceof Map<?, ?> v) {
                    reports.put(rname, new ReportMeta(rname, str(v.get("description")), strList(v.get("uses"))));
                }
            }
        }

        DomainNotes domain = DomainNotes.EMPTY;
        if (raw.get("domain") instanceof Map<?, ?> dm) {
            domain = new DomainNotes(str(dm.get("currency")), str(dm.get("timezone")), strList(dm.get("notes")));
        }

        return new SemanticModel(name, tables, kpis, reports, domain);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    /** Parse a JToon list (or comma-separated scalar) into a trimmed, non-blank string list. */
    private static List<String> strList(Object v) {
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> l) {
            for (Object o : l) {
                String s = String.valueOf(o).trim();
                if (!s.isEmpty()) out.add(s);
            }
        } else if (v instanceof String s && !s.isBlank()) {
            for (String p : s.split(",")) {
                String t = p.trim();
                if (!t.isEmpty()) out.add(t);
            }
        }
        return out;
    }
}

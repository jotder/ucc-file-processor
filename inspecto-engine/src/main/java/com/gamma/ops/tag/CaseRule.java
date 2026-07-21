package com.gamma.ops.tag;

import com.gamma.config.io.ConfigCodec;
import com.gamma.ops.OperationalObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A <strong>Case Rule</strong> (GLOSSARY §9 — rule-raised cases, C5): auto-grouping of Incidents into
 * a Case. When at least {@link #threshold} Incidents match {@link #filter} within the last
 * {@link #windowMinutes}, they are grouped under one Case — the mechanical tail of the
 * Alert → Incident → Case chain. Reuses {@link TagRule.Filter} for the incident-matching predicate.
 *
 * <p>Authored as a {@code *_caserule.toon} (a {@code case_rule { … }} block) loaded at bootstrap, or
 * saved at runtime via {@code POST /cases/rules} (persisted under the write root, rescanned at boot).
 * On evaluation the raised Case is titled {@link #title} and inherits the rule's {@link #category} /
 * {@link #tags}; re-evaluation attaches new matches to the same still-open Case (marked
 * {@code attributes.raisedByRule}), so grouping is idempotent.
 *
 * @since 5.0.0
 */
@com.gamma.api.PublicApi(since = "5.0.0")
public record CaseRule(String name, String title, TagRule.Filter filter, int threshold,
                       long windowMinutes, String category, String tags, long createdAt) {

    public CaseRule {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("case rule name is required");
        name = name.trim();
        if (title == null || title.isBlank()) throw new IllegalArgumentException("case rule needs a 'title' for the raised case");
        title = title.trim();
        if (filter == null || !filter.hasCriterion())
            throw new IllegalArgumentException("a case rule needs at least one criterion (type/q/status/priority/severity/category)");
        if (threshold < 1) throw new IllegalArgumentException("case rule threshold must be >= 1");
        if (windowMinutes < 0) throw new IllegalArgumentException("case rule windowMinutes must be >= 0");
        category = category == null || category.isBlank() ? null : category.trim();
        tags = tags == null || tags.isBlank() ? null : tags.trim();
    }

    /** Whether {@code o} satisfies this rule's incident-matching filter. */
    public boolean matches(OperationalObject o) {
        return filter.matches(o);
    }

    /** JSON-ready view (stable key order) — backs the {@code /cases/rules} API. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("title", title);
        m.put("filter", filter.toMap());
        m.put("threshold", threshold);
        m.put("windowMinutes", windowMinutes);
        if (category != null) m.put("category", category);
        if (tags != null) m.put("tags", tags);
        m.put("createdAt", createdAt);
        return m;
    }

    /** Load a {@code *_caserule.toon} (a {@code case_rule { … }} block). */
    @SuppressWarnings("unchecked")
    public static CaseRule load(Path path) throws IOException {
        Map<String, Object> root = ConfigCodec.toMap(Files.readString(path));
        Object r = root.get("case_rule");
        if (!(r instanceof Map)) throw new IllegalArgumentException(path + " has no 'case_rule' block");
        return fromMap((Map<String, Object>) r);
    }

    /** Parse + validate from a decoded {@code case_rule { … }} map (filter fields nested or flattened). */
    @SuppressWarnings("unchecked")
    public static CaseRule fromMap(Map<String, Object> m) {
        if (m == null) throw new IllegalArgumentException("missing 'case_rule' block");
        Map<String, Object> f = m.get("filter") instanceof Map ? (Map<String, Object>) m.get("filter") : m;
        TagRule.Filter filter = new TagRule.Filter(str(f, "type"), str(f, "q"), str(f, "status"),
                str(f, "priority"), str(f, "severity"), str(f, "category"));
        return new CaseRule(str(m, "name"), str(m, "title"), filter,
                (int) longOr(m.get("threshold"), 2), longOr(m.get("windowMinutes"), 1440),
                str(m, "category"), str(m, "tags"), longOr(m.get("createdAt"), System.currentTimeMillis()));
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) return null;
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static long longOr(Object v, long def) {
        if (v == null) return def;
        try {
            return Long.parseLong(v.toString().trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}

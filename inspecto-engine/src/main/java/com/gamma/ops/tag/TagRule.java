package com.gamma.ops.tag;

import com.gamma.config.io.ConfigCodec;
import com.gamma.ops.ObjectService;
import com.gamma.ops.ObjectType;
import com.gamma.ops.OperationalObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A <strong>Tag Rule</strong> (GLOSSARY §9) — a saved search that applies a {@link Tag}, the Gmail-filter
 * metaphor: it auto-tags newly created objects that match its {@link Filter} (hooked into
 * {@link ObjectService#open}) and can be applied in bulk to every existing match
 * ({@code POST /tags/rules/{name}/apply}).
 *
 * <p>Authored as a {@code *_tagrule.toon} (a {@code tag_rule { … }} block) loaded at bootstrap, or saved at
 * runtime via {@code POST /tags/rules} (which persists the same file under the write root). A rule must set
 * at least one criterion — an unconstrained rule would tag everything.
 *
 * @since 5.0.0
 */
@com.gamma.api.PublicApi(since = "5.0.0")
public record TagRule(String name, String tag, Filter filter, long createdAt) {

    public TagRule {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("tag rule name is required");
        name = name.trim();
        if (tag == null || tag.isBlank()) throw new IllegalArgumentException("tag rule needs a 'tag' to apply");
        tag = tag.trim();
        if (tag.contains(",")) throw new IllegalArgumentException("tag names may not contain commas");
        if (filter == null || !filter.hasCriterion())
            throw new IllegalArgumentException("a tag rule needs at least one criterion (type/q/status/priority/severity/category)");
    }

    /** Whether this rule's filter matches {@code o}. */
    public boolean matches(OperationalObject o) {
        return filter.matches(o);
    }

    /** JSON-ready view (stable key order) — backs the {@code /tags/rules} API. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("tag", tag);
        m.put("filter", filter.toMap());
        m.put("createdAt", createdAt);
        return m;
    }

    /** Load a {@code *_tagrule.toon} (a {@code tag_rule { … }} block). */
    @SuppressWarnings("unchecked")
    public static TagRule load(Path path) throws IOException {
        Map<String, Object> root = ConfigCodec.toMap(Files.readString(path));
        Object r = root.get("tag_rule");
        if (!(r instanceof Map)) throw new IllegalArgumentException(path + " has no 'tag_rule' block");
        return fromMap((Map<String, Object>) r);
    }

    /** Parse + validate from a decoded {@code tag_rule { … }} map (filter fields nested or flattened). */
    @SuppressWarnings("unchecked")
    public static TagRule fromMap(Map<String, Object> m) {
        if (m == null) throw new IllegalArgumentException("missing 'tag_rule' block");
        Map<String, Object> f = m.get("filter") instanceof Map ? (Map<String, Object>) m.get("filter") : m;
        Filter filter = new Filter(str(f, "type"), str(f, "q"), str(f, "status"),
                str(f, "priority"), str(f, "severity"), str(f, "category"));
        long at = 0;
        Object created = m.get("createdAt");
        if (created != null) {
            try {
                at = Long.parseLong(created.toString().trim());
            } catch (NumberFormatException ignored) {
                // informational only
            }
        }
        return new TagRule(str(m, "name"), str(m, "tag"), filter, at > 0 ? at : System.currentTimeMillis());
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) return null;
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * The rule's criteria — every set field must match: {@code type}/{@code status}/{@code priority}/
     * {@code severity} exact (case-insensitive), {@code category} a path prefix (e.g. {@code "Pipeline"}
     * or {@code "Pipeline / Ingest"}), {@code q} a case-insensitive substring of title + description.
     * Incident statuses are compared on the mail lifecycle (GLOSSARY §9), tolerating the legacy
     * pre-rename names so config-overridden deployments keep matching.
     */
    public record Filter(String type, String q, String status, String priority, String severity, String category) {

        /** Validates {@code type} eagerly so a bad value rejects at authoring time, not at match time. */
        public Filter {
            if (type != null && !type.isBlank()) ObjectType.of(type.trim());
        }

        /** At least one criterion is set (an empty filter would match everything). */
        public boolean hasCriterion() {
            return notBlank(type) || notBlank(q) || notBlank(status) || notBlank(priority)
                    || notBlank(severity) || notBlank(category);
        }

        /** Whether {@code o} satisfies every set criterion. */
        public boolean matches(OperationalObject o) {
            if (notBlank(type) && o.objectType() != ObjectType.of(type.trim())) return false;
            if (notBlank(status) && !normalizedStatus(o).equalsIgnoreCase(normalizeIncident(o, status))) return false;
            if (notBlank(priority) && !priority.trim().equalsIgnoreCase(nullToEmpty(o.priority()))) return false;
            if (notBlank(severity) && !severity.trim().equalsIgnoreCase(nullToEmpty(o.severity()))) return false;
            if (notBlank(category) && !nullToEmpty(o.attributes().get("category")).startsWith(category.trim())) return false;
            if (notBlank(q) && !(o.title() + " " + o.description()).toLowerCase(Locale.ROOT)
                    .contains(q.trim().toLowerCase(Locale.ROOT))) return false;
            return true;
        }

        /** JSON-ready view of the set criteria only. */
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            if (notBlank(type)) m.put("type", type);
            if (notBlank(q)) m.put("q", q);
            if (notBlank(status)) m.put("status", status);
            if (notBlank(priority)) m.put("priority", priority);
            if (notBlank(severity)) m.put("severity", severity);
            if (notBlank(category)) m.put("category", category);
            return m;
        }

        private String normalizedStatus(OperationalObject o) {
            return normalizeIncident(o, o.status());
        }

        /** Fold the legacy incident lifecycle names onto the mail lifecycle for comparison. */
        private static String normalizeIncident(OperationalObject o, String status) {
            String s = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
            if (o.objectType() != ObjectType.INCIDENT) return s;
            return switch (s) {
                case "OPEN" -> "IDENTIFIED";
                case "ASSIGNED", "IN_PROGRESS" -> "DIAGNOSING";
                case "CLOSED" -> "ARCHIVED";
                default -> s;
            };
        }

        private static boolean notBlank(String s) {
            return s != null && !s.isBlank();
        }

        private static String nullToEmpty(String s) {
            return s == null ? "" : s.trim();
        }
    }
}

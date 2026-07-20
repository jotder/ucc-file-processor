package com.gamma.intelligence.action;

import com.eoiagent.core.ToolCall;
import com.eoiagent.core.ToolResult;
import com.gamma.config.safety.ConfigSafetyValidator;
import com.gamma.config.safety.SafetyPolicy;
import com.gamma.config.spec.Finding;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The execution + preview logic for the two P3 component <em>act</em> tools (autonomy L2), shared by
 * the tool bodies in {@code InspectoTools} and the dry-run previewer in {@link AgentApprovals}:
 *
 * <ul>
 *   <li>{@code component_apply} — promote a validated draft into the live registry (DRAFT→ACTIVE):
 *       hard-refuse anything the safety gate rejects, then {@code PUT} (existing, {@code If-Match}) or
 *       {@code POST} (new) through {@link ControlPlaneClient} so the write is the same audited,
 *       gated control-plane contract a human uses.</li>
 *   <li>{@code component_rollback} — restore an archived version via the existing
 *       {@code POST /components/{type}/{id}/versions/{v}/restore} route.</li>
 * </ul>
 *
 * <p>The {@link #preview} the operator reviews is read-only (a top-level diff + safety findings),
 * computed straight off the {@link ComponentStore} — never the mutating path.
 */
public final class ComponentActions {

    public static final String TOOL_COMPONENT_APPLY = "component_apply";
    public static final String TOOL_COMPONENT_ROLLBACK = "component_rollback";

    private ComponentActions() {
    }

    // --- execution (post-approval, via the audited control plane) ---------------------------------

    /** {@code component_apply}: validate then write the draft as the live component, attributed to {@code session}. */
    public static ToolResult apply(ControlPlaneClient client, ToolCall call, String session) {
        String type = str(call, "type");
        String id = str(call, "id");
        Map<String, Object> config = mapArg(call, "config");
        if (type == null || type.isBlank()) return error("type is required");
        if (id == null || id.isBlank()) return error("id is required");
        if (config == null) return error("config is required and must be an object");

        List<Finding> unsafe = ConfigSafetyValidator.check(configType(type), config, SafetyPolicy.defaultPolicy());
        if (!unsafe.isEmpty()) {
            return error("refused: config does not clear the safety gate — " + summarize(unsafe)
                    + " (fix via component_draft, then re-apply)");
        }

        ControlPlaneClient.Response current = client.exchange("GET", "/components/" + type + "/" + id, null, null, session);
        if (current.status() < 0) return error(current.raw());

        ControlPlaneClient.Response wrote;
        boolean created;
        if (current.status() == 200) {
            created = false;
            wrote = client.exchange("PUT", "/components/" + type + "/" + id, config, current.etag(), session);
        } else if (current.status() == 404) {
            created = true;
            Map<String, Object> body = new LinkedHashMap<>(config);
            body.put("id", id); // createComponent reads the id from the body
            wrote = client.exchange("POST", "/components/" + type, body, null, session);
        } else {
            return error("could not read current " + type + "/" + id + ": status " + current.status());
        }
        if (!wrote.ok()) return error("apply failed: status " + wrote.status() + " — " + wrote.raw());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("applied", true);
        result.put("type", type);
        result.put("id", id);
        result.put("created", created);
        result.put("etag", wrote.etag());
        result.put("actor", "agent:" + session);
        return ok(result);
    }

    /** {@code component_rollback}: restore an archived version, attributed to {@code session}. */
    public static ToolResult rollback(ControlPlaneClient client, ToolCall call, String session) {
        String type = str(call, "type");
        String id = str(call, "id");
        Integer version = intArg(call, "version");
        if (type == null || type.isBlank()) return error("type is required");
        if (id == null || id.isBlank()) return error("id is required");
        if (version == null) return error("version is required and must be an integer");

        ControlPlaneClient.Response r = client.exchange("POST",
                "/components/" + type + "/" + id + "/versions/" + version + "/restore", null, null, session);
        if (r.status() < 0) return error(r.raw());
        if (!r.ok()) return error("rollback failed: status " + r.status() + " — " + r.raw());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("restored", true);
        result.put("type", type);
        result.put("id", id);
        result.put("version", version);
        result.put("etag", r.etag());
        result.put("actor", "agent:" + session);
        return ok(result);
    }

    // --- preview (read-only, shown to the operator before approval) -------------------------------

    /** The dry-run diff/effects the operator reviews. Read-only; empty note when the registry is absent. */
    public static Map<String, Object> preview(ComponentStore components, ToolCall call) {
        if (components == null) return Map.of("note", "component registry unavailable (no -Dassist.write.root)");
        return switch (call.toolName()) {
            case TOOL_COMPONENT_APPLY -> previewApply(components, call);
            case TOOL_COMPONENT_ROLLBACK -> previewRollback(components, call);
            default -> Map.of();
        };
    }

    private static Map<String, Object> previewApply(ComponentStore components, ToolCall call) {
        String type = str(call, "type");
        String id = str(call, "id");
        Map<String, Object> config = mapArg(call, "config");
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("action", "apply");
        p.put("target", type + "/" + id);
        if (type == null || id == null || config == null) {
            p.put("error", "type, id and config are required");
            return p;
        }
        Optional<Map<String, Object>> current = currentContent(components, type, id);
        p.put("willCreate", current.isEmpty());
        p.put("diff", diff(current.orElse(Map.of()), config));
        List<Finding> unsafe = ConfigSafetyValidator.check(configType(type), config, SafetyPolicy.defaultPolicy());
        p.put("safe", unsafe.isEmpty());
        if (!unsafe.isEmpty()) p.put("safetyFindings", unsafe.stream().map(ComponentActions::findingMap).toList());
        return p;
    }

    private static Map<String, Object> previewRollback(ComponentStore components, ToolCall call) {
        String type = str(call, "type");
        String id = str(call, "id");
        Integer version = intArg(call, "version");
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("action", "rollback");
        p.put("target", type + "/" + id);
        p.put("version", version);
        if (type == null || id == null || version == null) {
            p.put("error", "type, id and version are required");
            return p;
        }
        Optional<Map<String, Object>> current = currentContent(components, type, id);
        Map<String, Object> target;
        try {
            target = components.versionContent(type, id, version).orElse(null);
        } catch (RuntimeException e) {
            p.put("error", e.getMessage());
            return p;
        }
        if (target == null) {
            p.put("error", "no version " + version + " of " + type + "/" + id);
            return p;
        }
        p.put("diff", diff(current.orElse(Map.of()), target));
        return p;
    }

    private static Optional<Map<String, Object>> currentContent(ComponentStore components, String type, String id) {
        try {
            return components.get(type, id).map(ComponentRegistry.Component::content);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    // --- helpers ----------------------------------------------------------------------------------

    /** A shallow, top-level structural diff of two config maps — enough for a human to review an apply. */
    private static Map<String, Object> diff(Map<String, Object> from, Map<String, Object> to) {
        Set<String> keys = new TreeSet<>();
        keys.addAll(from.keySet());
        keys.addAll(to.keySet());
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<Map<String, Object>> changed = new ArrayList<>();
        for (String k : keys) {
            boolean inFrom = from.containsKey(k);
            boolean inTo = to.containsKey(k);
            if (!inFrom) {
                added.add(k);
            } else if (!inTo) {
                removed.add(k);
            } else if (!java.util.Objects.equals(from.get(k), to.get(k))) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("field", k);
                c.put("from", from.get(k));
                c.put("to", to.get(k));
                changed.add(c);
            }
        }
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("added", added);
        d.put("removed", removed);
        d.put("changed", changed);
        return d;
    }

    private static String summarize(List<Finding> findings) {
        return findings.stream().map(f -> f.fieldPath() + ": " + f.message())
                .limit(3).reduce((a, b) -> a + "; " + b).orElse("(no detail)");
    }

    private static Map<String, Object> findingMap(Finding f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("severity", f.severity().name());
        m.put("fieldPath", f.fieldPath());
        m.put("message", f.message());
        return m;
    }

    /** Map a component kind to its {@code ConfigSpecs} config type ({@code alert-rule}→{@code alert}). */
    private static String configType(String kind) {
        String k = kind.trim().toLowerCase(Locale.ROOT);
        return k.equals("alert-rule") ? "alert" : k;
    }

    private static String str(ToolCall call, String key) {
        Object v = args(call).get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static Integer intArg(ToolCall call, String key) {
        Object v = args(call).get(key);
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapArg(ToolCall call, String key) {
        Object v = args(call).get(key);
        return v instanceof Map<?, ?> ? (Map<String, Object>) v : null;
    }

    private static Map<String, Object> args(ToolCall call) {
        return call.arguments() == null ? Map.of() : call.arguments();
    }

    private static ToolResult ok(Object value) {
        return new ToolResult(true, value, null, Map.of());
    }

    private static ToolResult error(String message) {
        return new ToolResult(false, null, message, Map.of());
    }
}

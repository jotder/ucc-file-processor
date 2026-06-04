package com.gamma.agent.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.agentkernel.model.ModelProvider;
import com.gamma.agentkernel.model.ModelRequest;
import com.gamma.agentkernel.model.ModelTier;
import com.gamma.assist.AssistRequest;
import com.gamma.assist.AssistResult;
import com.gamma.assist.AssistResult.Citation;
import com.gamma.catalog.MetadataGraphService;
import com.gamma.catalog.MetadataNode;
import com.gamma.catalog.NodeKind;
import com.gamma.config.io.ConfigCodec;
import com.gamma.config.spec.Finding;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The fifth and final MVP assist slice (v3.7.0, M7, C1): {@code diagnose-and-alert}. This class
 * serves the request/response half — <b>NL → alert rule</b> ("warn when error rate exceeds 5% on
 * EVENTS") → a validated alert-rule draft the operator saves as a {@code *_alert.toon}. The
 * event-driven half (auto-diagnosing FAILED batches) is the {@link com.gamma.agent.diagnose.FailureReactor},
 * wired separately in {@code UccAssistAgent}; both share the M7 design.
 *
 * <h3>Generate → validate → repair</h3>
 * The model emits a small JSON object; the skill runs it through the deterministic
 * {@link AlertRuleValidator} (shape + numeric bounds + catalog-grounded {@code onPipeline}) via a
 * {@link RepairLoop}, re-prompting with the verbatim findings so a structurally-wrong local-model
 * answer becomes an invisible retry rather than a surfaced failure. It guards <em>shape</em>, not
 * <em>intent</em> — so the skill surfaces a {@code humanReadable} description for the operator to
 * confirm the rule means what they intended.
 *
 * <p><b>Draft-only</b> (V-9): {@code applyVia} is always {@code null}; nothing in the engine executes
 * the rule yet, the agent holds no write token, and the draft {@code .toon} is the user's to save.
 *
 * @since 3.7.0
 */
public final class DiagnoseAndAlertSkill implements Skill {

    public static final String ID = "diagnose-and-alert";
    private static final int MAX_REPAIR_ROUNDS = 3;

    private static final String SYSTEM = """
            You translate a natural-language alerting request into an alert rule for the UCC File
            Processor. Reply with ONLY a JSON object (no prose, no markdown) with these fields:
              "name":        a short kebab-case rule name derived from the request
              "metric":      one of: error_rate | failed_batches | rejected_files | duration_ms
              "comparator":  one of: gt | gte | lt | lte
              "threshold":   a positive number; for error_rate use a fraction in (0,1] (5% -> 0.05)
              "window":      a duration like 1h, 30m, 1d, or a batch count like 20b
              "severity":    one of: INFO | WARNING | CRITICAL
              "on_pipeline": the pipeline this rule watches, or null for all pipelines
            Use on_pipeline ONLY when the request names a pipeline, and ONLY a name from the KNOWN
            PIPELINES list; otherwise set it to null.""";

    private final ObjectMapper json = new ObjectMapper();

    @Override public String id() { return ID; }

    /** Root-cause/alert reasoning routes to MEDIUM (7B); hosted-recommended in connected mode. */
    @Override public ModelTier tier() { return ModelTier.MEDIUM; }

    @Override
    public AssistResult run(AssistRequest request, AssistContext ctx) {
        String userText = (request.userText() == null) ? "" : request.userText().trim();
        if (userText.isEmpty()) {
            return AssistResult.unavailable(ID,
                    "diagnose-and-alert needs a natural-language request in 'userText', "
                            + "e.g. \"warn when the error rate exceeds 5% on EVENTS\"");
        }

        ModelProvider model = ctx.models().providerFor(tier());
        if (!model.available()) {
            return AssistResult.unavailable(ID,
                    "the assist model (tier " + tier() + ") is not available — enable the assist "
                            + "layer and a local Ollama endpoint to use diagnose-and-alert");
        }

        // ── grounding: known pipeline names (so onPipeline resolves to a real SOURCE node) ──
        Map<String, String> pipelineIdByName = knownPipelines(ctx.catalog());
        String knownList = pipelineIdByName.isEmpty()
                ? "(none)" : String.join(", ", pipelineIdByName.keySet());
        String basePrompt = "REQUEST: " + userText
                + "\n\nKNOWN PIPELINES (use an exact name for on_pipeline, else null): " + knownList;

        // ── generate → validate → repair ──
        RepairLoop.Result<Draft> result = RepairLoop.run(MAX_REPAIR_ROUNDS,
                feedback -> {
                    String prompt = (feedback == null) ? basePrompt : basePrompt + "\n\n" + feedback;
                    return model.generate(ModelRequest.json(tier(), SYSTEM, prompt)).text();
                },
                raw -> parseAndValidate(raw, pipelineIdByName.keySet()));

        if (!result.ok()) {
            String last = result.errors().isEmpty() ? "unknown error"
                    : result.errors().get(result.errors().size() - 1);
            return AssistResult.unavailable(ID,
                    "could not produce a valid alert rule after " + result.rounds()
                            + " attempt(s): " + last);
        }

        // ── build the validated draft payload ──
        Draft d = result.value();
        String humanReadable = describe(d.rule);
        String draftToon = ConfigCodec.toToon(Map.of("alert", d.rule));

        List<Citation> citations = new ArrayList<>();
        List<String> links = new ArrayList<>();
        Object onPipeline = d.rule.get("onPipeline");
        if (onPipeline != null && pipelineIdByName.containsKey(onPipeline.toString())) {
            String pid = pipelineIdByName.get(onPipeline.toString());
            citations.add(new Citation("catalog", pid));        // derived, not parsed from the model
            links.add("/catalog/tables/" + pid);
        }
        citations.add(new Citation("oracle", "alert-rule"));    // the rule shape was validated

        Map<String, Object> data = new LinkedHashMap<>(d.rule);
        data.put("humanReadable", humanReadable);
        data.put("draftToon", draftToon);
        data.put("repaired", result.repaired());
        data.put("findings", List.of());                        // clean by construction (oracle passed)

        String answer = humanReadable
                + ". Review the draft below and save it as a *_alert.toon to enable it.";
        return AssistResult.draft(ID, answer, citations, links, data);
    }

    // ── oracle: parse + validate the model's JSON into an alert-rule draft ─────────────

    /** The validated rule fields (insertion-ordered for a stable {@code .toon}). */
    private record Draft(Map<String, Object> rule) {}

    private Draft parseAndValidate(String raw, Set<String> knownPipelines) throws Exception {
        JsonNode root = json.readTree(raw);

        Map<String, Object> rule = new LinkedHashMap<>();
        putIf(rule, "name", text(root, "name"));
        putIf(rule, "metric", lower(text(root, "metric")));
        putIf(rule, "comparator", lower(text(root, "comparator")));
        Double threshold = number(root.get("threshold"));
        if (threshold != null) rule.put("threshold", threshold);
        putIf(rule, "window", text(root, "window"));
        putIf(rule, "severity", upper(text(root, "severity")));
        String onPipeline = text(root, "on_pipeline");
        if (onPipeline != null) rule.put("onPipeline", onPipeline);

        List<Finding> findings = AlertRuleValidator.check(rule, knownPipelines);
        if (!findings.isEmpty()) {
            throw new IllegalArgumentException("alert rule violation(s): " + findings);
        }
        return new Draft(rule);
    }

    /** A one-line human-readable summary of the validated rule. */
    static String describe(Map<String, Object> r) {
        String op = switch (String.valueOf(r.get("comparator"))) {
            case "gt" -> "exceeds";
            case "gte" -> "is at least";
            case "lt" -> "drops below";
            case "lte" -> "is at most";
            default -> String.valueOf(r.get("comparator"));
        };
        String scope = r.containsKey("onPipeline") ? " on '" + r.get("onPipeline") + "'" : " across all pipelines";
        return String.valueOf(r.get("severity")) + " alert '" + r.get("name") + "': when "
                + r.get("metric") + " " + op + " " + r.get("threshold")
                + " over " + r.get("window") + scope;
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────

    private static Map<String, String> knownPipelines(MetadataGraphService catalog) {
        Map<String, String> byName = new LinkedHashMap<>();
        if (catalog == null) return byName;
        for (MetadataNode s : catalog.nodesOfKind(NodeKind.SOURCE)) byName.put(s.label(), s.id());
        return byName;
    }

    private static void putIf(Map<String, Object> m, String k, String v) {
        if (v != null) m.put(k, v);
    }

    private static String text(JsonNode root, String field) {
        JsonNode v = root.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText();
        return (s == null || s.isBlank() || "null".equalsIgnoreCase(s)) ? null : s.trim();
    }

    private static String lower(String s) { return s == null ? null : s.toLowerCase(); }
    private static String upper(String s) { return s == null ? null : s.toUpperCase(); }

    private static Double number(JsonNode v) {
        if (v == null || v.isNull()) return null;
        if (v.isNumber()) return v.doubleValue();
        try { return Double.parseDouble(v.asText().trim()); } catch (Exception e) { return null; }
    }

    /** Unused; kept for symmetry with other skills' Set helpers. */
    @SuppressWarnings("unused")
    private static Set<String> asSet(String... v) { return new LinkedHashSet<>(List.of(v)); }
}

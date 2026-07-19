package com.gamma.intelligence.pack;

import com.eoiagent.core.RunId;
import com.eoiagent.core.ToolCall;
import com.eoiagent.core.ToolResult;
import com.eoiagent.memory.ChatMessageRecord;
import com.eoiagent.memory.ChatRole;
import com.eoiagent.model.ChatOptions;
import com.eoiagent.model.ChatRequest;
import com.eoiagent.model.ChatResult;
import com.eoiagent.model.LlmGateway;
import com.eoiagent.tool.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.intelligence.investigation.Case;
import com.gamma.intelligence.investigation.Incident;
import com.gamma.pipeline.ComponentStore;
import com.gamma.service.CollectorService;
import com.gamma.util.BrowsableStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Runs a playbook investigation (AGT-5 P1 slice C). The deterministic tools compute the evidence,
 * the model judges it (D6): the playbook gathers a fixed-order evidence bundle by invoking the
 * analysis tools ({@code timeline_build} → {@code config_versions_diff} → {@code diff_batches} →
 * {@code anomaly_scan}, each best-effort), then asks the model ONCE to rank hypotheses and draft a
 * fix, producing a {@link Case}.
 *
 * <p>Single-shot synthesis (not model-driven ReAct) is deliberate for P1: the eoiagent session path
 * is hardwired to {@code GoalKind.QA} until the upstream {@code INVESTIGATION} seam (slice B) lands,
 * and a fixed recipe is fully deterministic under a stub gateway. When B lands, the synthesis step
 * can widen to model-driven tool selection without changing this class's inputs or the Case shape.
 */
public final class Investigator {

    private static final Logger log = LoggerFactory.getLogger(Investigator.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int DEFAULT_SINCE_MINUTES = 1440;

    private final List<Tool> tools;
    private final ComponentStore components;
    private final LlmGateway gateway;

    public Investigator(CollectorService service, ComponentStore components,
                        Supplier<List<BrowsableStore>> browseStores, LlmGateway gateway) {
        this.tools = InspectoTools.tools(service, components, browseStores);
        this.components = components;
        this.gateway = gateway;
    }

    /** Root-cause investigation → a ranked-hypotheses {@link Case} with a fix draft (when warranted). */
    public Case investigate(Incident incident) {
        Map<String, Object> p = incident.params();
        Map<String, Object> evidence = new LinkedHashMap<>();

        // `focus` narrows the whole timeline; it is distinct from focusType/focusId (which select the
        // component for the config diff) — don't conflate them, or the trigger signal drops out.
        Map<String, Object> tl = invoke("timeline_build",
                args("sinceMinutes", p.getOrDefault("sinceMinutes", DEFAULT_SINCE_MINUTES), "focus", p.get("focus")));
        List<Map<String, Object>> timeline = tl == null ? List.of() : asList(tl.get("timeline"));
        if (tl != null) evidence.put("timeline", tl.get("timeline"));

        if (has(p, "focusType") && has(p, "focusId")) {
            Map<String, Object> cd = invoke("config_versions_diff",
                    args("type", p.get("focusType"), "id", p.get("focusId")));
            if (cd != null) evidence.put("configChanges", cd);
        }
        if (has(p, "pipeline") && has(p, "batchA") && has(p, "batchB")) {
            Map<String, Object> bd = invoke("diff_batches",
                    args("pipeline", p.get("pipeline"), "batchA", p.get("batchA"), "batchB", p.get("batchB")));
            if (bd != null) evidence.put("batchDiff", bd);
        }
        if (has(p, "table") && has(p, "column")) {
            Map<String, Object> an = invoke("anomaly_scan",
                    args("table", p.get("table"), "column", p.get("column")));
            if (an != null) evidence.put("anomaly", an);
        }

        String answer = synthesize(Playbooks.load(Playbooks.ROOT_CAUSE_ANALYSIS), incident, evidence);
        Synthesis s = parse(answer);
        List<String> fixRefs = writeFixDraft(s.fixDraft);
        return new Case(UUID.randomUUID().toString(), incident.incidentRef(), incident.triggerSignal(),
                timeline, s.hypotheses, s.outcome, fixRefs, Instant.now());
    }

    /** Impact / blast-radius investigation over the same synthesis path (timeline-grounded, P1 scope). */
    public Case impact(Incident incident) {
        Map<String, Object> p = incident.params();
        Map<String, Object> evidence = new LinkedHashMap<>();
        Map<String, Object> tl = invoke("timeline_build",
                args("sinceMinutes", p.getOrDefault("sinceMinutes", DEFAULT_SINCE_MINUTES), "focus", p.get("focus")));
        List<Map<String, Object>> timeline = tl == null ? List.of() : asList(tl.get("timeline"));
        if (tl != null) evidence.put("timeline", tl.get("timeline"));

        String answer = synthesize(Playbooks.load(Playbooks.IMPACT_ANALYSIS), incident, evidence);
        Synthesis s = parse(answer);
        return new Case(UUID.randomUUID().toString(), incident.incidentRef(), incident.triggerSignal(),
                timeline, s.hypotheses, s.outcome, List.of(), Instant.now());
    }

    // ── synthesis ──────────────────────────────────────────────────────────────

    private String synthesize(String systemPrompt, Incident incident, Map<String, Object> evidence) {
        String user = "INCIDENT: " + incident.incidentRef() + "\nTRIGGER: " + toJson(incident.triggerSignal())
                + "\nEVIDENCE:\n" + toJson(evidence);
        ChatRequest req = new ChatRequest(List.of(
                new ChatMessageRecord(ChatRole.SYSTEM, systemPrompt, Instant.now(), Map.of()),
                new ChatMessageRecord(ChatRole.USER, user, Instant.now(), Map.of())),
                List.of(), ChatOptions.defaults());
        ChatResult r = gateway.chat(req);
        return r.text() == null ? "" : r.text();
    }

    /** The parsed synthesis: ranked hypotheses, a one-line outcome, and an optional fix draft. */
    private record Synthesis(List<Map<String, Object>> hypotheses, String outcome, Map<String, Object> fixDraft) {}

    /** Parse the model's JSON answer; fail-closed to an inconclusive, empty Synthesis. */
    private static Synthesis parse(String answer) {
        String json = extractJsonObject(answer);
        if (json == null) return new Synthesis(List.of(), "inconclusive (no structured answer)", null);
        try {
            JsonNode root = JSON.readTree(json);
            List<Map<String, Object>> hyps = new ArrayList<>();
            if (root.has("hypotheses") && root.get("hypotheses").isArray()) {
                for (JsonNode h : root.get("hypotheses")) {
                    hyps.add(JSON.convertValue(h, MAP_TYPE));
                }
            }
            String outcome = root.hasNonNull("outcome") ? root.get("outcome").asText() : "inconclusive";
            Map<String, Object> fix = root.hasNonNull("fixDraft") && root.get("fixDraft").isObject()
                    ? JSON.convertValue(root.get("fixDraft"), MAP_TYPE) : null;
            return new Synthesis(hyps, outcome, fix);
        } catch (com.fasterxml.jackson.core.JsonProcessingException | RuntimeException e) {
            log.warn("Unparseable RCA synthesis, filing inconclusive Case: {}", e.getMessage());
            return new Synthesis(List.of(), "inconclusive (unparseable answer)", null);
        }
    }

    /** Persist a fix draft as a DRAFT component (D7, L1 — draft, never apply); empty when unpersistable. */
    private List<String> writeFixDraft(Map<String, Object> fixDraft) {
        if (fixDraft == null) return List.of();
        Object kind = fixDraft.get("kind");
        Object id = fixDraft.get("id");
        if (!(kind instanceof String k) || k.isBlank() || !(id instanceof String i) || i.isBlank()) return List.of();
        if (components == null) {
            log.info("Fix draft for {}/{} not persisted (no component write root configured)", k, i);
            return List.of();
        }
        Map<String, Object> content = new LinkedHashMap<>();
        Object cfg = fixDraft.get("config");
        if (cfg instanceof Map<?, ?> m) m.forEach((ck, cv) -> content.put(String.valueOf(ck), cv));
        content.put("status", "draft");        // L1: draft, never applied by the agent
        content.put("authoredBy", "agent:rca"); // actor audit stamped into the component itself
        try {
            components.write(k, i, content);
            return List.of("component:" + k + "/" + i);
        } catch (IOException | IllegalArgumentException e) {
            log.warn("Failed to persist fix draft {}/{}: {}", k, i, e.getMessage());
            return List.of();
        }
    }

    // ── tool invocation + helpers ────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> invoke(String name, Map<String, Object> args) {
        Tool tool = tools.stream().filter(t -> t.spec().name().equals(name)).findFirst().orElse(null);
        if (tool == null) return null;
        ToolResult r = tool.invoke(new ToolCall(name, args, new RunId("rca-" + UUID.randomUUID())));
        if (!r.ok()) {
            log.debug("RCA evidence tool '{}' returned no data: {}", name, r.error());
            return null;
        }
        return r.value() instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    /** Build a tool-args map, dropping null values (so an absent optional arg is simply omitted). */
    private static Map<String, Object> args(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int idx = 0; idx < kv.length; idx += 2) {
            if (kv[idx + 1] != null) m.put((String) kv[idx], kv[idx + 1]);
        }
        return m;
    }

    private static boolean has(Map<String, Object> p, String key) {
        Object v = p.get(key);
        return v != null && !String.valueOf(v).isBlank();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asList(Object v) {
        return v instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
    }

    private static String toJson(Object v) {
        try {
            return JSON.writeValueAsString(v);
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            return String.valueOf(v);
        }
    }

    /** Extract the first balanced {@code { … }} block from a model answer (tolerates ```json fences/prose). */
    private static String extractJsonObject(String answer) {
        if (answer == null) return null;
        int start = answer.indexOf('{');
        if (start < 0) return null;
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int idx = start; idx < answer.length(); idx++) {
            char c = answer.charAt(idx);
            if (inString) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') inString = false;
            } else if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) return answer.substring(start, idx + 1);
            }
        }
        return null;
    }

    private static final com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>> MAP_TYPE =
            new com.fasterxml.jackson.core.type.TypeReference<>() {};
}

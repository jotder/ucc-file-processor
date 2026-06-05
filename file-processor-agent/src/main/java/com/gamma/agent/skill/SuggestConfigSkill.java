package com.gamma.agent.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.agentkernel.agent.AgentContext;
import com.gamma.agentkernel.agent.AgentRequest;
import com.gamma.agentkernel.agent.AgentResult;
import com.gamma.agentkernel.agent.Capability;
import com.gamma.agentkernel.agent.CapabilitySpec;
import com.gamma.agentkernel.error.AgentError;
import com.gamma.agentkernel.model.ModelProvider;
import com.gamma.agentkernel.model.ModelRequest;
import com.gamma.agentkernel.model.ModelTier;
import com.gamma.agentkernel.reason.RepairLoop;
import com.gamma.agentkernel.tool.CredibilityTier;
import com.gamma.agentkernel.tool.Evidence;
import com.gamma.catalog.MetadataGraphService;
import com.gamma.catalog.MetadataNode;
import com.gamma.catalog.NodeKind;
import com.gamma.config.io.ConfigCodec;
import com.gamma.config.io.ConfigLoader;
import com.gamma.config.safety.ConfigSafetyValidator;
import com.gamma.config.safety.SafetyPolicy;
import com.gamma.config.spec.ConfigSpec;
import com.gamma.config.spec.ConfigSpecs;
import com.gamma.config.spec.FieldSpec;
import com.gamma.config.spec.Finding;
import com.gamma.config.spec.RawConfig;
import com.gamma.config.spec.Severity;
import com.gamma.enrich.EnrichmentConfig;
import com.gamma.job.JobConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The third assist slice (v3.5.0, A3): {@code suggest-config} — given a source sample and a partial
 * config, the agent suggests field values with rationale and returns a <b>validated config draft</b>
 * the user reviews and saves. The "30-field config form + docs hunt" replacement.
 *
 * <h3>Generate → validate → repair, behind a hard safety gate</h3>
 * The model proposes {@code {fields:[{name,value,rationale,confidence}]}}; the skill merges those onto
 * the partial config and runs the candidate through a {@link RepairLoop} whose oracle is the
 * declarative {@link ConfigSpecs} (structural validity) <em>and</em> the hard-fail
 * {@link ConfigSafetyValidator} (path jail / numeric bounds / output allow-list — security guardrail
 * R6). A draft that would write outside the workspace, oversubscribe the box, or target an unknown
 * sink is rejected and the verbatim reason fed back — it can never be surfaced to the user. Structural
 * validity is not semantic correctness, so each field carries {@code rationale} + {@code confidence}
 * for the human to confirm (the model can invent a plausible-but-wrong delimiter that still parses).
 *
 * <h3>Safety & scope</h3>
 * Draft-only (V-9): {@code applyVia} is always {@code null}; the user saves the returned {@code .toon}.
 * All config types are supported generically via {@link ConfigSpecs#forType}; the safety gate's
 * path/numeric/output rules apply to the path-bearing types (pipeline, enrichment).
 *
 * @since 3.5.0
 */
public final class SuggestConfigSkill implements Capability {

    public static final String ID = "suggest-config";

    private static final CapabilitySpec SPEC = new CapabilitySpec(ID, 1,
            "Suggest config field values with rationale and return a validated, safety-checked draft.",
            ModelTier.MEDIUM, 0.5, java.time.Duration.ofSeconds(60),
            java.util.Set.of(), java.util.Set.of());

    private static final int MAX_REPAIR_ROUNDS = 3;

    private static final ConfigLoader LOADER = ConfigLoader.filesystem(); // pure validate(spec, map)

    private static final String SYSTEM = """
            You complete configuration for the UCC File Processor. Given the config type, a sample of
            the source data, the fields already filled in, and the field catalog, suggest values for
            the MISSING or improvable fields. Reply with ONLY a JSON object (no prose, no markdown):
              {"fields":[{"name":"<dotted.field.path>","value":<value>,
                          "rationale":"<why>","confidence":"high|medium|low"}]}
            Use the exact dotted field paths from the FIELD CATALOG. Suggest filesystem paths only
            under the existing directories implied by the partial config; never invent absolute system
            paths. Prefer the simplest values that fit the sample.""";

    private final ObjectMapper json = new ObjectMapper();
    private final SafetyPolicy policy;

    public SuggestConfigSkill() {
        this(SafetyPolicy.defaultPolicy());
    }

    SuggestConfigSkill(SafetyPolicy policy) {
        this.policy = policy;
    }

    @Override public CapabilitySpec spec() { return SPEC; }

    @Override
    public AgentResult run(AgentRequest request, AgentContext context) throws AgentError {
        UccAgentContext ctx = (UccAgentContext) context;
        ModelTier tier = ctx.effectiveTier(SPEC.defaultTier());
        String type = firstNonBlank(request.context("configType"),
                str(request.partialInput().get("configType")));
        if (type == null) {
            return AgentResult.unavailable(ID,
                    "suggest-config needs a 'configType' (pipeline|enrichment|job|schema|meta) in screenContext");
        }
        type = type.toLowerCase();
        ConfigSpec spec = ConfigSpecs.forType(type);
        if (spec == null) {
            return AgentResult.unavailable(ID, "unknown config type '" + type + "'");
        }

        ModelProvider model = ctx.models().providerFor(tier);
        if (!model.available()) {
            return AgentResult.unavailable(ID,
                    "the assist model (tier " + tier + ") is not available — enable the assist "
                            + "layer and a local Ollama endpoint to use suggest-config");
        }

        String sourceSample = request.context("sourceSample");
        Map<String, Object> partial = deepCopy(request.partialInput());
        partial.remove("configType");   // a control key, not part of the config
        partial.remove("sourceSample");

        Map<String, String> tableIdByName = knownTables(ctx.catalog());

        String basePrompt = "CONFIG TYPE: " + type
                + "\n\nFIELD CATALOG (use these exact dotted paths):\n" + fieldCatalog(spec)
                + "\n\nPARTIAL CONFIG (already filled in):\n" + ConfigCodec.toToon(wrapForDisplay(partial))
                + (sourceSample == null ? "" : "\n\nSOURCE SAMPLE:\n" + sourceSample)
                + (tableIdByName.isEmpty() ? "" : "\n\nKNOWN TABLES: " + String.join(", ", tableIdByName.keySet()));

        final String t = type;
        final Map<String, Object> base = partial;
        RepairLoop.Result<Draft> result = RepairLoop.run(MAX_REPAIR_ROUNDS,
                feedback -> {
                    String prompt = (feedback == null) ? basePrompt : basePrompt + "\n\n" + feedback;
                    return model.generate(ModelRequest.json(tier, SYSTEM, prompt)).text();
                },
                raw -> parseAndValidate(raw, t, spec, base));

        if (!result.ok()) {
            String last = result.errors().isEmpty() ? "unknown error"
                    : result.errors().get(result.errors().size() - 1);
            return AgentResult.unavailable(ID,
                    "could not produce a safe, valid config after " + result.rounds()
                            + " attempt(s): " + last);
        }

        Draft d = result.value();
        String draftToon = ConfigCodec.toToon(d.configMap);

        List<Evidence> evidence = new ArrayList<>();
        List<String> links = new ArrayList<>();
        // Grounded evidence: any known table the draft actually references (derived, not parsed).
        for (Map.Entry<String, String> e : tableIdByName.entrySet()) {
            if (referencesName(d.configMap, e.getKey())) {
                evidence.add(new Evidence(e.getValue(), CredibilityTier.AUTHORITATIVE, "catalog", e.getValue(), 1.0, null));
                links.add("/catalog/tables/" + e.getValue());
            }
        }
        // what validated it
        evidence.add(new Evidence("config-spec+safety:" + type, CredibilityTier.DERIVED, "oracle",
                "config-spec+safety:" + type, 1.0, null));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("configType", type);
        data.put("fields", d.fields);
        data.put("validated", true);
        data.put("safetyChecked", true);
        data.put("draftToon", draftToon);
        data.put("repaired", result.repaired());
        data.put("findings", d.findings.stream()
                .map(f -> Map.<String, Object>of(
                        "severity", String.valueOf(f.severity()),
                        "field", String.valueOf(f.fieldPath()),
                        "message", String.valueOf(f.message())))
                .toList());

        String answer = "Suggested " + d.fields.size() + " field(s) for the " + type
                + " config; the draft passed structural validation and the safety gate. "
                + "Review the rationale/confidence per field, then save the draft below.";

        return AgentResult.draft(ID, SPEC.version(), answer, evidence, links, null, 1.0, tier, data);
    }

    // ── oracle: parse JSON → merge → validate (spec + safety) → type-parse ──────────────

    private record Draft(Map<String, Object> configMap, List<Map<String, Object>> fields,
                         List<Finding> findings) {}

    private Draft parseAndValidate(String raw, String type, ConfigSpec spec, Map<String, Object> partial)
            throws Exception {
        JsonNode root = json.readTree(raw);
        JsonNode fieldsNode = root.get("fields");
        if (fieldsNode == null || !fieldsNode.isArray() || fieldsNode.isEmpty()) {
            throw new IllegalArgumentException(
                    "missing required 'fields' array of {name,value,rationale,confidence}");
        }

        Map<String, Object> candidate = deepCopy(partial);
        List<Map<String, Object>> fields = new ArrayList<>();
        for (JsonNode fn : fieldsNode) {
            String name = text(fn, "name");
            if (name == null) {
                throw new IllegalArgumentException("each field needs a 'name' (a dotted config path)");
            }
            Object value = jsonValue(fn.get("value"));
            setPath(candidate, name, value);

            Map<String, Object> fm = new LinkedHashMap<>();
            fm.put("name", name);
            fm.put("value", value);
            fm.put("rationale", orDefault(text(fn, "rationale"), ""));
            fm.put("confidence", orDefault(text(fn, "confidence"), "medium"));
            fields.add(fm);
        }

        // Oracle 1 — structural validity (spec). Oracle 2 — the hard safety gate (R6).
        List<Finding> specFindings = LOADER.validate(spec, candidate);
        List<Finding> safety = ConfigSafetyValidator.check(type, candidate, policy);

        List<Finding> fatal = new ArrayList<>(safety); // every safety finding is fatal
        specFindings.stream().filter(f -> f.severity() == Severity.ERROR).forEach(fatal::add);
        if (!fatal.isEmpty()) {
            throw new IllegalArgumentException("config rejected (" + fatal.size() + " issue(s)): " + fatal);
        }

        // Oracle 3 — pure type-parse where it needs no external file (pipeline resolves a schema off
        // disk, so it leans on the spec + safety gate above instead).
        switch (type) {
            case "job" -> JobConfig.fromMap(candidate);
            case "enrichment" -> EnrichmentConfig.fromMap(candidate, RawConfig.str(candidate, "transform"));
            default -> { /* pipeline / schema / meta */ }
        }

        return new Draft(candidate, fields, specFindings); // findings = surviving WARNINGs
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────

    private static Map<String, String> knownTables(MetadataGraphService catalog) {
        Map<String, String> byName = new LinkedHashMap<>();
        for (NodeKind kind : new NodeKind[]{NodeKind.SOURCE, NodeKind.EVENT_TABLE}) {
            for (MetadataNode n : catalog.nodesOfKind(kind)) {
                byName.putIfAbsent(n.label(), n.id());
            }
        }
        return byName;
    }

    /** Whether {@code name} appears as a string value anywhere in the (small) config map. */
    private static boolean referencesName(Object node, String name) {
        if (node instanceof Map<?, ?> m) {
            for (Object v : m.values()) if (referencesName(v, name)) return true;
            return false;
        }
        if (node instanceof List<?> l) {
            for (Object v : l) if (referencesName(v, name)) return true;
            return false;
        }
        return name.equals(String.valueOf(node));
    }

    private static String fieldCatalog(ConfigSpec spec) {
        StringBuilder sb = new StringBuilder();
        for (FieldSpec f : spec.fields()) {
            sb.append("- ").append(f.path()).append(" (").append(f.type());
            if (f.required()) sb.append(", required");
            sb.append(")");
            if (!f.description().isBlank()) sb.append(": ").append(f.description());
            sb.append('\n');
        }
        return sb.toString();
    }

    /** ConfigCodec.toToon needs a non-empty map; wrap an empty partial so the prompt shows "{}". */
    private static Map<String, Object> wrapForDisplay(Map<String, Object> partial) {
        return partial.isEmpty() ? Map.of("(empty)", "fill all fields") : partial;
    }

    @SuppressWarnings("unchecked")
    private static void setPath(Map<String, Object> root, String dotted, Object value) {
        String[] segs = dotted.split("\\.");
        Map<String, Object> cur = root;
        for (int i = 0; i < segs.length - 1; i++) {
            Object nxt = cur.get(segs[i]);
            if (!(nxt instanceof Map)) {
                nxt = new LinkedHashMap<String, Object>();
                cur.put(segs[i], nxt);
            }
            cur = (Map<String, Object>) nxt;
        }
        cur.put(segs[segs.length - 1], value);
    }

    private Map<String, Object> deepCopy(Map<String, Object> in) {
        if (in == null || in.isEmpty()) return new LinkedHashMap<>();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> copy = json.readValue(json.writeValueAsString(in), LinkedHashMap.class);
            return copy;
        } catch (Exception e) {
            return new LinkedHashMap<>(in); // shallow fallback
        }
    }

    private Object jsonValue(JsonNode v) {
        if (v == null || v.isNull()) return null;
        if (v.isBoolean()) return v.asBoolean();
        if (v.isInt()) return v.asInt();
        if (v.isLong()) return v.asLong();
        if (v.isNumber()) return v.asDouble();
        if (v.isObject() || v.isArray()) return json.convertValue(v, Object.class);
        return v.asText();
    }

    private static String text(JsonNode root, String field) {
        JsonNode v = root.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText();
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static String orDefault(String v, String fallback) {
        return (v == null || v.isBlank()) ? fallback : v;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        return (b != null && !b.isBlank()) ? b : null;
    }
}

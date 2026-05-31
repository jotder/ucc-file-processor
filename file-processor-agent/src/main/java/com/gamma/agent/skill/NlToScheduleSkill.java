package com.gamma.agent.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.agent.model.ModelProvider;
import com.gamma.agent.model.ModelRequest;
import com.gamma.agent.model.ModelTier;
import com.gamma.assist.AssistRequest;
import com.gamma.assist.AssistResult;
import com.gamma.assist.AssistResult.Citation;
import com.gamma.catalog.MetadataGraphService;
import com.gamma.catalog.MetadataNode;
import com.gamma.catalog.NodeKind;
import com.gamma.config.io.ConfigCodec;
import com.gamma.config.io.ConfigLoader;
import com.gamma.config.spec.ConfigSpecs;
import com.gamma.config.spec.Finding;
import com.gamma.config.spec.Severity;
import com.gamma.job.JobConfig;
import com.gamma.service.CronExpression;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The second assist slice (v3.4.0, A2): {@code nl-to-schedule} — the first <em>write-adjacent</em>
 * skill, and the cron-builder-widget replacement. Given a natural-language scheduling request
 * ("every weekday 6am after adjustment_etl") it produces a <b>validated JobConfig draft</b>:
 * {@code {cron, onPipeline, jobType, humanReadable, nextRuns[], draftToon}} that the user reviews
 * and saves as a {@code *_job.toon}.
 *
 * <h3>Generate → validate → repair</h3>
 * The model emits a small JSON object; the skill runs it through a deterministic oracle — the core
 * {@link CronExpression} parser, {@link JobConfig#fromMap} (which validates the type + cron eagerly),
 * and the declarative {@link ConfigSpecs#job()} spec — via a {@link RepairLoop} that re-prompts with
 * the verbatim oracle error, capped at a few rounds. This makes a structurally-wrong local-model
 * answer an invisible internal retry rather than a surfaced failure. It guards <em>structure</em>,
 * not <em>semantics</em> — so the skill also returns {@code humanReadable} + {@code nextRuns} for the
 * human to confirm the schedule actually means what they intended.
 *
 * <h3>Grounding & safety</h3>
 * {@code on_pipeline} must resolve to a real catalog SOURCE node — the model is handed the known
 * pipeline names and an unknown one is rejected by the oracle, so the citation (the node id) is
 * derived, never fabricated. The milestone is <b>draft-only</b> (V-9): {@code applyVia} is always
 * {@code null}, the agent holds no write token, and the draft {@code .toon} is the user's to save.
 *
 * @since 3.4.0
 */
public final class NlToScheduleSkill implements Skill {

    public static final String ID = "nl-to-schedule";
    private static final int MAX_REPAIR_ROUNDS = 3;
    private static final int NEXT_RUNS = 5;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Pure validator (no file I/O) — {@link ConfigLoader#validate} is a function of (spec, map). */
    private static final ConfigLoader LOADER = ConfigLoader.filesystem();

    private static final String SYSTEM = """
            You translate a natural-language scheduling request into a job schedule for the UCC
            File Processor. Reply with ONLY a JSON object (no prose, no markdown) with these fields:
              "name":         a short kebab-case job name derived from the request
              "cron":         a 5-field cron expression "minute hour day month day-of-week"
                              (use named days like MON-FRI where natural; do NOT include seconds)
              "job_type":     one of: ingest | enrich | report | maintenance
              "on_pipeline":  the upstream pipeline this should run after, or null if none
            Use on_pipeline ONLY when the request names an upstream pipeline, and ONLY a name from the
            KNOWN PIPELINES list; otherwise set it to null. Prefer the simplest cron that matches.""";

    private final ObjectMapper json = new ObjectMapper();
    private final ZoneId zone;

    public NlToScheduleSkill() {
        this(ZoneId.systemDefault());
    }

    NlToScheduleSkill(ZoneId zone) {
        this.zone = zone;
    }

    @Override public String id() { return ID; }

    /** The floor tier; {@link #routeTier} escalates compositional phrasing to MEDIUM at run time. */
    @Override public ModelTier tier() { return ModelTier.SMALL; }

    @Override
    public AssistResult run(AssistRequest request, AssistContext ctx) {
        String userText = (request.userText() == null) ? "" : request.userText().trim();
        if (userText.isEmpty()) {
            return AssistResult.unavailable(ID,
                    "nl-to-schedule needs a natural-language request in 'userText', "
                            + "e.g. \"every weekday at 6am after adjustment_etl\"");
        }

        // ── tier routing: plain → SMALL, compositional/relative/timezone → MEDIUM (V-5/V-8) ──
        ModelTier chosen = routeTier(userText);
        ModelProvider model = ctx.models().provider(chosen);
        if (!model.available() && chosen == ModelTier.SMALL) {
            ModelProvider medium = ctx.models().provider(ModelTier.MEDIUM);
            if (medium.available()) { model = medium; chosen = ModelTier.MEDIUM; }
        }
        if (!model.available()) {
            return AssistResult.unavailable(ID,
                    "the assist model (tier " + chosen + ") is not available — enable the assist "
                            + "layer and a local Ollama endpoint to use nl-to-schedule");
        }

        // ── grounding: known pipeline names (so on_pipeline resolves to a real SOURCE node) ──
        Map<String, String> pipelineIdByName = knownPipelines(ctx.catalog());
        String knownList = pipelineIdByName.isEmpty()
                ? "(none)" : String.join(", ", pipelineIdByName.keySet());
        String basePrompt = "REQUEST: " + userText
                + "\n\nKNOWN PIPELINES (use an exact name for on_pipeline, else null): " + knownList;

        // ── generate → validate → repair ──
        final ModelProvider m = model;
        final ModelTier t = chosen;
        RepairLoop.Result<Draft> result = RepairLoop.run(MAX_REPAIR_ROUNDS,
                feedback -> {
                    String prompt = (feedback == null) ? basePrompt : basePrompt + "\n\n" + feedback;
                    return m.generate(ModelRequest.json(t, SYSTEM, prompt));
                },
                raw -> parseAndValidate(raw, pipelineIdByName.keySet()));

        if (!result.ok()) {
            String last = result.errors().isEmpty() ? "unknown error"
                    : result.errors().get(result.errors().size() - 1);
            return AssistResult.unavailable(ID,
                    "could not produce a valid schedule after " + result.rounds()
                            + " attempt(s): " + last);
        }

        // ── build the validated draft payload ──
        Draft d = result.value();
        String humanReadable = CronDescriber.describe(d.cron);
        List<String> nextRuns = computeNextRuns(d.cron, zone, NEXT_RUNS);
        String draftToon = ConfigCodec.toToon(d.jobMap);

        List<Citation> citations = new ArrayList<>();
        List<String> links = new ArrayList<>();
        if (d.onPipeline != null && pipelineIdByName.containsKey(d.onPipeline)) {
            String pid = pipelineIdByName.get(d.onPipeline);
            citations.add(new Citation("catalog", pid));    // derived, not parsed from the model
            links.add("/catalog/tables/" + pid);
        }
        citations.add(new Citation("oracle", "cron:" + d.cron)); // the schedule was validated by the cron oracle

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", d.name);
        data.put("cron", d.cron);
        data.put("jobType", d.jobType);
        if (d.onPipeline != null) data.put("onPipeline", d.onPipeline); // omit nulls (data rejects null values)
        data.put("humanReadable", humanReadable);
        data.put("nextRuns", nextRuns);
        data.put("draftToon", draftToon);
        data.put("repaired", result.repaired());
        data.put("findings", d.findings.stream()
                .map(f -> Map.<String, Object>of(
                        "severity", String.valueOf(f.severity()),
                        "field", String.valueOf(f.fieldPath()),
                        "message", String.valueOf(f.message())))
                .toList());

        String answer = humanReadable
                + (d.onPipeline != null ? " (also after the '" + d.onPipeline + "' pipeline commits)" : "")
                + ". Review the draft below and save it as a *_job.toon to schedule it.";

        return AssistResult.draft(ID, answer, citations, links, data);
    }

    // ── tier routing heuristic ───────────────────────────────────────────────────────

    /**
     * Plain phrasing stays on the SMALL (2–3B) tier; compositional / relative / timezone phrasing
     * escalates to MEDIUM (7B), which is materially better at "after X", relative dates, and zones.
     */
    static ModelTier routeTier(String userText) {
        if (userText == null || userText.isBlank()) return ModelTier.SMALL;
        String t = userText.toLowerCase();
        String[] compositional = {
                " after ", " before ", " every other ", " unless ", " except", " and ",
                "timezone", "time zone", " utc", " gmt", " est", " pst", "pacific", "eastern",
                "last ", "first ", "weekday", "weekend", "business day"
        };
        for (String c : compositional) {
            if (t.contains(c)) return ModelTier.MEDIUM;
        }
        return ModelTier.SMALL;
    }

    // ── oracle: parse + validate the model's JSON into a JobConfig draft ───────────────

    /** Holds the validated draft pieces produced by the oracle. */
    private record Draft(Map<String, Object> jobMap, String name, String jobType,
                         String cron, String onPipeline, List<Finding> findings) {}

    /**
     * Parse the model's JSON, ground {@code on_pipeline}, and run the deterministic oracle. Throws an
     * {@link Exception} with a human-usable message on any rejection — that message is what the
     * {@link RepairLoop} feeds back to the model for self-correction.
     */
    private Draft parseAndValidate(String raw, Set<String> knownPipelines) throws Exception {
        JsonNode root = json.readTree(raw);

        String cron = text(root, "cron");
        if (cron == null) throw new IllegalArgumentException("missing required field 'cron'");
        String name = orDefault(text(root, "name"), "scheduled-job");
        String jobType = orDefault(text(root, "job_type"), "maintenance").toLowerCase();
        String onPipeline = text(root, "on_pipeline");

        if (onPipeline != null && !knownPipelines.contains(onPipeline)) {
            throw new IllegalArgumentException("on_pipeline '" + onPipeline
                    + "' is not a known pipeline; use one of " + knownPipelines + " or null");
        }

        Map<String, Object> jobSection = new LinkedHashMap<>();
        jobSection.put("name", name);
        jobSection.put("type", jobType);
        jobSection.put("cron", cron);
        if (onPipeline != null) jobSection.put("on_pipeline", onPipeline);
        jobSection.put("enabled", true);
        Map<String, Object> jobMap = Map.of("job", jobSection);

        // Oracle 1 — the cron parses (5/6 fields, ranges, named days): throws a specific message.
        CronExpression.parse(cron);
        // Oracle 2 — JobConfig parses (validates type via JobType.from + cron eagerly).
        JobConfig.fromMap(jobMap);
        // Oracle 3 — declarative job spec: fail on any ERROR-severity finding.
        List<Finding> findings = LOADER.validate(ConfigSpecs.job(), jobMap);
        List<Finding> errors = findings.stream().filter(f -> f.severity() == Severity.ERROR).toList();
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("job spec violation(s): " + errors);
        }

        return new Draft(jobMap, name, jobType, cron, onPipeline, findings);
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────

    private static Map<String, String> knownPipelines(MetadataGraphService catalog) {
        Map<String, String> byName = new LinkedHashMap<>();
        for (MetadataNode s : catalog.nodesOfKind(NodeKind.SOURCE)) {
            byName.put(s.label(), s.id());
        }
        return byName;
    }

    private static List<String> computeNextRuns(String cron, ZoneId zone, int k) {
        CronExpression expr = CronExpression.parse(cron);
        List<String> out = new ArrayList<>(k);
        ZonedDateTime cursor = ZonedDateTime.now(zone);
        for (int i = 0; i < k; i++) {
            cursor = expr.next(cursor);
            out.add(cursor.format(TS));
        }
        return out;
    }

    /** A non-blank, non-JSON-null string field, else null. */
    private static String text(JsonNode root, String field) {
        JsonNode v = root.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText();
        return (s == null || s.isBlank() || "null".equalsIgnoreCase(s)) ? null : s.trim();
    }

    private static String orDefault(String v, String fallback) {
        return (v == null || v.isBlank()) ? fallback : v;
    }
}

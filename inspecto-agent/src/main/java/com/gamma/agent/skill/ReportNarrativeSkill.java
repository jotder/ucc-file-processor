package com.gamma.agent.skill;

import static com.gamma.agent.skill.SkillInputs.firstNonBlank;
import static com.gamma.agent.skill.SkillInputs.orDefault;
import static com.gamma.agent.skill.SkillInputs.str;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.agent.kernel.agent.AgentContext;
import com.gamma.agent.kernel.agent.AgentRequest;
import com.gamma.agent.kernel.agent.AgentResult;
import com.gamma.agent.kernel.agent.Capability;
import com.gamma.agent.kernel.agent.CapabilitySpec;
import com.gamma.agent.kernel.error.AgentError;
import com.gamma.agent.kernel.model.ModelProvider;
import com.gamma.agent.kernel.model.ModelRequest;
import com.gamma.agent.kernel.model.ModelTier;
import com.gamma.agent.kernel.reason.RepairLoop;
import com.gamma.agent.kernel.tool.CredibilityTier;
import com.gamma.agent.kernel.tool.Evidence;
import com.gamma.agent.kernel.tool.GroundingGuard;
import com.gamma.agent.kernel.tool.NumericGroundingGuard;
import com.gamma.config.spec.Finding;
import com.gamma.report.ReportService;
import com.gamma.report.ReportService.Window;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code report-narrative} (M8 / v3.8.0, B2): turn a structured pipeline report into a short,
 * plain-language narrative — the "read this dense report JSON for me" replacement.
 *
 * <h3>Strictly extractive — narrate the numbers, never compute them</h3>
 * A small (2B) model is enough <em>because</em> the skill does not trust it to do arithmetic: it
 * resolves the report server-side (so the figures are platform-computed, not caller-supplied), asks the
 * model only to restate them in prose, and then runs the result through the deterministic
 * {@link NumericGroundingGuard}, which rejects any number that does not appear in the source report. An
 * invented figure is fed back to the {@link RepairLoop} and repaired — never surfaced. This is the only
 * safety mechanism the skill needs, and it is fully CPU-testable.
 *
 * <h3>Abstain-safe</h3>
 * With no model available the skill still does useful work: it assembles a <b>deterministic template
 * narrative</b> from the report's own fields (grounded by construction), flagged {@code
 * modelBacked=false}. Read-only / draft-only (V-9): {@code applyVia} is always {@code null}.
 *
 * @since 3.8.0
 */
public final class ReportNarrativeSkill implements Capability {

    public static final String ID = "report-narrative";

    private static final CapabilitySpec SPEC = new CapabilitySpec(ID, 1,
            "Narrate a structured pipeline report in plain language, grounded to the report's own figures.",
            ModelTier.SMALL, com.gamma.agent.model.AssistTunables.confidenceThreshold(0.5), java.time.Duration.ofSeconds(60),
            java.util.Set.of(), java.util.Set.of());

    private static final int MAX_REPAIR_ROUNDS = com.gamma.agent.model.AssistTunables.repairRounds(2);

    private static final String SYSTEM = """
            You write a short (2-4 sentence) plain-language narrative summarising a data-pipeline report.
            Use ONLY the numbers and units that appear in the REPORT below, and restate them exactly
            (durations are in milliseconds). You may express a given ratio as a percentage (an error rate
            of 0.017 may be written "1.7%"), but otherwise do NOT compute, derive, or invent any figure
            that is not in the report. Reply with the narrative text only — no preamble, no markdown.""";

    private final ObjectMapper json = new ObjectMapper();

    @Override public CapabilitySpec spec() { return SPEC; }

    @Override
    public AgentResult run(AgentRequest request, AgentContext context) throws AgentError {
        UccAgentContext ctx = (UccAgentContext) context;
        ModelTier tier = ctx.effectiveTier(SPEC.defaultTier());
        String reportType = firstNonBlank(request.context("reportType"),
                str(request.partialInput().get("reportType")));

        // Resolve the report map: either supplied verbatim, or fetched from the ReportService by selector.
        Map<String, Object> report;
        String label;
        Object supplied = request.partialInput().get("report");
        if (supplied == null) supplied = request.screenContext().get("report");
        if (supplied instanceof Map<?, ?>) {
            report = asStringKeyedMap((Map<?, ?>) supplied);
            label = orDefault(reportType, "custom");
        } else {
            if (reportType == null) {
                return AgentResult.unavailable(ID, "report-narrative needs a 'reportType' "
                        + "(status|service|batch|enrichment) or a 'report' object to narrate");
            }
            if (ctx.reports() == null) {
                return AgentResult.unavailable(ID, "no report service is available to resolve a '"
                        + reportType + "' report");
            }
            try {
                report = resolve(ctx.reports(), reportType, request);
            } catch (IllegalArgumentException e) {
                return AgentResult.unavailable(ID, e.getMessage());
            }
            if (report == null) {
                return AgentResult.unavailable(ID, "unknown reportType '" + reportType
                        + "' (expected status|service|batch|enrichment)");
            }
            label = reportType;
        }

        String reportJson = writeJson(report);
        List<Evidence> evidence = List.of(new Evidence(label, CredibilityTier.AUTHORITATIVE, "report", label, 1.0, null));
        // The allowed-evidence the grounding guard checks the narration against (the report's figures).
        List<Evidence> allowed = List.of(new Evidence(report, CredibilityTier.AUTHORITATIVE, "report", label, 1.0, null));

        ModelProvider model = ctx.models() == null ? null : ctx.models().providerFor(tier);
        if (model == null || !model.available()) {
            // Abstain-safe: a deterministic, grounded-by-construction narrative from the report fields.
            String narrative = templateNarrative(label, report);
            return AgentResult.draft(ID, SPEC.version(), "Assembled a deterministic report narrative (no model "
                    + "available).", evidence, List.of(), null, 1.0, tier,
                    data(narrative, label, false, false, List.of()));
        }

        final ModelProvider m = model;
        String basePrompt = "REPORT:\n" + reportJson;
        RepairLoop.Result<String> result = RepairLoop.run(MAX_REPAIR_ROUNDS,
                feedback -> {
                    String prompt = (feedback == null) ? basePrompt : basePrompt + "\n\n" + feedback;
                    return m.generate(ModelRequest.text(tier, SYSTEM, prompt)).text();
                },
                raw -> {
                    String narrative = raw == null ? "" : raw.trim();
                    GroundingGuard.Verdict v = new NumericGroundingGuard().check(narrative, allowed);
                    if (!v.grounded()) {
                        throw new IllegalArgumentException("ungrounded figure(s): "
                                + String.join(", ", v.ungrounded()));
                    }
                    return narrative;
                });

        if (!result.ok()) {
            String last = result.errors().isEmpty() ? "unknown error"
                    : result.errors().get(result.errors().size() - 1);
            return AgentResult.unavailable(ID, "could not produce a grounded narrative after "
                    + result.rounds() + " attempt(s): " + last);
        }

        return AgentResult.draft(ID, SPEC.version(), "Generated a grounded narrative of the " + label + " report; "
                + "every figure traces to the report.", evidence, List.of(), null, 1.0, tier,
                data(result.value(), label, true, result.repaired(), List.of()));
    }

    // ── report resolution ────────────────────────────────────────────────────────────────

    private Map<String, Object> resolve(ReportService reports, String reportType, AgentRequest request) {
        String from = firstNonBlank(request.context("from"), str(request.partialInput().get("from")));
        String to = firstNonBlank(request.context("to"), str(request.partialInput().get("to")));
        Window window = Window.of(from, to);
        String pipeline = firstNonBlank(request.context("pipeline"),
                str(request.partialInput().get("pipeline")));
        String jobName = firstNonBlank(request.context("job"), str(request.partialInput().get("job")));

        Object report = switch (reportType.toLowerCase()) {
            case "status" -> reports.statusReport();
            case "service" -> reports.serviceReport(window);
            case "batch" -> {
                if (pipeline == null) throw new IllegalArgumentException(
                        "a 'batch' report needs a 'pipeline' name");
                yield reports.batchReport(pipeline, window);   // throws on unknown pipeline
            }
            case "enrichment" -> {
                if (jobName == null) throw new IllegalArgumentException(
                        "an 'enrichment' report needs a 'job' name");
                yield reports.enrichmentReport(jobName, window);   // throws on unknown job
            }
            default -> null;
        };
        return report == null ? null : convert(report);
    }

    private Map<String, Object> convert(Object report) {
        return json.convertValue(report, new TypeReference<LinkedHashMap<String, Object>>() {});
    }

    // ── deterministic fallback ─────────────────────────────────────────────────────────────

    /** A grounded-by-construction narrative: it only ever restates the report's own scalar fields. */
    private static String templateNarrative(String label, Map<String, Object> report) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Object> e : report.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Map || v instanceof Iterable) continue;   // skip nested collections
            if (v == null) continue;
            parts.add(e.getKey() + " = " + v);
        }
        return "Report (" + label + "): " + String.join(", ", parts) + ".";
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────

    private static Map<String, Object> data(String narrative, String reportType, boolean modelBacked,
                                             boolean repaired, List<Finding> findings) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("narrative", narrative);
        data.put("reportType", reportType);
        data.put("grounded", true);
        data.put("modelBacked", modelBacked);
        data.put("repaired", repaired);
        data.put("findings", findings);
        return data;
    }

    private String writeJson(Map<String, Object> report) {
        try {
            return json.writeValueAsString(report);
        } catch (Exception e) {
            return String.valueOf(report);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asStringKeyedMap(Map<?, ?> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : raw.entrySet()) out.put(String.valueOf(e.getKey()), e.getValue());
        return out;
    }

}

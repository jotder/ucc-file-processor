package com.gamma.intelligence.pack;

import com.eoiagent.app.PromptProfile;
import com.eoiagent.core.GoalKind;

import java.util.Map;

/**
 * Persona + per-{@link GoalKind} system prompts, per
 * {@code docs/superpower/embedded-intelligence-plan.md} §5 (Constitution layer). {@code
 * systemPrompt} is total: every {@link GoalKind} gets a sensible default, though P0's host layer
 * (eoiagent {@code DefaultAgentSession}) only ever drives {@code QA} — the others are groundwork
 * for later phases (P1 investigation, P2 authoring).
 */
final class InspectoPromptProfile implements PromptProfile {

    private static final Map<String, String> GLOSSARY = GlossaryLoader.load();

    @Override
    public String persona() {
        return "You are Inspecto's embedded operator/analyst assistant.";
    }

    @Override
    public String systemPrompt(GoalKind kind) {
        String base = persona() + " Use the canonical vocabulary (Pipeline, Dataset, Incident, "
                + "Expectation/Alert Rule/Decision Rule, Measure, Source, Widget) — never a banned "
                + "synonym. Cite the tool or doc that grounds each claim; state uncertainty rather "
                + "than guessing. Prefer routing the user to an existing page (a NavigationIntent) "
                + "over re-deriving what's already shown there.";
        return switch (kind) {
            case SQL_GEN -> base + " Generate read-only SQL against known Datasets only.";
            case INVESTIGATION -> base + " Investigate using read-only tools; rank causes by evidence.";
            case PIPELINE_AUTHOR -> base + " Draft Pipeline configuration for human review; never apply it.";
            default -> base; // QA, ANALYSIS, OPERATIONAL_ACTION — sensible default
        };
    }

    @Override
    public Map<String, String> domainGlossary() {
        return GLOSSARY;
    }
}

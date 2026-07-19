import type { DecisionRule } from 'app/inspecto/api/decision-rules.service';
import { ComponentKind, ConfigFinding, Ref, decisionRuleRefs, getKind, hasEditorRoute, registerEditorRoute, registerKind } from 'app/inspecto/component-model';

/**
 * The `decision-rule` {@link ComponentKind} — R5 of the living-operational-system roadmap: the
 * Decision network's flagship joins the metadata network. A decision rule is atomic (its target and
 * consequence targets are *referenced* artifacts, not parts); its lineage is `binds` its target
 * pipeline/job + `invokes` the components its platform consequences act on (via {@link decisionRuleRefs}).
 * Authoring = the Decision Rules pane; exec = the decision runner (mock: `executeConsequences`).
 * Replaces the atomic `rule` shell (bare "Rule" is a banned term — GLOSSARY §4).
 */
export const DECISION_RULE_KIND: ComponentKind<DecisionRule> = {
    id: 'decision-rule',
    label: 'Decision Rule',
    allowedPartKinds: [],
    wiring: 'none',
    config: {
        validate: validateDecisionRuleConfig,
        create: () =>
            ({
                name: '', description: '', targetType: 'pipeline', target: '',
                when: { kind: 'group', op: 'AND', items: [] }, consequences: [], priority: 100, enabled: true,
            }) as DecisionRule,
    },
    deriveRefs: (config: DecisionRule): Ref[] => decisionRuleRefs(config as unknown as Record<string, unknown>),
    authoring: { editorKey: 'decision-rule' },
    exec: { runnerKey: 'decision-rule' },
};

/** Tiny hand-written validator (the pane's form owns field-level UX): identity, a target, at least one consequence. */
export function validateDecisionRuleConfig(config: unknown): ConfigFinding[] {
    const c = (config ?? {}) as Partial<DecisionRule>;
    const findings: ConfigFinding[] = [];
    if (!c.name) findings.push({ severity: 'error', path: 'name', message: 'A decision rule needs a name.' });
    if (!c.target) findings.push({ severity: 'error', path: 'target', message: 'Pick a target pipeline or job.' });
    if (!c.consequences?.length) findings.push({ severity: 'error', path: 'consequences', message: 'Add at least one consequence.' });
    return findings;
}

if (!getKind(DECISION_RULE_KIND.id)) {
    registerKind(DECISION_RULE_KIND);
}
// The Decision Rules pane edits via dialogs (no /:id route) — the pane itself is the editor target.
if (!hasEditorRoute('decision-rule')) {
    registerEditorRoute('decision-rule', () => ['/decision-rules']);
}

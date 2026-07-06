import type { Ref } from '../component-model/component-types';

/**
 * R5 of the living-operational-system roadmap — the Decision network's typed **Consequence**. The
 * three canonical rule kinds each hard-wired one consequence (Expectation → incident, Alert Rule →
 * fire, Decision Rule → route/tag/quarantine/drop). R5 unifies them on `Condition → Evaluation →
 * Consequence[]`: a Consequence is a typed action a **decision engine** (a rule today, the AI Assist
 * next) produces, executed via the Execution / Signal networks.
 *
 * This widens Decision Rule's original `DecisionConsequence` (kept `action` + `destination` for the
 * routing actions, so the existing editor/handler/seeds barely change) with the platform actions and
 * an optional component `target`. Framework-free — unit-tests in plain vitest.
 */

/** The record-routing actions (Drools-style) — a matching record is subjected to these. */
export type RoutingAction = 'route' | 'tag' | 'quarantine' | 'drop';
/** The platform actions — executed through the Execution / Signal networks (mock-first in this slice). */
export type PlatformAction = 'emit-signal' | 'create-alert' | 'start-job' | 'trigger-pipeline' | 'render-widget' | 'generate-report' | 'invoke-api';
export type ConsequenceType = RoutingAction | PlatformAction;

export const ROUTING_ACTIONS: RoutingAction[] = ['route', 'tag', 'quarantine', 'drop'];
export const PLATFORM_ACTIONS: PlatformAction[] = ['emit-signal', 'create-alert', 'start-job', 'trigger-pipeline', 'render-widget', 'generate-report', 'invoke-api'];

/** One consequence a decision engine produces; a rule may stack several. */
export interface Consequence {
    action: ConsequenceType;
    /** route ⇒ branch · tag ⇒ tag value · quarantine ⇒ reason (the routing actions' one field). */
    destination?: string | null;
    /** The component a platform action acts on (start-job ⇒ job, trigger-pipeline ⇒ pipeline, render-widget ⇒ widget). */
    target?: { kind: string; id: string } | null;
    /** Action-specific params: emit-signal {type,severity,message} · create-alert {rule,metric} · invoke-api {url} · generate-report {name}. */
    params?: Record<string, unknown>;
}

/** Which component kind each platform action targets — drives the editor's target picker + the `invokes` edges. */
export const CONSEQUENCE_TARGET_KIND: Partial<Record<ConsequenceType, string>> = {
    'start-job': 'job',
    'trigger-pipeline': 'pipeline',
    'render-widget': 'widget',
};

/** Human labels for the action select. */
export const CONSEQUENCE_LABELS: Record<ConsequenceType, string> = {
    route: 'Route to branch',
    tag: 'Tag record',
    quarantine: 'Quarantine',
    drop: 'Drop',
    'emit-signal': 'Emit signal',
    'create-alert': 'Create alert',
    'start-job': 'Start job',
    'trigger-pipeline': 'Trigger pipeline',
    'render-widget': 'Render widget',
    'generate-report': 'Generate report',
    'invoke-api': 'Invoke API',
};

/**
 * The metadata-network edges a rule's consequences add: a platform action that targets a component
 * `invokes` it (start-job → job, trigger-pipeline → pipeline, render-widget → widget). These become
 * the decision rule's lineage in the reuse-graph / bundle closure / delete-protection (R1 vocabulary).
 */
export function consequenceRefs(consequences: Consequence[]): Ref[] {
    const refs: Ref[] = [];
    consequences.forEach((c, i) => {
        const kind = CONSEQUENCE_TARGET_KIND[c.action];
        if (kind && c.target?.id) refs.push({ kind, id: c.target.id, rel: 'invokes', via: `consequence${i}` });
    });
    return refs;
}

/**
 * The single secondary input a consequence row shows in the editor, and what it means per action:
 * a routing `destination`, a component `target` id, or a `param` value. Lets the form keep one input
 * per row while covering all action types.
 */
export interface ConsequenceInputSpec {
    show: boolean;
    label: string;
    required: boolean;
    kind: 'destination' | 'target' | 'param';
    targetKind?: string;
    paramKey?: string;
}

export function consequenceInputSpec(action: ConsequenceType): ConsequenceInputSpec {
    switch (action) {
        case 'route': return { show: true, label: 'Branch', required: true, kind: 'destination' };
        case 'tag': return { show: true, label: 'Tag value', required: true, kind: 'destination' };
        case 'quarantine': return { show: true, label: 'Reason (optional)', required: false, kind: 'destination' };
        case 'drop': return { show: false, label: '', required: false, kind: 'destination' };
        case 'start-job': return { show: true, label: 'Job id', required: true, kind: 'target', targetKind: 'job' };
        case 'trigger-pipeline': return { show: true, label: 'Pipeline id', required: true, kind: 'target', targetKind: 'pipeline' };
        case 'render-widget': return { show: true, label: 'Widget id', required: true, kind: 'target', targetKind: 'widget' };
        case 'emit-signal': return { show: true, label: 'Signal type', required: true, kind: 'param', paramKey: 'type' };
        case 'create-alert': return { show: true, label: 'Alert name', required: true, kind: 'param', paramKey: 'rule' };
        case 'generate-report': return { show: true, label: 'Report name', required: true, kind: 'param', paramKey: 'name' };
        case 'invoke-api': return { show: true, label: 'API URL', required: true, kind: 'param', paramKey: 'url' };
    }
}

/** Build a Consequence from an action + the single editor detail value (inverse of {@link consequenceDetail}). */
export function buildConsequence(action: ConsequenceType, detail: string): Consequence {
    const spec = consequenceInputSpec(action);
    const val = detail.trim();
    if (spec.kind === 'target') return { action, target: val ? { kind: spec.targetKind!, id: val } : null };
    if (spec.kind === 'param') return { action, params: val ? { [spec.paramKey!]: val } : {} };
    return { action, destination: spec.show && val ? val : null };
}

/** The editor detail value for a consequence (inverse of {@link buildConsequence}) — for edit prefill. */
export function consequenceDetail(c: Consequence): string {
    const spec = consequenceInputSpec(c.action);
    if (spec.kind === 'target') return c.target?.id ?? '';
    if (spec.kind === 'param') return String(c.params?.[spec.paramKey!] ?? '');
    return c.destination ?? '';
}

/** The result of running one consequence through the Execution/Signal networks (the `apply` response). */
export interface ExecutedConsequence {
    action: string;
    status: 'executed' | 'skipped';
    detail: string;
}

/** A one-line human summary of a consequence (the ledger / proposal list / reuse-graph tooltip). */
export function describeConsequence(c: Consequence): string {
    switch (c.action) {
        case 'route': return `Route to ${c.destination ?? '?'}`;
        case 'tag': return `Tag "${c.destination ?? '?'}"`;
        case 'quarantine': return c.destination ? `Quarantine (${c.destination})` : 'Quarantine';
        case 'drop': return 'Drop';
        case 'emit-signal': return `Emit signal ${(c.params?.['type'] as string) ?? ''}`.trim();
        case 'create-alert': return `Create alert ${(c.params?.['rule'] as string) ?? ''}`.trim();
        case 'start-job': return `Start job ${c.target?.id ?? '?'}`;
        case 'trigger-pipeline': return `Trigger pipeline ${c.target?.id ?? '?'}`;
        case 'render-widget': return `Render widget ${c.target?.id ?? '?'}`;
        case 'generate-report': return `Generate report ${(c.params?.['name'] as string) ?? ''}`.trim();
        case 'invoke-api': return `Invoke API ${(c.params?.['url'] as string) ?? ''}`.trim();
        default: return c.action;
    }
}

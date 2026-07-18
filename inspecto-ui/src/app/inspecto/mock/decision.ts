import type { DecisionRule } from '../api/decision-rules.service';
import { type Consequence, type ExecutedConsequence, describeConsequence } from '../decision/consequence';
import type { Ref } from '../component-model/component-types';
import type { SignalSeverity } from '../signal/signal';
import { MockStore } from './mock-store';
import { emitSignal } from './signals';

/**
 * R5 — the Decision network's execution seam (mock-first). A **decision engine** (a rule today, the AI
 * Assist next) produces {@link Consequence}s; {@link executeConsequences} runs the *platform* actions
 * through the Execution / Signal networks — the R4↔R5 link: `emit-signal` / `create-alert` write into
 * the one signal ledger via {@link emitSignal}. Record-routing actions (route/tag/quarantine/drop)
 * take effect during the target pipeline's live runs (backend `DecisionRuleApplier`), not on demand —
 * so `apply` reports them as skipped here, mirroring the real `/apply` route.
 * Framework-free — unit-tests in plain vitest.
 */

/** Execute a rule's consequences, emitting the resulting signals; returns what ran (for the UI + ledger proof). */
export function executeConsequences(store: MockStore, space: string, rule: DecisionRule): ExecutedConsequence[] {
    const now = Date.now();
    const source: Ref = { kind: 'decision-rule', id: rule.name, rel: 'emits' };
    const corr = `decision:${rule.name}`;
    return rule.consequences.map((c, i) => executeOne(store, space, rule, c, i, source, corr, now));
}

function executeOne(
    store: MockStore, space: string, rule: DecisionRule, c: Consequence, i: number,
    source: Ref, correlationId: string, now: number,
): ExecutedConsequence {
    const sev = (c.params?.['severity'] as SignalSeverity | undefined) ?? 'info';
    switch (c.action) {
        case 'emit-signal': {
            const type = String(c.params?.['type'] ?? 'DECISION');
            emitSignal(store, space, {
                signalId: `dec-${now}-${i}`, type, at: now, source, correlationId, severity: sev,
                payload: { message: String(c.params?.['message'] ?? `${rule.name} emitted ${type}`), rule: rule.name },
            });
            return { action: c.action, status: 'executed', detail: `emitted ${type}` };
        }
        case 'create-alert': {
            const ruleName = String(c.params?.['rule'] ?? rule.name);
            emitSignal(store, space, {
                signalId: `dec-alert-${now}-${i}`, type: 'ALERT_FIRED', at: now, source, correlationId,
                severity: (c.params?.['severity'] as SignalSeverity | undefined) ?? 'warn',
                payload: {
                    rule: ruleName, pipeline: rule.target, metric: String(c.params?.['metric'] ?? 'decision'),
                    value: 1, comparator: 'eq', threshold: 1, window: 'n/a', message: `Alert raised by decision rule ${rule.name}`,
                },
            });
            return { action: c.action, status: 'executed', detail: `alert ${ruleName}` };
        }
        case 'start-job': {
            const job = c.target?.id;
            if (!job) return { action: c.action, status: 'skipped', detail: 'no target job' };
            emitSignal(store, space, {
                signalId: `dec-${now}-${i}`, type: 'JOB_STARTED', at: now, source, correlationId, severity: 'info',
                payload: { message: `Started job ${job} (decision "${rule.name}")`, job },
            });
            return { action: c.action, status: 'executed', detail: `started job ${job}` };
        }
        case 'trigger-pipeline': {
            const pipe = c.target?.id;
            if (!pipe) return { action: c.action, status: 'skipped', detail: 'no target pipeline' };
            emitSignal(store, space, {
                signalId: `dec-${now}-${i}`, type: 'PIPELINE_TRIGGERED', at: now, source, correlationId, severity: 'info',
                payload: { message: `Triggered pipeline ${pipe} (decision "${rule.name}")`, pipeline: pipe },
            });
            return { action: c.action, status: 'executed', detail: `triggered ${pipe}` };
        }
        case 'render-widget':
        case 'generate-report':
        case 'invoke-api': {
            emitSignal(store, space, {
                signalId: `dec-${now}-${i}`, type: 'DECISION_ACTION', at: now, source, correlationId, severity: 'info',
                payload: { message: describeConsequence(c), action: c.action },
            });
            return { action: c.action, status: 'executed', detail: describeConsequence(c) };
        }
        default:
            // route / tag / quarantine / drop — applied to matching records during the target
            // pipeline's runs (backend parity: DecisionRoutes.executeOne), never on demand here.
            return { action: c.action, status: 'skipped', detail: 'applied to matching records during the target pipeline\'s runs' };
    }
}

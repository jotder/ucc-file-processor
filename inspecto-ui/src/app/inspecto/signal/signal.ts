import type { Ref } from '../component-model/component-types';
import type { EventRow } from '../api/events.service';
import type { FiredAlert } from '../api/alerts.service';

/**
 * R4 of the living-operational-system roadmap — the Signal network's ONE envelope. Previously the
 * platform carried three parallel operational shapes in three stores (`EventRow`, `FiredAlert`,
 * `NotificationRow`); R4 unifies emission onto a single {@link Signal} written to one ledger
 * (`inspecto/mock/signals.ts`), and the old read surfaces (`/events`, `/alerts`) become thin
 * **projections** back to their view-models via the pure functions here.
 *
 * A Signal is a *lightweight emitted fact — it announces, never decides* (living-operational-system §1).
 * Its `source` is a metadata {@link Ref} (`rel:'emits'`), so the runtime Signal network joins the R1
 * lineage graph. Framework-free (no Angular) — unit-tests in plain vitest.
 */

/** Severity ladder, least → most severe. Unifies `EVENT_LEVELS` (TRACE..ERROR) and `FiredAlert` (INFO/WARNING/CRITICAL). */
export const SIGNAL_SEVERITIES = ['trace', 'debug', 'info', 'warn', 'error', 'critical'] as const;
export type SignalSeverity = (typeof SIGNAL_SEVERITIES)[number];

/** One emitted operational fact — the unit of the Signal network. `payload` is the structured detail bag (never row content). */
export interface Signal {
    signalId: string;
    type: string; // BATCH_COMMITTED | ALERT_FIRED | JOB_SUCCEEDED | EXPECTATION_FAILED | OBJECT_ACTIVITY | AUDIT | …
    at: number; // epoch millis
    source: Ref; // {kind,id,rel:'emits'} — the producer that raised it
    correlationId?: string | null;
    severity: SignalSeverity;
    payload: Record<string, unknown>;
}

/** Notify-worthy signal types — `fanOut` runs for these when a signal is emitted (see `notifyMeta`). */
export const NOTIFY_TYPES = new Set<string>(['ALERT_FIRED', 'INCIDENT_OPENED', 'EXPECTATION_FAILED']);

/** `EventRow.level` values, TRACE..ERROR (mirrors `EVENT_LEVELS` without importing it, to keep this leaf-free). */
const EVENT_LEVEL_OF: Record<SignalSeverity, string> = {
    trace: 'TRACE', debug: 'DEBUG', info: 'INFO', warn: 'WARN', error: 'ERROR', critical: 'ERROR',
};
const SEVERITY_OF_LEVEL: Record<string, SignalSeverity> = {
    TRACE: 'trace', DEBUG: 'debug', INFO: 'info', WARN: 'warn', ERROR: 'error',
};
const ALERT_SEVERITY_OF: Record<SignalSeverity, string> = {
    trace: 'INFO', debug: 'INFO', info: 'INFO', warn: 'WARNING', error: 'CRITICAL', critical: 'CRITICAL',
};
const SEVERITY_OF_ALERT: Record<string, SignalSeverity> = { INFO: 'info', WARNING: 'warn', CRITICAL: 'critical' };

/** The `EVENT_LEVELS` projection of a severity (`critical` collapses to `ERROR` — the event ladder tops out there). */
export function severityToLevel(sev: SignalSeverity): string {
    return EVENT_LEVEL_OF[sev] ?? 'INFO';
}
/** A legacy `EventRow.level` lifted onto the severity ladder. */
export function levelToSeverity(level: string): SignalSeverity {
    return SEVERITY_OF_LEVEL[level] ?? 'info';
}
/** A `FiredAlert.severity` (INFO/WARNING/CRITICAL) lifted onto the severity ladder. */
export function alertSeverityToSignal(severity: string): SignalSeverity {
    return SEVERITY_OF_ALERT[severity] ?? 'info';
}

export function isAlertSignal(s: Signal): boolean {
    return s.type === 'ALERT_FIRED';
}

/** A human label for a signal's source Ref: `engine` / `audit` for system producers, else `<kind>/<id>`. */
export function sourceLabel(source: Ref): string {
    return source.kind === source.id ? source.kind : `${source.kind}/${source.id}`;
}

/** Project a Signal onto the `EventRow` view (`/events` read surface). `severity`/`sourceRef` are display-only extras. */
export function signalToEvent(s: Signal): EventRow {
    const p = s.payload;
    return {
        eventId: s.signalId,
        ts: s.at,
        timestamp: new Date(s.at).toISOString(),
        level: severityToLevel(s.severity),
        type: s.type,
        source: sourceLabel(s.source),
        pipeline: (p['pipeline'] as string | undefined) ?? null,
        correlationId: s.correlationId ?? null,
        message: (p['message'] as string | undefined) ?? s.type,
        attributes: (p['attributes'] as Record<string, string> | undefined) ?? {},
        severity: s.severity,
        sourceRef: s.source,
    };
}

/** Project an alert-typed Signal onto the `FiredAlert` view (`/alerts` read surface). */
export function signalToAlert(s: Signal): FiredAlert {
    const p = s.payload;
    return {
        rule: (p['rule'] as string | undefined) ?? '',
        severity: ALERT_SEVERITY_OF[s.severity] ?? 'INFO',
        pipeline: (p['pipeline'] as string | undefined) ?? '',
        metric: (p['metric'] as string | undefined) ?? '',
        value: Number(p['value'] ?? 0),
        comparator: (p['comparator'] as string | undefined) ?? '',
        threshold: Number(p['threshold'] ?? 0),
        window: (p['window'] as string | undefined) ?? '',
        epochMillis: s.at,
        message: (p['message'] as string | undefined) ?? '',
    };
}

/** Adapter for seeds/producers that still build an `EventRow` literal — lift it into the canonical Signal shape. */
export function eventToSignal(e: EventRow): Signal {
    return {
        signalId: e.eventId,
        type: e.type,
        at: e.ts,
        source: { kind: e.source, id: e.source, rel: 'emits' },
        correlationId: e.correlationId,
        severity: levelToSeverity(e.level),
        payload: { message: e.message, pipeline: e.pipeline, attributes: e.attributes },
    };
}

/** Adapter for seeds that still build a `FiredAlert` literal — lift it into the canonical Signal shape. */
export function alertToSignal(a: FiredAlert, signalId = `alert-${a.epochMillis}`): Signal {
    return {
        signalId,
        type: 'ALERT_FIRED',
        at: a.epochMillis,
        source: { kind: 'alert-rule', id: a.rule, rel: 'emits' },
        correlationId: null,
        severity: alertSeverityToSignal(a.severity),
        payload: {
            rule: a.rule, pipeline: a.pipeline, metric: a.metric, value: a.value,
            comparator: a.comparator, threshold: a.threshold, window: a.window, message: a.message,
        },
    };
}

/** The `fanOut` arguments for a notify-worthy signal (else null). Keeps notification bodies stable across producers. */
export function notifyMeta(s: Signal): { category: string; title: string; body: string; sourceId: string | null } | null {
    const p = s.payload;
    if (s.type === 'ALERT_FIRED') {
        return { category: 'OPS', title: `Alert: ${p['rule']}`, body: `${p['metric']} on ${p['pipeline']}`, sourceId: (p['rule'] as string) ?? null };
    }
    if (s.type === 'INCIDENT_OPENED') {
        return { category: 'OPS', title: `Incident opened: ${p['title']}`, body: (p['description'] as string) ?? '', sourceId: s.source.id };
    }
    if (s.type === 'EXPECTATION_FAILED') {
        return { category: 'OPS', title: (p['title'] as string) ?? `Expectation failed: ${p['name']}`, body: (p['description'] as string) ?? '', sourceId: (p['incidentId'] as string) ?? s.source.id };
    }
    return null;
}

import type { AlertRule, FiredAlert } from '../../api/alerts.service';
import { EVENT_LEVELS, type EventRow } from '../../api/events.service';
import type { AuditRow, EnrichmentJobView } from '../../api/models';
import type { ObjectGraph, ObjectLink, ObjectNote, OperationalObject } from '../../api/objects.service';
import { type Signal, alertToSignal, isAlertSignal, signalToAlert, signalToEvent } from '../../signal/signal';
import { MockFlags } from '../mock-flags';
import { error, json, match, MockHandler, MockRequest } from '../mock-http';
import { MockStore } from '../mock-store';
import { emitSignal, SIGNALS_COLL } from '../signals';

/**
 * The operational-intelligence mock domain (events · alerts · objects · enrichment) — the port of
 * the old `ops-mock` interceptor onto the persistent {@link MockStore}, so the reusable query panel
 * runs over real-shaped, reload-surviving rows with no backend. Events and fired alerts are also
 * appended to by the liveness simulator (`../simulator.ts`), so the Ops screens visibly move.
 * User-triggered mutations the old mock let pass (transition/comment) still fall through.
 */

export const EVENT_VIEWS_COLL = 'event-view';
export const ALERT_RULES_COLL = 'alert-rule';
export const OPS_OBJECTS_COLL = 'ops-object';
export const OBJECT_LINKS_COLL = 'object-link';
export const OBJECT_NOTES_COLL = 'object-note';
export const ENRICHMENT_COLL = 'enrichment-job';

const EVENTS_SEARCH = /\/events\/search$/;
const EVENTS_EXPORT = /\/events\/export$/;
const EVENTS_VIEWS = /\/events\/views$/;
const EVENT_VIEW_DELETE = /\/events\/views\/([^/]+)\/delete$/;
const ALERTS_RULES = /\/alerts\/rules$/;
const ALERTS_EVAL = /\/alerts\/evaluate$/;
const ALERTS = /\/alerts$/;
const OBJECTS = /\/objects$/;
const OBJECT_ONE = /\/objects\/([^/]+)$/;
const OBJECT_TRANSITION = /\/objects\/([^/]+)\/transition$/;
const OBJECT_LINKS = /\/objects\/([^/]+)\/links$/;
const OBJECT_GRAPH = /\/objects\/([^/]+)\/graph$/;
const OBJECT_COMMENTS = /\/objects\/([^/]+)\/comments$/;
const OBJECT_ATTACHMENTS = /\/objects\/([^/]+)\/attachments$/;
const OBJECT_RCA = /\/objects\/([^/]+)\/rca$/;
const RCA_TEMPLATES = /\/rca\/templates$/;

/** Happy-path workflow: action → resulting status (the real backend validates per-type state machines). */
const OBJECT_ACTION_STATUS: Record<string, string> = {
    assign: 'ASSIGNED',
    start: 'IN_PROGRESS',
    investigate: 'INVESTIGATING',
    escalate: 'ESCALATED',
    ack: 'ACKNOWLEDGED',
    resolve: 'RESOLVED',
    close: 'CLOSED',
};

const RCA_TEMPLATE_CATALOG = [
    { name: 'incident-default', sections: ['Summary', 'Timeline', 'Root cause', 'Impact', 'Remediation'] },
    { name: 'case-fraud', sections: ['Summary', 'Entities involved', 'Evidence', 'Findings', 'Disposition'] },
];
const ENRICH_RUNS = /\/enrichment\/([^/]+)\/runs$/;
const ENRICH_LINEAGE = /\/enrichment\/([^/]+)\/lineage$/;
const ENRICH_LIST = /\/enrichment$/;

export function opsHandler(flags: MockFlags): MockHandler {
    return (req: MockRequest, store: MockStore) => {
        if (!flags.mockOps) return undefined;
        const { method, url, space } = req;
        let m: string[] | null;

        if (method === 'GET' && EVENTS_SEARCH.test(url)) {
            return json(filterEvents(projectEvents(store, space), req.params));
        }
        if (method === 'GET' && EVENTS_EXPORT.test(url)) {
            return json(eventsCsv(filterEvents(projectEvents(store, space), req.params)));
        }
        if (method === 'GET' && EVENTS_VIEWS.test(url)) return json(store.list(space, EVENT_VIEWS_COLL));
        if (method === 'POST' && EVENTS_VIEWS.test(url)) {
            // The real API takes a FLATTENED body ({name, level, type, …}); tolerate a nested `filters` too.
            const { name = 'view', filters, ...rest } = (req.body ?? {}) as {
                name?: string;
                filters?: Record<string, string>;
            } & Record<string, string>;
            const view = { name, filters: filters ?? rest, createdAt: Date.now() };
            return json(store.put(space, EVENT_VIEWS_COLL, view.name, view));
        }
        if (method === 'POST' && (m = match(url, EVENT_VIEW_DELETE))) {
            store.delete(space, EVENT_VIEWS_COLL, m[1]);
            return json({ deleted: m[1] });
        }
        if (method === 'GET' && ALERTS_RULES.test(url)) return json(store.list<AlertRule>(space, ALERT_RULES_COLL));
        if (method === 'POST' && ALERTS_EVAL.test(url)) {
            // Manual sweep: breach the first armed rule so the button visibly does something in mock dev.
            const rule = store.list<AlertRule>(space, ALERT_RULES_COLL)[0];
            if (!rule) return json([]);
            const fired: FiredAlert = {
                rule: rule.name,
                severity: rule.severity,
                pipeline: rule.onPipeline ?? 'cdr_ingest',
                metric: rule.metric,
                value: rule.threshold + 1,
                comparator: rule.comparator,
                threshold: rule.threshold,
                window: rule.window,
                epochMillis: Date.now(),
                message: `Manual sweep: ${rule.metric} ${rule.comparator} ${rule.threshold} breached (${rule.name})`,
            };
            // Emit to the one ledger; emitSignal fans out the notification. /alerts reads it back as a FiredAlert.
            emitSignal(store, space, alertToSignal(fired, `fired-${fired.epochMillis}`));
            return json([fired]);
        }
        if (method === 'GET' && ALERTS.test(url)) {
            const sorted = projectAlerts(store, space).sort((a, b) => b.epochMillis - a.epochMillis);
            const limit = Number(req.params['limit']);
            return json(Number.isFinite(limit) && limit > 0 ? sorted.slice(0, limit) : sorted);
        }
        if (method === 'GET' && OBJECTS.test(url)) {
            return json(filterObjects(store.list<OperationalObject>(space, OPS_OBJECTS_COLL), req.params));
        }
        if (method === 'POST' && OBJECTS.test(url)) {
            const b = (req.body ?? {}) as Record<string, unknown> & { title?: string };
            if (!b.title) return error(422, 'title is required');
            const now = Date.now();
            const obj: OperationalObject = {
                id: 'obj-' + now,
                objectType: String(b['type'] ?? 'INCIDENT').toUpperCase(),
                title: b.title,
                description: String(b['description'] ?? ''),
                status: 'OPEN',
                severity: b['severity'] as string | undefined,
                priority: b['priority'] as string | undefined,
                owner: b['owner'] as string | undefined,
                assignee: b['assignee'] as string | undefined,
                correlationId: b['correlationId'] as string | undefined,
                attributes: b['dueInMinutes'] ? { dueAt: String(now + Number(b['dueInMinutes']) * 60_000) } : {},
                createdAt: now,
                updatedAt: now,
                closedAt: 0,
            };
            store.put(space, OPS_OBJECTS_COLL, obj.id, obj);
            if (obj.objectType === 'INCIDENT') {
                // Emit an INCIDENT_OPENED signal; emitSignal fans out the notification (was a direct fanOut).
                emitSignal(store, space, {
                    signalId: `incident-${now}`,
                    type: 'INCIDENT_OPENED',
                    at: now,
                    source: { kind: 'incident', id: obj.id, rel: 'emits' },
                    correlationId: obj.correlationId ?? null,
                    severity: obj.severity?.toUpperCase() === 'CRITICAL' ? 'critical' : 'warn',
                    payload: { title: obj.title, description: obj.description },
                });
            }
            return json(obj);
        }
        if (method === 'POST' && (m = match(url, OBJECT_TRANSITION))) {
            const obj = store.get<OperationalObject>(space, OPS_OBJECTS_COLL, m[1]);
            if (!obj) return error(404, `object ${m[1]} not found`);
            const action = String((req.body as { action?: string })?.action ?? '');
            const status = OBJECT_ACTION_STATUS[action];
            if (!status) return error(422, `unknown action "${action}"`);
            const now = Date.now();
            const next = { ...obj, status, updatedAt: now, closedAt: status === 'CLOSED' ? now : obj.closedAt };
            store.put(space, OPS_OBJECTS_COLL, next.id, next);
            // A user action is a signal producer too (living-operational-system §5): the operator moved an object.
            emitSignal(store, space, {
                signalId: `obj-act-${now}`,
                type: 'OBJECT_ACTIVITY',
                at: now,
                source: { kind: 'user', id: 'operator', rel: 'emits' },
                correlationId: obj.correlationId ?? next.id,
                severity: 'info',
                payload: { message: `${action} → ${status} on ${obj.title}`, objectId: obj.id, objectType: obj.objectType, action, status },
            });
            return json(next);
        }
        if (method === 'GET' && (m = match(url, OBJECT_LINKS))) {
            const id = m[1];
            return json(store.list<ObjectLink>(space, OBJECT_LINKS_COLL).filter((l) => l.from === id || l.to === id));
        }
        if (method === 'POST' && (m = match(url, OBJECT_LINKS))) {
            const from = store.get<OperationalObject>(space, OPS_OBJECTS_COLL, m[1]);
            if (!from) return error(404, `object ${m[1]} not found`);
            const b = (req.body ?? {}) as { to?: string; relationship?: string };
            const to = store.get<OperationalObject>(space, OPS_OBJECTS_COLL, b.to ?? '');
            if (!to) return error(422, `target object ${b.to} not found`);
            const link: ObjectLink = {
                from: from.id,
                fromType: from.objectType,
                to: to.id,
                toType: to.objectType,
                relationship: b.relationship ?? 'RELATED_TO',
                createdAt: Date.now(),
            };
            return json(store.put(space, OBJECT_LINKS_COLL, `${link.from}->${link.to}:${link.relationship}`, link));
        }
        if (method === 'GET' && (m = match(url, OBJECT_GRAPH))) {
            return json(objectGraph(
                m[1],
                Number(req.params['depth']) || 2,
                store.list<OperationalObject>(space, OPS_OBJECTS_COLL),
                store.list<ObjectLink>(space, OBJECT_LINKS_COLL),
            ));
        }
        if ((m = match(url, OBJECT_COMMENTS)) || (m = match(url, OBJECT_ATTACHMENTS))) {
            const id = m[1];
            const kind = OBJECT_COMMENTS.test(url) ? 'COMMENT' : 'ATTACHMENT';
            if (method === 'GET') {
                return json(store.list<ObjectNote>(space, OBJECT_NOTES_COLL)
                    .filter((n) => n.objectId === id && n.kind === kind)
                    .sort((a, b) => a.createdAt - b.createdAt));
            }
            if (method === 'POST') {
                if (!store.get(space, OPS_OBJECTS_COLL, id)) return error(404, `object ${id} not found`);
                const b = (req.body ?? {}) as Record<string, string>;
                if (kind === 'COMMENT' && !b['body']) return error(422, 'body is required');
                if (kind === 'ATTACHMENT' && (!b['name'] || !b['uri'])) return error(422, 'name and uri are required');
                return json(putNote(store, space, id, kind, b['author'] ?? 'operator',
                    kind === 'COMMENT' ? b['body'] : (b['caption'] ?? b['name']),
                    kind === 'ATTACHMENT'
                        ? { name: b['name'], uri: b['uri'], ...(b['contentType'] ? { contentType: b['contentType'] } : {}) }
                        : undefined));
            }
        }
        if (method === 'POST' && (m = match(url, OBJECT_RCA))) {
            const id = m[1];
            if (!store.get(space, OPS_OBJECTS_COLL, id)) return error(404, `object ${id} not found`);
            const t = (req.body as { template?: string | { name?: string; sections?: string[] } })?.template;
            const sections = typeof t === 'string'
                ? (RCA_TEMPLATE_CATALOG.find((c) => c.name === t)?.sections ?? [])
                : (t?.sections ?? []);
            if (!sections.length) return error(422, 'unknown RCA template');
            // One comment per section, matching the pane's "seed an RCA skeleton" semantics.
            return json(sections.map((s) => putNote(store, space, id, 'COMMENT', 'rca', `## ${s}\n`)));
        }
        if (method === 'GET' && RCA_TEMPLATES.test(url)) return json(RCA_TEMPLATE_CATALOG);
        if (method === 'GET' && (m = match(url, OBJECT_ONE))) {
            const obj = store.get<OperationalObject>(space, OPS_OBJECTS_COLL, m[1]);
            return obj ? json(obj) : error(404, `object ${m[1]} not found`);
        }
        if (method === 'GET' && ((m = match(url, ENRICH_RUNS)) || (m = match(url, ENRICH_LINEAGE)))) {
            return json(enrichRuns(m[1]));
        }
        if (method === 'GET' && ENRICH_LIST.test(url)) return json(store.list<EnrichmentJobView>(space, ENRICHMENT_COLL));

        return undefined;
    };
}

/** Apply the GET /objects query semantics: type/status/severity/assignee/correlationId exact, `q` substring, `limit`. */
export function filterObjects(rows: OperationalObject[], params: Record<string, string>): OperationalObject[] {
    const q = params['q']?.toLowerCase();
    let out = rows
        .filter((o) => !params['type'] || o.objectType === params['type'].toUpperCase())
        .filter((o) => !params['status'] || o.status === params['status'].toUpperCase())
        .filter((o) => !params['severity'] || o.severity === params['severity'].toUpperCase())
        .filter((o) => !params['assignee'] || o.assignee === params['assignee'])
        .filter((o) => !params['correlationId'] || o.correlationId === params['correlationId'])
        .filter((o) => !q || (o.title + ' ' + o.description).toLowerCase().includes(q))
        .sort((a, b) => b.updatedAt - a.updatedAt);
    const limit = Number(params['limit']);
    if (Number.isFinite(limit) && limit > 0) out = out.slice(0, limit);
    return out;
}

/** Correlation subgraph via undirected BFS from `root` up to `depth` hops; edges among included nodes only. */
export function objectGraph(root: string, depth: number, objects: OperationalObject[], links: ObjectLink[]): ObjectGraph {
    const byId = new Map(objects.map((o) => [o.id, o]));
    const included = new Set<string>();
    let frontier = [root];
    for (let d = 0; d <= depth && frontier.length; d++) {
        const next: string[] = [];
        for (const id of frontier) {
            if (included.has(id) || !byId.has(id)) continue;
            included.add(id);
            for (const l of links) {
                if (l.from === id) next.push(l.to);
                if (l.to === id) next.push(l.from);
            }
        }
        frontier = next;
    }
    const nodes = [...included].map((id) => byId.get(id)!).map((o) => ({
        id: o.id, objectType: o.objectType, title: o.title, status: o.status, severity: o.severity,
    }));
    const edges = links.filter((l) => included.has(l.from) && included.has(l.to));
    return { root, depth, nodes, edges };
}

let noteSeq = 0;

function putNote(
    store: MockStore, space: string, objectId: string, kind: string, author: string, body: string,
    attributes?: Record<string, string>,
): ObjectNote {
    const note: ObjectNote = {
        id: `note-${Date.now()}-${++noteSeq}`,
        objectId, kind, author, body,
        ...(attributes ? { attributes } : {}),
        createdAt: Date.now(),
    };
    return store.put(space, OBJECT_NOTES_COLL, note.id, note);
}

/** Project the unified Signal ledger onto the `EventRow` view (`/events` read surface, R4). */
export function projectEvents(store: MockStore, space: string): EventRow[] {
    return store.list<Signal>(space, SIGNALS_COLL).map(signalToEvent);
}

/**
 * Project the ledger's alert **records** onto the `FiredAlert` view (`/alerts` read surface, R4). An
 * alert record carries a `rule` payload — this excludes bare `ALERT_FIRED` *log* signals (operational
 * entries that merely mention an alert, no structured fields), which still appear in the Events ledger.
 */
export function projectAlerts(store: MockStore, space: string): FiredAlert[] {
    return store.list<Signal>(space, SIGNALS_COLL).filter((s) => isAlertSignal(s) && s.payload['rule']).map(signalToAlert);
}

/**
 * Apply the GET /events/search query semantics over stored rows: newest-first; `level` is a MINIMUM on
 * the {@link EVENT_LEVELS} ladder; `type`/`pipeline`/`correlationId` exact; `q` case-insensitive
 * substring on message|source; `limit` caps the page.
 */
export function filterEvents(rows: EventRow[], params: Record<string, string>): EventRow[] {
    const minLevel = params['level'] ? EVENT_LEVELS.indexOf(params['level'] as (typeof EVENT_LEVELS)[number]) : -1;
    const q = params['q']?.toLowerCase();
    let out = rows
        .filter((e) => minLevel < 0 || EVENT_LEVELS.indexOf(e.level as (typeof EVENT_LEVELS)[number]) >= minLevel)
        .filter((e) => !params['type'] || e.type === params['type'])
        .filter((e) => !params['pipeline'] || e.pipeline === params['pipeline'])
        .filter((e) => !params['correlationId'] || e.correlationId === params['correlationId'])
        .filter((e) => !q || e.message.toLowerCase().includes(q) || e.source.toLowerCase().includes(q))
        .sort((a, b) => b.ts - a.ts);
    const limit = Number(params['limit']);
    if (Number.isFinite(limit) && limit > 0) out = out.slice(0, limit);
    return out;
}

/** Matching events as CSV text (GET /events/export) — same column order as the real exporter. */
export function eventsCsv(rows: EventRow[]): string {
    const cols: (keyof EventRow)[] = ['timestamp', 'level', 'type', 'source', 'pipeline', 'correlationId', 'message'];
    const esc = (v: unknown): string => {
        const s = v == null ? '' : String(v);
        return /[",\n]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s;
    };
    return [cols.join(','), ...rows.map((r) => cols.map((c) => esc(r[c])).join(','))].join('\n');
}

/** Deterministic per-job run history — generated, not stored (read-only reporting rows). */
function enrichRuns(job: string): AuditRow[] {
    const now = Date.now();
    return Array.from({ length: 20 }, (_, i) => ({
        run_id: job + '-r' + (1000 + i),
        status: i % 7 === 0 ? 'FAILED' : 'SUCCESS',
        output_rows: String((i * 311) % 9000),
        duration_ms: String(800 + ((i * 53) % 5000)),
        run_ts: new Date(now - i * 86_400_000).toISOString(),
    }));
}

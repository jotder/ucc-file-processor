import type { AlertRule, FiredAlert } from '../../api/alerts.service';
import { EVENT_LEVELS, type EventRow } from '../../api/events.service';
import type { AuditRow, EnrichmentJobView } from '../../api/models';
import {
    normalizeIncidentStatus,
    type ObjectGraph,
    type ObjectLink,
    type ObjectNote,
    type CaseRule,
    type OperationalObject,
    type Tag,
    type TagRule,
    type TagRuleFilter,
} from '../../api/objects.service';
import { type Signal, alertToSignal, isAlertSignal, signalToAlert, signalToEvent } from '../../signal/signal';
import { MockFlags } from '../mock-flags';
import { error, json, match, MockHandler, MockRequest } from '../mock-http';
import { MockStore } from '../mock-store';
import { emitSignal, SIGNALS_COLL } from '../signals';
import { batches, PIPELINES } from './demo.handler';

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
export const TAGS_COLL = 'tag';
export const TAG_RULES_COLL = 'tag-rule';
export const CASE_RULES_COLL = 'case-rule';

const EVENTS_SEARCH = /\/events\/search$/;
const EVENTS_EXPORT = /\/events\/export$/;
const EVENTS_VIEWS = /\/events\/views$/;
const EVENT_VIEW_DELETE = /\/events\/views\/([^/]+)\/delete$/;
const ALERTS_RULES = /\/alerts\/rules$/;
const ALERTS_RULE_ONE = /\/alerts\/rules\/([^/]+)$/;
const ALERTS_EVAL = /\/alerts\/evaluate$/;
const ALERTS = /\/alerts$/;
const OBJECTS = /\/objects$/;
const OBJECTS_ANALYTICS = /\/objects\/analytics$/;
const CASE_RULES = /\/cases\/rules$/;
const CASE_RULE_ONE = /\/cases\/rules\/([^/]+)$/;
const CASE_RULE_EVAL = /\/cases\/rules\/([^/]+)\/evaluate$/;
const OBJECT_ONE = /\/objects\/([^/]+)$/;
const OBJECT_TRANSITION = /\/objects\/([^/]+)\/transition$/;
const OBJECT_LINKS = /\/objects\/([^/]+)\/links$/;
const OBJECT_MERGE = /\/objects\/([^/]+)\/merge$/;
const OBJECT_SPLIT = /\/objects\/([^/]+)\/split$/;
const OBJECT_GRAPH = /\/objects\/([^/]+)\/graph$/;
const OBJECT_COMMENTS = /\/objects\/([^/]+)\/comments$/;
const OBJECT_ATTACHMENTS = /\/objects\/([^/]+)\/attachments$/;
const OBJECT_RCA = /\/objects\/([^/]+)\/rca$/;
const RCA_TEMPLATES = /\/rca\/templates$/;
const WORKFLOW_ONE = /\/workflows\/([^/]+)$/;
const TAGS = /\/tags$/;
const TAG_RULES = /\/tags\/rules$/;
const TAG_RULE_ONE = /\/tags\/rules\/([^/]+)$/;
const TAG_RULE_APPLY = /\/tags\/rules\/([^/]+)\/apply$/;

/** Happy-path workflow: action → resulting status (the real backend validates per-type state machines). */
const OBJECT_ACTION_STATUS: Record<string, string> = {
    assign: 'ASSIGNED',
    start: 'IN_PROGRESS',
    investigate: 'INVESTIGATING',
    escalate: 'ESCALATED',
    ack: 'ACKNOWLEDGED',
    resolve: 'RESOLVED',
    close: 'CLOSED',
    // Incident mail-lifecycle (GLOSSARY §9): IDENTIFIED → DIAGNOSING → RESOLVED → ARCHIVED.
    accept: 'DIAGNOSING',
    archive: 'ARCHIVED',
    reopen: 'DIAGNOSING',
};

/** Terminal statuses that stamp `closedAt`. */
const CLOSING_STATUSES = new Set(['CLOSED', 'ARCHIVED']);

/** The effective lifecycles (GET /workflows/{type}) — mirrors the backend built-ins (C6). */
const WORKFLOWS: Record<string, unknown> = {
    INCIDENT: {
        type: 'INCIDENT', initial: 'IDENTIFIED',
        states: ['IDENTIFIED', 'ARCHIVED', 'DIAGNOSING', 'RESOLVED'],
        terminal: ['ARCHIVED'],
        transitions: [
            { from: 'IDENTIFIED', to: 'DIAGNOSING', action: 'accept' },
            { from: 'IDENTIFIED', to: 'ARCHIVED', action: 'archive' },
            { from: 'IDENTIFIED', to: 'RESOLVED', action: 'resolve' },
            { from: 'DIAGNOSING', to: 'ARCHIVED', action: 'archive' },
            { from: 'DIAGNOSING', to: 'RESOLVED', action: 'resolve' },
            { from: 'RESOLVED', to: 'ARCHIVED', action: 'archive' },
            { from: 'RESOLVED', to: 'DIAGNOSING', action: 'reopen' },
            { from: 'ARCHIVED', to: 'DIAGNOSING', action: 'reopen' },
        ],
    },
    CASE: {
        type: 'CASE', initial: 'OPEN',
        states: ['OPEN', 'INVESTIGATING', 'ESCALATED', 'RESOLVED', 'CLOSED'],
        terminal: ['CLOSED'],
        transitions: [
            { from: 'OPEN', to: 'INVESTIGATING', action: 'investigate' },
            { from: 'INVESTIGATING', to: 'ESCALATED', action: 'escalate' },
            { from: 'INVESTIGATING', to: 'RESOLVED', action: 'resolve' },
            { from: 'ESCALATED', to: 'RESOLVED', action: 'resolve' },
            { from: 'RESOLVED', to: 'CLOSED', action: 'close' },
        ],
    },
    ALERT: {
        type: 'ALERT', initial: 'OPEN',
        states: ['OPEN', 'ACKNOWLEDGED', 'RESOLVED'],
        terminal: ['RESOLVED'],
        transitions: [
            { from: 'OPEN', to: 'ACKNOWLEDGED', action: 'ack' },
            { from: 'OPEN', to: 'RESOLVED', action: 'resolve' },
            { from: 'ACKNOWLEDGED', to: 'RESOLVED', action: 'resolve' },
        ],
    },
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
        // Rule authoring (audit C3; mirrors /decision-rules). Store-backed so authored rules
        // survive reload and feed the evaluate sweep below like seeded ones.
        if (method === 'POST' && ALERTS_RULES.test(url)) {
            const b = (req.body ?? {}) as Partial<AlertRule>;
            if (!b.name || !b.metric) return error(422, 'name and metric are required');
            if (store.has(space, ALERT_RULES_COLL, b.name)) return error(409, `alert rule "${b.name}" already exists`);
            return json(store.put(space, ALERT_RULES_COLL, b.name, b as AlertRule));
        }
        if (method === 'PUT' && (m = match(url, ALERTS_RULE_ONE))) {
            const name = m[1]; // match() already URI-decodes captures
            if (!store.has(space, ALERT_RULES_COLL, name)) return error(404, `no alert rule "${name}"`);
            const b = (req.body ?? {}) as Partial<AlertRule>;
            return json(store.put(space, ALERT_RULES_COLL, name, { ...b, name } as AlertRule));
        }
        if (method === 'DELETE' && (m = match(url, ALERTS_RULE_ONE))) {
            const name = m[1];
            if (!store.has(space, ALERT_RULES_COLL, name)) return error(404, `no alert rule "${name}"`);
            store.delete(space, ALERT_RULES_COLL, name);
            return json({ deleted: name });
        }
        if (method === 'POST' && ALERTS_EVAL.test(url)) {
            // Manual sweep: real ledger math per armed rule (mirrors AlertService.evaluate — ledger
            // rows -> metricValue -> comparator), not a fabricated breach. A rule with no `onPipeline`
            // is checked against every pipeline (each breaching pipeline fires its own alert).
            const now = Date.now();
            const fired: FiredAlert[] = [];
            for (const rule of store.list<AlertRule>(space, ALERT_RULES_COLL)) {
                const targets = rule.onPipeline ? [rule.onPipeline] : PIPELINES;
                for (const pipeline of targets) {
                    const rows = rowsInWindow(batches(pipeline), rule.window, now);
                    if (!rows.length) continue;
                    const value = ledgerMetric(rule.metric, rows);
                    if (!breaches(rule, value)) continue;
                    const alert: FiredAlert = {
                        rule: rule.name,
                        severity: rule.severity,
                        pipeline,
                        metric: rule.metric,
                        value,
                        comparator: rule.comparator,
                        threshold: rule.threshold,
                        window: rule.window,
                        epochMillis: now,
                        message: `${rule.metric} ${rule.comparator} ${rule.threshold} breached on ${pipeline} (${rule.name})`,
                    };
                    // Emit to the one ledger; emitSignal fans out the notification. /alerts reads it back.
                    emitSignal(store, space, alertToSignal(alert, `fired-${now}-${fired.length}`));
                    fired.push(alert);
                }
            }
            return json(fired);
        }
        if (method === 'GET' && ALERTS.test(url)) {
            const sorted = projectAlerts(store, space).sort((a, b) => b.epochMillis - a.epochMillis);
            const limit = Number(req.params['limit']);
            return json(Number.isFinite(limit) && limit > 0 ? sorted.slice(0, limit) : sorted);
        }
        // Checked before the /objects list + /objects/{id} routes so "analytics" isn't read as an id (C4).
        if (method === 'GET' && OBJECTS_ANALYTICS.test(url)) {
            return json(objectAnalytics(store.list<OperationalObject>(space, OPS_OBJECTS_COLL), req.params['type']));
        }
        if (method === 'GET' && OBJECTS.test(url)) {
            return json(filterObjects(store.list<OperationalObject>(space, OPS_OBJECTS_COLL), req.params));
        }
        // Rule-raised cases (C5): checked before /objects/{id} (path prefix is /cases, no collision).
        if (method === 'GET' && CASE_RULES.test(url)) {
            return json(store.list<CaseRule>(space, CASE_RULES_COLL).sort((a, b) => a.name.localeCompare(b.name)));
        }
        if (method === 'POST' && CASE_RULES.test(url)) {
            const b = (req.body ?? {}) as Partial<CaseRule>;
            const filter = (b.filter ?? {}) as TagRuleFilter;
            if (!b.name || !b.title) return error(422, 'name and title are required');
            const hasCriterion = ['type', 'q', 'status', 'priority', 'severity', 'category']
                .some((k) => (filter as Record<string, string>)[k]);
            if (!hasCriterion) return error(422, 'a case rule needs at least one criterion');
            return json(store.put(space, CASE_RULES_COLL, b.name, {
                name: b.name, title: b.title, filter,
                threshold: b.threshold ?? 2, windowMinutes: b.windowMinutes ?? 1440,
                ...(b.category ? { category: b.category } : {}),
                ...(b.tags ? { tags: b.tags } : {}),
                createdAt: Date.now(),
            } satisfies CaseRule));
        }
        if (method === 'POST' && (m = match(url, CASE_RULE_EVAL))) {
            const rule = store.get<CaseRule>(space, CASE_RULES_COLL, m[1]);
            if (!rule) return error(404, `no case rule "${m[1]}"`);
            return evaluateCaseRule(store, space, rule);
        }
        if (method === 'DELETE' && (m = match(url, CASE_RULE_ONE))) {
            if (!store.has(space, CASE_RULES_COLL, m[1])) return error(404, `no case rule "${m[1]}"`);
            store.delete(space, CASE_RULES_COLL, m[1]);
            return json({ deleted: m[1], fileRemoved: true });
        }
        if (method === 'POST' && OBJECTS.test(url)) {
            const b = (req.body ?? {}) as Record<string, unknown> & { title?: string };
            if (!b.title) return error(422, 'title is required');
            const now = Date.now();
            const objectType = String(b['type'] ?? 'INCIDENT').toUpperCase();
            const obj: OperationalObject = {
                id: newObjId(),
                objectType,
                title: b.title,
                description: String(b['description'] ?? ''),
                // Incidents enter the mail lifecycle at IDENTIFIED (Inbox); other types keep OPEN.
                status: objectType === 'INCIDENT' ? 'IDENTIFIED' : 'OPEN',
                severity: b['severity'] as string | undefined,
                priority: b['priority'] as string | undefined,
                owner: b['owner'] as string | undefined,
                assignee: b['assignee'] as string | undefined,
                correlationId: b['correlationId'] as string | undefined,
                attributes: {
                    ...((b['attributes'] as Record<string, string> | undefined) ?? {}),
                    ...(b['dueInMinutes'] ? { dueAt: String(now + Number(b['dueInMinutes']) * 60_000) } : {}),
                },
                createdAt: now,
                updatedAt: now,
                closedAt: 0,
            };
            // Tag Rules apply automatically to incoming objects (the Gmail-filter semantics).
            autoApplyTagRules(store, space, obj);
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
            const next = { ...obj, status, updatedAt: now, closedAt: CLOSING_STATUSES.has(status) ? now : obj.closedAt };
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
        if (method === 'DELETE' && (m = match(url, OBJECT_LINKS))) {
            const from = m[1];
            if (!store.get(space, OPS_OBJECTS_COLL, from)) return error(404, `object ${from} not found`);
            const to = req.params['to'];
            if (!to) return error(400, "query must include 'to'");
            const rel = (req.params['relationship'] ?? 'RELATED_TO').toUpperCase();
            const key = `${from}->${to}:${rel}`;
            if (!store.has(space, OBJECT_LINKS_COLL, key)) return error(404, `no such link ${from} -> ${to}`);
            store.delete(space, OBJECT_LINKS_COLL, key);
            return json({ from, to, deleted: true });
        }
        // Case group management (GLOSSARY §9 — Split & Merge); mirrors ObjectService.mergeCases/splitCase.
        if (method === 'POST' && (m = match(url, OBJECT_MERGE))) {
            return mergeCases(store, space, m[1], (req.body ?? {}) as { sources?: string[]; actor?: string });
        }
        if (method === 'POST' && (m = match(url, OBJECT_SPLIT))) {
            return splitCase(store, space, m[1], (req.body ?? {}) as {
                title?: string; members?: string[]; assignee?: string; actor?: string;
            });
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
        if (method === 'GET' && (m = match(url, WORKFLOW_ONE))) {
            const wf = WORKFLOWS[m[1].toUpperCase()];
            return wf ? json(wf) : error(400, `unknown object type '${m[1]}'`);
        }
        // ── tag registry + Tag Rules (design §7 follow-up on the real backend) ───────────────
        if (method === 'GET' && TAGS.test(url)) {
            return json(store.list<Tag>(space, TAGS_COLL).sort((a, b) => a.name.localeCompare(b.name)));
        }
        if (method === 'POST' && TAGS.test(url)) {
            const name = String((req.body as { name?: string })?.name ?? '').trim();
            if (!name) return error(422, 'tag name is required');
            if (name.includes(',')) return error(422, 'tag names may not contain commas');
            if (store.has(space, TAGS_COLL, name)) return error(409, `tag "${name}" already exists`);
            return json(store.put(space, TAGS_COLL, name, { name, createdAt: Date.now() } satisfies Tag));
        }
        if (method === 'GET' && TAG_RULES.test(url)) {
            return json(store.list<TagRule>(space, TAG_RULES_COLL).sort((a, b) => a.name.localeCompare(b.name)));
        }
        if (method === 'POST' && TAG_RULES.test(url)) {
            const b = (req.body ?? {}) as Partial<TagRule>;
            const filter = b.filter ?? {};
            if (!b.name || !b.tag) return error(422, 'name and tag are required');
            const hasCriterion = ['type', 'q', 'status', 'priority', 'severity', 'category']
                .some((k) => (filter as Record<string, string>)[k]);
            if (!hasCriterion) return error(422, 'a Tag Rule needs at least one criterion');
            // Saving a rule implicitly registers its tag (Gmail creates the label with the filter).
            if (!store.has(space, TAGS_COLL, b.tag)) {
                store.put(space, TAGS_COLL, b.tag, { name: b.tag, createdAt: Date.now() } satisfies Tag);
            }
            return json(store.put(space, TAG_RULES_COLL, b.name, {
                name: b.name, tag: b.tag, filter, createdAt: Date.now(),
            } satisfies TagRule));
        }
        if (method === 'DELETE' && (m = match(url, TAG_RULE_ONE))) {
            if (!store.has(space, TAG_RULES_COLL, m[1])) return error(404, `no tag rule "${m[1]}"`);
            store.delete(space, TAG_RULES_COLL, m[1]);
            return json({ deleted: m[1] });
        }
        if (method === 'POST' && (m = match(url, TAG_RULE_APPLY))) {
            const rule = store.get<TagRule>(space, TAG_RULES_COLL, m[1]);
            if (!rule) return error(404, `no tag rule "${m[1]}"`);
            let matched = 0;
            let updated = 0;
            for (const o of store.list<OperationalObject>(space, OPS_OBJECTS_COLL)) {
                if (!tagRuleMatches(rule, o)) continue;
                matched++;
                const tags = csvTags(o.attributes?.['tags']);
                if (tags.includes(rule.tag)) continue;
                updated++;
                store.put(space, OPS_OBJECTS_COLL, o.id, {
                    ...o,
                    attributes: { ...(o.attributes ?? {}), tags: [...tags, rule.tag].join(',') },
                    updatedAt: Date.now(),
                });
            }
            return json({ matched, updated });
        }
        if (method === 'GET' && (m = match(url, OBJECT_ONE))) {
            const obj = store.get<OperationalObject>(space, OPS_OBJECTS_COLL, m[1]);
            return obj ? json(obj) : error(404, `object ${m[1]} not found`);
        }
        // Field patch (priority / severity / assignee / attributes merge) — backend follow-up, see
        // docs/superpower/incidents-mail-ui-design.md §7. Drives Prioritize / tags / postmortem saves.
        if (method === 'PATCH' && (m = match(url, OBJECT_ONE))) {
            const obj = store.get<OperationalObject>(space, OPS_OBJECTS_COLL, m[1]);
            if (!obj) return error(404, `object ${m[1]} not found`);
            const b = (req.body ?? {}) as {
                priority?: string; severity?: string; assignee?: string; attributes?: Record<string, string>;
            };
            const next: OperationalObject = {
                ...obj,
                ...(b.priority !== undefined ? { priority: b.priority } : {}),
                ...(b.severity !== undefined ? { severity: b.severity } : {}),
                ...(b.assignee !== undefined ? { assignee: b.assignee } : {}),
                attributes: { ...(obj.attributes ?? {}), ...(b.attributes ?? {}) },
                updatedAt: Date.now(),
            };
            return json(store.put(space, OPS_OBJECTS_COLL, next.id, next));
        }
        if (method === 'GET' && ((m = match(url, ENRICH_RUNS)) || (m = match(url, ENRICH_LINEAGE)))) {
            return json(enrichRuns(m[1]));
        }
        if (method === 'GET' && ENRICH_LIST.test(url)) return json(store.list<EnrichmentJobView>(space, ENRICHMENT_COLL));

        return undefined;
    };
}

/** Split the `attributes.tags` CSV. */
function csvTags(csv: string | undefined): string[] {
    return (csv ?? '').split(',').map((t) => t.trim()).filter(Boolean);
}

// ── case group management (GLOSSARY §9 — Split & Merge; mirrors ObjectService) ──────────────────

/** The ids a case CONTAINS (its member incidents). */
function membersOf(store: MockStore, space: string, caseId: string): string[] {
    return store.list<ObjectLink>(space, OBJECT_LINKS_COLL)
        .filter((l) => l.from === caseId && l.relationship === 'CONTAINS')
        .map((l) => l.to);
}

/** A case usable in a group operation: exists, is a CASE, and is not closed/merged. */
function activeCase(store: MockStore, space: string, id: string):
    { ok: OperationalObject } | { err: ReturnType<typeof error> } {
    const o = store.get<OperationalObject>(space, OPS_OBJECTS_COLL, id);
    if (!o) return { err: error(404, `object ${id} not found`) };
    if (o.objectType !== 'CASE') return { err: error(422, `${id} is not a CASE`) };
    if (o.closedAt > 0 || o.attributes?.['mergedInto'])
        return { err: error(422, `case ${id} is closed${o.attributes?.['mergedInto'] ? ' (already merged)' : ''}`) };
    return { ok: o };
}

/** Re-point a member CONTAINS edge (idempotent on the target side). */
function moveMember(store: MockStore, space: string, fromCase: string, toCase: string, member: string): void {
    store.delete(space, OBJECT_LINKS_COLL, `${fromCase}->${member}:CONTAINS`);
    store.put(space, OBJECT_LINKS_COLL, `${toCase}->${member}:CONTAINS`, {
        from: toCase, fromType: 'CASE', to: member, toType: 'INCIDENT',
        relationship: 'CONTAINS', createdAt: Date.now(),
    } satisfies ObjectLink);
}

function mergeCases(store: MockStore, space: string, survivorId: string,
                    body: { sources?: string[]; actor?: string }) {
    const sources = (body.sources ?? []).filter(Boolean);
    if (!sources.length) return error(400, "body must include non-empty 'sources'");
    const survivorCheck = activeCase(store, space, survivorId);
    if ('err' in survivorCheck) return survivorCheck.err;
    const absorbed: OperationalObject[] = [];
    for (const id of new Set(sources)) {
        if (id === survivorId) return error(422, 'a case cannot be merged into itself');
        const check = activeCase(store, space, id);
        if ('err' in check) return check.err;
        absorbed.push(check.ok);
    }
    const now = Date.now();
    let moved = 0;
    const tags = new Set(csvTags(survivorCheck.ok.attributes?.['tags']));
    for (const src of absorbed) {
        for (const memberId of membersOf(store, space, src.id)) {
            moveMember(store, space, src.id, survivorId, memberId);
            moved++;
        }
        csvTags(src.attributes?.['tags']).forEach((t) => tags.add(t));
        store.put(space, OBJECT_LINKS_COLL, `${src.id}->${survivorId}:MERGED_INTO`, {
            from: src.id, fromType: 'CASE', to: survivorId, toType: 'CASE',
            relationship: 'MERGED_INTO', createdAt: now,
        } satisfies ObjectLink);
        store.put(space, OPS_OBJECTS_COLL, src.id, {
            ...src, status: 'CLOSED', closedAt: now, updatedAt: now,
            attributes: { ...(src.attributes ?? {}), mergedInto: survivorId },
        });
        putNote(store, space, src.id, 'COMMENT', body.actor ?? 'operator', `Merged into ${survivorId}.`);
        putNote(store, space, survivorId, 'COMMENT', body.actor ?? 'operator',
            `Absorbed ${src.id} ("${src.title}").`);
    }
    const survivor = store.get<OperationalObject>(space, OPS_OBJECTS_COLL, survivorId)!;
    const updated = store.put(space, OPS_OBJECTS_COLL, survivorId, {
        ...survivor, updatedAt: now,
        attributes: { ...(survivor.attributes ?? {}), ...(tags.size ? { tags: [...tags].join(',') } : {}) },
    });
    return json({ survivor: updated, merged: absorbed.map((s) => s.id), membersMoved: moved });
}

function splitCase(store: MockStore, space: string, caseId: string,
                   body: { title?: string; members?: string[]; assignee?: string; actor?: string }) {
    const title = (body.title ?? '').trim();
    const members = (body.members ?? []).filter(Boolean);
    if (!title) return error(400, "body must include 'title'");
    if (!members.length) return error(400, "body must include non-empty 'members'");
    const check = activeCase(store, space, caseId);
    if ('err' in check) return check.err;
    const contained = new Set(membersOf(store, space, caseId));
    for (const memberId of members) {
        if (!contained.has(memberId)) return error(422, `case ${caseId} does not contain member '${memberId}'`);
    }
    const now = Date.now();
    const original = check.ok;
    const part: OperationalObject = {
        id: newObjId(),
        objectType: 'CASE',
        title,
        description: `Split from ${caseId}: ${original.title}`,
        status: 'OPEN',
        severity: original.severity,
        priority: original.priority,
        owner: original.owner,
        assignee: body.assignee || undefined,
        correlationId: original.correlationId,
        attributes: {
            ...(original.attributes?.['category'] ? { category: original.attributes['category'] } : {}),
            ...(original.attributes?.['tags'] ? { tags: original.attributes['tags'] } : {}),
        },
        createdAt: now,
        updatedAt: now,
        closedAt: 0,
    };
    store.put(space, OPS_OBJECTS_COLL, part.id, part);
    for (const memberId of members) moveMember(store, space, caseId, part.id, memberId);
    store.put(space, OBJECT_LINKS_COLL, `${part.id}->${caseId}:SPLIT_FROM`, {
        from: part.id, fromType: 'CASE', to: caseId, toType: 'CASE',
        relationship: 'SPLIT_FROM', createdAt: now,
    } satisfies ObjectLink);
    putNote(store, space, caseId, 'COMMENT', body.actor ?? 'operator',
        `Split ${members.length} member(s) out into ${part.id} ("${title}").`);
    putNote(store, space, part.id, 'COMMENT', body.actor ?? 'operator', `Split from ${caseId}.`);
    return json({ case: part, membersMoved: members.length });
}

/** Every set filter criterion must match; incident statuses are compared on the mail lifecycle. */
export function filterMatches(f: TagRuleFilter, o: OperationalObject): boolean {
    const status = o.objectType === 'INCIDENT' ? normalizeIncidentStatus(o.status) : (o.status ?? '').toUpperCase();
    if (f.type && o.objectType !== f.type.toUpperCase()) return false;
    if (f.status && status !== f.status.toUpperCase()) return false;
    if (f.priority && (o.priority ?? '').toUpperCase() !== f.priority.toUpperCase()) return false;
    if (f.severity && (o.severity ?? '').toUpperCase() !== f.severity.toUpperCase()) return false;
    if (f.category && !(o.attributes?.['category'] ?? '').startsWith(f.category)) return false;
    if (f.q && !(o.title + ' ' + o.description).toLowerCase().includes(f.q.toLowerCase())) return false;
    return true;
}

/** Every set Tag-Rule criterion must match (delegates to {@link filterMatches}). */
export function tagRuleMatches(rule: TagRule, o: OperationalObject): boolean {
    return filterMatches(rule.filter ?? {}, o);
}

/** Case analytics rollup (C4) — mirrors ObjectService.analytics. */
function objectAnalytics(rows: OperationalObject[], type: string | undefined): Record<string, unknown> {
    const t = (type ?? 'CASE').toUpperCase();
    const terminal = t === 'INCIDENT' ? ['ARCHIVED'] : t === 'CASE' ? ['CLOSED'] : ['RESOLVED', 'CLOSED', 'ARCHIVED'];
    const all = rows.filter((o) => o.objectType === t);
    const byStatus: Record<string, number> = {};
    const byCategory: Record<string, number> = {};
    const byPriority: Record<string, number> = {};
    let backlog = 0;
    let cycleSum = 0;
    let cycleCount = 0;
    let impactAmount = 0;
    let recordsAffected = 0;
    for (const o of all) {
        const st = (o.status ?? 'UNKNOWN').toUpperCase();
        byStatus[st] = (byStatus[st] ?? 0) + 1;
        const cat = (o.attributes?.['category'] ?? '').split('/')[0].trim() || 'UNCATEGORIZED';
        byCategory[cat] = (byCategory[cat] ?? 0) + 1;
        const pr = (o.priority ?? '').toUpperCase() || 'NONE';
        byPriority[pr] = (byPriority[pr] ?? 0) + 1;
        if (!terminal.includes(st)) backlog++;
        if (o.closedAt > 0 && o.closedAt >= o.createdAt) {
            cycleSum += o.closedAt - o.createdAt;
            cycleCount++;
        }
        impactAmount += Number(o.attributes?.['impactAmount']) || 0;
        recordsAffected += Number(o.attributes?.['recordsAffected']) || 0;
    }
    return {
        type: t,
        total: all.length,
        backlog,
        byStatus,
        byCategory,
        byPriority,
        cycleTime: { count: cycleCount, avgMs: cycleCount === 0 ? 0 : Math.floor(cycleSum / cycleCount) },
        impact: { impactAmount, recordsAffected },
    };
}

/**
 * Ledger-metric math for `/alerts/evaluate` — mirrors `AlertService.metricValue` /
 * `AlertService.inWindow` (window grammar `Ns|Nm|Nh|Nd` duration or `Nb` last-N-batches; metric ∈
 * error_rate|failed_batches|rejected_files|duration_ms over the pipeline's committed-batch ledger).
 * A rule whose metric isn't one of these (e.g. a BI-5 dataset/measure rule, unsupported offline)
 * evaluates to 0 rather than crashing — it simply never breaches via this sweep.
 */
function rowsInWindow(rows: Record<string, string>[], window: string, nowMs: number): Record<string, string>[] {
    const m = /^(\d+)([smhdb])$/.exec(window);
    if (!m) return rows;
    const n = Number(m[1]);
    if (m[2] === 'b') return rows.slice(0, n); // rows are newest-first already
    const spanMs = n * ({ s: 1000, m: 60_000, h: 3_600_000, d: 86_400_000 } as Record<string, number>)[m[2]];
    const cutoff = nowMs - spanMs;
    return rows.filter((r) => new Date(r['committed_at']).getTime() >= cutoff);
}

function ledgerMetric(metric: string, rows: Record<string, string>[]): number {
    switch (metric) {
        case 'error_rate': {
            const totalIn = rows.reduce((s, r) => s + Number(r['input_rows']), 0);
            const totalOut = rows.reduce((s, r) => s + Number(r['output_rows']), 0);
            return totalIn === 0 ? 0 : 1 - Math.min(totalOut, totalIn) / totalIn;
        }
        case 'failed_batches':
            return rows.filter((r) => r['status'] === 'FAILED').length;
        case 'rejected_files':
            return rows.reduce((s, r) => s + Number(r['rejected_files']), 0);
        case 'duration_ms':
            return rows.reduce((s, r) => s + Number(r['duration_ms']), 0) / rows.length;
        default:
            return 0;
    }
}

function breaches(rule: AlertRule, value: number): boolean {
    switch (rule.comparator) {
        case 'gt': return value > rule.threshold;
        case 'gte': return value >= rule.threshold;
        case 'lt': return value < rule.threshold;
        case 'lte': return value <= rule.threshold;
        default: return false;
    }
}

/** Evaluate a Case Rule (C5) — mirrors ObjectService.evaluateCaseRule (open/attach, idempotent). */
function evaluateCaseRule(store: MockStore, space: string, rule: CaseRule): ReturnType<typeof json> {
    const now = Date.now();
    const cutoff = rule.windowMinutes <= 0 ? 0 : now - rule.windowMinutes * 60_000;
    const allObjects = store.list<OperationalObject>(space, OPS_OBJECTS_COLL);
    const contained = new Set(
        store.list<ObjectLink>(space, OBJECT_LINKS_COLL)
            .filter((l) => l.relationship === 'CONTAINS')
            .map((l) => l.to),
    );
    const matches = allObjects.filter(
        (o) => o.objectType === 'INCIDENT' && o.createdAt >= cutoff && filterMatches(rule.filter, o) && !contained.has(o.id),
    );
    if (!matches.length) return json({ matched: 0, grouped: 0, caseId: null, opened: false });

    let caseId = allObjects.find(
        (o) => o.objectType === 'CASE' && o.attributes?.['raisedByRule'] === rule.name && o.closedAt === 0,
    )?.id ?? null;
    let opened = false;
    if (!caseId) {
        if (matches.length < rule.threshold) return json({ matched: matches.length, grouped: 0, caseId: null, opened: false });
        caseId = newObjId();
        store.put(space, OPS_OBJECTS_COLL, caseId, {
            id: caseId, objectType: 'CASE', title: rule.title,
            description: `Auto-raised by case rule '${rule.name}'`, status: 'OPEN',
            correlationId: matches[0].correlationId,
            attributes: {
                raisedByRule: rule.name,
                ...(rule.category ? { category: rule.category } : {}),
                ...(rule.tags ? { tags: rule.tags } : {}),
            },
            createdAt: now, updatedAt: now, closedAt: 0,
        } as OperationalObject);
        opened = true;
    }
    for (const inc of matches) {
        store.put(space, OBJECT_LINKS_COLL, `${caseId}->${inc.id}:CONTAINS`, {
            from: caseId, fromType: 'CASE', to: inc.id, toType: 'INCIDENT',
            relationship: 'CONTAINS', createdAt: now,
        } satisfies ObjectLink);
    }
    return json({ matched: matches.length, grouped: matches.length, caseId, opened });
}

/** Merge every matching Tag Rule's tag into a (new, not yet stored) object's `attributes.tags`. */
function autoApplyTagRules(store: MockStore, space: string, obj: OperationalObject): void {
    const tags = new Set(csvTags(obj.attributes?.['tags']));
    for (const rule of store.list<TagRule>(space, TAG_RULES_COLL)) {
        if (tagRuleMatches(rule, obj)) tags.add(rule.tag);
    }
    if (tags.size && obj.attributes) obj.attributes['tags'] = [...tags].join(',');
}

/** Apply the GET /objects query semantics: type/status/severity/assignee/correlationId exact, `q` substring, `offset`+`limit` (offset paging, R6 — mirrors the real ObjectQuery). */
export function filterObjects(rows: OperationalObject[], params: Record<string, string>): OperationalObject[] {
    const q = params['q']?.toLowerCase();
    const out = rows
        .filter((o) => !params['type'] || o.objectType === params['type'].toUpperCase())
        .filter((o) => !params['status'] || o.status === params['status'].toUpperCase())
        .filter((o) => !params['severity'] || o.severity === params['severity'].toUpperCase())
        .filter((o) => !params['assignee'] || o.assignee === params['assignee'])
        .filter((o) => !params['correlationId'] || o.correlationId === params['correlationId'])
        .filter((o) => !q || (o.title + ' ' + o.description).toLowerCase().includes(q))
        .sort((a, b) => b.updatedAt - a.updatedAt);
    return pageSlice(out, params);
}

/** Apply `?offset=&limit=` over filtered rows — the mock mirror of the backend's offset paging (R6). */
function pageSlice<T>(rows: T[], params: Record<string, string>): T[] {
    const offset = Number(params['offset']);
    const from = Number.isFinite(offset) && offset > 0 ? offset : 0;
    const limit = Number(params['limit']);
    return Number.isFinite(limit) && limit > 0 ? rows.slice(from, from + limit) : rows.slice(from);
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

let objSeq = 0;
/** Collision-proof object id — a bare `obj-<now>` collides when two objects are created in the same ms. */
function newObjId(): string {
    return `obj-${Date.now()}-${++objSeq}`;
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
 * substring on message|source; `offset`+`limit` select the page (offset paging, R6 — mirrors the real EventQuery).
 */
export function filterEvents(rows: EventRow[], params: Record<string, string>): EventRow[] {
    const minLevel = params['level'] ? EVENT_LEVELS.indexOf(params['level'] as (typeof EVENT_LEVELS)[number]) : -1;
    const q = params['q']?.toLowerCase();
    const out = rows
        .filter((e) => minLevel < 0 || EVENT_LEVELS.indexOf(e.level as (typeof EVENT_LEVELS)[number]) >= minLevel)
        .filter((e) => !params['type'] || e.type === params['type'])
        .filter((e) => !params['pipeline'] || e.pipeline === params['pipeline'])
        .filter((e) => !params['correlationId'] || e.correlationId === params['correlationId'])
        .filter((e) => !q || e.message.toLowerCase().includes(q) || e.source.toLowerCase().includes(q))
        .sort((a, b) => b.ts - a.ts);
    return pageSlice(out, params);
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

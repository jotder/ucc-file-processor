import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { apiUrl, toParams } from './api-base';

/** A managed operational object (GET /objects) — ALERT / INCIDENT / CASE / TASK on one table. */
export interface OperationalObject {
    id: string;
    objectType: string;
    title: string;
    description: string;
    status: string;
    severity?: string;
    priority?: string;
    owner?: string;
    assignee?: string;
    correlationId?: string;
    attributes?: Record<string, string>;
    createdAt: number;
    updatedAt: number;
    closedAt: number;
}

/** A directed correlation edge between two objects (OBJECT_LINK). */
export interface ObjectLink {
    from: string;
    fromType: string;
    to: string;
    toType: string;
    relationship: string;
    createdAt: number;
}

/** A light object summary node in a correlation subgraph (GET /objects/{id}/graph). */
export interface ObjectGraphNode {
    id: string;
    objectType: string;
    title: string;
    status: string;
    severity?: string;
}

/** A correlation subgraph around a root object. */
export interface ObjectGraph {
    root: string;
    depth: number;
    nodes: ObjectGraphNode[];
    edges: ObjectLink[];
}

/** An append-only note on an object — a comment or an attachment reference. */
export interface ObjectNote {
    id: string;
    objectId: string;
    kind: 'COMMENT' | 'ATTACHMENT' | string;
    author: string;
    body: string;
    attributes?: Record<string, string>;
    createdAt: number;
}

/** An RCA template (GET /rca/templates). */
export interface RcaTemplate {
    name: string;
    sections: string[];
}

/** One legal workflow move (GET /workflows/{type}). */
export interface WorkflowTransition {
    from: string;
    to: string;
    action: string;
}

/**
 * The effective (possibly `*_workflow.toon`-overridden) lifecycle of an object type
 * (GET /workflows/{type}) — `states` come initial-first in presentation order, so a pane can derive
 * its folders and action verbs instead of hardcoding lifecycles.
 */
export interface WorkflowDef {
    type: string;
    initial: string;
    states: string[];
    terminal: string[];
    transitions: WorkflowTransition[];
}

/** A Case Rule (GET /cases/rules) — auto-groups matching incidents into a case (GLOSSARY §9, C5). */
export interface CaseRule {
    name: string;
    title: string;
    filter: TagRuleFilter;
    threshold: number;
    windowMinutes: number;
    category?: string;
    tags?: string;
    createdAt?: number;
}

/** Evaluate outcome (POST /cases/rules/{name}/evaluate). */
export interface CaseRuleEvaluation {
    matched: number;
    grouped: number;
    caseId: string | null;
    opened: boolean;
}

/** Case analytics rollup (GET /objects/analytics?type=CASE) — C4. */
export interface ObjectAnalytics {
    type: string;
    total: number;
    backlog: number;
    byStatus: Record<string, number>;
    byCategory: Record<string, number>;
    byPriority: Record<string, number>;
    cycleTime: { count: number; avgMs: number };
    impact: { impactAmount: number; recordsAffected: number };
}

/** Merge outcome (POST /objects/{id}/merge) — GLOSSARY §9 case group management. */
export interface MergeResult {
    survivor: OperationalObject;
    merged: string[];
    membersMoved: number;
}

/** Split outcome (POST /objects/{id}/split) — GLOSSARY §9 case group management. */
export interface SplitResult {
    case: OperationalObject;
    membersMoved: number;
}

/** A user-created tag in the registry (GET /tags). Objects carry tags as a CSV in `attributes.tags`. */
export interface Tag {
    name: string;
    createdAt: number;
}

/** Criteria of a Tag Rule — every set field must match (q is a title+description substring). */
export interface TagRuleFilter {
    type?: string;
    q?: string;
    status?: string;
    priority?: string;
    severity?: string;
    /** Category path prefix, e.g. "Pipeline" or "Pipeline / Ingest". */
    category?: string;
}

/**
 * A **Tag Rule** (GLOSSARY §9) — a saved search that applies a tag, Gmail-filter style:
 * automatically to newly created objects, and in bulk to existing matches via `apply`.
 */
export interface TagRule {
    name: string;
    tag: string;
    filter: TagRuleFilter;
    createdAt?: number;
}

/**
 * Map a legacy built-in-workflow incident status onto the mail lifecycle (GLOSSARY §9:
 * IDENTIFIED → DIAGNOSING → RESOLVED → ARCHIVED), so panes and Tag Rules fold/match correctly
 * against a backend still running OPEN → ASSIGNED → IN_PROGRESS → RESOLVED → CLOSED.
 */
export function normalizeIncidentStatus(status: string | undefined): string {
    const s = (status ?? '').toUpperCase();
    switch (s) {
        case 'OPEN':
            return 'IDENTIFIED';
        case 'ASSIGNED':
        case 'IN_PROGRESS':
            return 'DIAGNOSING';
        case 'CLOSED':
            return 'ARCHIVED';
        default:
            return s;
    }
}

/** Filters for GET /objects. */
export interface ObjectFilter {
    type?: string;
    status?: string;
    severity?: string;
    assignee?: string;
    owner?: string;
    correlationId?: string;
    q?: string;
    limit?: number;
    offset?: number;
}

/** Body for POST /objects. */
export interface CreateObject {
    type?: string;
    title: string;
    description?: string;
    severity?: string;
    priority?: string;
    owner?: string;
    assignee?: string;
    correlationId?: string;
    attributes?: Record<string, string>;
    dueInMinutes?: number;
    dueAt?: number;
    /** ≥1 existing object to correlate with — mandatory at creation (product decision 2026-07-22). */
    links?: { to: string; relationship?: string }[];
}

/** Partial update for PATCH /objects/{id} — `attributes` merge onto the stored map. */
export interface UpdateObject {
    priority?: string;
    severity?: string;
    assignee?: string;
    attributes?: Record<string, string>;
}

/**
 * The Operational Intelligence object engine (Phases 2–4; CONTROL scope). One table backs ALERTs
 * (auto-promoted), INCIDENTs and CASEs (operator-created), each walking a config-driven workflow; objects
 * carry correlation links (an OBJECT_LINK graph) and an append-only note thread (comments / attachment
 * references / RCA skeletons).
 */
@Injectable({ providedIn: 'root' })
export class ObjectsService {
    private http = inject(HttpClient);

    list(filter: ObjectFilter = {}): Observable<OperationalObject[]> {
        return this.http.get<OperationalObject[]>(apiUrl('/objects'), {
            params: toParams(filter as Record<string, unknown>),
        });
    }

    get(id: string): Observable<OperationalObject> {
        return this.http.get<OperationalObject>(apiUrl(`/objects/${encodeURIComponent(id)}`));
    }

    create(body: CreateObject): Observable<OperationalObject> {
        return this.http.post<OperationalObject>(apiUrl('/objects'), body);
    }

    /** Patch mutable fields (priority / severity / assignee / attributes merge) — PATCH /objects/{id}. */
    update(id: string, patch: UpdateObject): Observable<OperationalObject> {
        return this.http.patch<OperationalObject>(apiUrl(`/objects/${encodeURIComponent(id)}`), patch);
    }

    /** Apply a workflow action (e.g. assign / start / resolve / close / investigate / escalate). */
    transition(id: string, action: string, actor?: string): Observable<OperationalObject> {
        return this.http.post<OperationalObject>(
            apiUrl(`/objects/${encodeURIComponent(id)}/transition`), { action, actor });
    }

    links(id: string): Observable<ObjectLink[]> {
        return this.http.get<ObjectLink[]>(apiUrl(`/objects/${encodeURIComponent(id)}/links`));
    }

    link(id: string, to: string, relationship: string, actor?: string): Observable<ObjectLink> {
        return this.http.post<ObjectLink>(
            apiUrl(`/objects/${encodeURIComponent(id)}/links`), { to, relationship, actor });
    }

    /** The effective lifecycle for an object type — drives workflow-derived folders/actions (C6). */
    workflow(type: string): Observable<WorkflowDef> {
        return this.http.get<WorkflowDef>(apiUrl(`/workflows/${encodeURIComponent(type)}`));
    }

    // ── rule-raised cases (C5) + analytics (C4) ─────────────────────────────────────────────

    caseRules(): Observable<CaseRule[]> {
        return this.http.get<CaseRule[]>(apiUrl('/cases/rules'));
    }

    saveCaseRule(rule: CaseRule): Observable<CaseRule> {
        return this.http.post<CaseRule>(apiUrl('/cases/rules'), rule);
    }

    deleteCaseRule(name: string): Observable<{ deleted: string }> {
        return this.http.delete<{ deleted: string }>(apiUrl(`/cases/rules/${encodeURIComponent(name)}`));
    }

    /** Auto-group matching incidents into a case (open or attach); returns the grouping outcome. */
    evaluateCaseRule(name: string): Observable<CaseRuleEvaluation> {
        return this.http.post<CaseRuleEvaluation>(apiUrl(`/cases/rules/${encodeURIComponent(name)}/evaluate`), {});
    }

    /** Case (or any object type) analytics rollup — cycle time, backlog, impact totals (C4). */
    analytics(type: string): Observable<ObjectAnalytics> {
        return this.http.get<ObjectAnalytics>(apiUrl('/objects/analytics'), { params: toParams({ type }) });
    }

    /** Remove one correlation edge (e.g. a member incident out of a Case's Contents). */
    unlink(id: string, to: string, relationship: string, actor?: string): Observable<{ deleted: boolean }> {
        return this.http.delete<{ deleted: boolean }>(apiUrl(`/objects/${encodeURIComponent(id)}/links`), {
            params: toParams({ to, relationship, actor }),
        });
    }

    /** Merge `sources` cases into the surviving case `id` (GLOSSARY §9 — Merge). */
    mergeCases(id: string, sources: string[], actor?: string): Observable<MergeResult> {
        return this.http.post<MergeResult>(apiUrl(`/objects/${encodeURIComponent(id)}/merge`), { sources, actor });
    }

    /** Split the listed member incidents out of case `id` into a new case (GLOSSARY §9 — Split). */
    splitCase(id: string, body: { title: string; members: string[]; assignee?: string; actor?: string }):
        Observable<SplitResult> {
        return this.http.post<SplitResult>(apiUrl(`/objects/${encodeURIComponent(id)}/split`), body);
    }

    graph(id: string, depth = 2): Observable<ObjectGraph> {
        return this.http.get<ObjectGraph>(apiUrl(`/objects/${encodeURIComponent(id)}/graph`), {
            params: toParams({ depth }),
        });
    }

    comments(id: string): Observable<ObjectNote[]> {
        return this.http.get<ObjectNote[]>(apiUrl(`/objects/${encodeURIComponent(id)}/comments`));
    }

    addComment(id: string, body: string, author?: string): Observable<ObjectNote> {
        return this.http.post<ObjectNote>(
            apiUrl(`/objects/${encodeURIComponent(id)}/comments`), { body, author });
    }

    attachments(id: string): Observable<ObjectNote[]> {
        return this.http.get<ObjectNote[]>(apiUrl(`/objects/${encodeURIComponent(id)}/attachments`));
    }

    addAttachment(id: string, a: { name: string; uri: string; contentType?: string; author?: string; caption?: string }):
        Observable<ObjectNote> {
        return this.http.post<ObjectNote>(apiUrl(`/objects/${encodeURIComponent(id)}/attachments`), a);
    }

    /** Seed an RCA skeleton — by template name, or an inline {sections[]} / {name,sections[]}. */
    applyRca(id: string, template: string | { name?: string; sections: string[] }, actor?: string):
        Observable<ObjectNote[]> {
        return this.http.post<ObjectNote[]>(apiUrl(`/objects/${encodeURIComponent(id)}/rca`), { template, actor });
    }

    rcaTemplates(): Observable<RcaTemplate[]> {
        return this.http.get<RcaTemplate[]>(apiUrl('/rca/templates'));
    }

    // ── tags & Tag Rules (mock-backed; real routes are a design-§7 follow-up) ────────────────

    tags(): Observable<Tag[]> {
        return this.http.get<Tag[]>(apiUrl('/tags'));
    }

    createTag(name: string): Observable<Tag> {
        return this.http.post<Tag>(apiUrl('/tags'), { name });
    }

    tagRules(): Observable<TagRule[]> {
        return this.http.get<TagRule[]>(apiUrl('/tags/rules'));
    }

    saveTagRule(rule: TagRule): Observable<TagRule> {
        return this.http.post<TagRule>(apiUrl('/tags/rules'), rule);
    }

    deleteTagRule(name: string): Observable<{ deleted: string }> {
        return this.http.delete<{ deleted: string }>(apiUrl(`/tags/rules/${encodeURIComponent(name)}`));
    }

    /** Bulk-apply a saved Tag Rule to every existing match — returns how many were tagged. */
    applyTagRule(name: string): Observable<{ matched: number; updated: number }> {
        return this.http.post<{ matched: number; updated: number }>(
            apiUrl(`/tags/rules/${encodeURIComponent(name)}/apply`), {});
    }
}

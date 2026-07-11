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
}

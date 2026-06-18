import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { apiUrl } from './api-base';

/** A node-type family (palette grouping + role checks); mirrors the backend `NodeCategory`. */
export type FlowNodeCategory = 'SOURCE' | 'PARSE' | 'TRANSFORM' | 'SINK' | 'CONTROL' | string;

/** A compact flow entry (GET /flows) — one per registered pipeline, lifted to a flow graph. */
export interface FlowSummary {
    name: string;
    active: boolean;
    nodeCount: number;
    edgeCount: number;
    produces: string[];
    consumes: string[];
}

/** One node in a flow graph projection (structural only — no raw config; the inspector shows this). */
export interface FlowNode {
    id: string;
    type: string;
    category: FlowNodeCategory;
    label: string;
    name?: string;
    description?: string;
    use?: string;
    store?: string;
    sourceStore?: string;
    sinkKind?: string;
    restsOnDisk?: boolean;
}

/** One relationship-typed edge. `kind` styles the line (solid data / dashed control / route). */
export interface FlowEdge {
    from: string;
    to: string;
    rel: string;
    kind: 'data' | 'control' | 'route';
    routeKey?: string;
}

/** A flow graph projection (GET /flows/{id}/graph) for the G6 renderer. */
export interface FlowGraph {
    name: string;
    active: boolean;
    nodes: FlowNode[];
    edges: FlowEdge[];
    produces: string[];
    consumes: string[];
}

/** A node-type descriptor for the editor palette (GET /flows/node-types). */
export interface FlowNodeType {
    type: string;
    category: FlowNodeCategory;
    label: string;
    description: string;
    accepts: string[];
    emits: string[];
    emitsNamedRoutes: boolean;
}

/** A node in the combined topology: a flow node (with its owning `flow`) or a synthetic `STORE` join node. */
export interface CombinedNode extends FlowNode {
    flow?: string;   // the owning flow (absent on synthetic store nodes)
}

/** An edge in the combined topology; `kind:'store'` is a producer→store or store→consumer join edge. */
export interface CombinedEdge {
    from: string;
    to: string;
    rel: string;
    kind: 'data' | 'control' | 'route' | 'store';
    routeKey?: string;
    restsOnDisk?: boolean;   // on store edges: whether the joined store rests on disk
    flow?: string;
}

/** The combined pipeline+job topology (GET /flows/combined): flows joined at shared store nodes (T24). */
export interface FlowCombined {
    flows: { name: string; active: boolean }[];
    nodes: CombinedNode[];
    edges: CombinedEdge[];
    links: { producer: string; store: string; consumer: string }[];
}

/** Read-only flow-graph projection: the pipeline-as-graph view + the editor palette (CONTROL scope). */
@Injectable({ providedIn: 'root' })
export class FlowsService {
    private http = inject(HttpClient);

    /** Every registered pipeline, lifted to a flow-graph summary. */
    list(): Observable<FlowSummary[]> {
        return this.http.get<FlowSummary[]>(apiUrl('/flows'));
    }

    /** The full graph projection for one flow, by its (normalised) name. */
    graph(name: string): Observable<FlowGraph> {
        return this.http.get<FlowGraph>(apiUrl(`/flows/${encodeURIComponent(name)}/graph`));
    }

    /** The node-type catalog for the palette. */
    nodeTypes(): Observable<FlowNodeType[]> {
        return this.http.get<FlowNodeType[]>(apiUrl('/flows/node-types'));
    }

    /** The combined pipeline+job topology — every flow joined at the shared store nodes (T24). */
    combined(): Observable<FlowCombined> {
        return this.http.get<FlowCombined>(apiUrl('/flows/combined'));
    }
}

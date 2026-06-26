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

// ── Authored flows (editor build-side) — the lossless shape that round-trips through the backend ──

/** A node in an authored flow (config-bearing — what GET /flows/authored/{id}/raw and PUT exchange). */
export interface AuthoredNode {
    id: string;
    type: string;
    name?: string;
    description?: string;
    use?: string;
    config?: Record<string, unknown>;
}

/** An authored edge (`rel` = `data` | `control` | `route:<key>` | …). */
export interface AuthoredEdge {
    from: string;
    rel: string;
    to: string;
}

/** A full authored flow definition (GET …/raw, POST/PUT body) — lossless, unlike the read-only projection. */
export interface AuthoredFlow {
    name: string;
    active: boolean;
    nodes: AuthoredNode[];
    edges: AuthoredEdge[];
}

/** One produced relation at a node in a dry-run (exact count + a bounded row sample). */
export interface DryRunRelation {
    rel: string;
    rowCount: number;
    rows: Record<string, unknown>[];
}

/** A non-sink node's dry-run outputs. */
export interface DryRunNode {
    node: string;
    type: string;
    relations: DryRunRelation[];
}

/** A sink branch's dry-run outcome. */
export interface DryRunSink {
    node: string;
    store: string;
    rowCount: number;
    rows: Record<string, unknown>[];
}

/** The dry-run result (POST /flows/authored/{id}/dry-run): seed + per-node + per-sink counts. */
export interface FlowDryRunResult {
    seedNode: string;
    nodes: DryRunNode[];
    sinks: DryRunSink[];
}

/** Result of testing a single processor node over a bounded sample (POST /components/{type}/{id}/test). */
export interface ComponentTestResult {
    type: string;
    id: string;
    ok: boolean;
    detail: string;
    rowCount: number;
    rows: Record<string, unknown>[];
}

// ── Run-to-here (the in-editor build-and-test loop) ──

/** One relationship a node produced during a run-to-here (success/unmatched/kept/dropped/route:<key>). */
export interface FlowRunRelation {
    node: string;
    rel: string;
    rowCount: number;
    rows: Record<string, unknown>[];
}

/** The materialized output a run-to-here landed (scratch only — never a production write). */
export interface FlowRunOutput {
    store: string;
    format: string;
    path: string;
    rowCount: number;
}

/**
 * Result of running an authored flow up to a node over picked inbox files
 * (POST /flows/authored/{id}/run?to={nodeId}). Per-relation counts + a bounded sample, plus the scratch
 * Parquet the run landed — the editor's incremental "build a little, test a little" feedback.
 */
export interface FlowRunResult {
    seedNode: string;
    toNode: string;
    files: string[];
    relations: FlowRunRelation[];
    output: FlowRunOutput | null;
    warnings: string[];
}

// ── Data-plane provenance (T22) — per-edge record counts of a past flow run ──

/** One run of a flow that recorded provenance (GET /provenance/batches), newest first. */
export interface ProvenanceBatch {
    batchId: string;
    runTs: string;
    totalRows: number;
}

/** The records a node emitted on one relationship during a run (GET /provenance) — a Sankey edge weight. */
export interface ProvenanceCount {
    nodeId: string;
    rel: string;
    rowCount: number;
}

/** Read-only flow-graph projection + authored-flow CRUD/dry-run for the editor (CONTROL scope). */
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

    // ── authored-flow CRUD + dry-run (editor; all writes 503 without -Dassist.write.root) ──

    /** Summaries of every authored flow (empty list when no write root). */
    authoredList(): Observable<FlowSummary[]> {
        return this.http.get<FlowSummary[]>(apiUrl('/flows/authored'));
    }

    /** The lossless authored definition (nodes with config) for editing — round-trips through PUT. */
    authoredRaw(id: string): Observable<AuthoredFlow> {
        return this.http.get<AuthoredFlow>(apiUrl(`/flows/authored/${encodeURIComponent(id)}/raw`));
    }

    /** Create a new authored flow (409 if the name exists). */
    createAuthored(flow: AuthoredFlow): Observable<unknown> {
        return this.http.post(apiUrl('/flows/authored'), flow);
    }

    /** Replace an authored flow wholesale (URL id authoritative; 422 on validation errors). */
    replaceAuthored(id: string, flow: AuthoredFlow): Observable<unknown> {
        return this.http.put(apiUrl(`/flows/authored/${encodeURIComponent(id)}`), flow);
    }

    /** Delete an authored flow. */
    deleteAuthored(id: string): Observable<unknown> {
        return this.http.delete(apiUrl(`/flows/authored/${encodeURIComponent(id)}`));
    }

    /** Dry-run a bounded sample through an authored flow (per-node + per-sink counts; no production write). */
    dryRunAuthored(id: string, sampleRows: Record<string, unknown>[]): Observable<FlowDryRunResult> {
        return this.http.post<FlowDryRunResult>(
            apiUrl(`/flows/authored/${encodeURIComponent(id)}/dry-run`), { sampleRows });
    }

    /** Test a single processor node over a bounded sample (no production write) — the per-processor test. */
    testNode(type: string, id: string): Observable<ComponentTestResult> {
        return this.http.post<ComponentTestResult>(
            apiUrl(`/components/${encodeURIComponent(type)}/${encodeURIComponent(id)}/test`), {});
    }

    /**
     * Run the authored flow up to {nodeId} over the chosen inbox `files`, materializing to a scratch store
     * (no production write). Drives the editor's run-to-here loop — per-relation counts + the Parquet landed.
     */
    runToNode(id: string, nodeId: string, files: string[]): Observable<FlowRunResult> {
        return this.http.post<FlowRunResult>(
            apiUrl(`/flows/authored/${encodeURIComponent(id)}/run`),
            { files },
            { params: { to: nodeId } });
    }

    // ── data-plane provenance (T22; 404 unless -Dprovenance.backend=duckdb) ──

    /** Recent runs of a flow that recorded provenance (newest first). */
    provenanceBatches(flow: string): Observable<ProvenanceBatch[]> {
        return this.http.get<ProvenanceBatch[]>(apiUrl('/provenance/batches'), { params: { flow } });
    }

    /** The per-(node, relationship) record counts of one run — painted onto the flow's edges as weights. */
    provenance(flow: string, batch: string): Observable<ProvenanceCount[]> {
        return this.http.get<ProvenanceCount[]>(apiUrl('/provenance'), { params: { flow, batch } });
    }
}

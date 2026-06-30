import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { apiUrl } from './api-base';

/** A node-type family (palette grouping + role checks); mirrors the backend `NodeCategory`. */
export type PipelineNodeCategory = 'SOURCE' | 'PARSE' | 'TRANSFORM' | 'SINK' | 'CONTROL' | string;

/** A compact pipeline entry (GET /flows) — one per registered pipeline, lifted to a pipeline graph. */
export interface PipelineSummary {
    name: string;
    active: boolean;
    nodeCount: number;
    edgeCount: number;
    produces: string[];
    consumes: string[];
}

/** One node in a pipeline graph projection (structural only — no raw config; the inspector shows this). */
export interface PipelineNode {
    id: string;
    type: string;
    category: PipelineNodeCategory;
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
export interface PipelineEdge {
    from: string;
    to: string;
    rel: string;
    kind: 'data' | 'control' | 'route';
    routeKey?: string;
}

/** A pipeline graph projection (GET /flows/{id}/graph) for the G6 renderer. */
export interface PipelineGraph {
    name: string;
    active: boolean;
    nodes: PipelineNode[];
    edges: PipelineEdge[];
    produces: string[];
    consumes: string[];
}

/** A node-type descriptor for the editor palette (GET /flows/node-types). */
export interface PipelineNodeType {
    type: string;
    category: PipelineNodeCategory;
    label: string;
    description: string;
    accepts: string[];
    emits: string[];
    emitsNamedRoutes: boolean;
}

/** A node in the combined topology: a pipeline node (with its owning `flow`) or a synthetic `STORE` join node. */
export interface CombinedNode extends PipelineNode {
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
export interface PipelineCombined {
    flows: { name: string; active: boolean }[];
    nodes: CombinedNode[];
    edges: CombinedEdge[];
    links: { producer: string; store: string; consumer: string }[];
}

// ── Authored pipelines (editor build-side) — the lossless shape that round-trips through the backend ──

/** A node in an authored pipeline (config-bearing — what GET /flows/authored/{id}/raw and PUT exchange). */
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

/** A full authored pipeline definition (GET …/raw, POST/PUT body) — lossless, unlike the read-only projection. */
export interface AuthoredPipeline {
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
export interface PipelineDryRunResult {
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
export interface PipelineRunRelation {
    node: string;
    rel: string;
    rowCount: number;
    rows: Record<string, unknown>[];
}

/** The materialized output a run-to-here landed (scratch only — never a production write). */
export interface PipelineRunOutput {
    store: string;
    format: string;
    path: string;
    rowCount: number;
}

/**
 * Result of running an authored pipeline up to a node over picked inbox files
 * (POST /flows/authored/{id}/run?to={nodeId}). Per-relation counts + a bounded sample, plus the scratch
 * Parquet the run landed — the editor's incremental "build a little, test a little" feedback.
 */
export interface PipelineRunResult {
    seedNode: string;
    toNode: string;
    files: string[];
    relations: PipelineRunRelation[];
    output: PipelineRunOutput | null;
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

/** Read-only pipeline-graph projection + authored-pipeline CRUD/dry-run for the editor (CONTROL scope). */
@Injectable({ providedIn: 'root' })
export class PipelinesService {
    private http = inject(HttpClient);

    /** Every registered pipeline, lifted to a pipeline-graph summary. */
    list(): Observable<PipelineSummary[]> {
        return this.http.get<PipelineSummary[]>(apiUrl('/flows'));
    }

    /** The full graph projection for one pipeline, by its (normalised) name. */
    graph(name: string): Observable<PipelineGraph> {
        return this.http.get<PipelineGraph>(apiUrl(`/flows/${encodeURIComponent(name)}/graph`));
    }

    /** The node-type catalog for the palette. */
    nodeTypes(): Observable<PipelineNodeType[]> {
        return this.http.get<PipelineNodeType[]>(apiUrl('/flows/node-types'));
    }

    /** The combined pipeline+job topology — every pipeline joined at the shared store nodes (T24). */
    combined(): Observable<PipelineCombined> {
        return this.http.get<PipelineCombined>(apiUrl('/flows/combined'));
    }

    // ── authored-pipeline CRUD + dry-run (editor; all writes 503 without -Dassist.write.root) ──

    /** Summaries of every authored pipeline (empty list when no write root). */
    authoredList(): Observable<PipelineSummary[]> {
        return this.http.get<PipelineSummary[]>(apiUrl('/flows/authored'));
    }

    /** The lossless authored definition (nodes with config) for editing — round-trips through PUT. */
    authoredRaw(id: string): Observable<AuthoredPipeline> {
        return this.http.get<AuthoredPipeline>(apiUrl(`/flows/authored/${encodeURIComponent(id)}/raw`));
    }

    /** Create a new authored pipeline (409 if the name exists). */
    createAuthored(pipeline: AuthoredPipeline): Observable<unknown> {
        return this.http.post(apiUrl('/flows/authored'), pipeline);
    }

    /** Replace an authored pipeline wholesale (URL id authoritative; 422 on validation errors). */
    replaceAuthored(id: string, pipeline: AuthoredPipeline): Observable<unknown> {
        return this.http.put(apiUrl(`/flows/authored/${encodeURIComponent(id)}`), pipeline);
    }

    /** Delete an authored pipeline. */
    deleteAuthored(id: string): Observable<unknown> {
        return this.http.delete(apiUrl(`/flows/authored/${encodeURIComponent(id)}`));
    }

    /** Dry-run a bounded sample through an authored pipeline (per-node + per-sink counts; no production write). */
    dryRunAuthored(id: string, sampleRows: Record<string, unknown>[]): Observable<PipelineDryRunResult> {
        return this.http.post<PipelineDryRunResult>(
            apiUrl(`/flows/authored/${encodeURIComponent(id)}/dry-run`), { sampleRows });
    }

    /** Test a single processor node over a bounded sample (no production write) — the per-processor test. */
    testNode(type: string, id: string): Observable<ComponentTestResult> {
        return this.http.post<ComponentTestResult>(
            apiUrl(`/components/${encodeURIComponent(type)}/${encodeURIComponent(id)}/test`), {});
    }

    /**
     * Run the authored pipeline up to {nodeId} over the chosen inbox `files`, materializing to a scratch store
     * (no production write). Drives the editor's run-to-here loop — per-relation counts + the Parquet landed.
     */
    runToNode(id: string, nodeId: string, files: string[]): Observable<PipelineRunResult> {
        return this.http.post<PipelineRunResult>(
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

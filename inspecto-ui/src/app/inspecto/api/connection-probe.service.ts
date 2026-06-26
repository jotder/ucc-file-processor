import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { apiUrl, toParams } from './api-base';

/**
 * The graded "test connection" + "explore" + "sample" surface for a connection profile — the four verbs of
 * the external-system connection library (connect · explore · test · sample), exposed UI-first as a frozen
 * contract the mock interceptor serves now and the real backend (delegating to the library) serves later.
 *
 * <p>Distinct from {@link ConnectionsService}, which keeps the legacy reachability-only {@code test()}; this
 * service is the new, graded surface. Secrets never travel here — a probe reports whether {@code ${…}}
 * references resolve, never their values; sampled rows are preview-only and never persisted.
 */

/** One graded check in a connection probe (design §3: REACHABILITY/AUTHENTICATE/READ/WRITE/LIST). */
export type ProbeCheck = 'reachability' | 'authenticate' | 'read' | 'write' | 'list';

/** The outcome of one {@link ProbeCheck}. {@code skipped} = not requested or not supported by the connector. */
export interface CheckOutcome {
    check: ProbeCheck;
    ok: boolean;
    skipped?: boolean;
    detail: string;
    latencyMs?: number;
}

/** The result of a graded connection probe — supersedes the reachability-only {@code ConnectionTestResult}. */
export interface ConnectionProbeResult {
    id: string;
    connector: string;
    endpoint: string;
    /** Overall verdict: every requested, non-skipped check passed. */
    ok: boolean;
    /** Whether all {@code ${…}} credential references resolve in this environment (no value exposed). */
    secretsResolved: boolean;
    checks: CheckOutcome[];
}

/** What an explore node is — files/dirs for filesystem connectors, schema/table/column for DB, bucket for object stores. */
export type ResourceKind = 'dir' | 'file' | 'bucket' | 'schema' | 'table' | 'column';

/** One node in the resource-explore tree. Children are lazy-loaded via {@link ConnectionProbeService.explore}. */
export interface ResourceNode {
    name: string;
    /** Connector-relative locator used to expand children or pull a sample. */
    path: string;
    kind: ResourceKind;
    /** Whether this node can be expanded (drives lazy loading; leaves are false). */
    hasChildren: boolean;
    sizeBytes?: number;
    modifiedAt?: string;
    readable?: boolean;
    writable?: boolean;
}

/** A bounded sample extracted from a resource node for preview — never persisted server-side. */
export interface SampleResult {
    path: string;
    columns: string[];
    rows: Record<string, unknown>[];
    /** True when more data exists beyond the returned sample. */
    truncated: boolean;
    detail?: string;
}

/** Optional request shaping for a probe (which checks to run, how many entries the LIST check may return). */
export interface ProbeRequest {
    checks?: ProbeCheck[];
    sampleLimit?: number;
}

@Injectable({ providedIn: 'root' })
export class ConnectionProbeService {
    private http = inject(HttpClient);

    /** Graded test connection: connect + read/write permission + a bounded list (design §3). */
    probe(id: string, req: ProbeRequest = {}): Observable<ConnectionProbeResult> {
        return this.http.post<ConnectionProbeResult>(
            apiUrl(`/connections/${encodeURIComponent(id)}/probe`), req);
    }

    /** Explore the resource: children of {@code path} (the root when {@code path} is omitted). Permission-aware. */
    explore(id: string, path?: string): Observable<ResourceNode[]> {
        return this.http.get<ResourceNode[]>(
            apiUrl(`/connections/${encodeURIComponent(id)}/explore`), { params: toParams({ path }) });
    }

    /** Extract a bounded sample from {@code path} (first {@code limit} rows / file head) for preview only. */
    sample(id: string, path: string, limit = 50): Observable<SampleResult> {
        return this.http.get<SampleResult>(
            apiUrl(`/connections/${encodeURIComponent(id)}/sample`), { params: toParams({ path, limit }) });
    }
}

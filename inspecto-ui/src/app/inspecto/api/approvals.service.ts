import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { map, Observable } from 'rxjs';
import { apiUrl, toParams } from './api-base';

/**
 * One agent approval request (GET /agent/approvals[/{id}]) — the wire view of the backend
 * `Approval.toView()`. A mutating agent tool call (AGT-5 P3) parks in the intelligence module's
 * approvals inbox until an operator decides here; {@link ApprovalsService.decide} resumes (approve) or
 * denies the gated tool. `preview` is the read-only dry-run the operator reviews.
 */
export interface AgentApproval {
    id: string;
    tool: string;
    /** How the ensuing control-plane write audits — `"agent:<runId>"`. */
    agentActor: string;
    summary: string;
    arguments: Record<string, unknown>;
    preview: Record<string, unknown>;
    status: 'PENDING' | 'APPROVED' | 'DENIED' | 'TIMED_OUT';
    requestedAt: string;
    decidedAt: string | null;
    decidedBy: string | null;
}

/** The operator's verdict on a pending approval (POST /agent/approvals/{id}/decision). */
export type ApprovalDecision = 'approve' | 'decline';

/**
 * The AGT-5 P3 approvals inbox (autonomy L2). Reads degrade to an empty list when the agent's act tier
 * is off, and to a 503 when the optional intelligence module is absent — the page handles both.
 */
@Injectable({ providedIn: 'root' })
export class ApprovalsService {
    private http = inject(HttpClient);

    /** Recent approvals (pending + decided), newest-first, capped at {@code limit}. */
    list(limit = 50): Observable<AgentApproval[]> {
        return this.http
            .get<{ approvals: AgentApproval[] }>(apiUrl('/agent/approvals'), { params: toParams({ limit }) })
            .pipe(map((r) => r.approvals ?? []));
    }

    /** One approval by id. 404 when unknown. */
    get(id: string): Observable<AgentApproval> {
        return this.http.get<AgentApproval>(apiUrl(`/agent/approvals/${encodeURIComponent(id)}`));
    }

    /**
     * Approve or decline a pending request — resumes (approve) or denies the parked gated tool call.
     * Returns the updated view; 404 when the id is unknown or already decided.
     */
    decide(id: string, decision: ApprovalDecision, decidedBy?: string): Observable<AgentApproval> {
        return this.http.post<AgentApproval>(
            apiUrl(`/agent/approvals/${encodeURIComponent(id)}/decision`),
            { decision, decidedBy },
        );
    }
}

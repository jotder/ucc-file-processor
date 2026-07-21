import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { map, Observable } from 'rxjs';
import { apiUrl, toParams } from './api-base';

/** How an action class may run autonomously (AGT-5 P4, L3). Mirrors the backend `AutonomyPolicy.Mode`. */
export type AutonomyMode = 'OFF' | 'SHADOW' | 'AUTO';

/** Per-action-class policy: mode + rolling-window budget (0 = no limit on that window). */
export interface ClassPolicy {
    mode: AutonomyMode;
    maxPerHour: number;
    maxPerDay: number;
}

/** The bounded-autonomy policy (GET/PUT /agent/policy) — the wire view of the backend `AutonomyPolicy`. */
export interface AutonomyPolicy {
    /** The global hard-off: when true, every action class is denied regardless of its mode. */
    killSwitch: boolean;
    classes: Record<string, ClassPolicy>;
    updatedAt: string | null;
    updatedBy: string | null;
}

/** What became of one autonomous decision the ops_monitor loop reached (GET /agent/actions). */
export type ActionStatus = 'SKIPPED' | 'SHADOWED' | 'SUCCEEDED' | 'FAILED';

/** One autonomy-ledger entry — the "what the agent did, why, and spend" record. */
export interface AutonomyAction {
    id: string;
    actionClass: string;
    subject: Record<string, unknown>;
    /** The policy verdict outcome: ALLOW / SHADOW / DENY. */
    decision: string;
    reason: string;
    status: ActionStatus;
    detail: string;
    at: string;
}

/**
 * The AGT-5 P4 autonomy control surface (L3). Reads the policy + the action ledger and lets an operator
 * set per-class mode/budget and throw the kill switch. The optional intelligence module answers 503
 * when absent (no L3 tier) for `/agent/policy`; `/agent/actions` degrades to an empty list — the
 * dashboard page handles both.
 */
@Injectable({ providedIn: 'root' })
export class AutonomyService {
    private http = inject(HttpClient);

    /** The current policy (kill switch + per-class mode/budget). 503 when there is no L3 tier. */
    policy(): Observable<AutonomyPolicy> {
        return this.http.get<AutonomyPolicy>(apiUrl('/agent/policy'));
    }

    /** Replace the whole policy. Returns the stored view. */
    updatePolicy(policy: Pick<AutonomyPolicy, 'killSwitch' | 'classes'>): Observable<AutonomyPolicy> {
        return this.http.put<AutonomyPolicy>(apiUrl('/agent/policy'), policy);
    }

    /** The one-call hard-off / resume. Returns the updated policy view. */
    setKillSwitch(engaged: boolean): Observable<AutonomyPolicy> {
        return this.http.post<AutonomyPolicy>(apiUrl('/agent/policy/kill-switch'), { engaged });
    }

    /** Recent autonomous decisions (executed, shadowed, skipped), newest-first, capped at `limit`. */
    actions(limit = 100): Observable<AutonomyAction[]> {
        return this.http
            .get<{ actions: AutonomyAction[] }>(apiUrl('/agent/actions'), { params: toParams({ limit }) })
            .pipe(map((r) => r.actions ?? []));
    }
}

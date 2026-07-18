import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import type { ConditionGroup } from '../query/query-types';
import { apiUrl } from './api-base';

/**
 * The five data-quality check kinds (docs/GLOSSARY.md — Expectation, Great-Expectations model). The
 * first four check one `column`; `condition` (2026-07-18) instead evaluates an arbitrary `when`
 * condition tree as the violation predicate directly (the same `query-types` shape Decision Rules
 * author) — no `column` needed, since the tree names its own field(s).
 */
export type ExpectationKind = 'non_null' | 'range' | 'regex' | 'referential' | 'condition';

/** Outcome of the latest evaluation of one Expectation. */
export interface ExpectationResult {
    status: 'PASSED' | 'FAILED';
    /** Records that violated the check in the evaluated window. */
    violations: number;
    checkedAt: number;
}

/**
 * Expectation — a data-quality rule (non-null / range / regex / referential) attached to a Pipeline
 * or Job and evaluated over its records. `name` is the identity (storage key), like Jobs. A FAILED
 * evaluation raises an Incident correlated `expectation:<name>`.
 */
export interface Expectation {
    name: string;
    description?: string;
    targetType: 'pipeline' | 'job';
    /** The pipeline/job the check runs against. */
    target: string;
    /** The record field the check inspects (every kind but `condition`, which names its own field(s)). */
    column?: string;
    kind: ExpectationKind;
    // kind-specific parameters (only the ones for `kind` are set)
    min?: number | null;
    max?: number | null;
    pattern?: string | null;
    refDataset?: string | null;
    refColumn?: string | null;
    /** `condition` kind only: the violation predicate itself. */
    when?: ConditionGroup | null;
    severity: string;
    enabled: boolean;
    lastResult?: ExpectationResult | null;
    createdAt: number;
    updatedAt: number;
}

export type ExpectationUpsert = Omit<Expectation, 'lastResult' | 'createdAt' | 'updatedAt'>;

@Injectable({ providedIn: 'root' })
export class ExpectationsService {
    private http = inject(HttpClient);

    list(): Observable<Expectation[]> {
        return this.http.get<Expectation[]>(apiUrl('/expectations'));
    }

    create(body: ExpectationUpsert): Observable<Expectation> {
        return this.http.post<Expectation>(apiUrl('/expectations'), body);
    }

    update(name: string, body: ExpectationUpsert): Observable<Expectation> {
        return this.http.put<Expectation>(apiUrl(`/expectations/${encodeURIComponent(name)}`), body);
    }

    remove(name: string): Observable<void> {
        return this.http.delete<void>(apiUrl(`/expectations/${encodeURIComponent(name)}`));
    }

    /** Run one check now; a failure raises an Incident (see the mock/backend contract). */
    evaluate(name: string): Observable<Expectation> {
        return this.http.post<Expectation>(apiUrl(`/expectations/${encodeURIComponent(name)}/evaluate`), {});
    }

    /** Run every enabled check; returns the refreshed expectations. */
    evaluateAll(): Observable<Expectation[]> {
        return this.http.post<Expectation[]>(apiUrl('/expectations/evaluate'), {});
    }
}

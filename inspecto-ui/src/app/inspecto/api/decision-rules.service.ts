import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { ConditionGroup } from '../query/query-types';
import type { Consequence, ExecutedConsequence } from '../decision/consequence';
import { apiUrl } from './api-base';

// R5: consequences are the unified, typed {@link Consequence} (routing actions + platform actions). The
// old names are kept as aliases so existing editor/handler call sites compile unchanged.
export type { Consequence } from '../decision/consequence';
/** @deprecated The routing subset (route/tag/quarantine/drop) — the full action set is `ConsequenceType`. */
export type DecisionConsequenceAction = 'route' | 'tag' | 'quarantine' | 'drop';
/** @deprecated Alias — a rule now stacks unified {@link Consequence}s. */
export type DecisionConsequence = Consequence;

/** Outcome of the latest dry-run simulation of one Decision Rule. */
export interface DecisionSimulation {
    matched: number;
    total: number;
    checkedAt: number;
}

/**
 * Decision Rule — a business routing rule: WHEN records of the target Pipeline/Job match the
 * condition tree, THEN the consequences apply (route/tag/quarantine/drop). Surfaced first-class
 * (previously buried in the `transform.route` node's edge metadata). `name` is the identity.
 */
export interface DecisionRule {
    name: string;
    description?: string;
    targetType: 'pipeline' | 'job';
    target: string;
    when: ConditionGroup;
    consequences: Consequence[];
    /** Lower fires first when several rules target the same records. */
    priority: number;
    enabled: boolean;
    lastSimulation?: DecisionSimulation | null;
    createdAt: number;
    updatedAt: number;
}

export type DecisionRuleUpsert = Omit<DecisionRule, 'lastSimulation' | 'createdAt' | 'updatedAt'>;

/** Result of executing a rule's consequences (POST /decision-rules/{name}/apply) — R5. */
export interface DecisionApplyResult {
    rule: string;
    executed: ExecutedConsequence[];
}

@Injectable({ providedIn: 'root' })
export class DecisionRulesService {
    private http = inject(HttpClient);

    list(): Observable<DecisionRule[]> {
        return this.http.get<DecisionRule[]>(apiUrl('/decision-rules'));
    }

    create(body: DecisionRuleUpsert): Observable<DecisionRule> {
        return this.http.post<DecisionRule>(apiUrl('/decision-rules'), body);
    }

    update(name: string, body: DecisionRuleUpsert): Observable<DecisionRule> {
        return this.http.put<DecisionRule>(apiUrl(`/decision-rules/${encodeURIComponent(name)}`), body);
    }

    remove(name: string): Observable<void> {
        return this.http.delete<void>(apiUrl(`/decision-rules/${encodeURIComponent(name)}`));
    }

    /** Dry-run: how many of `sampleRows` the when-clause would match (no side effects). The caller
     *  supplies the rows — a rule's target is a pipeline/job, not a queryable dataset, so the sample is
     *  the row source (typically a bounded fetch from the target's store). An empty sample yields 0/0. */
    simulate(name: string, sampleRows: Record<string, unknown>[] = []): Observable<DecisionRule> {
        return this.http.post<DecisionRule>(apiUrl(`/decision-rules/${encodeURIComponent(name)}/simulate`), { sampleRows });
    }

    /** Execute the rule's consequences through the Execution/Signal networks (R5) — emit-signal / create-alert
     *  land on the Signal Ledger. Returns what ran. */
    apply(name: string): Observable<DecisionApplyResult> {
        return this.http.post<DecisionApplyResult>(apiUrl(`/decision-rules/${encodeURIComponent(name)}/apply`), {});
    }
}

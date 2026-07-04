import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { ConditionGroup } from '../query/query-types';
import { apiUrl } from './api-base';

/** What a matching record is subjected to (docs/GLOSSARY.md — Decision Rule, Drools-style routing). */
export type DecisionConsequenceAction = 'route' | 'tag' | 'quarantine' | 'drop';

/** One consequence a rule applies to matching records; a rule may stack several. */
export interface DecisionConsequence {
    action: DecisionConsequenceAction;
    /** route ⇒ the named branch (`route:<destination>` edge); tag ⇒ the tag value; quarantine ⇒ optional reason. */
    destination?: string | null;
}

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
    consequences: DecisionConsequence[];
    /** Lower fires first when several rules target the same records. */
    priority: number;
    enabled: boolean;
    lastSimulation?: DecisionSimulation | null;
    createdAt: number;
    updatedAt: number;
}

export type DecisionRuleUpsert = Omit<DecisionRule, 'lastSimulation' | 'createdAt' | 'updatedAt'>;

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

    /** Dry-run: how many of the target's records the when-clause would match (no side effects). */
    simulate(name: string): Observable<DecisionRule> {
        return this.http.post<DecisionRule>(apiUrl(`/decision-rules/${encodeURIComponent(name)}/simulate`), {});
    }
}

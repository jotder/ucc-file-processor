import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { ComponentsService } from 'app/inspecto/api';
import { ConditionGroup, emptyGroup } from 'app/inspecto/query';
import { RuleTemplate } from './rule-types';

/**
 * Rule template store — Pro Max. Persists {@link RuleTemplate}s through the reusable component registry as the
 * `rule` component type (mock-backed today like parser `grammar`; real backend later). Keeps the rest of the
 * app from knowing rules are "just components".
 */
@Injectable({ providedIn: 'root' })
export class RulesService {
    private components = inject(ComponentsService);

    list(): Observable<RuleTemplate[]> {
        return this.components.list('rule').pipe(map((defs) => defs.map((d) => fromContent(d.name, d.content))));
    }

    save(rule: RuleTemplate): Observable<RuleTemplate> {
        return this.components.create('rule', { id: rule.id, ...toContent(rule) }).pipe(map(() => rule));
    }

    remove(id: string): Observable<unknown> {
        return this.components.remove('rule', id);
    }
}

function toContent(r: RuleTemplate): Record<string, unknown> {
    return { name: r.name, source: r.source, projection: r.projection, where: r.where, sqlOverride: r.sqlOverride ?? null };
}

function fromContent(name: string, content: Record<string, unknown>): RuleTemplate {
    return {
        id: name,
        name: (content['name'] as string) ?? name,
        source: (content['source'] as string) ?? 'data',
        projection: (content['projection'] as string[] | '*') ?? '*',
        where: (content['where'] as ConditionGroup) ?? emptyGroup(),
        sqlOverride: (content['sqlOverride'] as string | null) ?? null,
    };
}

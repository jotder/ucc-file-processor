import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { ComponentsService } from 'app/inspecto/api';
import { Requirement, RequirementKind, RequirementStatus } from './requirement-types';

/**
 * Requirement store — persists {@link Requirement}s through the component registry as the `requirement`
 * component type (mock-served by the unified mock store today). Mirrors `rules.service.ts`/
 * `datasets.service.ts` — a requirement is "just a component" with a status-lifecycle body.
 */
@Injectable({ providedIn: 'root' })
export class RequirementsService {
    private components = inject(ComponentsService);

    list(): Observable<Requirement[]> {
        return this.components.list('requirement').pipe(map((defs) => defs.map((d) => fromContent(d.name, d.content))));
    }

    save(r: Requirement): Observable<Requirement> {
        return this.components.update('requirement', r.id, { id: r.id, ...toContent(r) }).pipe(map(() => r));
    }

    create(r: Requirement): Observable<Requirement> {
        return this.components.create('requirement', { id: r.id, ...toContent(r) }).pipe(map(() => r));
    }
}

function toContent(r: Requirement): Record<string, unknown> {
    return {
        title: r.title,
        kind: r.kind,
        description: r.description,
        status: r.status,
        submittedAt: r.submittedAt,
        decisionNote: r.decisionNote ?? null,
        decidedAt: r.decidedAt ?? null,
        deliveredNote: r.deliveredNote ?? null,
        deliveredAt: r.deliveredAt ?? null,
    };
}

function fromContent(name: string, content: Record<string, unknown>): Requirement {
    return {
        id: name,
        title: (content['title'] as string) ?? name,
        kind: (content['kind'] as RequirementKind) ?? 'kpi',
        description: (content['description'] as string) ?? '',
        status: (content['status'] as RequirementStatus) ?? 'submitted',
        submittedAt: (content['submittedAt'] as string) ?? '',
        decisionNote: (content['decisionNote'] as string | undefined) ?? undefined,
        decidedAt: (content['decidedAt'] as string | undefined) ?? undefined,
        deliveredNote: (content['deliveredNote'] as string | undefined) ?? undefined,
        deliveredAt: (content['deliveredAt'] as string | undefined) ?? undefined,
    };
}

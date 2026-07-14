import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { ComponentsService } from 'app/inspecto/api';
import { CompareColumn, ReconBreak, Reconciliation, ReconciliationConfig } from './reconciliation-types';

/**
 * Reconciliation store — persists {@link Reconciliation}s through the component registry as the
 * `reconciliation` component type (mock-served today). Mirrors `rules.service.ts`/`datasets.service.ts` —
 * a reconciliation is "just a component" whose body carries the match config + the last run's breaks.
 */
@Injectable({ providedIn: 'root' })
export class ReconciliationsService {
    private components = inject(ComponentsService);

    list(): Observable<Reconciliation[]> {
        return this.components.list('reconciliation').pipe(map((defs) => defs.map((d) => fromContent(d.name, d.content))));
    }

    get(id: string): Observable<Reconciliation> {
        return this.components.get('reconciliation', id).pipe(map((d) => fromContent(d.name, d.content)));
    }

    create(r: Reconciliation): Observable<Reconciliation> {
        return this.components.create('reconciliation', { id: r.id, ...toContent(r) }).pipe(map(() => r));
    }

    save(r: Reconciliation): Observable<Reconciliation> {
        return this.components.update('reconciliation', r.id, { id: r.id, ...toContent(r) }).pipe(map(() => r));
    }

    remove(id: string): Observable<unknown> {
        return this.components.remove('reconciliation', id);
    }
}

function toContent(r: Reconciliation): Record<string, unknown> {
    return {
        name: r.name,
        leftDataset: r.leftDataset,
        rightDataset: r.rightDataset,
        ...(r.thirdDataset ? { thirdDataset: r.thirdDataset } : {}),
        keyColumns: r.keyColumns,
        compareColumns: r.compareColumns,
        ...(r.bands ? { bands: r.bands } : {}),
        breaks: r.breaks,
        lastRunAt: r.lastRunAt ?? null,
    };
}

function fromContent(name: string, content: Record<string, unknown>): Reconciliation {
    const c = content as Partial<ReconciliationConfig> & { name?: string };
    return {
        id: name,
        name: c.name ?? name,
        leftDataset: c.leftDataset ?? '',
        rightDataset: c.rightDataset ?? '',
        thirdDataset: c.thirdDataset,
        keyColumns: (c.keyColumns as string[]) ?? [],
        compareColumns: (c.compareColumns as CompareColumn[]) ?? [],
        bands: c.bands,
        breaks: (c.breaks as ReconBreak[]) ?? [],
        lastRunAt: c.lastRunAt ?? null,
    };
}

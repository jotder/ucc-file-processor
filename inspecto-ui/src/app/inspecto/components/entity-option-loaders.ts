import { inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { ComponentsService, ConnectionsService, JobsService, RunsService } from 'app/inspecto/api';
import { AttributeOption } from 'app/inspecto/component-model';
import { AttributeOptionLoader } from './schema-form.component';

const toOptions = (names: string[]): AttributeOption[] =>
    [...new Set(names)].sort().map((n) => ({ value: n, label: n }));

/**
 * Suggestion loaders for the recurring "reference an existing entity" autocomplete fields
 * (`docs/superpower/ui-design-review.md` R2) — pipelines, jobs, datasets the app already knows, so
 * authors pick instead of retyping ids from memory. `inject()`-based: call from a field initializer
 * or constructor. Suggestions assist, they never constrain — free text stays valid (forward-compat).
 */
export function pipelineOptionLoader(): AttributeOptionLoader {
    const runs = inject(RunsService);
    return async () => toOptions((await firstValueFrom(runs.list())).map((r) => r.name));
}

/** Pipelines or jobs, following a sibling type discriminator (default key `targetType`). */
export function pipelineOrJobOptionLoader(targetTypeKey = 'targetType'): AttributeOptionLoader {
    const runs = inject(RunsService);
    const jobs = inject(JobsService);
    return async (v) =>
        v[targetTypeKey] === 'job'
            ? toOptions((await firstValueFrom(jobs.list())).map((j) => j.name))
            : toOptions((await firstValueFrom(runs.list())).map((r) => r.name));
}

/** Registered dataset components (the Catalog's dataset kind). */
export function datasetOptionLoader(): AttributeOptionLoader {
    const components = inject(ComponentsService);
    return async () => toOptions((await firstValueFrom(components.list('dataset'))).map((d) => d.name));
}

/** Saved Connection profiles (by id) — the collector's `connection:` reference. */
export function connectionOptionLoader(): AttributeOptionLoader {
    const connections = inject(ConnectionsService);
    return async () => toOptions((await firstValueFrom(connections.list())).map((c) => c.id));
}

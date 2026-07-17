import { inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { ComponentsService, ConnectionsService, DbBrowserService, DecisionRulesService, JobsService, RunsService } from 'app/inspecto/api';
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

/**
 * Columns of the store a sibling field names (expectation `column` follows `target`, `refColumn`
 * follows `refDataset`) — a 1-row `/db/table` probe of that store's records, labelled with the
 * column type. No sibling value yet, or an unreadable store, degrades to no suggestions.
 */
export function columnOptionLoader(sourceKey: string): AttributeOptionLoader {
    const db = inject(DbBrowserService);
    return async (v) => {
        const name = String(v[sourceKey] ?? '').trim();
        if (!name) return [];
        const res = await firstValueFrom(db.table({ name, limit: 1 }));
        return res.columns.map((c) => ({ value: c.name, label: c.type ? `${c.name} (${c.type.toLowerCase()})` : c.name }));
    };
}

/** Saved Connection profiles (by id) — the collector's `connection:` reference. */
export function connectionOptionLoader(): AttributeOptionLoader {
    const connections = inject(ConnectionsService);
    return async () => toOptions((await firstValueFrom(connections.list())).map((c) => c.id));
}

/** The registry-store kinds a Requirement is plausibly delivered by (see componentRefOptionLoader). */
const DELIVERABLE_KINDS = ['dataset', 'query', 'widget', 'dashboard', 'rule', 'reconciliation'] as const;

/**
 * Cross-kind component references in the house `<kind>/<id>` form (requirements "Delivered via"
 * picker) — registry components plus the own-store entities (pipelines, jobs, decision rules).
 * Every source is best-effort (`allSettled`): an unreachable store just contributes no options.
 */
export function componentRefOptionLoader(): AttributeOptionLoader {
    const components = inject(ComponentsService);
    const runs = inject(RunsService);
    const jobs = inject(JobsService);
    const decisionRules = inject(DecisionRulesService);
    return async () => {
        const sources: Promise<AttributeOption[]>[] = [
            ...DELIVERABLE_KINDS.map(async (kind) =>
                (await firstValueFrom(components.list(kind))).map((c) => ({ value: `${kind}/${c.name}`, label: `${kind}/${c.name}` }))),
            (async () => (await firstValueFrom(runs.list())).map((r) => ({ value: `pipeline/${r.name}`, label: `pipeline/${r.name}` })))(),
            (async () => (await firstValueFrom(jobs.list())).map((j) => ({ value: `job/${j.name}`, label: `job/${j.name}` })))(),
            (async () => (await firstValueFrom(decisionRules.list())).map((d) => ({ value: `decision-rule/${d.name}`, label: `decision-rule/${d.name}` })))(),
        ];
        const settled = await Promise.allSettled(sources);
        return settled
            .flatMap((s) => (s.status === 'fulfilled' ? s.value : []))
            .sort((a, b) => a.value.localeCompare(b.value));
    };
}

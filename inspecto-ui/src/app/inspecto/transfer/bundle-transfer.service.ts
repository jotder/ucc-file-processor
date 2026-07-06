import { Injectable, inject } from '@angular/core';
import { Observable, forkJoin, of } from 'rxjs';
import { catchError, concatMap, map } from 'rxjs/operators';
import {
    ComponentType,
    ComponentsService,
    ConnectionProfile,
    ConnectionsService,
    DecisionRule,
    DecisionRulesService,
    DecisionRuleUpsert,
    JobsService,
    PipelinesService,
    SpacesService,
} from 'app/inspecto/api';
import type { AuthoredPipeline } from 'app/inspecto/api/pipelines.service';
import type { JobDetail, JobUpsert } from 'app/inspecto/api/jobs.service';
import { BUNDLE_KINDS, BundleItem, BundleKind, MetadataBundle, buildBundle, withDependencies } from './bundle';

const COMPONENT_KINDS = BUNDLE_KINDS.map((k) => k.kind).filter(
    (k): k is Extract<BundleKind, ComponentType> =>
        k !== 'connection' && k !== 'authored-pipeline' && k !== 'job' && k !== 'decision-rule',
);

/** A job's transportable metadata — the upsert shape; runtime state (last status/run/next fire) never travels. */
function jobContent(job: JobDetail): Record<string, unknown> {
    const { name, type, cron, onPipeline, enabled, catchUp, params } = job;
    return { name, type, cron: cron ?? null, onPipeline: onPipeline ?? null, enabled, catchUp, params };
}

/** A decision rule's transportable metadata (the upsert shape) — runtime `lastSimulation`/timestamps never travel. */
function decisionRuleContent(rule: DecisionRule): Record<string, unknown> {
    const { name, description, targetType, target, when, consequences, priority, enabled } = rule;
    return { name, description: description ?? '', targetType, target, when, consequences, priority, enabled };
}

/**
 * The single source of Metadata-Bundle transport (R6): loading every exportable artifact off the
 * instance, writing an imported item through the right store, and building/downloading a bundle.
 * Extracted from the Settings Transfer pane so the shared import dialog and the per-surface
 * `<inspecto-transfer-menu>` reuse the exact same load/write path — one format, three surfaces.
 */
@Injectable({ providedIn: 'root' })
export class BundleTransferService {
    private components = inject(ComponentsService);
    private connections = inject(ConnectionsService);
    private pipelines = inject(PipelinesService);
    private jobs = inject(JobsService);
    private decisionRules = inject(DecisionRulesService);
    private spaces = inject(SpacesService);

    /** Load every exportable artifact (component kinds + connections + lossless pipelines + jobs + rules). */
    loadAll(): Observable<BundleItem[]> {
        const componentLists = Object.fromEntries(
            COMPONENT_KINDS.map((kind) => [kind, this.components.list(kind).pipe(catchError(() => of([])))]),
        );
        return forkJoin({
            ...componentLists,
            connection: this.connections.list().pipe(catchError(() => of([]))),
            pipelineNames: this.pipelines.authoredList().pipe(map((list) => list.map((p) => p.name)), catchError(() => of([] as string[]))),
            jobNames: this.jobs.list().pipe(map((list) => list.map((j) => j.name)), catchError(() => of([] as string[]))),
            decisionRules: this.decisionRules.list().pipe(catchError(() => of([] as DecisionRule[]))),
        }).pipe(
            concatMap((res) => {
                const raws = (res['pipelineNames'] as string[]).map((name) => this.pipelines.authoredRaw(name).pipe(catchError(() => of(null))));
                const jobDetails = (res['jobNames'] as string[]).map((name) => this.jobs.get(name).pipe(catchError(() => of(null))));
                return forkJoin({
                    pipelines: raws.length ? forkJoin(raws) : of([] as (AuthoredPipeline | null)[]),
                    jobs: jobDetails.length ? forkJoin(jobDetails) : of([] as (JobDetail | null)[]),
                }).pipe(map(({ pipelines, jobs }) => ({ res, pipelines, jobs })));
            }),
            map(({ res, pipelines, jobs }) => {
                const items: BundleItem[] = [];
                for (const kind of COMPONENT_KINDS) {
                    for (const def of res[kind] as { name: string; content: Record<string, unknown> }[]) {
                        items.push({ kind, id: def.name, content: def.content });
                    }
                }
                for (const c of res['connection'] as ConnectionProfile[]) {
                    items.push({ kind: 'connection', id: c.id, content: c as unknown as Record<string, unknown> });
                }
                for (const p of pipelines) if (p) items.push({ kind: 'authored-pipeline', id: p.name, content: p as unknown as Record<string, unknown> });
                for (const j of jobs) if (j) items.push({ kind: 'job', id: j.name, content: jobContent(j) });
                for (const r of res['decisionRules'] as DecisionRule[]) items.push({ kind: 'decision-rule', id: r.name, content: decisionRuleContent(r) });
                return items;
            }),
        );
    }

    /** Write one imported item through the store that owns its kind. `overwrite` = update vs create. */
    write(item: BundleItem, overwrite: boolean): Observable<unknown> {
        const { kind, id, content } = item;
        if (kind === 'connection') {
            const profile = { ...(content as unknown as ConnectionProfile), id };
            return overwrite ? this.connections.update(id, profile) : this.connections.create(profile);
        }
        if (kind === 'authored-pipeline') {
            const pipeline = { ...(content as unknown as AuthoredPipeline), name: id };
            return overwrite ? this.pipelines.replaceAuthored(id, pipeline) : this.pipelines.createAuthored(pipeline);
        }
        if (kind === 'job') {
            const job = { ...(content as unknown as JobUpsert), name: id };
            return overwrite ? this.jobs.update(id, job) : this.jobs.create(job);
        }
        if (kind === 'decision-rule') {
            const rule = { ...(content as unknown as DecisionRuleUpsert), name: id };
            return overwrite ? this.decisionRules.update(id, rule) : this.decisionRules.create(rule);
        }
        return overwrite ? this.components.update(kind, id, content) : this.components.create(kind, { id, ...content });
    }

    /** Build a bundle for a selection, optionally expanded to its dependency closure against `available`. */
    buildExport(selected: BundleItem[], available: BundleItem[], includeDeps: boolean): { bundle: MetadataBundle; missing: string[] } {
        let items = selected;
        let missing: string[] = [];
        if (includeDeps) ({ items, missing } = withDependencies(selected, available));
        return { bundle: buildBundle(items, this.spaces.currentSpaceId()), missing };
    }

    /** Trigger a browser download of a bundle as pretty-printed JSON. */
    download(bundle: MetadataBundle): void {
        const space = bundle.sourceSpace ?? 'default';
        const stamp = bundle.exportedAt.slice(0, 16).replace(/[:T]/g, '-');
        const url = URL.createObjectURL(new Blob([JSON.stringify(bundle, null, 2)], { type: 'application/json' }));
        const link = document.createElement('a');
        link.href = url;
        link.download = `inspecto-bundle-${space}-${stamp}.json`;
        link.click();
        URL.revokeObjectURL(url);
    }
}

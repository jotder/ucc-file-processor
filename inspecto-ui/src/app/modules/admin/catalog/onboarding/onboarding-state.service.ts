import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, catchError, forkJoin, map, of, switchMap, tap } from 'rxjs';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage, ConfigDeleteResult, ConfigService, ConfigWriteResult, ParsingPreview, SchemaPreview } from 'app/inspecto/api';
import { mergeBlock } from './onboarding-config-utils';

/** Stage ids across both kinds — a Stream uses schema/enrichment, a Reference keys. */
export type OnboardingStageId = 'collection' | 'parsing' | 'schema' | 'enrichment' | 'keys' | 'publish';

/** Computed, never stored: readiness is derived from the config blocks on every read. */
export type StageStatus = 'empty' | 'configured' | 'validated';

export interface OnboardingStage {
    id: OnboardingStageId;
    label: string;
    icon: string;
    hint: string;
    optional?: boolean;
}

const STREAM_STAGES: OnboardingStage[] = [
    { id: 'collection', label: 'Collection', icon: 'heroicons_outline:inbox-arrow-down', hint: 'Where the files come from' },
    { id: 'parsing', label: 'Parsing', icon: 'heroicons_outline:code-bracket', hint: 'How raw bytes become rows' },
    { id: 'schema', label: 'Schema & Mapping', icon: 'heroicons_outline:table-cells', hint: 'Names, types and casts' },
    { id: 'enrichment', label: 'Enrichment', icon: 'heroicons_outline:sparkles', hint: 'Joins and aggregations', optional: true },
    { id: 'publish', label: 'Dataset & Go-live', icon: 'heroicons_outline:rocket-launch', hint: 'Output format and activation' },
];

const REFERENCE_STAGES: OnboardingStage[] = [
    { id: 'collection', label: 'Collection', icon: 'heroicons_outline:inbox-arrow-down', hint: 'Where the dumps come from' },
    { id: 'parsing', label: 'Parsing', icon: 'heroicons_outline:code-bracket', hint: 'How raw bytes become rows' },
    // Required, not optional: a pipeline cannot arm without a schema — the keys stage IS where a
    // Reference gets its columns/types (plus the honest full-replace load-policy note).
    { id: 'keys', label: 'Keys & Load', icon: 'heroicons_outline:key', hint: 'Columns, types and the full-replace load' },
    { id: 'publish', label: 'Publish', icon: 'heroicons_outline:rocket-launch', hint: 'Make it bindable by name' },
];

/**
 * Per-onboarding-session state (provided by the shell, one instance per opened draft): the
 * server-held config is THE draft — every stage save is a `POST /config/write overwrite:true`
 * and readiness is recomputed from the blocks, never stored. Session-only extras (the captured
 * sample, the last parse preview, the active pane's dirty check) live here so the stage panes
 * and the sample panel share them without inputs.
 */
@Injectable()
export class OnboardingStateService {
    private configApi = inject(ConfigService);
    private toastr = inject(ToastrService);

    readonly name = signal('');
    readonly loading = signal(false);
    /** 404 on load — no draft/pipeline of that name on the server. */
    readonly missing = signal(false);
    readonly writesDisabled = signal(false);
    readonly config = signal<Record<string, unknown> | null>(null);
    readonly activeStageId = signal<OnboardingStageId>('collection');

    // ── sample-as-thread (session-held, re-capturable; v1 keeps it out of the config) ──
    readonly sample = signal<{ name: string; text: string } | null>(null);
    readonly parsePreview = signal<ParsingPreview | null>(null);
    readonly parseError = signal<string | null>(null);
    readonly schemaPreview = signal<SchemaPreview | null>(null);
    readonly schemaError = signal<string | null>(null);
    /** The companion `EnrichmentConfig` (Streams only, `<name>_enrich`) — server-held like the
     *  draft itself; null = none authored yet (the stage is optional). */
    readonly enrichmentConfig = signal<Record<string, unknown> | null>(null);

    /** The active pane's unsaved-changes probe (registered on init, cleared on destroy). */
    private dirtyCheck: (() => boolean) | null = null;

    readonly kind = computed<'stream' | 'reference'>(() =>
        String((this.config() ?? {})['produces'] ?? '') === 'reference' ? 'reference' : 'stream',
    );
    readonly active = computed(() => (this.config() ?? {})['active'] === true);
    readonly stages = computed<OnboardingStage[]>(() =>
        this.kind() === 'reference' ? REFERENCE_STAGES : STREAM_STAGES,
    );
    /** The engine's normalized pipeline id (`Identity.pipelineName`) — what `BatchEvent.pipeline()`
     *  carries and what an enrichment's `triggers.on_pipeline` must therefore use. */
    readonly normalizedName = computed(() =>
        String((this.config() ?? {})['name'] ?? this.name()).toLowerCase().replace(/ /g, '_'),
    );
    /** Companion enrichment identity, mirroring the schema convention (`<pipeline>_schema`). */
    enrichName(): string {
        return `${this.name()}_enrich`;
    }

    readonly stageStatus = computed<Record<OnboardingStageId, StageStatus>>(() => {
        const cfg = this.config() ?? {};
        const proc = (cfg['processing'] ?? {}) as Record<string, unknown>;
        const parsing = (cfg['parsing'] ?? {}) as Record<string, unknown>;
        const hasSchema =
            !!proc['schema_file'] ||
            (Array.isArray(proc['schemas']) && proc['schemas'].length > 0) ||
            !!proc['ingester'] || !!parsing['plugin'];
        const parsingConfigured = 'parsing' in cfg;
        const schemaStatus: StageStatus = hasSchema
            ? this.schemaPreview() && !this.schemaError() ? 'validated' : 'configured'
            : 'empty';
        return {
            collection: 'collector' in cfg ? 'configured' : 'empty',
            parsing: parsingConfigured
                ? this.parsePreview() && !this.parseError() ? 'validated' : 'configured'
                : 'empty',
            schema: schemaStatus,
            // The Reference "Keys & Load" stage authors the same schema artifact.
            keys: schemaStatus,
            enrichment: this.enrichmentConfig() ? 'configured' : 'empty',
            publish: 'output' in cfg || this.active() ? 'configured' : 'empty',
        };
    });

    /** Draft (incomplete) → Ready (complete, inactive) → Live (active). */
    readonly lifecycle = computed<'Draft' | 'Ready' | 'Live'>(() => {
        if (this.active()) return 'Live';
        const status = this.stageStatus();
        const required = this.stages().filter((s) => !s.optional && s.id !== 'publish');
        const complete = required.every((s) => status[s.id] !== 'empty') && status['publish'] !== 'empty';
        return complete ? 'Ready' : 'Draft';
    });

    /** The first not-yet-configured stage in data-path order — where a resumed session lands
     *  (an unimplemented stage's placeholder honestly names the next step). */
    readonly firstOpenStage = computed<OnboardingStageId>(() => {
        const status = this.stageStatus();
        const next = this.stages().find((s) => status[s.id] === 'empty');
        return (next ?? this.stages()[0]).id;
    });

    load(name: string): void {
        this.name.set(name);
        this.loading.set(true);
        this.missing.set(false);
        this.enrichmentConfig.set(null);
        this.configApi.read('pipeline', name).subscribe({
            next: (r) => {
                this.config.set(r.config);
                this.loading.set(false);
                // Streams may carry a companion enrichment; 404 just means none authored yet.
                if (this.kind() === 'stream') {
                    this.configApi.read('enrichment', this.enrichName()).subscribe({
                        next: (er) => this.enrichmentConfig.set(er.config),
                        error: () => this.enrichmentConfig.set(null),
                    });
                }
            },
            error: (e) => {
                this.loading.set(false);
                if (e?.status === 404) this.missing.set(true);
                else if (e?.status === 503) this.writesDisabled.set(true);
                else this.toastr.error(apiErrorMessage(e, `Could not load "${name}".`));
            },
        });
    }

    /**
     * Stage save: deep-merge `patch` over the server-held config and overwrite the file (the
     * running service hot-reloads it by mtime). `undefined` patch values delete their key.
     */
    saveBlock(patch: Record<string, unknown>): Observable<ConfigWriteResult> {
        const next = mergeBlock(this.config() ?? {}, patch);
        return this.configApi.write('pipeline', next, { overwrite: true }).pipe(
            tap({
                next: (r) => {
                    this.config.set(next);
                    const warnings = (r.findings ?? []).length;
                    if (warnings) {
                        this.toastr.warning(
                            `${r.findings[0].message}${warnings > 1 ? ` (+${warnings - 1} more)` : ''}`,
                            'Saved with warnings',
                        );
                    }
                },
                error: (e) => {
                    if (e?.status === 503) this.writesDisabled.set(true);
                    else this.toastr.error(apiErrorMessage(e, 'Could not save the draft.'));
                },
            }),
        );
    }

    /**
     * Draft discard — the server refuses an active pipeline (409); the shell confirms first. Deletes
     * the pipeline first (the authoritative, gated op), then best-effort cascades the guided companions
     * so no orphan `<name>_schema` / `<name>_enrich` configs linger. Companion failures (404 = never
     * authored, or anything else) don't fail the discard — the pipeline is already gone. (The in-memory
     * registry still ghosts the deleted pipeline for ≤60s; that unregister is a backend concern.)
     */
    discardDraft(): Observable<ConfigDeleteResult> {
        const name = this.name();
        return this.configApi.remove('pipeline', name).pipe(
            switchMap((res) =>
                forkJoin([
                    this.configApi.remove('schema', `${name}_schema`).pipe(catchError(() => of(null))),
                    this.configApi.remove('enrichment', `${name}_enrich`).pipe(catchError(() => of(null))),
                ]).pipe(map(() => res)),
            ),
        );
    }

    captureSample(name: string, text: string): void {
        this.sample.set({ name, text });
        // A new sample invalidates every downstream test result — the thread restarts at raw.
        this.parsePreview.set(null);
        this.parseError.set(null);
        this.schemaPreview.set(null);
        this.schemaError.set(null);
    }

    clearSample(): void {
        this.sample.set(null);
        this.parsePreview.set(null);
        this.parseError.set(null);
        this.schemaPreview.set(null);
        this.schemaError.set(null);
    }

    registerDirtyCheck(fn: () => boolean): void {
        this.dirtyCheck = fn;
    }

    unregisterDirtyCheck(fn: () => boolean): void {
        if (this.dirtyCheck === fn) this.dirtyCheck = null;
    }

    isDirty(): boolean {
        return this.dirtyCheck?.() ?? false;
    }
}

import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage, ConfigDeleteResult, ConfigService, ConfigWriteResult, ParsingPreview } from 'app/inspecto/api';
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
    /** Panes for these land in a later phase; the rail still shows the whole journey. */
    implemented: boolean;
}

const STREAM_STAGES: OnboardingStage[] = [
    { id: 'collection', label: 'Collection', icon: 'heroicons_outline:inbox-arrow-down', hint: 'Where the files come from', implemented: true },
    { id: 'parsing', label: 'Parsing', icon: 'heroicons_outline:code-bracket', hint: 'How raw bytes become rows', implemented: true },
    { id: 'schema', label: 'Schema & Mapping', icon: 'heroicons_outline:table-cells', hint: 'Names, types and casts', implemented: false },
    { id: 'enrichment', label: 'Enrichment', icon: 'heroicons_outline:sparkles', hint: 'Joins and aggregations', optional: true, implemented: false },
    { id: 'publish', label: 'Dataset & Go-live', icon: 'heroicons_outline:rocket-launch', hint: 'Output format and activation', implemented: false },
];

const REFERENCE_STAGES: OnboardingStage[] = [
    { id: 'collection', label: 'Collection', icon: 'heroicons_outline:inbox-arrow-down', hint: 'Where the dumps come from', implemented: true },
    { id: 'parsing', label: 'Parsing', icon: 'heroicons_outline:code-bracket', hint: 'How raw bytes become rows', implemented: true },
    { id: 'keys', label: 'Keys & Load', icon: 'heroicons_outline:key', hint: 'Full-replace load policy', optional: true, implemented: false },
    { id: 'publish', label: 'Publish', icon: 'heroicons_outline:rocket-launch', hint: 'Make it bindable by name', implemented: false },
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

    /** The active pane's unsaved-changes probe (registered on init, cleared on destroy). */
    private dirtyCheck: (() => boolean) | null = null;

    readonly kind = computed<'stream' | 'reference'>(() =>
        String((this.config() ?? {})['produces'] ?? '') === 'reference' ? 'reference' : 'stream',
    );
    readonly active = computed(() => (this.config() ?? {})['active'] === true);
    readonly stages = computed<OnboardingStage[]>(() =>
        this.kind() === 'reference' ? REFERENCE_STAGES : STREAM_STAGES,
    );

    readonly stageStatus = computed<Record<OnboardingStageId, StageStatus>>(() => {
        const cfg = this.config() ?? {};
        const proc = (cfg['processing'] ?? {}) as Record<string, unknown>;
        const parsing = (cfg['parsing'] ?? {}) as Record<string, unknown>;
        const hasSchema =
            !!proc['schema_file'] ||
            (Array.isArray(proc['schemas']) && proc['schemas'].length > 0) ||
            !!proc['ingester'] || !!parsing['plugin'];
        const parsingConfigured = 'parsing' in cfg;
        return {
            collection: 'collector' in cfg ? 'configured' : 'empty',
            parsing: parsingConfigured
                ? this.parsePreview() && !this.parseError() ? 'validated' : 'configured'
                : 'empty',
            schema: hasSchema ? 'configured' : 'empty',
            enrichment: 'empty', // companion EnrichmentConfig — P3
            keys: 'empty', // Reference load policy — P3 (full-replace is today's only semantics)
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
        this.configApi.read('pipeline', name).subscribe({
            next: (r) => {
                this.config.set(r.config);
                this.loading.set(false);
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

    /** Draft discard — the server refuses an active pipeline (409); the shell confirms first. */
    discardDraft(): Observable<ConfigDeleteResult> {
        return this.configApi.remove('pipeline', this.name());
    }

    captureSample(name: string, text: string): void {
        this.sample.set({ name, text });
        // A new sample invalidates the previous parse result — the thread restarts at raw.
        this.parsePreview.set(null);
        this.parseError.set(null);
    }

    clearSample(): void {
        this.sample.set(null);
        this.parsePreview.set(null);
        this.parseError.set(null);
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

import { ChangeDetectionStrategy, Component, OnDestroy, OnInit, computed, effect, inject, signal } from '@angular/core';
import { FormArray, FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ToastrService } from 'ngx-toastr';
import { CatalogService, ConfigService, LensService, SpacesService, apiErrorMessage } from 'app/inspecto/api';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { SqlCodemirrorComponent } from 'app/inspecto/data-table';
import { OnboardingStateService } from './onboarding-state.service';

const IDENTIFIER_RE = /^[A-Za-z_][A-Za-z0-9_]*$/;

interface ReferenceRow {
    name: string;
    mode: 'ref' | 'path';
    ref: string;
    path: string;
    format: string;
}

/**
 * Enrichment stage (optional, Streams) — authors the companion `EnrichmentConfig`
 * (`<pipeline>_enrich`): reference bindings (first-class by name to a `produces: reference`
 * pipeline, or a direct file path) + the transform SQL. Everything else is derived and shown, not
 * asked: input = this pipeline's Stage-1 output, trigger = `on_pipeline` with the engine's
 * normalized id (what `BatchEvent.pipeline()` carries), output = the space's `enriched/` convention.
 * Saves write the config AND re-register it (`POST /enrichment`) — enrichments do not hot-reload
 * by mtime, so registration is what makes a save apply to the running service.
 */
@Component({
    selector: 'app-onboarding-enrichment-pane',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatProgressSpinnerModule,
        MatSelectModule,
        MatTooltipModule,
        InspectoEmptyStateComponent,
        SqlCodemirrorComponent,
    ],
    templateUrl: './enrichment-pane.component.html',
})
export class OnboardingEnrichmentPaneComponent implements OnInit, OnDestroy {
    protected readonly state = inject(OnboardingStateService);
    protected readonly lens = inject(LensService);
    private configApi = inject(ConfigService);
    private catalogApi = inject(CatalogService);
    private spaces = inject(SpacesService);
    private fb = inject(FormBuilder);
    private toastr = inject(ToastrService);

    /** The form shows only once the author opts in (the stage is optional). */
    readonly started = signal(this.state.enrichmentConfig() !== null);
    readonly saving = signal(false);
    /** Produced Reference Datasets bindable by name (id = the producer's normalized pipeline id). */
    readonly referenceOptions = signal<{ id: string; label: string }[]>([]);

    readonly referencesForm: FormGroup = this.fb.group({ references: this.fb.array<FormGroup>([]) });
    get referenceRows(): FormArray<FormGroup> {
        return this.referencesForm.controls['references'] as FormArray<FormGroup>;
    }
    readonly sql = signal('SELECT * FROM input');
    private savedSql = this.sql();

    /** The DuckDB views the transform can select from: `input` + each reference alias. */
    readonly availableViews = computed(() => {
        void this.viewsTick();
        return ['input', ...this.referenceRows.controls
            .map((g) => String(g.getRawValue()['name'] ?? '').trim())
            .filter((n) => IDENTIFIER_RE.test(n))];
    });
    /** Bumped on reference-row edits so `availableViews` recomputes (forms aren't signals). */
    private readonly viewsTick = signal(0);

    private readonly dirtyCheck = (): boolean => this.referencesForm.dirty || this.sql() !== this.savedSql;

    constructor() {
        this.state.registerDirtyCheck(this.dirtyCheck);
        // Hydrate from the server-held companion — including when its (async) read lands after
        // this pane mounted. Never clobber unsaved edits.
        effect(() => {
            const cfg = this.state.enrichmentConfig();
            if (cfg && !this.dirtyCheck()) {
                this.hydrate(cfg);
                this.started.set(true);
            }
        });
    }

    ngOnInit(): void {
        this.catalogApi.references().subscribe({
            next: (nodes) => {
                const self = this.state.normalizedName();
                this.referenceOptions.set(nodes
                    .filter((n) => typeof n.attrs?.['pipeline'] === 'string' && n.attrs['pipeline'] !== self)
                    .map((n) => ({ id: String(n.attrs!['pipeline']), label: n.label })));
            },
            error: () => this.referenceOptions.set([]),
        });
    }

    ngOnDestroy(): void {
        this.state.unregisterDirtyCheck(this.dirtyCheck);
    }

    start(): void {
        this.started.set(true);
    }

    addReference(): void {
        this.addRow({ name: '', mode: 'ref', ref: '', path: '', format: 'CSV' });
        this.referencesForm.markAsDirty();
    }

    removeReference(i: number): void {
        this.referenceRows.removeAt(i);
        this.referencesForm.markAsDirty();
        this.viewsTick.update((n) => n + 1);
    }

    private addRow(row: ReferenceRow): void {
        const g = this.fb.group({
            name: [row.name, [Validators.required, Validators.pattern(IDENTIFIER_RE)]],
            mode: [row.mode],
            ref: [row.ref],
            path: [row.path],
            format: [row.format],
        });
        g.valueChanges.subscribe(() => this.viewsTick.update((n) => n + 1));
        this.referenceRows.push(g);
    }

    onSqlChange(value: string): void {
        this.sql.set(value);
    }

    private hydrate(cfg: Record<string, unknown>): void {
        const refs = (cfg['references'] ?? {}) as Record<string, Record<string, unknown>>;
        this.referenceRows.clear();
        for (const [name, r] of Object.entries(refs)) {
            const byName = typeof r['ref'] === 'string' && String(r['ref']).trim() !== '';
            this.addRow({
                name,
                mode: byName ? 'ref' : 'path',
                ref: byName ? String(r['ref']) : '',
                path: byName ? '' : String(r['path'] ?? ''),
                format: String(r['format'] ?? 'CSV'),
            });
        }
        this.sql.set(String(cfg['transform'] ?? this.sql()));
        this.savedSql = this.sql();
        this.referencesForm.markAsPristine();
        this.viewsTick.update((n) => n + 1);
    }

    // ── derived plumbing (shown, never asked) ────────────────────────────────────

    private base(): string {
        return this.spaces.currentSpaceId() ? `spaces/${this.spaces.currentSpaceId()}` : '.';
    }

    /** Input = this pipeline's Stage-1 output (kept verbatim on resume — the file is the truth). */
    inputBlock(): Record<string, unknown> {
        const existing = this.state.enrichmentConfig();
        if (existing?.['input']) return existing['input'] as Record<string, unknown>;
        const dirs = ((this.state.config() ?? {})['dirs'] ?? {}) as Record<string, unknown>;
        const output = ((this.state.config() ?? {})['output'] ?? {}) as Record<string, unknown>;
        return {
            database: String(dirs['database'] ?? ''),
            format: String(output['format'] ?? 'PARQUET').toUpperCase(),
            partitions: ['year', 'month', 'day'],
        };
    }

    outputBlock(): Record<string, unknown> {
        const existing = this.state.enrichmentConfig();
        if (existing?.['output']) return existing['output'] as Record<string, unknown>;
        return {
            database: `${this.base()}/data/enriched/${this.state.enrichName()}`,
            format: 'PARQUET',
            partitions: ['year', 'month', 'day'],
        };
    }

    /** Rows validated into the config's `references:` map, or null on a blocking problem. */
    private buildReferences(): Record<string, Record<string, string>> | null {
        if (this.referenceRows.invalid) {
            this.referenceRows.controls.forEach((g) => g.markAllAsTouched());
            this.toastr.warning('Every reference alias must be a valid identifier (letters, digits, _).');
            return null;
        }
        const rows = this.referenceRows.controls.map((g) => g.getRawValue() as ReferenceRow);
        const names = rows.map((r) => r.name.trim());
        const dup = names.find((n, i) => names.indexOf(n) !== i);
        if (dup) {
            this.toastr.warning(`Duplicate reference alias "${dup}" — aliases must be unique.`);
            return null;
        }
        const out: Record<string, Record<string, string>> = {};
        for (const r of rows) {
            if (r.mode === 'ref') {
                if (!r.ref.trim()) {
                    this.toastr.warning(`Reference "${r.name}" needs a published Reference to bind.`);
                    return null;
                }
                out[r.name.trim()] = { ref: r.ref.trim() };
            } else {
                if (!r.path.trim()) {
                    this.toastr.warning(`Reference "${r.name}" needs a file path.`);
                    return null;
                }
                out[r.name.trim()] = { path: r.path.trim(), format: r.format };
            }
        }
        return out;
    }

    save(): void {
        if (!this.lens.canAuthorWorkbench()) return;
        const references = this.buildReferences();
        if (references === null) return;
        if (!this.sql().trim()) {
            this.toastr.warning('The transform SQL is required — it defines the enriched output.');
            return;
        }
        const draft: Record<string, unknown> = {
            name: this.state.enrichName(),
            input: this.inputBlock(),
            output: this.outputBlock(),
            transform: this.sql().trim(),
            triggers: { on_pipeline: this.state.normalizedName() },
        };
        if (Object.keys(references).length > 0) draft['references'] = references;

        this.saving.set(true);
        this.configApi.write('enrichment', draft, { overwrite: true }).subscribe({
            next: (written) => {
                // Register every save: enrichments do NOT hot-reload by mtime (unlike the pipeline).
                this.configApi.registerEnrichment(written.path).subscribe({
                    next: () => {
                        this.saving.set(false);
                        this.finishSave(draft);
                        this.toastr.success('Enrichment saved — runs after every committed batch');
                    },
                    error: (e) => {
                        this.saving.set(false);
                        this.finishSave(draft);
                        this.toastr.warning(apiErrorMessage(e,
                            'Saved, but registering failed — it will load on the next service restart.'));
                    },
                });
            },
            error: (e) => {
                this.saving.set(false);
                this.toastr.error(apiErrorMessage(e, 'Could not save the enrichment.'));
            },
        });
    }

    private finishSave(draft: Record<string, unknown>): void {
        this.state.enrichmentConfig.set(draft);
        this.savedSql = this.sql();
        this.referencesForm.markAsPristine();
    }
}

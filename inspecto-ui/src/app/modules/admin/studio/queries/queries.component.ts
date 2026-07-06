import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ToastrService } from 'ngx-toastr';
import { LensService, ParameterContextService, apiErrorMessage } from 'app/inspecto/api';
import { BUILTIN_PARAMS, ParameterDef, findParameters, resolveParameters } from 'app/inspecto/query';
import { ResultSet, describeResultSet, recommend } from 'app/inspecto/viz';
import { registerBuiltinViz } from 'app/inspecto/viz/plugins';
import { runSql } from 'app/inspecto/data-table/sql/sql-run';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { StatusBadgeComponent } from 'app/inspecto/components/status-badge.component';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { uniqueNameValidator } from 'app/inspecto/investigation/unique-name';
import { Dataset } from '../datasets/dataset-types';
import { DatasetsService } from '../datasets/datasets.service';
import { SAMPLE_SOURCES } from '../datasets/dataset-sources';
import { Query, buildQuery } from './query-types';
import { QueriesService } from './queries.service';
import './query.kind'; // ensure the query kind is registered

// The Show-Me recommender (used in the preview) scores against the registered viz plugins; register the
// built-ins here so the Query Library works standalone (guarded — no-op if the widgets feature loaded first).
registerBuiltinViz();

/** The outcome of a preview run — the resolved SQL, plus (on success) the described result set. */
interface PreviewState {
    resolvedSql: string;
    resultSet?: ResultSet;
    rows?: Record<string, unknown>[];
    recommended?: string[];
    error?: string;
}

/** Strip a `$token` (e.g. `$day(-7)`) to its name (`day`). */
function tokenName(raw: string): string {
    return raw.replace(/^\$/, '').replace(/\(.*\)$/, '');
}

/**
 * **Query Library** — R3 of the living-operational-system roadmap (§4): author reusable `query`
 * components. A query reads a source dataset, holds SQL that may reference `$`-parameters, and previews
 * offline (resolve params → AlaSQL `runSql` → {@link describeResultSet}). One saved query can then be
 * bound by many widgets. Mock-first; everything runs in-browser. Follows the house form rules
 * (ask-the-minimum, duplicate-name = inline block).
 */
@Component({
    selector: 'app-queries',
    standalone: true,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatProgressSpinnerModule,
        MatSelectModule,
        MatTooltipModule,
        InspectoAlertComponent,
        InspectoEmptyStateComponent,
        StatusBadgeComponent,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    templateUrl: './queries.component.html',
})
export class QueriesComponent implements OnInit {
    private fb = inject(FormBuilder);
    private queriesApi = inject(QueriesService);
    private datasetsApi = inject(DatasetsService);
    private paramCtx = inject(ParameterContextService);
    private confirm = inject(InspectoConfirmService);
    private toastr = inject(ToastrService);
    private lens = inject(LensService);
    private destroyRef = inject(DestroyRef);

    /** Authoring gate — Business lens views read-only (capability seam, not lens identity). */
    readonly canAuthor = this.lens.canAuthorWorkbench;

    readonly queries = signal<Query[]>([]);
    readonly datasets = signal<Dataset[]>([]);
    readonly loading = signal(false);
    readonly editing = signal(false);
    readonly editingExisting = signal(false);
    readonly saving = signal(false);
    readonly writesDisabled = signal(false);
    readonly running = signal(false);
    readonly preview = signal<PreviewState | null>(null);

    readonly form = this.fb.group({
        name: ['', [Validators.required, Validators.pattern(/^[A-Za-z0-9][A-Za-z0-9._-]*$/)]],
        description: [''],
        datasetId: this.fb.nonNullable.control('', Validators.required),
        text: this.fb.nonNullable.control(''),
    });

    /** Live mirror of the SQL text (drives parameter detection) + the user-declared param defaults/types.
     *  Types are preserved from a loaded/seeded query (no type picker in the MVP — new tokens default to string). */
    readonly text = signal('');
    readonly paramDefaults = signal<Record<string, string>>({});
    readonly paramTypes = signal<Record<string, ParameterDef['type']>>({});

    /** The distinct built-in `$`-tokens present (resolved from context — shown read-only). */
    readonly builtinTokens = computed(() => findParameters(this.text()).filter((t) => BUILTIN_PARAMS.includes(tokenName(t) as (typeof BUILTIN_PARAMS)[number])));
    /** The distinct user-declared `$`-token names present (each gets an editable default). */
    readonly userParamNames = computed(() => [...new Set(findParameters(this.text()).map(tokenName).filter((n) => !BUILTIN_PARAMS.includes(n as (typeof BUILTIN_PARAMS)[number])))]);

    ngOnInit(): void {
        this.form.controls.text.valueChanges
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe((t) => this.text.set(t));
        this.datasetsApi.list().subscribe({
            next: (d) => this.datasets.set(d),
            error: () => this.toastr.warning('Could not load datasets.'),
        });
        this.load();
    }

    load(): void {
        this.loading.set(true);
        this.queriesApi.list().subscribe({
            next: (q) => {
                this.queries.set(q);
                this.loading.set(false);
            },
            error: () => this.loading.set(false),
        });
    }

    newQuery(): void {
        this.form.reset({ name: '', description: '', datasetId: '', text: '' });
        this.form.controls.name.enable();
        this.form.controls.name.setValidators([Validators.required, Validators.pattern(/^[A-Za-z0-9][A-Za-z0-9._-]*$/), uniqueNameValidator(() => this.queries().map((q) => q.id))]);
        this.form.controls.name.updateValueAndValidity({ emitEvent: false });
        this.paramDefaults.set({});
        this.paramTypes.set({});
        this.text.set('');
        this.preview.set(null);
        this.editingExisting.set(false);
        this.editing.set(true);
    }

    editQuery(q: Query): void {
        this.form.reset({ name: q.name, description: q.description ?? '', datasetId: q.datasetId ?? '', text: q.text ?? '' });
        this.form.controls.name.setValidators([Validators.required]);
        this.form.controls.name.disable(); // id is immutable on edit
        this.paramDefaults.set(Object.fromEntries(q.parameters.map((p) => [p.name, p.default ?? ''])));
        this.paramTypes.set(Object.fromEntries(q.parameters.map((p) => [p.name, p.type])));
        this.text.set(q.text ?? '');
        this.preview.set(null);
        this.editingExisting.set(true);
        this.editing.set(true);
    }

    cancel(): void {
        this.editing.set(false);
        this.preview.set(null);
    }

    setParamDefault(name: string, value: string): void {
        this.paramDefaults.set({ ...this.paramDefaults(), [name]: value });
    }

    /** The declared parameters = each user token + its (possibly empty) default and preserved type. */
    private paramDefs(): ParameterDef[] {
        const defaults = this.paramDefaults();
        const types = this.paramTypes();
        return this.userParamNames().map((name) => ({ name, type: types[name] ?? 'string', default: defaults[name] ?? '' }));
    }

    private selectedDataset(): Dataset | undefined {
        return this.datasets().find((d) => d.id === this.form.controls.datasetId.value);
    }

    async run(): Promise<void> {
        const ds = this.selectedDataset();
        const resolvedSql = resolveParameters(this.form.controls.text.value, this.paramDefs(), this.paramCtx.context());
        this.running.set(true);
        const rows = SAMPLE_SOURCES[ds?.sourceName ?? ''] ?? [];
        const res = await runSql(resolvedSql, ds?.sourceName ?? 'data', rows);
        if (!res.ok) {
            this.preview.set({ resolvedSql, error: res.error });
            this.running.set(false);
            return;
        }
        const hints = (ds?.columns ?? []).map((c) => ({ name: c.name, type: c.type, role: c.role }));
        const resultSet = describeResultSet(res.rows, hints);
        const recommended = recommend(resultSet).slice(0, 3).map((p) => p.meta.label);
        this.preview.set({ resolvedSql, resultSet, rows: res.rows.slice(0, 20), recommended });
        this.running.set(false);
    }

    save(): void {
        // The name control is disabled on edit; `.value` still exposes it, and validity is only enforced
        // on create (the shared duplicate/pattern rules are create-only).
        const name = String(this.form.controls.name.value ?? '').trim();
        if (!name || !this.form.controls.datasetId.value || (this.form.controls.name.enabled && this.form.controls.name.invalid)) {
            this.form.markAllAsTouched();
            return;
        }
        const ds = this.selectedDataset();
        const q = buildQuery(name, 'sql', {
            datasetId: this.form.controls.datasetId.value,
            sourceName: ds?.sourceName,
            text: this.form.controls.text.value,
            parameters: this.paramDefs(),
        });
        q.description = this.form.controls.description.value || undefined;
        this.saving.set(true);
        this.queriesApi.save(q).subscribe({
            next: () => {
                this.saving.set(false);
                this.toastr.success(`Query "${q.name}" saved`);
                this.editing.set(false);
                this.load();
            },
            error: (e) => {
                this.saving.set(false);
                if (e?.status === 503) this.writesDisabled.set(true);
                this.toastr.error(e?.status === 503 ? 'Writes are disabled (no write root configured).' : apiErrorMessage(e, `Could not save "${q.name}"`));
            },
        });
    }

    async remove(q: Query): Promise<void> {
        if (!(await this.confirm.confirmDestructive(`Delete query "${q.name}"?`))) return;
        this.queriesApi.remove(q.id).subscribe({
            next: () => {
                this.toastr.success(`Query "${q.name}" deleted`);
                this.load();
            },
            error: (e) => this.toastr.error(apiErrorMessage(e, `Could not delete "${q.name}"`)),
        });
    }

    /** The generic `duplicate` message the shared validator raises (mirrors the other authoring forms). */
    errorFor(control: 'name'): string | null {
        const c = this.form.controls[control];
        if (!c.touched) return null;
        if (c.hasError('required')) return 'Required.';
        if (c.hasError('pattern')) return 'Letters, digits, dot, dash, underscore; start alphanumeric.';
        if (c.hasError('duplicate')) return 'That name is already taken.';
        return null;
    }
}

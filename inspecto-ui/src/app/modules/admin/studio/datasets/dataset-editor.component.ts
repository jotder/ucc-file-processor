import { ChangeDetectionStrategy, Component, DestroyRef, Input, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { AbstractControl, FormBuilder, ReactiveFormsModule, ValidatorFn, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSelectModule } from '@angular/material/select';
import { Router, RouterLink } from '@angular/router';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage } from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { ComponentHistoryDialog } from 'app/inspecto/components/component-history.dialog';
import { TransferMenuComponent } from 'app/inspecto/transfer';
import { ColumnMeta, QueryChange, QueryModel, QueryPanelComponent, QuerySource, inferColumns } from 'app/inspecto/query';
import { DatasetCalculatedComponent } from './dataset-calculated.component';
import { DatasetColumnsComponent } from './dataset-columns.component';
import { DatasetMeasuresComponent } from './dataset-measures.component';
import { SAMPLE_SOURCES, SAMPLE_SOURCE_NAMES } from './dataset-sources';
import { buildDataset, CalculatedColumn, Dataset, DatasetColumn, DatasetKind, NamedMeasure, inferRoles } from './dataset-types';
import { DatasetsService } from './datasets.service';

const KINDS: DatasetKind[] = ['virtual', 'physical', 'materialized'];

/** Rejects a value (case-insensitive, trimmed) already present in `taken` → `{ duplicate: true }`. */
function uniqueNameValidator(taken: string[]): ValidatorFn {
    const set = new Set(taken.map((t) => t.trim().toLowerCase()));
    return (c: AbstractControl) => (set.has(String(c.value ?? '').trim().toLowerCase()) ? { duplicate: true } : null);
}

/**
 * Dataset editor — create or edit a Studio {@link Dataset}. A **virtual** dataset embeds the Query Core
 * ({@link QueryPanelComponent}) over a sample source to author its SQL view; physical/materialized carry a
 * reference. Either way the operator tags column **roles/formats** ({@link DatasetColumnsComponent}) and saves
 * via {@link DatasetsService} (the mock-backed `dataset` component kind). Mirrors the rule editor flow.
 */
@Component({
    selector: 'app-dataset-editor',
    standalone: true,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatProgressSpinnerModule,
        MatSelectModule,
        RouterLink,
        InspectoAlertComponent,
        QueryPanelComponent,
        DatasetColumnsComponent,
        DatasetCalculatedComponent,
        DatasetMeasuresComponent,
        TransferMenuComponent,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    templateUrl: './dataset-editor.component.html',
})
export class DatasetEditorComponent implements OnInit {
    private fb = inject(FormBuilder);
    private datasets = inject(DatasetsService);
    private toastr = inject(ToastrService);
    private router = inject(Router);
    private destroyRef = inject(DestroyRef);
    private matDialog = inject(MatDialog);

    /** Route param — the dataset id to edit; absent on the `new` route (create mode). */
    @Input() id?: string;

    /** This saved dataset as a transfer reference — export is offered only in edit mode. */
    get transferItems(): { kind: 'dataset'; id: string }[] {
        return this.id ? [{ kind: 'dataset', id: this.id }] : [];
    }

    readonly kinds = KINDS;
    readonly sourceNames = SAMPLE_SOURCE_NAMES;
    readonly editing = signal(false);
    readonly saving = signal(false);
    readonly writesDisabled = signal(false);

    readonly form = this.fb.group({
        name: ['', [Validators.required, Validators.pattern(/^[A-Za-z0-9][A-Za-z0-9._-]*$/)]],
        kind: this.fb.nonNullable.control<DatasetKind>('virtual'),
        sourceName: this.fb.nonNullable.control(SAMPLE_SOURCE_NAMES[0] ?? 'data'),
        physicalRef: this.fb.nonNullable.control(''),
    });

    readonly kind = signal<DatasetKind>('virtual');
    readonly sourceName = signal(SAMPLE_SOURCE_NAMES[0] ?? 'data');
    readonly columns = signal<DatasetColumn[]>([]);
    readonly calculated = signal<CalculatedColumn[]>([]);
    readonly measures = signal<NamedMeasure[]>([]);
    private readonly model = signal<QueryModel | null>(null);

    /** The Query Core source for the embedded panel — the selected sample source's rows + inferred columns. */
    readonly querySource = computed<QuerySource>(() => {
        const name = this.sourceName();
        const rows = SAMPLE_SOURCES[name] ?? [];
        return { name, rows, columns: this.inferredColumns() };
    });
    private readonly inferredColumns = computed<ColumnMeta[]>(() => inferColumns(SAMPLE_SOURCES[this.sourceName()] ?? []));

    readonly isVirtual = computed(() => this.kind() === 'virtual');

    ngOnInit(): void {
        // React to source/kind changes so the column tagger + panel stay in sync.
        this.form.controls.kind.valueChanges
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe((k) => this.kind.set(k));
        this.form.controls.sourceName.valueChanges
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe((s) => {
                this.sourceName.set(s);
                this.columns.set(inferRoles(this.inferredColumns()));
                this.model.set(null);
            });

        if (this.id) {
            this.editing.set(true);
            this.loadExisting(this.id);
        } else {
            this.columns.set(inferRoles(this.inferredColumns()));
            // Product-wide rule: block a duplicate id inline on create rather than relying on the server 409.
            this.datasets
                .list()
                .pipe(takeUntilDestroyed(this.destroyRef))
                .subscribe((all) => {
                    this.form.controls.name.addValidators(uniqueNameValidator(all.map((d) => d.id)));
                    this.form.controls.name.updateValueAndValidity({ emitEvent: false });
                });
        }
    }

    private loadExisting(id: string): void {
        this.datasets.get(id).subscribe({
            next: (d) => this.seed(d),
            error: (e) => this.toastr.error(apiErrorMessage(e, `Could not load dataset "${id}"`)),
        });
    }

    /** Show version history for this saved dataset; reload its state after a restore (MET-5). Edit mode only. */
    history(): void {
        if (!this.id) return;
        const id = this.id;
        this.matDialog.open(ComponentHistoryDialog, { data: { type: 'dataset', id, label: id } })
            .afterClosed()
            .subscribe((restored) => {
                if (restored) this.loadExisting(id);
            });
    }

    private seed(d: Dataset): void {
        this.form.patchValue({ name: d.name, kind: d.kind, sourceName: d.sourceName, physicalRef: d.physicalRef ?? '' });
        this.form.controls.name.disable(); // id is immutable on edit
        this.kind.set(d.kind);
        this.sourceName.set(d.sourceName);
        // Saved roles take precedence; fall back to fresh inference for any new source columns.
        const inferred = inferRoles(this.inferredColumns());
        const bySaved = new Map(d.columns.map((c) => [c.name, c]));
        this.columns.set(inferred.map((c) => bySaved.get(c.name) ?? c));
        this.calculated.set(d.calculated);
        this.measures.set(d.measures);
        this.model.set(d.query ?? null);
    }

    onQueryChange(change: QueryChange): void {
        this.model.set(change.model);
    }

    onColumnsChange(cols: DatasetColumn[]): void {
        this.columns.set(cols);
    }

    onMeasuresChange(measures: NamedMeasure[]): void {
        this.measures.set(measures);
    }

    onCalculatedChange(calculated: CalculatedColumn[]): void {
        this.calculated.set(calculated);
    }

    save(): void {
        const ctrl = this.form.controls.name;
        const name = String(ctrl.value ?? '').trim() || (this.id ?? '');
        if (!name || (ctrl.enabled && ctrl.invalid)) {
            this.form.markAllAsTouched();
            return;
        }
        const kind = this.form.controls.kind.value;
        const ds = buildDataset(name, kind, this.form.controls.sourceName.value, {
            query: kind === 'virtual' ? this.model() : null,
            physicalRef: kind === 'virtual' ? null : this.form.controls.physicalRef.value || null,
            columns: this.columns(),
            measures: this.measures(),
            calculated: this.calculated(),
        });
        this.saving.set(true);
        this.datasets.save(ds).subscribe({
            next: () => {
                this.saving.set(false);
                this.toastr.success(`Dataset "${name}" saved`);
                this.router.navigate(['/catalog/datasets']);
            },
            error: (e) => {
                this.saving.set(false);
                if (e?.status === 503) this.writesDisabled.set(true);
                this.toastr.error(
                    e?.status === 503
                        ? 'Writes are disabled (no write root configured).'
                        : apiErrorMessage(e, `Could not save "${name}"`),
                );
            },
        });
    }
}

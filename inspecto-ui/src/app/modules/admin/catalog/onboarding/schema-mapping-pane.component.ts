import { ChangeDetectionStrategy, Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { FormArray, FormBuilder, FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Router } from '@angular/router';
import { ToastrService } from 'ngx-toastr';
import { ConfigService, LensService, SpacesService, apiErrorMessage } from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { QueryPanelComponent } from 'app/inspecto/query/query-panel.component';
import { QuerySource } from 'app/inspecto/query/query-types';
import { OnboardingStateService } from './onboarding-state.service';

/** The four types `TransformCompiler.direct()` actually TRY_CASTs — everything else is stored as
 *  text (honesty guard: no type offered here implies rigor the engine does not apply). */
const SCHEMA_TYPES = ['VARCHAR', 'DOUBLE', 'DATE', 'TIMESTAMP'] as const;

const IDENTIFIER_RE = /^[A-Za-z_][A-Za-z0-9_]*$/;

/** A parsed column name → a valid SQL identifier (`Identifiers.validate`'s own pattern), so an
 *  auto-derived field name is register-able without hand-editing. */
function sanitizeIdentifier(raw: string, index: number): string {
    let s = raw.trim().toUpperCase().replace(/[^A-Z0-9_]+/g, '_');
    s = s.replace(/^_+/, '').replace(/_+$/, '');
    if (/^[0-9]/.test(s)) s = `_${s}`;
    return s || `FIELD_${index}`;
}

/** `raw.fields[].selector` semantics differ by frontend (P2 recon): delimited/fixedwidth address
 *  the parsed column by its 0-based position; json/text_regex address it by the key/group name. */
function deriveSelector(frontend: string, index: number, columnName: string): string {
    return frontend === 'delimited' || frontend === 'fixedwidth' ? String(index) : columnName;
}

interface FieldRow {
    include: boolean;
    name: string;
    selector: string;
    type: string;
}

/**
 * Schema & Mapping stage — authors the legacy `schema` config (`raw.fields[]` + `mapping.rules[]`)
 * a pipeline's `processing.schema_file` points at. Gated on a parsed sample (fields are derived
 * from `ParsingPreview.columns`, not hand-typed); "Validate types" continues the sample thread by
 * TRY_CASTing the SAME parsed rows against the chosen types (`POST /config/preview/schema`) —
 * exactly what production's `DIRECT` mapping would cast, per the four honest types offered.
 */
@Component({
    selector: 'app-onboarding-schema-mapping-pane',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatCheckboxModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatProgressSpinnerModule,
        MatSelectModule,
        MatTooltipModule,
        InspectoAlertComponent,
        InspectoEmptyStateComponent,
        QueryPanelComponent,
    ],
    templateUrl: './schema-mapping-pane.component.html',
})
export class OnboardingSchemaMappingPaneComponent implements OnInit, OnDestroy {
    protected readonly state = inject(OnboardingStateService);
    protected readonly lens = inject(LensService);
    private configApi = inject(ConfigService);
    private spaces = inject(SpacesService);
    private router = inject(Router);
    private fb = inject(FormBuilder);
    private toastr = inject(ToastrService);

    protected readonly types = SCHEMA_TYPES;

    private readonly pipelineName = String((this.state.config() ?? {})['name'] ?? '');
    protected readonly existingSchemaFile = (() => {
        const proc = (this.state.config() ?? {})['processing'] as Record<string, unknown> | undefined;
        return String(proc?.['schema_file'] ?? '').trim();
    })();

    private base(): string {
        return this.spaces.currentSpaceId() ? `spaces/${this.spaces.currentSpaceId()}` : '.';
    }
    private schemaName(): string {
        return `${this.pipelineName}_schema`;
    }
    private conventionPath(): string {
        return `${this.base()}/config/${this.schemaName()}.toon`;
    }

    /** A schema_file set to a path the guided editor did not write — authored in the TOON directly. */
    readonly foreignManaged = signal(
        this.existingSchemaFile !== '' && this.existingSchemaFile !== this.conventionPath(),
    );
    readonly hasSource = computed(() => !!this.state.parsePreview() || !!this.existingSchemaFile);
    readonly loading = signal(false);
    readonly saving = signal(false);
    readonly testing = signal(false);

    readonly fieldsForm: FormGroup = this.fb.group({ fields: this.fb.array<FormGroup>([]) });
    get fieldRows(): FormArray<FormGroup> {
        return this.fieldsForm.controls['fields'] as FormArray<FormGroup>;
    }
    readonly partitionKeyControl = new FormControl('');

    readonly includedNames = signal<string[]>([]);
    readonly rejectedSource = computed<QuerySource>(() => ({
        name: 'rejected',
        rows: this.state.schemaPreview()?.rejectedRows ?? [],
    }));

    private readonly dirtyCheck = (): boolean => this.fieldsForm.dirty || this.partitionKeyControl.dirty;

    constructor() {
        this.state.registerDirtyCheck(this.dirtyCheck);
    }

    ngOnInit(): void {
        if (this.foreignManaged()) return;
        if (this.existingSchemaFile) {
            this.loading.set(true);
            this.configApi.read('schema', this.schemaName()).subscribe({
                next: (r) => {
                    this.loading.set(false);
                    this.hydrateFromSchema(r.config);
                },
                error: (e) => {
                    this.loading.set(false);
                    if (e?.status !== 404) this.toastr.warning(apiErrorMessage(e, 'Could not load the saved schema.'));
                    this.deriveFromSample();
                },
            });
        } else {
            this.deriveFromSample();
        }
    }

    ngOnDestroy(): void {
        this.state.unregisterDirtyCheck(this.dirtyCheck);
    }

    private deriveFromSample(): void {
        const preview = this.state.parsePreview();
        if (!preview) return;
        this.fieldRows.clear();
        preview.columns.forEach((col, i) => {
            this.addRow({
                include: true,
                name: sanitizeIdentifier(col, i),
                selector: deriveSelector(preview.frontend, i, col),
                type: 'VARCHAR',
            });
        });
        this.syncIncludedNames();
    }

    private hydrateFromSchema(config: Record<string, unknown>): void {
        const raw = (config['raw'] ?? {}) as Record<string, unknown>;
        const fields = Array.isArray(raw['fields']) ? (raw['fields'] as Record<string, unknown>[]) : [];
        this.fieldRows.clear();
        for (const f of fields) {
            this.addRow({
                include: true,
                name: String(f['name'] ?? ''),
                selector: String(f['selector'] ?? ''),
                type: String(f['type'] ?? 'VARCHAR'),
            });
        }
        this.partitionKeyControl.setValue(String(config['partitionKey'] ?? ''), { emitEvent: false });
        this.syncIncludedNames();
        // A freshly loaded (unedited) resume state is pristine, not dirty.
        this.fieldsForm.markAsPristine();
        this.partitionKeyControl.markAsPristine();
    }

    private addRow(row: FieldRow): void {
        const g = this.fb.group({
            include: [row.include],
            name: [row.name, [Validators.required, Validators.pattern(IDENTIFIER_RE)]],
            selector: [{ value: row.selector, disabled: true }],
            type: [row.type],
        });
        g.valueChanges.subscribe(() => this.syncIncludedNames());
        this.fieldRows.push(g);
    }

    private syncIncludedNames(): void {
        this.includedNames.set(
            this.fieldRows.controls
                .map((g) => g.getRawValue() as FieldRow)
                .filter((r) => r.include)
                .map((r) => r.name.trim()),
        );
    }

    jumpToParsing(): void {
        this.router.navigate(['/catalog', 'onboard', this.state.name(), 'parsing']);
    }

    /** Validated, included rows — a schema draft's `raw.fields[]` + one straight-through
     *  `mapping.rules[]` entry each (`SchemaExtractor`'s own shape); `null` on a blocking problem. */
    private buildFields(): { fields: Record<string, string>[]; rules: Record<string, string>[] } | null {
        if (this.fieldRows.invalid) {
            this.fieldRows.controls.forEach((g) => g.markAllAsTouched());
            this.toastr.warning('Every field name must start with a letter or _ and use only letters, digits, _.');
            return null;
        }
        const rows = this.fieldRows.controls.map((g) => g.getRawValue() as FieldRow).filter((r) => r.include);
        if (rows.length === 0) {
            this.toastr.warning('Include at least one field.');
            return null;
        }
        const names = rows.map((r) => r.name.trim());
        const dup = names.find((n, i) => names.indexOf(n) !== i);
        if (dup) {
            this.toastr.warning(`Duplicate field name "${dup}" — names must be unique.`);
            return null;
        }
        return {
            fields: rows.map((r) => ({ name: r.name.trim(), selector: r.selector, type: r.type })),
            rules: rows.map((r) => ({ targetColumn: r.name.trim(), sourceExpression: r.name.trim() })),
        };
    }

    testTypes(): void {
        const preview = this.state.parsePreview();
        const built = this.buildFields();
        if (!preview || !built) return;
        this.testing.set(true);
        this.state.schemaError.set(null);
        this.configApi.previewSchema({ raw: { fields: built.fields } }, preview.rows).subscribe({
            next: (p) => {
                this.testing.set(false);
                this.state.schemaPreview.set(p);
            },
            error: (e) => {
                this.testing.set(false);
                this.state.schemaPreview.set(null);
                this.state.schemaError.set(apiErrorMessage(e, 'The sample does not cast with these types.'));
            },
        });
    }

    save(): void {
        if (!this.lens.canAuthorWorkbench()) return;
        const built = this.buildFields();
        if (!built) return;
        const schemaDraft = {
            partitionKey: this.partitionKeyControl.value?.trim() || undefined,
            raw: { name: this.schemaName(), format: 'CSV', fields: built.fields },
            mapping: { canonicalName: this.pipelineName, rawName: this.pipelineName, rules: built.rules },
        };
        this.saving.set(true);
        this.configApi.write('schema', schemaDraft, { overwrite: true }).subscribe({
            next: () => {
                this.state.saveBlock({ processing: { schema_file: this.conventionPath() } }).subscribe({
                    next: () => {
                        this.saving.set(false);
                        this.fieldsForm.markAsPristine();
                        this.partitionKeyControl.markAsPristine();
                        this.toastr.success('Schema saved');
                    },
                    error: () => this.saving.set(false),
                });
            },
            error: (e) => {
                this.saving.set(false);
                this.toastr.error(apiErrorMessage(e, 'Could not save the schema.'));
            },
        });
    }
}

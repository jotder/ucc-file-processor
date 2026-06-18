import { Component, computed, inject, signal } from '@angular/core';
import { FormArray, FormBuilder, FormGroup, FormsModule, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { Observable } from 'rxjs';
import { ToastrService } from 'ngx-toastr';
import {
    apiErrorMessage,
    ComponentDef,
    ComponentsService,
    ComponentType,
    GrammarPreview,
    RelationsPreview,
    SinkPreview,
} from 'app/inspecto/api';
import { StatusBadgeComponent } from 'app/inspecto/components/status-badge.component';

/** Dialog data: `def` set ⇒ edit mode (id locked, Test available); absent ⇒ create. */
interface ComponentFormData {
    kind: ComponentType;
    def?: ComponentDef;
}

/** Close payload: the saved component, or a 503 signal so the caller can hide mutate actions. */
export interface ComponentFormResult {
    saved?: ComponentDef;
    writesDisabled?: boolean;
}

const TRANSFORM_SUBTYPES = [
    'transform.map', 'transform.select', 'transform.derive', 'transform.filter', 'transform.validate',
    'transform.dedup.marker', 'transform.dedup.fingerprint', 'transform.route', 'transform.split', 'transform.merge',
];
const SCHEMA_TYPES = ['string', 'integer', 'bigint', 'double', 'boolean', 'date', 'timestamp'];
const SINK_KINDS = ['sink.persistent', 'sink.materialized', 'sink.view'];
const SINK_FORMATS = ['parquet', 'csv', 'json', 'avro'];

/**
 * Create/edit a registry component (grammar / schema / transform / sink) — generalises the connection form
 * to the non-secret kinds (T19). Structured fields per kind; a transform's operator config and free-form
 * keys are authored as JSON. An inline **Test** panel runs the saved component over a sample through the
 * production dry-run endpoints (T18) on a throwaway DuckDB — no write. Submits to POST/PUT /components/{type}.
 */
@Component({
    selector: 'app-component-form-dialog',
    standalone: true,
    imports: [
        ReactiveFormsModule, FormsModule, MatDialogModule, MatButtonModule, MatFormFieldModule,
        MatIconModule, MatInputModule, MatSelectModule, MatSlideToggleModule, StatusBadgeComponent,
    ],
    templateUrl: './component-form.dialog.html',
})
export class ComponentFormDialog {
    private fb = inject(FormBuilder);
    private api = inject(ComponentsService);
    private toastr = inject(ToastrService);
    private ref = inject(MatDialogRef<ComponentFormDialog, ComponentFormResult>);
    readonly data = inject<ComponentFormData>(MAT_DIALOG_DATA);

    readonly kind = this.data.kind;
    readonly isEdit = !!this.data.def;
    readonly transformSubtypes = TRANSFORM_SUBTYPES;
    readonly schemaTypes = SCHEMA_TYPES;
    readonly sinkKinds = SINK_KINDS;
    readonly sinkFormats = SINK_FORMATS;

    saving = false;
    readonly testing = signal(false);
    readonly grammarResult = signal<GrammarPreview | null>(null);
    readonly relationsResult = signal<RelationsPreview | null>(null);
    readonly sinkResult = signal<SinkPreview | null>(null);
    readonly testError = signal<string | null>(null);

    /** Raw sample input for the Test panel: free text for grammar, a JSON rows array otherwise. */
    sampleText = this.kind === 'grammar' ? 'a,b,c\n1,2,3' : '';
    sampleRows = '[{ "id": "1", "amt": "150" }, { "id": "x", "amt": "abc" }]';

    readonly title = computed(() => `${this.isEdit ? 'Edit' : 'New'} ${this.kind}`);

    form: FormGroup = this.fb.group({
        id: [
            { value: '', disabled: this.isEdit },
            [Validators.required, Validators.pattern(/^[A-Za-z0-9][A-Za-z0-9._-]*$/)],
        ],
        // grammar
        delimiter: [','],
        hasHeader: [false],
        skipHeaderLines: [0],
        quote: [''],
        escape: [''],
        encoding: [''],
        // schema
        fields: this.fb.array([] as FormGroup[]),
        // transform
        subtype: [TRANSFORM_SUBTYPES[0]],
        config: ['{\n  "where": "CAST(amt AS INT) >= 100"\n}'],
        // sink
        sinkKind: [SINK_KINDS[0]],
        store: [''],
        format: ['parquet'],
        partitions: [''],
    });

    constructor() {
        const c = this.data.def?.content ?? {};
        if (this.data.def) this.form.patchValue({ id: this.data.def.name });

        if (this.kind === 'grammar') {
            this.form.patchValue({
                delimiter: str(c['delimiter'], ','),
                hasHeader: c['has_header'] === true || c['has_header'] === 'true',
                skipHeaderLines: num(c['skip_header_lines'], 0),
                quote: str(c['quote'], ''),
                escape: str(c['escape'], ''),
                encoding: str(c['encoding'], ''),
            });
        } else if (this.kind === 'schema') {
            for (const f of schemaFieldsOf(c)) {
                this.fields.push(this.fb.group({
                    name: [str(f['name'], ''), Validators.required],
                    type: [str(f['type'], 'string')],
                    format: [str(f['format'], '')],
                }));
            }
            if (this.fields.length === 0) this.addField();
        } else if (this.kind === 'transform') {
            const { type, ...rest } = c as { type?: string };
            this.form.patchValue({
                subtype: typeof type === 'string' ? type : TRANSFORM_SUBTYPES[0],
                config: Object.keys(rest).length ? JSON.stringify(rest, null, 2) : this.form.get('config')!.value,
            });
        } else if (this.kind === 'sink') {
            this.form.patchValue({
                sinkKind: str(c['type'], SINK_KINDS[0]),
                store: str(c['store'], ''),
                format: str(c['format'], 'parquet'),
                partitions: partitionsOf(c).join(', '),
            });
        }
    }

    get fields(): FormArray {
        return this.form.get('fields') as FormArray;
    }

    addField(): void {
        this.fields.push(this.fb.group({ name: ['', Validators.required], type: ['string'], format: [''] }));
    }

    removeField(i: number): void {
        this.fields.removeAt(i);
    }

    /** Assemble the kind-specific content map (without the routing `id`, which create() adds). */
    private buildContent(): Record<string, unknown> | null {
        const v = this.form.getRawValue();
        switch (this.kind) {
            case 'grammar': {
                const out: Record<string, unknown> = { delimiter: v.delimiter || ',', has_header: !!v.hasHeader };
                if (Number(v.skipHeaderLines) > 0) out['skip_header_lines'] = Number(v.skipHeaderLines);
                if (v.quote) out['quote'] = v.quote;
                if (v.escape) out['escape'] = v.escape;
                if (v.encoding) out['encoding'] = v.encoding;
                return out;
            }
            case 'schema': {
                const fields = (v.fields as { name: string; type: string; format: string }[])
                    .filter((f) => f.name?.trim())
                    .map((f) => {
                        const field: Record<string, unknown> = { name: f.name.trim(), type: f.type };
                        if (f.format?.trim()) field['format'] = f.format.trim();
                        return field;
                    });
                return { fields };
            }
            case 'transform': {
                let config: Record<string, unknown> = {};
                if (v.config?.trim()) {
                    try {
                        config = JSON.parse(v.config);
                    } catch {
                        this.toastr.error('Config is not valid JSON.');
                        return null;
                    }
                }
                return { type: v.subtype, ...config };
            }
            case 'sink': {
                const out: Record<string, unknown> = { type: v.sinkKind };
                if (v.store?.trim()) out['store'] = v.store.trim();
                if (v.format) out['format'] = v.format;
                const parts = (v.partitions as string).split(',').map((p) => p.trim()).filter(Boolean);
                if (parts.length) out['partitions'] = parts;
                return out;
            }
        }
    }

    submit(): void {
        if (this.form.invalid) {
            this.form.markAllAsTouched();
            return;
        }
        const content = this.buildContent();
        if (!content) return;
        const id = this.form.getRawValue().id as string;
        this.saving = true;
        const req$ = this.isEdit ? this.api.update(this.kind, id, content) : this.api.create(this.kind, { id, ...content });
        req$.subscribe({
            next: (saved) => {
                this.saving = false;
                this.toastr.success(`${this.kind} "${id}" ${this.isEdit ? 'updated' : 'created'}`);
                this.ref.close({ saved });
            },
            error: (e) => {
                this.saving = false;
                const msg =
                    e?.status === 503 ? 'Writes are disabled (no write root configured).'
                    : e?.status === 409 ? `A ${this.kind} "${id}" already exists.`
                    : apiErrorMessage(e, `Could not save "${id}".`);
                this.toastr.error(msg);
                if (e?.status === 503) this.ref.close({ writesDisabled: true });
            },
        });
    }

    /** Run the saved component over the sample through the dry-run endpoints (edit mode only). */
    runTest(): void {
        const id = this.data.def?.name;
        if (!id) return;
        this.testing.set(true);
        this.testError.set(null);
        this.grammarResult.set(null);
        this.relationsResult.set(null);
        this.sinkResult.set(null);

        if (this.kind === 'grammar') {
            this.api.testGrammar(id, this.sampleText).subscribe({
                next: (r) => { this.testing.set(false); this.grammarResult.set(r); },
                error: (e) => this.failTest(e),
            });
            return;
        }
        let rows: Record<string, unknown>[];
        try {
            rows = JSON.parse(this.sampleRows);
            if (!Array.isArray(rows)) throw new Error('expected an array of rows');
        } catch (e) {
            this.testing.set(false);
            this.testError.set('Sample rows must be a JSON array of objects.');
            return;
        }
        const obs: Observable<RelationsPreview | SinkPreview> =
            this.kind === 'schema' ? this.api.testSchema(id, rows)
            : this.kind === 'transform' ? this.api.testTransform(id, rows)
            : this.api.testSink(id, rows);
        obs.subscribe({
            next: (r) => {
                this.testing.set(false);
                if (this.kind === 'sink') this.sinkResult.set(r as SinkPreview);
                else this.relationsResult.set(r as RelationsPreview);
            },
            error: (e) => this.failTest(e),
        });
    }

    private failTest(e: unknown): void {
        this.testing.set(false);
        this.testError.set(apiErrorMessage(e, 'Test failed.'));
    }

    /** Column names across a preview's sample rows (for a small results table header). */
    columnsOf(rows: Record<string, unknown>[]): string[] {
        const cols = new Set<string>();
        for (const r of rows) for (const k of Object.keys(r)) cols.add(k);
        return [...cols];
    }
}

// ── content readers (defensive against the loosely-typed .toon content map) ────────────────────────────

function str(v: unknown, dflt: string): string {
    return v == null ? dflt : String(v);
}
function num(v: unknown, dflt: number): number {
    const n = Number(v);
    return Number.isFinite(n) ? n : dflt;
}
function schemaFieldsOf(c: Record<string, unknown>): Record<string, unknown>[] {
    const raw = c['raw'] as { fields?: unknown } | undefined;
    const src = (raw?.fields ?? c['fields'] ?? c['columns']) as unknown;
    return Array.isArray(src) ? (src.filter((x) => x && typeof x === 'object') as Record<string, unknown>[]) : [];
}
function partitionsOf(c: Record<string, unknown>): string[] {
    const src = c['partitions'];
    if (!Array.isArray(src)) return [];
    return src.map((p) => (p && typeof p === 'object' ? String((p as { column?: unknown }).column ?? '') : String(p))).filter(Boolean);
}

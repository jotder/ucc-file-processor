import { Component, DestroyRef, inject, OnInit, signal, ViewChild, ViewEncapsulation } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTabsModule } from '@angular/material/tabs';
import { Subscription } from 'rxjs';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage, ConfigService, ConfigSpec, ConfigType, FieldSpec, ValidateResult } from 'app/inspecto/api';
import { AttributeSpec, AttributeType } from 'app/inspecto/component-model';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { InspectoSchemaFormComponent } from 'app/inspecto/components/schema-form.component';
import { InspectoSkeletonComponent } from 'app/inspecto/components/skeleton.component';
import { StatusBadgeComponent } from 'app/inspecto/components/status-badge.component';

const CONFIG_TYPES: ConfigType[] = ['pipeline', 'enrichment', 'job', 'schema', 'meta'];

/** Stable schema-form control key for the nth spec field. Dotted `FieldSpec.path`s can't be control
 *  names (schema-form's `form.get(key)` splits on '.'), so we key by index and carry the path in the
 *  label, then reassemble the nested config from the field list on validate. */
const fieldKey = (index: number): string => `f${index}`;

/** Map a backend {@link FieldSpec} list to the shared {@link AttributeSpec} renderer's inputs.
 *  Required fields land in the always-visible tier, the rest in the collapsed "optional" tier; LIST
 *  fields keep the pane's comma-separated-text editing (schema-form has no chips type). The label is
 *  the field's dotted path (the .toon key being authored — the human `label` rides in the help). */
export function toAttrSpecs(fields: FieldSpec[]): AttributeSpec[] {
    return fields.map((f, i) => {
        const spec: AttributeSpec = {
            key: fieldKey(i),
            label: f.path,
            type: attrType(f),
            tier: f.required ? 'required' : 'optional',
            required: f.required,
            help: f.label && f.description ? `${f.label} — ${f.description}` : f.label || f.description,
        };
        if (f.enumValues && f.enumValues.length) spec.options = f.enumValues.map((o) => ({ value: o, label: o }));
        if (f.pattern) spec.pattern = f.pattern;
        if (f.type === 'LIST') spec.placeholder = 'comma-separated values…';
        if (f.defaultValue !== undefined && f.defaultValue !== null) {
            spec.default = f.type === 'LIST' && Array.isArray(f.defaultValue) ? f.defaultValue.join(', ') : f.defaultValue;
        }
        return spec;
    });
}

function attrType(f: FieldSpec): AttributeType {
    switch (f.type) {
        case 'ENUM': return 'select';
        case 'INT':
        case 'LONG': return 'number';
        case 'BOOL': return 'boolean';
        default: return 'string'; // STRING, FILEPATH, CRON, SQL, MAP, LIST (comma text)
    }
}

/** Build the nested config object from the flat schema-form value (keyed by {@link fieldKey}),
 *  splitting LIST fields back to string[] and dropping empties — the inverse of {@link toAttrSpecs}. */
export function assembleConfig(fields: FieldSpec[], values: Record<string, unknown>): Record<string, unknown> {
    const out: Record<string, unknown> = {};
    fields.forEach((f, i) => {
        let value = values[fieldKey(i)];
        if (f.type === 'LIST') {
            const arr = typeof value === 'string' ? value.split(',').map((s) => s.trim()).filter(Boolean) : [];
            value = arr.length ? arr : undefined;
        }
        if (value === undefined || value === null || value === '') return;
        const parts = f.path.split('.');
        let cur = out;
        for (let p = 0; p < parts.length - 1; p++) {
            cur[parts[p]] = cur[parts[p]] || {};
            cur = cur[parts[p]] as Record<string, unknown>;
        }
        cur[parts[parts.length - 1]] = value;
    });
    return out;
}

/**
 * Config authoring — spec-driven (ported from inspector-ui onto the gamma shell). Picking a type
 * loads its field/rule spec (GET /config/spec/{type}) and renders the shared `<inspecto-schema-form>`;
 * "Validate draft" posts the assembled config to POST /validate and shows the findings. A second mode
 * validates a saved .toon file by path. ARRAY fields are edited as comma-separated text.
 */
@Component({
    selector: 'app-config',
    standalone: true,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatCheckboxModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatSelectModule,
        MatTabsModule,
        InspectoEmptyStateComponent,
        InspectoSchemaFormComponent,
        InspectoSkeletonComponent,
        StatusBadgeComponent,
    ],
    templateUrl: './config.component.html',
    encapsulation: ViewEncapsulation.None,
})
export class ConfigComponent implements OnInit {
    private api = inject(ConfigService);
    private toastr = inject(ToastrService);
    private destroyRef = inject(DestroyRef);

    modeIndex = 0;
    get mode(): 'draft' | 'file' {
        return this.modeIndex === 0 ? 'draft' : 'file';
    }

    readonly types = CONFIG_TYPES;
    readonly typeCtrl = new FormControl<ConfigType>('pipeline', { nonNullable: true });
    readonly safetyCtrl = new FormControl(false, { nonNullable: true });
    readonly configPathCtrl = new FormControl('', { nonNullable: true });

    spec: ConfigSpec | null = null;
    specLoading = false;
    attrSpecs: AttributeSpec[] = [];

    /** Live JSON preview of the assembled config, refreshed from the schema-form's value changes.
     *  A signal: the ViewChild setter's initial refresh runs mid-CD (NG0100 with a plain property). */
    readonly assembledPreview = signal('{}');

    private form?: InspectoSchemaFormComponent;
    private previewSub?: Subscription;

    /** The schema-form remounts each time a spec loads; (re)wire the live preview to its value stream. */
    @ViewChild(InspectoSchemaFormComponent) set schemaForm(c: InspectoSchemaFormComponent | undefined) {
        this.form = c;
        this.previewSub?.unsubscribe();
        if (!c) return;
        const refresh = (): void => { this.assembledPreview.set(this.previewJson(c.value())); };
        refresh();
        this.previewSub = c.form.valueChanges.subscribe(refresh);
    }

    result: ValidateResult | null = null;
    validating = false;

    constructor() {
        this.destroyRef.onDestroy(() => this.previewSub?.unsubscribe());
    }

    ngOnInit(): void {
        this.loadSpec();
        this.typeCtrl.valueChanges
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe(() => this.loadSpec());
    }

    loadSpec(): void {
        this.result = null;
        this.spec = null;
        this.attrSpecs = [];
        this.assembledPreview.set('{}');
        this.specLoading = true;
        this.api.spec(this.typeCtrl.value).subscribe({
            next: (s) => {
                this.spec = s;
                this.attrSpecs = toAttrSpecs(s.fields);
                this.specLoading = false;
            },
            error: (e) => {
                this.spec = null;
                this.specLoading = false;
                this.toastr.warning(apiErrorMessage(e, `Could not load the "${this.typeCtrl.value}" spec.`));
            },
        });
    }

    private previewJson(values: Record<string, unknown>): string {
        try {
            return JSON.stringify(assembleConfig(this.spec?.fields ?? [], values), null, 2);
        } catch {
            return '{}';
        }
    }

    validateDraft(): void {
        const config = assembleConfig(this.spec?.fields ?? [], this.form?.value() ?? {});
        this.validating = true;
        this.result = null;
        this.api.validateDraft(this.typeCtrl.value, config, this.safetyCtrl.value).subscribe({
            next: (r) => {
                this.result = r;
                this.validating = false;
            },
            error: (e) => {
                this.validating = false;
                this.toastr.error(apiErrorMessage(e, 'Validation failed.'));
            },
        });
    }

    validateFile(): void {
        const path = this.configPathCtrl.value.trim();
        if (!path) {
            this.toastr.warning('Enter a config path');
            return;
        }
        this.validating = true;
        this.result = null;
        this.api.validateFile(path).subscribe({
            next: (r) => {
                this.result = r;
                this.validating = false;
            },
            error: (e) => {
                this.validating = false;
                this.toastr.error(apiErrorMessage(e, 'Validation failed.'));
            },
        });
    }

    copyPreview(): void {
        navigator.clipboard?.writeText(this.assembledPreview()).then(
            () => this.toastr.success('Config copied to clipboard'),
            () => this.toastr.warning('Clipboard unavailable'),
        );
    }
}

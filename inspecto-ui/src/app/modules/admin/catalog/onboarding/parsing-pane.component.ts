import { ChangeDetectionStrategy, Component, OnDestroy, ViewChild, computed, inject, signal } from '@angular/core';
import { FormArray, FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ToastrService } from 'ngx-toastr';
import { ConfigService, LensService, apiErrorMessage } from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoSchemaFormComponent } from 'app/inspecto/components/schema-form.component';
import { QueryPanelComponent } from 'app/inspecto/query/query-panel.component';
import { QuerySource } from 'app/inspecto/query/query-types';
import { PARSING_FRONTENDS, ParsingFrontend, parsingAttributesFor } from './parsing-attributes';
import { clearMissingRoots, flattenBlock, mergeBlock, nestKeys } from './onboarding-config-utils';
import { OnboardingStateService } from './onboarding-state.service';

/** The parsing-block roots this pane owns — switching frontend clears the others' sub-blocks. */
const PARSING_ROOTS = ['frontend', 'delimited', 'fixedwidth', 'json', 'text_regex', 'encoding', 'compression'];

/**
 * Parsing stage — authors the Stage-1 `parsing:` block over the four engine-real frontends and
 * chains the captured sample through `POST /config/preview/parsing`, so the builder sees THEIR
 * data parsed by the same DuckDB idioms the engine runs (D4 sample-as-thread, the raw→parsed
 * hop). A `plugin` frontend (Java ingester + segment schemas) is TOON-managed: shown, never
 * edited here.
 */
@Component({
    selector: 'app-onboarding-parsing-pane',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatButtonToggleModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatProgressSpinnerModule,
        MatTooltipModule,
        InspectoAlertComponent,
        InspectoSchemaFormComponent,
        QueryPanelComponent,
    ],
    templateUrl: './parsing-pane.component.html',
})
export class OnboardingParsingPaneComponent implements OnDestroy {
    protected readonly state = inject(OnboardingStateService);
    protected readonly lens = inject(LensService);
    private configApi = inject(ConfigService);
    private fb = inject(FormBuilder);
    private toastr = inject(ToastrService);

    @ViewChild('sf') schemaForm?: InspectoSchemaFormComponent;

    readonly frontends = PARSING_FRONTENDS;

    private readonly parsingBlock =
        ((this.state.config() ?? {})['parsing'] as Record<string, unknown> | undefined) ?? {};

    /** A plugin ingester (parsing.plugin / processing.ingester) is authored in the TOON, not here. */
    readonly pluginManaged: boolean =
        !!this.parsingBlock['plugin'] ||
        String(this.parsingBlock['frontend'] ?? '') === 'plugin' ||
        !!((this.state.config() ?? {})['processing'] as Record<string, unknown> | undefined)?.['ingester'];

    readonly frontend = signal<ParsingFrontend>(normalizeFrontend(this.parsingBlock['frontend']));
    readonly specs = computed(() => parsingAttributesFor(this.frontend()));
    readonly initial = flattenBlock(this.parsingBlock);
    /** Frontend switched since load — dirty even before a field is touched. */
    private readonly frontendTouched = signal(false);

    /** Bespoke fixed-width fields editor: name / start / length rows (`fixedwidth.fields[]`). */
    readonly fwForm: FormGroup = this.fb.group({ fields: this.fb.array<FormGroup>([]) });
    get fwFields(): FormArray<FormGroup> {
        return this.fwForm.controls['fields'] as FormArray<FormGroup>;
    }

    readonly saving = signal(false);
    readonly testing = signal(false);
    readonly parsedSource = computed<QuerySource>(() => ({
        name: 'parsed',
        rows: this.state.parsePreview()?.rows ?? [],
    }));

    private readonly dirtyCheck = (): boolean =>
        (this.schemaForm?.isDirty() ?? false) || this.fwForm.dirty || this.frontendTouched();

    constructor() {
        const fields = (this.parsingBlock['fixedwidth'] as Record<string, unknown> | undefined)?.['fields'];
        if (Array.isArray(fields)) {
            for (const f of fields as Record<string, unknown>[]) {
                this.addField(String(f['name'] ?? ''), Number(f['start'] ?? 0), Number(f['length'] ?? 1));
            }
        }
        this.state.registerDirtyCheck(this.dirtyCheck);
    }

    ngOnDestroy(): void {
        this.state.unregisterDirtyCheck(this.dirtyCheck);
    }

    setFrontend(f: ParsingFrontend): void {
        if (f === this.frontend()) return;
        this.frontend.set(f);
        this.frontendTouched.set(true);
        if (f === 'fixedwidth' && this.fwFields.length === 0) this.addField();
    }

    addField(name = '', start = 0, length = 1): void {
        this.fwFields.push(
            this.fb.group({
                name: [name, Validators.required],
                start: [start, [Validators.required, Validators.min(0)]],
                length: [length, [Validators.required, Validators.min(1)]],
            }),
        );
    }

    removeField(i: number): void {
        this.fwFields.removeAt(i);
        this.fwForm.markAsDirty();
    }

    /** The `parsing:` block as currently edited (frontend + its keys; other frontends cleared). */
    private buildParsingBlock(): Record<string, unknown> | null {
        if (!this.schemaForm?.validate()) return null;
        const nested = nestKeys(this.schemaForm.value());
        nested['frontend'] = this.frontend();
        if (this.frontend() === 'fixedwidth') {
            if (this.fwForm.invalid || this.fwFields.length === 0) {
                this.fwForm.markAllAsTouched();
                this.toastr.warning('Fixed width needs at least one field (name, start, length).');
                return null;
            }
            const fw = (nested['fixedwidth'] ??= {}) as Record<string, unknown>;
            fw['fields'] = this.fwFields.controls.map((g) => ({
                name: String(g.value['name'] ?? '').trim(),
                start: Number(g.value['start'] ?? 0),
                length: Number(g.value['length'] ?? 1),
            }));
        }
        return clearMissingRoots(nested, PARSING_ROOTS);
    }

    testParse(): void {
        const sample = this.state.sample();
        if (!sample) return;
        const parsing = this.buildParsingBlock();
        if (!parsing) return;
        const draft = mergeBlock(this.state.config() ?? {}, { parsing });
        this.testing.set(true);
        this.state.parseError.set(null);
        this.configApi.previewParsing(draft, sample.text).subscribe({
            next: (p) => {
                this.testing.set(false);
                this.state.parsePreview.set(p);
            },
            error: (e) => {
                this.testing.set(false);
                this.state.parsePreview.set(null);
                this.state.parseError.set(apiErrorMessage(e, 'The sample does not parse with these settings.'));
            },
        });
    }

    save(): void {
        if (!this.lens.canAuthorWorkbench()) return;
        const parsing = this.buildParsingBlock();
        if (!parsing) return;
        this.saving.set(true);
        this.state.saveBlock({ parsing }).subscribe({
            next: () => {
                this.saving.set(false);
                this.schemaForm?.form.markAsPristine();
                this.fwForm.markAsPristine();
                this.frontendTouched.set(false);
                this.toastr.success('Parsing saved');
            },
            error: () => this.saving.set(false),
        });
    }
}

function normalizeFrontend(raw: unknown): ParsingFrontend {
    const f = String(raw ?? 'delimited').trim().toLowerCase();
    if (f === 'fixed_width' || f === 'fixedwidth') return 'fixedwidth';
    if (f === 'json') return 'json';
    if (f === 'text_regex') return 'text_regex';
    return 'delimited';
}

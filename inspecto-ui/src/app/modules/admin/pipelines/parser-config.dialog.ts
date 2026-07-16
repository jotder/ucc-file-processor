import { NgClass } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, signal, ViewChild } from '@angular/core';
import { AbstractControl, FormBuilder, ReactiveFormsModule, ValidationErrors, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ToastrService } from 'ngx-toastr';
import {
    apiErrorMessage,
    Asn1Module,
    AuthoredNode,
    ComponentDef,
    ComponentsService,
    ParserPreview,
} from 'app/inspecto/api';
import { QueryPanelComponent, QuerySource } from 'app/inspecto/query';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoSchemaFormComponent } from 'app/inspecto/components/schema-form.component';
import { NodeConfigResult } from './node-config.dialog';
import { ParserTreeComponent } from './parser-tree.component';
import { modulePropFor, PARSER_TYPES, parserTypeDef, propsFor, sampleFor, toAttributeSpecs } from './parser-types';

/** Dialog data: the parser node to configure + its (resolved) type/category labels for the header. */
export interface ParserConfigData {
    node: AuthoredNode;
    typeLabel: string;
    categoryLabel: string;
}

/**
 * Parser configuration — the rich, multi-pane editor for a PARSE node (replaces the generic node-config popup
 * for parsers). Pick a file format (DSV / ASN.1 / JSON / …), fill its typed property sheet, paste sample
 * content, and Test to preview the parse: a searchable/sortable/filterable ag-Grid table for tabular formats,
 * or a collapsible tree ({@link ParserTreeComponent}) for hierarchical ones. The config persists as a reusable
 * `grammar` component (content `{ parser_type, ...props }`); on save the node is bound to it via `use`.
 *
 * The property sheet is **schema-driven** (`<inspecto-schema-form>` over `toAttributeSpecs`, review R2/R4):
 * each type's required fields sit up front, common tweaks are one click away under "Optional settings", and
 * rarely-touched tuning knobs (the shared Sampling controls, etc.) hide behind the advanced gear. The one
 * bespoke exception is the ASN.1 schema-module picker (network-backed dropdown + upload) — rendered directly
 * by this dialog via {@link modulePropFor}, not through the generic renderer.
 *
 * **Ask the minimum** (binding form rule): a fresh parser's config comes first; the grammar **name is asked
 * only at save time** (a save step, pre-filled `<type>_grammar`, unique) — mirrors
 * `app/inspecto/connections/connection-form.dialog`. Editing an existing grammar (chosen from the Grammar dropdown, or
 * pre-bound via the node's `use`) saves straight through with its id locked.
 */
@Component({
    selector: 'app-parser-config-dialog',
    standalone: true,
    imports: [
        NgClass,
        ReactiveFormsModule,
        MatDialogModule,
        MatButtonModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatProgressSpinnerModule,
        MatSelectModule,
        MatTooltipModule,
        QueryPanelComponent,
        InspectoAlertComponent,
        InspectoSchemaFormComponent,
        ParserTreeComponent,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    templateUrl: './parser-config.dialog.html',
})
export class ParserConfigDialog {
    private fb = inject(FormBuilder);
    private components = inject(ComponentsService);
    private toastr = inject(ToastrService);
    private ref = inject(MatDialogRef<ParserConfigDialog, NodeConfigResult>);
    readonly data = inject<ParserConfigData>(MAT_DIALOG_DATA);

    readonly parserTypes = PARSER_TYPES;

    /** The selected file format (drives the property sheet + table-vs-tree output). */
    readonly parserType = signal('dsv');
    readonly parserTypeLabel = computed(() => parserTypeDef(this.parserType()).label);
    readonly isHierarchical = computed(() => parserTypeDef(this.parserType()).hierarchical);

    /** The type's non-module properties, schema-form-ready (required/optional/advanced tiers). */
    readonly schemaFormSpecs = computed(() => toAttributeSpecs(this.parserType()));
    /** Saved values to seed the schema-form with; `undefined` ⇒ the type's plain defaults (fresh/new type). */
    readonly schemaFormInitial = signal<Record<string, unknown> | undefined>(undefined);
    @ViewChild(InspectoSchemaFormComponent) schemaForm!: InspectoSchemaFormComponent;

    /** The type's `module` property (the ASN.1 schema picker), if any — the one bespoke property. */
    readonly moduleProp = computed(() => modulePropFor(this.parserType()));
    /** The built content snapshot from the config step — captured before the save step unmounts the
     *  schema-form (its `<form>` is destroyed with the `@if` block, so it can't be read again there). */
    private pendingContent: Record<string, unknown> | null = null;
    /** NOTE: assumes at most one `module`-control property per type (true for all 9 formats today). */
    readonly moduleForm = this.fb.group({ schema_spec: ['', Validators.required] });

    /** Existing reusable grammars (the choose-or-create options). */
    readonly grammars = signal<ComponentDef[]>([]);
    /** The grammar being edited (dropdown-picked, or pre-bound via the node's `use`); `null` ⇒ authoring new. */
    readonly boundGrammarId = signal<string | null>(null);

    /** Raw sample content the user pastes; fed to the Test preview. */
    readonly sampleText = signal('');
    readonly preview = signal<ParserPreview | null>(null);
    readonly gridRows = signal<Record<string, unknown>[]>([]);
    /** Parsed table rows as a query-panel source (stable ref unless the rows change). */
    readonly parsedSource = computed<QuerySource>(() => ({ name: 'parsed', rows: this.gridRows() }));

    readonly testing = signal(false);
    readonly testError = signal<string | null>(null);
    readonly saving = signal(false);

    /** ASN.1 schema-module library (the `module` control's dropdown) + the downloaded source being viewed. */
    readonly asn1Modules = signal<Asn1Module[]>([]);
    readonly moduleText = signal<string | null>(null);
    readonly moduleLoading = signal(false);
    readonly moduleError = signal<string | null>(null);

    // ── View state: full-screen dialog · per-pane maximize ──
    /** Expand the whole dialog to fill the viewport. */
    readonly fullscreen = signal(false);
    /** Which single pane fills the body (`null` = the normal split + full-width record viewer). */
    readonly maximized = signal<'props' | 'sample' | 'module' | 'output' | null>(null);

    /** A pane gets the tall layout when it is maximized or the whole dialog is full-screen. */
    readonly bigProps = computed(() => this.fullscreen() || this.maximized() === 'props');
    readonly bigSample = computed(() => this.fullscreen() || this.maximized() === 'sample');
    readonly bigModule = computed(() => this.fullscreen() || this.maximized() === 'module');
    readonly bigOutput = computed(() => this.fullscreen() || this.maximized() === 'output');

    /** Create flow: `config` (pick/author + properties + test) → `save` (name, asked only now). Editing an
     *  existing grammar stays on `config` and saves straight through. */
    readonly step = signal<'config' | 'save'>('config');
    /** Save-step field (create only): the grammar name IS the unique id pipeline nodes reference via `use`. */
    readonly saveForm = this.fb.group({
        name: [
            '',
            [
                Validators.required,
                Validators.pattern(/^[A-Za-z0-9][A-Za-z0-9._-]*$/),
                (c: AbstractControl): ValidationErrors | null =>
                    this.grammars().some((g) => g.name === String(c.value ?? '').trim()) ? { duplicate: true } : null,
            ],
        ],
    });

    constructor() {
        this.sampleText.set(sampleFor('dsv'));
        this.components.list('grammar').subscribe({
            next: (list) => {
                this.grammars.set(list);
                const ref = this.data.node.use ?? '';
                const boundId = ref.startsWith('grammar/') ? ref.slice('grammar/'.length) : null;
                const bound = boundId ? list.find((g) => g.name === boundId) : null;
                if (bound) this.loadGrammar(bound);
            },
            error: () => this.grammars.set([]),
        });
    }

    /** Switch file format → reset the property sheet to that type's defaults + reseed the sample. */
    onTypeChange(type: string): void {
        this.parserType.set(type);
        this.schemaFormInitial.set(undefined);
        this.moduleForm.reset({ schema_spec: '' });
        this.sampleText.set(sampleFor(type));
        this.preview.set(null);
        this.testError.set(null);
        this.maximized.set(null); // the pane set may have changed (e.g. the ASN.1 grammar pane)
        this.maybeLoadModules(type);
    }

    /** Choose an existing grammar to edit, or `''` to author a new one (of the current type). */
    onGrammarChange(id: string): void {
        if (!id) {
            this.boundGrammarId.set(null);
            this.saveForm.reset({ name: '' });
            this.onTypeChange(this.parserType());
            return;
        }
        const def = this.grammars().find((g) => g.name === id);
        if (def) this.loadGrammar(def);
    }

    /** Load a saved grammar's content into the form (type + props), binding the id and locking the save step. */
    private loadGrammar(def: ComponentDef): void {
        const content = def.content ?? {};
        const type = typeof content['parser_type'] === 'string' ? (content['parser_type'] as string) : 'dsv';
        this.parserType.set(type);
        this.schemaFormInitial.set(content);
        this.moduleForm.patchValue({
            schema_spec: typeof content['schema_spec'] === 'string' ? (content['schema_spec'] as string) : '',
        });
        this.sampleText.set(sampleFor(type));
        this.boundGrammarId.set(def.name);
        this.saveForm.patchValue({ name: def.name });
        this.preview.set(null);
        this.step.set('config');
        this.maybeLoadModules(type);
        if (type === 'asn1' && typeof content['schema_spec'] === 'string' && content['schema_spec']) {
            this.viewModule(content['schema_spec'] as string);
        }
    }

    // ── ASN.1 schema-module picker (download to view, or upload from local) ──

    /** Load the module library when (and only when) the format is ASN.1; clears the viewer otherwise. */
    private maybeLoadModules(type: string): void {
        this.moduleText.set(null);
        this.moduleError.set(null);
        if (type !== 'asn1') return;
        this.components.asn1Modules().subscribe({
            next: (list) => this.asn1Modules.set(list),
            error: () => this.asn1Modules.set([]),
        });
    }

    /** Pick a module from the library → bind it as `schema_spec` and download its source for the viewer. */
    onModuleSelect(name: string): void {
        this.viewModule(name);
    }

    /** Download + show a module's source text (read-only). */
    private viewModule(name: string): void {
        if (!name) {
            this.moduleText.set(null);
            return;
        }
        this.moduleLoading.set(true);
        this.moduleError.set(null);
        this.components.asn1Module(name).subscribe({
            next: (m) => {
                this.moduleLoading.set(false);
                this.moduleText.set(m?.text ?? '');
            },
            error: (e) => {
                this.moduleLoading.set(false);
                this.moduleError.set(apiErrorMessage(e, 'Could not load module'));
            },
        });
    }

    /** Upload a local .asn/.asn1 file → register it in the library, bind + view it. */
    onModuleUpload(files: FileList | null): void {
        const file = files?.[0];
        if (!file) return;
        this.moduleLoading.set(true);
        this.moduleError.set(null);
        file.text().then(
            (text) =>
                this.components.uploadAsn1Module(file.name, text).subscribe({
                    next: () => {
                        this.moduleLoading.set(false);
                        this.moduleText.set(text);
                        this.moduleForm.get('schema_spec')?.setValue(file.name);
                        this.components.asn1Modules().subscribe((list) => this.asn1Modules.set(list));
                    },
                    error: (e) => {
                        this.moduleLoading.set(false);
                        this.moduleError.set(apiErrorMessage(e, 'Upload failed'));
                    },
                }),
            () => {
                this.moduleLoading.set(false);
                this.moduleError.set('Could not read the file.');
            },
        );
    }

    // ── Layout: full-screen + per-pane maximize ──

    /** A pane shows in the split layout, or is the single pane currently maximized. */
    paneVisible(pane: 'props' | 'sample' | 'module' | 'output'): boolean {
        return this.maximized() === null || this.maximized() === pane;
    }

    /** Maximize a pane to fill the body, or restore the split layout. */
    toggleMaximize(pane: 'props' | 'sample' | 'module' | 'output'): void {
        this.maximized.update((cur) => (cur === pane ? null : pane));
    }

    /** Expand the dialog to the full viewport (adds a panel class that overrides the open-time maxWidth). */
    toggleFullscreen(): void {
        const on = !this.fullscreen();
        this.fullscreen.set(on);
        if (on) this.ref.addPanelClass('dialog-fullscreen');
        else this.ref.removePanelClass('dialog-fullscreen');
    }

    /** Assemble the grammar content map: schema-form values + `parser_type` + the bespoke module field. */
    private buildContent(): Record<string, unknown> {
        const values = this.schemaForm.value();
        const out: Record<string, unknown> = { parser_type: this.parserType() };
        for (const p of propsFor(this.parserType())) {
            if (p.control === 'module') {
                out[p.key] = this.moduleForm.get('schema_spec')?.value ?? '';
                continue;
            }
            let v = values[p.key];
            if (p.control === 'number') v = v === '' || v == null ? null : Number(v);
            out[p.key] = v;
        }
        return out;
    }

    /** The suggested grammar name for a freshly authored parser: `<type>_grammar`, sanitized. */
    suggestedName(): string {
        return `${this.parserType()}_grammar`.replace(/[^A-Za-z0-9._-]+/g, '_');
    }

    /** Parse the sample with the in-progress config (no save) → table or tree preview. */
    test(): void {
        this.testing.set(true);
        this.testError.set(null);
        this.preview.set(null);
        this.components.previewParse(this.parserType(), this.buildContent(), this.sampleText()).subscribe({
            next: (p) => {
                this.testing.set(false);
                this.preview.set(p);
                if (p.kind === 'table') {
                    this.gridRows.set(p.rows);
                }
            },
            error: (e) => {
                this.testing.set(false);
                this.testError.set(apiErrorMessage(e, 'Parse test failed'));
            },
        });
    }

    /** Create flow only: leave the save step back to the config step (name is kept). */
    backToConfig(): void {
        this.step.set('config');
    }

    /** Save the parser as a reusable grammar (create or update) and bind the node to it via `use`. */
    save(): void {
        if (this.step() === 'config') {
            const specsOk = this.schemaForm.validate();
            const moduleRequired = !!this.moduleProp();
            if (moduleRequired) this.moduleForm.markAllAsTouched();
            if (!specsOk || (moduleRequired && this.moduleForm.invalid)) return;

            const content = this.buildContent();
            const bound = this.boundGrammarId();
            if (bound) {
                this.persist(bound, content);
                return;
            }
            // Ask the minimum: a fresh parser's name is asked only now, at save time.
            this.pendingContent = content;
            if (this.saveForm.controls.name.pristine) {
                this.saveForm.patchValue({ name: this.suggestedName() });
            }
            this.step.set('save');
            return;
        }

        if (this.saveForm.invalid) {
            this.saveForm.markAllAsTouched();
            return;
        }
        this.persist(String(this.saveForm.getRawValue().name ?? '').trim(), this.pendingContent!);
    }

    private persist(name: string, content: Record<string, unknown>): void {
        this.saving.set(true);
        const req$ = this.boundGrammarId()
            ? this.components.update('grammar', name, content)
            : this.components.create('grammar', { id: name, ...content });
        req$.subscribe({
            next: () => {
                this.saving.set(false);
                this.toastr.success(`Parser "${name}" saved`);
                this.ref.close({ node: { ...this.data.node, use: `grammar/${name}` } });
            },
            error: (e) => {
                this.saving.set(false);
                this.toastr.error(
                    e?.status === 503
                        ? 'Writes are disabled (no write root configured).'
                        : apiErrorMessage(e, `Could not save "${name}"`),
                );
            },
        });
    }
}

import { NgClass } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
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
import { NodeConfigResult } from './node-config.dialog';
import { ParserTreeComponent } from './parser-tree.component';
import { PARSER_TYPES, ParserProp, parserTypeDef, propsFor, sampleFor, sectionsFor } from './parser-types';

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
 */
@Component({
    selector: 'app-parser-config-dialog',
    standalone: true,
    imports: [
        NgClass,
        ReactiveFormsModule,
        MatDialogModule,
        MatButtonModule,
        MatCheckboxModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatProgressSpinnerModule,
        MatSelectModule,
        MatTooltipModule,
        QueryPanelComponent,
        InspectoAlertComponent,
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
    /** The set of property fields for the current type (own props + the shared Sampling section). */
    readonly props = computed(() => propsFor(this.parserType()));
    readonly sections = computed(() => sectionsFor(this.parserType()));
    readonly isHierarchical = computed(() => parserTypeDef(this.parserType()).hierarchical);

    /** The dynamic, per-type property form (rebuilt whenever the type changes). */
    readonly propsForm = signal<FormGroup>(this.fb.group({}));
    /** Existing reusable grammars (the choose-or-create options). */
    readonly grammars = signal<ComponentDef[]>([]);

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

    // ── View state: full-screen dialog · per-pane maximize · column search ──
    /** Expand the whole dialog to fill the viewport. */
    readonly fullscreen = signal(false);
    /** Which single pane fills the body (`null` = the normal split + full-width record viewer). */
    readonly maximized = signal<'props' | 'sample' | 'module' | 'output' | null>(null);

    /** A pane gets the tall layout when it is maximized or the whole dialog is full-screen. */
    readonly bigProps = computed(() => this.fullscreen() || this.maximized() === 'props');
    readonly bigSample = computed(() => this.fullscreen() || this.maximized() === 'sample');
    readonly bigModule = computed(() => this.fullscreen() || this.maximized() === 'module');
    readonly bigOutput = computed(() => this.fullscreen() || this.maximized() === 'output');

    /** Top-level controls: which grammar is bound (`''` = create new) + the id it saves under. */
    readonly form = this.fb.group({
        grammarId: this.fb.control(''),
        name: this.fb.control('', [Validators.required, Validators.pattern(/^[A-Za-z0-9][A-Za-z0-9._-]*$/)]),
    });

    constructor() {
        this.rebuildProps('dsv');
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
        this.rebuildProps(type);
        this.sampleText.set(sampleFor(type));
        this.preview.set(null);
        this.testError.set(null);
        this.maximized.set(null); // the pane set may have changed (e.g. the ASN.1 grammar pane)
        this.maybeLoadModules(type);
    }

    /** Choose an existing grammar to edit, or `''` to author a new one. */
    onGrammarChange(id: string): void {
        if (!id) {
            this.form.patchValue({ grammarId: '', name: '' });
            this.form.get('name')!.enable();
            this.rebuildProps(this.parserType());
            this.preview.set(null);
            return;
        }
        const def = this.grammars().find((g) => g.name === id);
        if (def) this.loadGrammar(def);
    }

    /** Load a saved grammar's content into the form (type + props), locking the id. */
    private loadGrammar(def: ComponentDef): void {
        const content = def.content ?? {};
        const type = typeof content['parser_type'] === 'string' ? (content['parser_type'] as string) : 'dsv';
        this.parserType.set(type);
        this.rebuildProps(type, content);
        this.sampleText.set(sampleFor(type));
        this.form.patchValue({ grammarId: def.name, name: def.name });
        this.form.get('name')!.disable();
        this.preview.set(null);
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
        this.propsForm().get('schema_spec')?.setValue(name);
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
                        this.propsForm().get('schema_spec')?.setValue(file.name);
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

    /** (Re)build the property form for `type`, seeding from `values` (a saved grammar) or the type defaults. */
    private rebuildProps(type: string, values?: Record<string, unknown>): void {
        const group: Record<string, unknown[]> = {};
        for (const p of propsFor(type)) {
            const init = values && p.key in values ? values[p.key] : p.default;
            group[p.key] = [init];
        }
        this.propsForm.set(this.fb.group(group));
    }

    /** Fields belonging to one section, in declared order (drives the grouped form layout). */
    propsInSection(section: string): ParserProp[] {
        return this.props().filter((p) => p.section === section);
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

    /** Assemble the grammar content map from the form (`parser_type` + typed props; numbers coerced). */
    private buildContent(): Record<string, unknown> {
        const raw = this.propsForm().getRawValue() as Record<string, unknown>;
        const out: Record<string, unknown> = { parser_type: this.parserType() };
        for (const p of propsFor(this.parserType())) {
            let v = raw[p.key];
            if (p.control === 'number') v = v === '' || v == null ? null : Number(v);
            out[p.key] = v;
        }
        return out;
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

    /** Save the parser as a reusable grammar (create or update) and bind the node to it via `use`. */
    save(): void {
        const nameCtrl = this.form.get('name')!;
        const name = String(nameCtrl.value ?? '').trim();
        if (!name || nameCtrl.invalid) {
            this.form.markAllAsTouched();
            return;
        }
        const content = this.buildContent();
        const exists = this.grammars().some((g) => g.name === name);
        this.saving.set(true);
        const req$ = exists
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

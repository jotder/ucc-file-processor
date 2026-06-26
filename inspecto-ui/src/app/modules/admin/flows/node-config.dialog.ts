import { Component, inject, signal } from '@angular/core';
import { FormArray, FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialog, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSelectModule } from '@angular/material/select';
import {
    apiErrorMessage,
    AuthoredNode,
    ComponentDef,
    ComponentsService,
    ComponentTestResult,
    ComponentType,
    FlowsService,
} from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { ComponentFormDialog, ComponentFormResult } from 'app/modules/admin/components/component-form.dialog';

/**
 * Dialog data: the node to configure, its (already-resolved) type/category labels for the header, and the
 * registry kind this node binds (`grammar` for a parser, `transform`, `sink`) — drives the in-graph
 * choose-or-create component picker. `null` ⇒ no registry binding (free-text `use` only).
 */
export interface NodeConfigData {
    node: AuthoredNode;
    typeLabel: string;
    categoryLabel: string;
    bindKind?: ComponentType | null;
}

/** Dialog close payload: the edited node (absent ⇒ the user cancelled). */
export interface NodeConfigResult {
    node: AuthoredNode;
}

/**
 * Per-processor configuration popup (NiFi "Configure Processor"). Opened by double-clicking a node on the
 * flow-editor canvas (or the inspector's Configure button); edits a single {@link AuthoredNode}'s
 * name/description/component-ref + scalar config rows and returns the updated node. Identity (`id`/`type`)
 * is fixed — it's shown read-only in the header. The canvas owns layout; this only touches the logical node.
 */
@Component({
    selector: 'app-node-config-dialog',
    standalone: true,
    imports: [
        ReactiveFormsModule,
        MatDialogModule,
        MatButtonModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatProgressSpinnerModule,
        MatSelectModule,
        InspectoAlertComponent,
    ],
    template: `
        <h2 mat-dialog-title>Configure · {{ data.node.id }}</h2>
        <form [formGroup]="form" (ngSubmit)="save()">
            <mat-dialog-content class="space-y-1">
                <p class="mb-2 text-xs opacity-70">{{ data.typeLabel }} · {{ data.categoryLabel }}</p>

                <mat-form-field class="w-full" subscriptSizing="dynamic">
                    <mat-label>Name</mat-label>
                    <input matInput formControlName="name" [placeholder]="data.node.id" />
                </mat-form-field>
                <mat-form-field class="w-full" subscriptSizing="dynamic">
                    <mat-label>Description</mat-label>
                    <input matInput formControlName="description" />
                </mat-form-field>
                @if (data.bindKind) {
                    <div class="mb-1">
                        <div class="flex items-end gap-2">
                            <mat-form-field class="flex-1" subscriptSizing="dynamic">
                                <mat-label>{{ bindLabel }}</mat-label>
                                <mat-select [value]="selectedComponentId()"
                                            (selectionChange)="selectComponent($event.value)">
                                    <mat-option [value]="null">— none —</mat-option>
                                    @for (o of componentOptions(); track o.name) {
                                        <mat-option [value]="o.name">{{ o.name }}</mat-option>
                                    }
                                </mat-select>
                            </mat-form-field>
                            <button mat-stroked-button type="button" class="mb-1" (click)="createComponent()">
                                <mat-icon svgIcon="heroicons_outline:plus"></mat-icon>
                                <span class="ml-1">New {{ data.bindKind }}</span>
                            </button>
                        </div>
                        <p class="text-xs opacity-60">
                            Choose a reusable {{ data.bindKind }} or create one inline · bound as
                            <span class="font-mono">{{ form.value.use || '—' }}</span>
                        </p>
                    </div>
                } @else {
                    <mat-form-field class="w-full" subscriptSizing="dynamic">
                        <mat-label>Use (component ref)</mat-label>
                        <input matInput formControlName="use" placeholder="transform/my_component" />
                    </mat-form-field>
                }

                <div class="mb-1 mt-2 flex items-center justify-between">
                    <span class="text-xs font-semibold uppercase opacity-70">Config</span>
                    <button mat-stroked-button type="button" (click)="addConfigRow()">
                        <mat-icon svgIcon="heroicons_outline:plus"></mat-icon>
                        <span class="ml-1">Add</span>
                    </button>
                </div>
                <div formArrayName="config" class="space-y-2">
                    @for (row of configRows.controls; track $index) {
                        <div class="flex items-center gap-1" [formGroupName]="$index">
                            <mat-form-field subscriptSizing="dynamic" class="flex-1">
                                <mat-label>Key</mat-label>
                                <input matInput formControlName="key" />
                            </mat-form-field>
                            <mat-form-field subscriptSizing="dynamic" class="flex-1">
                                <mat-label>Value</mat-label>
                                <input matInput formControlName="value" />
                            </mat-form-field>
                            <button mat-icon-button type="button" (click)="removeConfigRow($index)"
                                    aria-label="Remove config entry">
                                <mat-icon svgIcon="heroicons_outline:x-mark"></mat-icon>
                            </button>
                        </div>
                    }
                    @if (!configRows.length) {
                        <p class="text-sm opacity-60">No config — add a key/value entry above.</p>
                    }
                </div>

                <!-- Run just this processor over a bounded sample (no production write) -->
                <div class="pt-2">
                    <button type="button" mat-stroked-button (click)="test()" [disabled]="testing">
                        @if (testing) { <mat-spinner diameter="16" class="mr-2"></mat-spinner> }
                        <mat-icon class="icon-size-5" svgIcon="heroicons_outline:bolt"></mat-icon>
                        <span class="ml-1">Test processor</span>
                    </button>
                    @if (testResult; as r) {
                        <inspecto-alert
                            class="mt-2 block"
                            [variant]="r.ok ? 'success' : 'error'"
                            [icon]="r.ok ? 'heroicons_outline:check-circle' : 'heroicons_outline:x-circle'"
                        >
                            <span class="font-semibold">{{ r.ok ? 'Passed' : 'Failed' }}</span> · {{ r.rowCount }} row(s)
                            <div class="text-secondary mt-0.5">{{ r.detail }}</div>
                            @if (r.rows.length) {
                                <pre class="mt-1 max-h-32 overflow-auto rounded p-1 text-xs"
                                     style="background: var(--gamma-bg-default)">{{ preview(r.rows) }}</pre>
                            }
                        </inspecto-alert>
                    }
                </div>
            </mat-dialog-content>
            <mat-dialog-actions align="end">
                <button type="button" mat-button mat-dialog-close>Cancel</button>
                <button type="submit" mat-flat-button color="primary">Save</button>
            </mat-dialog-actions>
        </form>
    `,
})
export class NodeConfigDialog {
    private fb = inject(FormBuilder);
    private api = inject(FlowsService);
    private components = inject(ComponentsService);
    private dialog = inject(MatDialog);
    private ref = inject(MatDialogRef<NodeConfigDialog, NodeConfigResult>);
    readonly data = inject<NodeConfigData>(MAT_DIALOG_DATA);

    /** Existing components of the bound kind (the picker's options); empty until loaded / when not binding. */
    readonly componentOptions = signal<ComponentDef[]>([]);
    /** Title-cased label for the binding field ("Grammar" / "Transform" / "Sink"). */
    readonly bindLabel = this.data.bindKind
        ? this.data.bindKind.charAt(0).toUpperCase() + this.data.bindKind.slice(1)
        : '';

    testing = false;
    testResult: ComponentTestResult | null = null;

    readonly form = this.fb.group({
        name: this.fb.control(''),
        description: this.fb.control(''),
        use: this.fb.control(''),
        config: this.fb.array<ReturnType<NodeConfigDialog['configRow']>>([]),
    });

    constructor() {
        const n = this.data.node;
        this.form.patchValue({
            name: n.name ?? '',
            description: n.description ?? '',
            use: n.use ?? '',
        });
        for (const [key, value] of Object.entries(n.config ?? {})) {
            this.configRows.push(this.configRow(key, typeof value === 'string' ? value : JSON.stringify(value)));
        }
        if (this.data.bindKind) this.loadComponents();
    }

    private loadComponents(): void {
        this.components.list(this.data.bindKind!).subscribe({
            next: (list) => this.componentOptions.set(list),
            error: () => this.componentOptions.set([]),
        });
    }

    /** The currently-bound component id, parsed out of the `<kind>/<id>` ref in `use` (null when unbound). */
    selectedComponentId(): string | null {
        const use = this.form.get('use')!.value ?? '';
        const prefix = `${this.data.bindKind}/`;
        return use.startsWith(prefix) ? use.slice(prefix.length) : null;
    }

    /** Bind (or clear) the node's `use` to the chosen component of the bound kind. */
    selectComponent(id: string | null): void {
        this.form.patchValue({ use: id ? `${this.data.bindKind}/${id}` : '' });
    }

    /** Author a new component of the bound kind inline (reuses the registry form), then bind it. */
    createComponent(): void {
        if (!this.data.bindKind) return;
        const ref = this.dialog.open(ComponentFormDialog, {
            width: '560px',
            autoFocus: false,
            data: { kind: this.data.bindKind },
        });
        ref.afterClosed().subscribe((res?: ComponentFormResult) => {
            if (!res?.saved) return;
            this.componentOptions.update((opts) =>
                opts.some((o) => o.name === res.saved!.name) ? opts : [...opts, res.saved!]);
            this.selectComponent(res.saved.name);
        });
    }

    get configRows(): FormArray {
        return this.form.get('config') as FormArray;
    }

    addConfigRow(): void {
        this.configRows.push(this.configRow('', ''));
    }

    removeConfigRow(i: number): void {
        this.configRows.removeAt(i);
    }

    /** Run just this processor over a bounded sample through the production logic (scratch-only, no write). */
    test(): void {
        this.testing = true;
        this.testResult = null;
        this.api.testNode(this.data.node.type, this.data.node.id).subscribe({
            next: (r) => {
                this.testing = false;
                this.testResult = r;
            },
            error: (e) => {
                this.testing = false;
                this.testResult = {
                    type: this.data.node.type,
                    id: this.data.node.id,
                    ok: false,
                    detail: apiErrorMessage(e, 'Test failed'),
                    rowCount: 0,
                    rows: [],
                };
            },
        });
    }

    /** A compact JSON preview of the first few sample rows the test produced. */
    preview(rows: Record<string, unknown>[]): string {
        return rows.slice(0, 3).map((r) => JSON.stringify(r)).join('\n');
    }

    save(): void {
        const v = this.form.getRawValue();
        const config: Record<string, unknown> = {};
        for (const row of v.config as { key: string; value: string }[]) {
            if (row.key && row.key.trim()) config[row.key.trim()] = row.value;
        }
        const node: AuthoredNode = {
            id: this.data.node.id,
            type: this.data.node.type,
            name: v.name?.trim() || undefined,
            description: v.description?.trim() || undefined,
            use: v.use?.trim() || undefined,
            config: Object.keys(config).length ? config : undefined,
        };
        this.ref.close({ node });
    }

    private configRow(key: string, value: string) {
        return this.fb.group({
            key: this.fb.control(key, { nonNullable: true }),
            value: this.fb.control(value, { nonNullable: true }),
        });
    }
}

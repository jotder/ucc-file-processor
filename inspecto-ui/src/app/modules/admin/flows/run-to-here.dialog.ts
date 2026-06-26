import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import {
    AuthoredNode,
    ConnectionProbeService,
    FlowRunRelation,
    FlowRunResult,
    FlowsService,
    ResourceNode,
    apiErrorMessage,
} from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { ConnectionTreeComponent } from 'app/modules/admin/connections/connection-tree.component';

/** Dialog data: which authored flow + which node to run up to, plus the source's bound connection (if any). */
export interface RunToHereData {
    flowId: string;
    node: AuthoredNode;
    /** The seed source's connection id (`connections/<id>` → `<id>`), or null when none is bound. */
    connectionId: string | null;
}

/**
 * Run-to-here (the editor's build-and-test loop). Picks files from the source's inbox (reusing the
 * connection "Explore" tree), runs the authored subgraph up to {@link RunToHereData.node} over them, and
 * shows the per-relation counts (success / unmatched / kept / dropped) + a bounded sample + the scratch
 * Parquet the run landed. No production write — mock-backed via {@code POST …/run?to=}.
 */
@Component({
    selector: 'app-run-to-here-dialog',
    standalone: true,
    imports: [
        MatDialogModule,
        MatButtonModule,
        MatIconModule,
        MatProgressSpinnerModule,
        MatTooltipModule,
        ConnectionTreeComponent,
        InspectoAlertComponent,
        InspectoEmptyStateComponent,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <h2 mat-dialog-title>Run to here · {{ data.node.name || data.node.id }}</h2>
        <mat-dialog-content class="!max-h-[70vh]">
            <p class="text-secondary mb-3 text-sm">
                Runs the pipeline up to this node over the files you pick and lands the result as scratch
                Parquet — nothing is written to production.
            </p>

            <!-- inbox file picker (reuses the connection Explore tree) -->
            <section class="bg-card rounded-xl border p-3" style="border-color: var(--gamma-border)">
                <div class="flex items-center justify-between gap-2">
                    <h3 class="text-sm font-semibold">Inbox files</h3>
                    @if (data.connectionId) {
                        <span class="text-secondary font-mono text-xs">connections/{{ data.connectionId }}</span>
                    }
                </div>

                @if (!data.connectionId) {
                    <inspecto-alert class="mt-2 block" variant="info" icon="heroicons_outline:information-circle">
                        No source connection is bound — the run uses a built-in sample.
                    </inspecto-alert>
                } @else if (exploring()) {
                    <div class="mt-3 flex items-center gap-2 text-sm">
                        <mat-spinner diameter="16"></mat-spinner> Exploring…
                    </div>
                } @else if (rootNodes().length) {
                    <div class="mt-2 max-h-48 overflow-auto">
                        <app-connection-tree
                            [nodes]="rootNodes()"
                            [childrenByPath]="childrenByPath()"
                            [expanded]="expanded()"
                            [loadingPaths]="loadingPaths()"
                            [selectedPath]="null"
                            (expand)="onExpand($event)"
                            (select)="onSelect($event)"
                        />
                    </div>
                } @else {
                    <inspecto-empty-state
                        icon="heroicons_outline:folder-open"
                        message="Nothing to list on this connection."
                    />
                }

                @if (selectedFiles().length) {
                    <div class="mt-3 flex flex-wrap gap-1.5" aria-label="Selected files">
                        @for (p of selectedFiles(); track p) {
                            <span class="inline-flex items-center gap-1 rounded bg-gray-100 px-2 py-0.5 font-mono text-xs dark:bg-gray-800">
                                {{ basename(p) }}
                                <button type="button" class="opacity-60 hover:opacity-100"
                                        (click)="removeFile(p)" [attr.aria-label]="'Remove ' + p">
                                    <mat-icon class="icon-size-3" svgIcon="heroicons_outline:x-mark"></mat-icon>
                                </button>
                            </span>
                        }
                    </div>
                }
            </section>

            <div class="mt-3 flex items-center gap-3">
                <button mat-flat-button color="primary" (click)="run()" [disabled]="running()">
                    @if (running()) { <mat-spinner diameter="16" class="mr-2"></mat-spinner> }
                    <mat-icon class="icon-size-5" svgIcon="heroicons_outline:play"></mat-icon>
                    <span class="ml-1">Run to here</span>
                </button>
                <span class="text-secondary text-xs">
                    @if (selectedFiles().length) {
                        {{ selectedFiles().length }} file(s) selected
                    } @else {
                        no files selected — runs over a built-in sample
                    }
                </span>
            </div>

            @if (error()) {
                <inspecto-alert class="mt-3 block" variant="error" icon="heroicons_outline:x-circle">
                    {{ error() }}
                </inspecto-alert>
            }

            <!-- results -->
            @if (result(); as r) {
                @if (r.output; as o) {
                    <inspecto-alert class="mt-4 block" variant="success" icon="heroicons_outline:check-circle">
                        <span class="font-semibold">Wrote {{ o.rowCount }} row(s)</span>
                        · {{ o.format }} → <span class="font-mono">{{ o.path }}</span>
                    </inspecto-alert>
                }
                @for (w of r.warnings; track w) {
                    <inspecto-alert class="mt-2 block" variant="warning" icon="heroicons_outline:exclamation-triangle">
                        {{ w }}
                    </inspecto-alert>
                }

                <div class="mt-4 flex flex-col gap-3">
                    @for (rel of r.relations; track rel.node + ':' + rel.rel) {
                        <div class="bg-card rounded-xl border p-3" style="border-color: var(--gamma-border)">
                            <div class="flex flex-wrap items-baseline gap-x-2">
                                <span class="font-mono text-sm font-semibold">{{ rel.node }}</span>
                                <span class="rounded px-1.5 py-0.5 text-xs font-semibold uppercase"
                                      [class.opacity-70]="isReject(rel.rel)"
                                      style="background: var(--gamma-bg-default)">{{ rel.rel }}</span>
                                <span class="text-secondary text-xs">{{ rel.rowCount }} row(s)</span>
                            </div>
                            @if (rel.rows.length) {
                                <div class="mt-2 overflow-auto">
                                    <table class="w-full text-left text-xs">
                                        <thead class="text-secondary">
                                            <tr>
                                                @for (c of columnsOf(rel.rows); track c) {
                                                    <th scope="col" class="px-2 py-1 font-medium">{{ c }}</th>
                                                }
                                            </tr>
                                        </thead>
                                        <tbody class="font-mono">
                                            @for (row of rel.rows.slice(0, 5); track $index) {
                                                <tr class="border-t" style="border-color: var(--gamma-border)">
                                                    @for (c of columnsOf(rel.rows); track c) {
                                                        <td class="px-2 py-1">{{ cell(row, c) }}</td>
                                                    }
                                                </tr>
                                            }
                                        </tbody>
                                    </table>
                                </div>
                            }
                        </div>
                    }
                </div>
            }
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button type="button" mat-button mat-dialog-close>Close</button>
        </mat-dialog-actions>
    `,
})
export class RunToHereDialog implements OnInit {
    private api = inject(FlowsService);
    private probe = inject(ConnectionProbeService);
    private ref = inject(MatDialogRef<RunToHereDialog>);
    readonly data = inject<RunToHereData>(MAT_DIALOG_DATA);

    readonly exploring = signal(false);
    readonly rootNodes = signal<ResourceNode[]>([]);
    readonly childrenByPath = signal<Record<string, ResourceNode[]>>({});
    readonly expanded = signal<Set<string>>(new Set());
    readonly loadingPaths = signal<Set<string>>(new Set());
    readonly selectedFiles = signal<string[]>([]);

    readonly running = signal(false);
    readonly result = signal<FlowRunResult | null>(null);
    readonly error = signal<string | null>(null);

    ngOnInit(): void {
        if (!this.data.connectionId) return;
        this.exploring.set(true);
        this.probe.explore(this.data.connectionId).subscribe({
            next: (ns) => {
                this.rootNodes.set(ns);
                this.exploring.set(false);
            },
            error: () => this.exploring.set(false),
        });
    }

    onExpand(node: ResourceNode): void {
        const exp = new Set(this.expanded());
        if (exp.has(node.path)) {
            exp.delete(node.path);
            this.expanded.set(exp);
            return;
        }
        exp.add(node.path);
        this.expanded.set(exp);
        if (this.childrenByPath()[node.path] || !this.data.connectionId) return;
        this.loadingPaths.set(new Set(this.loadingPaths()).add(node.path));
        this.probe.explore(this.data.connectionId, node.path).subscribe({
            next: (kids) => {
                this.childrenByPath.set({ ...this.childrenByPath(), [node.path]: kids });
                this.clearLoading(node.path);
            },
            error: () => this.clearLoading(node.path),
        });
    }

    onSelect(node: ResourceNode): void {
        if (node.kind !== 'file') {
            if (node.hasChildren) this.onExpand(node);
            return;
        }
        const sel = new Set(this.selectedFiles());
        sel.has(node.path) ? sel.delete(node.path) : sel.add(node.path);
        this.selectedFiles.set([...sel]);
    }

    removeFile(path: string): void {
        this.selectedFiles.set(this.selectedFiles().filter((p) => p !== path));
    }

    run(): void {
        this.running.set(true);
        this.error.set(null);
        this.result.set(null);
        this.api.runToNode(this.data.flowId, this.data.node.id, this.selectedFiles()).subscribe({
            next: (r) => {
                this.running.set(false);
                this.result.set(r);
            },
            error: (e) => {
                this.running.set(false);
                this.error.set(apiErrorMessage(e, 'Run failed'));
            },
        });
    }

    /** Reject relationships (unmatched / dropped) render dimmed so the matched output reads first. */
    isReject(rel: string): boolean {
        return rel === 'unmatched' || rel === 'dropped';
    }

    columnsOf(rows: Record<string, unknown>[]): string[] {
        const cols = new Set<string>();
        for (const r of rows) for (const k of Object.keys(r)) cols.add(k);
        return [...cols];
    }

    cell(row: Record<string, unknown>, col: string): string {
        const v = row[col];
        return v == null ? '' : typeof v === 'string' ? v : JSON.stringify(v);
    }

    basename(path: string): string {
        const i = path.lastIndexOf('/');
        return i >= 0 ? path.slice(i + 1) : path;
    }

    private clearLoading(path: string): void {
        const loading = new Set(this.loadingPaths());
        loading.delete(path);
        this.loadingPaths.set(loading);
    }
}

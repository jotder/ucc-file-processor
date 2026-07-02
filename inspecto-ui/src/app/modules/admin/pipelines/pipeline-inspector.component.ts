import { ChangeDetectionStrategy, Component, EventEmitter, Input, Output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatSelectModule } from '@angular/material/select';
import { AuthoredNode } from 'app/inspecto/api';
import {
    categoryColor,
    categoryLabel,
    NodeStatus,
    nodeConfigEntries,
    statusIcon,
    statusLabel,
    statusTint,
} from './pipeline-graph';

/**
 * The pipeline editor's property drawer — one of three states: a selected node's summary + actions, a
 * selected edge's relationship picker, or an idle hint (Wave-1 decomposition of `PipelineEditorComponent`,
 * see `docs/superpower/reviews/pipeline-editor.md`). Purely presentational: the host computes `status`
 * (it alone holds the registry-refs/test-outcome state `statusOf` needs) and `category`; every rendering
 * decision here uses the framework-free helpers in `pipeline-graph.ts` directly.
 */
@Component({
    selector: 'app-pipeline-inspector',
    standalone: true,
    imports: [MatButtonModule, MatFormFieldModule, MatIconModule, MatSelectModule],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        @if (node) {
            <div class="mb-1 flex items-center justify-between gap-2">
                <h3 class="truncate text-sm font-semibold">Node · {{ node.id }}</h3>
                <span
                    class="shrink-0 rounded px-1.5 py-0.5 text-xs font-semibold"
                    [style.color]="categoryColor(category)"
                    style="background: var(--gamma-bg-default)"
                >
                    {{ categoryLabel(category) }}
                </span>
            </div>
            <p class="mb-1 text-xs opacity-70">{{ node.type }}</p>
            @if (status) {
                <p class="mb-2 inline-flex items-center gap-1 text-xs font-semibold" [style.color]="statusTint(status)">
                    <mat-icon class="icon-size-4" [svgIcon]="statusIcon(status)"></mat-icon>
                    {{ statusLabel(status) }}
                </p>
            }

            @if (node.name) {
                <p class="text-sm"><span class="opacity-70">name:</span> {{ node.name }}</p>
            }
            @if (node.use) {
                <p class="text-sm"><span class="opacity-70">use:</span> {{ node.use }}</p>
            }
            @if (node.description) {
                <p class="text-sm opacity-80">{{ node.description }}</p>
            }

            @if (configEntries().length) {
                <div class="mt-2">
                    <span class="text-xs font-semibold uppercase opacity-70">Config</span>
                    @for (c of configEntries(); track c.k) {
                        <div class="truncate text-sm"><span class="opacity-70">{{ c.k }}:</span> {{ c.v }}</div>
                    }
                </div>
            }

            <div class="mt-3 flex flex-wrap gap-2">
                <button mat-flat-button color="primary" type="button" (click)="configure.emit(node)">
                    <mat-icon svgIcon="heroicons_outline:cog-6-tooth"></mat-icon> Configure
                </button>
                <button mat-stroked-button type="button" (click)="runToHere.emit(node)">
                    <mat-icon svgIcon="heroicons_outline:play"></mat-icon> Run to here
                </button>
                <button mat-stroked-button type="button" (click)="connect.emit()">
                    <mat-icon svgIcon="heroicons_outline:arrow-right"></mat-icon> Connect
                </button>
                <button mat-stroked-button type="button" (click)="deleteSelected.emit()" aria-label="Delete node">
                    <mat-icon svgIcon="heroicons_outline:trash"></mat-icon> Delete
                </button>
            </div>
        } @else if (selectedEdgeId) {
            <h3 class="mb-2 text-sm font-semibold">Connection</h3>
            <mat-form-field class="w-full" subscriptSizing="dynamic">
                <mat-label>Relationship</mat-label>
                <mat-select [value]="selectedEdgeRel" (selectionChange)="edgeRelChange.emit($event.value)">
                    @for (r of candidateRels; track r) {
                        <mat-option [value]="r">{{ r }}</mat-option>
                    }
                </mat-select>
            </mat-form-field>
            <p class="mt-1 text-xs opacity-60">The source's output this connection carries.</p>
            <button class="mt-3" mat-stroked-button type="button" (click)="deleteSelected.emit()" aria-label="Delete connection">
                <mat-icon svgIcon="heroicons_outline:trash"></mat-icon> Delete connection
            </button>
        } @else {
            <p class="text-sm opacity-70">
                Drag a processor from the toolbar onto the canvas. Click a node or edge to select it;
                <b>double-click</b> a node (or use <b>Configure</b>) to edit its attributes.
                <b>Delete selected</b> removes the selected item.
            </p>
        }
    `,
})
export class PipelineInspectorComponent {
    @Input() node: AuthoredNode | null = null;
    /** The node's authoring status — the host computes this (it alone holds the ref/test-outcome state). */
    @Input() status: NodeStatus | null = null;
    /** The node's palette category — drives the label chip's text + colour. */
    @Input() category = '';
    @Input() selectedEdgeId: string | null = null;
    @Input() selectedEdgeRel: string | null = null;
    @Input() candidateRels: string[] = [];

    @Output() configure = new EventEmitter<AuthoredNode>();
    @Output() runToHere = new EventEmitter<AuthoredNode>();
    @Output() connect = new EventEmitter<void>();
    @Output() deleteSelected = new EventEmitter<void>();
    @Output() edgeRelChange = new EventEmitter<string>();

    readonly categoryColor = categoryColor;
    readonly categoryLabel = categoryLabel;
    readonly statusIcon = statusIcon;
    readonly statusTint = statusTint;
    readonly statusLabel = statusLabel;

    configEntries(): { k: string; v: string }[] {
        return this.node ? nodeConfigEntries(this.node) : [];
    }
}

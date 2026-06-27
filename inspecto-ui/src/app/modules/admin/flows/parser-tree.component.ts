import { ChangeDetectionStrategy, Component, Input, signal } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { ParserTreeNode } from 'app/inspecto/api';

/**
 * Recursive collapsible tree for a hierarchical parse preview (ASN.1 / JSON / XML) — the tree-shaped
 * counterpart to the flat ag-Grid output in {@link ParserConfigDialog}. Presentational only: takes a
 * {@link ParserTreeNode} forest and renders nested `treeitem`s, each showing its label, an optional type
 * chip, and a leaf value. Container nodes start expanded and toggle on click; nodes default-expand so the
 * record structure reads at a glance. A standalone component may reference its own selector recursively
 * (`<app-parser-tree>`) without self-importing.
 */
@Component({
    selector: 'app-parser-tree',
    standalone: true,
    imports: [MatIconModule],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <ul [attr.role]="root ? 'tree' : 'group'" class="m-0 list-none p-0" [class.pl-4]="!root">
            @for (n of nodes; track $index) {
                <li role="treeitem" [attr.aria-expanded]="hasChildren(n) ? isOpen($index) : null"
                    class="py-0.5">
                    <div class="flex items-start gap-1">
                        @if (hasChildren(n)) {
                            <button type="button"
                                    class="mt-0.5 shrink-0 opacity-70 hover:opacity-100"
                                    (click)="toggle($index)"
                                    [attr.aria-label]="(isOpen($index) ? 'Collapse ' : 'Expand ') + n.label">
                                <mat-icon class="icon-size-4"
                                          [svgIcon]="isOpen($index) ? 'heroicons_outline:chevron-down' : 'heroicons_outline:chevron-right'"></mat-icon>
                            </button>
                        } @else {
                            <span class="mt-0.5 inline-block w-4 shrink-0 text-center opacity-40">·</span>
                        }
                        <span class="min-w-0 break-words text-sm">
                            <span class="font-mono font-medium">{{ n.label }}</span>
                            @if (n.type) {
                                <span class="ml-1.5 rounded px-1 py-0.5 text-xs uppercase opacity-70"
                                      style="background: var(--gamma-bg-default)">{{ n.type }}</span>
                            }
                            @if (n.value != null) {
                                <span class="ml-1.5 font-mono opacity-80">= {{ n.value }}</span>
                            }
                        </span>
                    </div>
                    @if (hasChildren(n) && isOpen($index)) {
                        <app-parser-tree [nodes]="n.children!" [root]="false" />
                    }
                </li>
            }
        </ul>
    `,
})
export class ParserTreeComponent {
    @Input({ required: true }) nodes: ParserTreeNode[] = [];
    /** The top-level instance is the ARIA `tree`; nested instances are `group`s. */
    @Input() root = true;

    /** Indices the user has explicitly collapsed; everything else renders expanded. */
    private readonly collapsed = signal<Set<number>>(new Set());

    hasChildren(n: ParserTreeNode): boolean {
        return !!n.children && n.children.length > 0;
    }

    isOpen(i: number): boolean {
        return !this.collapsed().has(i);
    }

    toggle(i: number): void {
        const next = new Set(this.collapsed());
        next.has(i) ? next.delete(i) : next.add(i);
        this.collapsed.set(next);
    }
}

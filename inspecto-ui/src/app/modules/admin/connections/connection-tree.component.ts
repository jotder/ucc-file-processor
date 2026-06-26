import { ChangeDetectionStrategy, Component, EventEmitter, Input, Output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { ResourceNode } from 'app/inspecto/api';

/**
 * Minimal recursive resource-tree (the connection "Explore" pane). Presentational: the parent
 * (connection workbench) owns the lazy-loaded children (`childrenByPath`) and the `expanded` set and
 * reacts to `(expand)` / `(select)`. Self-references for recursion — no extra dependency, design-system
 * tokens only (no hardcoded colours). Files/tables are leaves and emit `select` for the sample preview.
 */
@Component({
    selector: 'app-connection-tree',
    standalone: true,
    imports: [MatButtonModule, MatIconModule, MatProgressSpinnerModule],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <ul class="m-0 list-none p-0">
            @for (n of nodes; track n.path) {
                @if (visible(n)) {
                <li>
                    <div
                        class="flex items-center gap-1 rounded hover:bg-gray-100 dark:hover:bg-gray-800"
                        [class.bg-gray-100]="selectedPath === n.path"
                        [class.dark:bg-gray-800]="selectedPath === n.path"
                    >
                        @if (loadingPaths.has(n.path)) {
                            <mat-spinner diameter="16" class="mx-2 shrink-0"></mat-spinner>
                        } @else if (n.hasChildren) {
                            <button
                                mat-icon-button
                                class="icon-size-5"
                                (click)="expand.emit(n)"
                                [attr.aria-expanded]="expanded.has(n.path)"
                                [attr.aria-label]="(expanded.has(n.path) ? 'Collapse ' : 'Expand ') + n.name"
                            >
                                <mat-icon
                                    class="icon-size-4"
                                    [svgIcon]="expanded.has(n.path) ? 'heroicons_outline:chevron-down' : 'heroicons_outline:chevron-right'"
                                ></mat-icon>
                            </button>
                        } @else {
                            <span class="inline-block w-8 shrink-0"></span>
                        }
                        <button
                            type="button"
                            class="tree-row flex min-w-0 flex-auto items-center gap-2 px-1 py-1 text-left text-sm"
                            [class.font-semibold]="selectedPath === n.path"
                            [attr.aria-current]="selectedPath === n.path ? 'true' : null"
                            (click)="select.emit(n)"
                            (keydown)="onKey(n, $event)"
                        >
                            <mat-icon class="icon-size-4 shrink-0" [svgIcon]="icon(n)"></mat-icon>
                            <span class="truncate">{{ n.name }}</span>
                            @if (n.sizeBytes != null) {
                                <span class="text-secondary ml-auto pl-2 text-xs">{{ size(n.sizeBytes) }}</span>
                            }
                        </button>
                    </div>
                    @if (expanded.has(n.path)) {
                        <app-connection-tree
                            class="block pl-5"
                            [nodes]="childrenByPath[n.path] ?? []"
                            [childrenByPath]="childrenByPath"
                            [expanded]="expanded"
                            [loadingPaths]="loadingPaths"
                            [selectedPath]="selectedPath"
                            [filter]="filter"
                            (expand)="expand.emit($event)"
                            (select)="select.emit($event)"
                        />
                    }
                </li>
                }
            }
        </ul>
    `,
})
export class ConnectionTreeComponent {
    @Input() nodes: ResourceNode[] = [];
    @Input() childrenByPath: Record<string, ResourceNode[]> = {};
    @Input() expanded: Set<string> = new Set();
    @Input() loadingPaths: Set<string> = new Set();
    @Input() selectedPath: string | null = null;
    /** Case-insensitive name filter; a node stays visible if its name (or a loaded descendant) matches. */
    @Input() filter = '';
    @Output() expand = new EventEmitter<ResourceNode>();
    @Output() select = new EventEmitter<ResourceNode>();

    /** Whether to render this node under the active filter (self-match, loaded-descendant match, or not-yet-loaded). */
    visible(n: ResourceNode): boolean {
        const q = this.filter.trim().toLowerCase();
        return q ? this.matchesDeep(n, q) : true;
    }

    private matchesDeep(n: ResourceNode, q: string): boolean {
        if (n.name.toLowerCase().includes(q)) return true;
        // Filter over what's loaded: a parent stays only if a loaded descendant matches. (Expand first to
        // search deeper — we can't match inside an unfetched folder.)
        const kids = this.childrenByPath[n.path];
        return kids ? kids.some((k) => this.matchesDeep(k, q)) : false;
    }

    /**
     * Keyboard: ArrowRight opens a collapsed parent, ArrowLeft closes an expanded one, ArrowUp/Down roves focus
     * across all visible rows (Enter/Space select via the native button). Roving walks to the outermost tree so
     * focus crosses nesting levels.
     */
    onKey(n: ResourceNode, ev: KeyboardEvent): void {
        if (ev.key === 'ArrowRight' && n.hasChildren && !this.expanded.has(n.path)) {
            ev.preventDefault();
            this.expand.emit(n);
        } else if (ev.key === 'ArrowLeft' && this.expanded.has(n.path)) {
            ev.preventDefault();
            this.expand.emit(n);
        } else if (ev.key === 'ArrowDown' || ev.key === 'ArrowUp') {
            ev.preventDefault();
            const btn = ev.currentTarget as HTMLElement;
            let root = btn.closest('app-connection-tree') as HTMLElement | null;
            while (root?.parentElement?.closest('app-connection-tree')) {
                root = root.parentElement.closest('app-connection-tree') as HTMLElement;
            }
            const rows = Array.from(root?.querySelectorAll<HTMLElement>('button.tree-row') ?? []);
            const i = rows.indexOf(btn);
            (ev.key === 'ArrowDown' ? rows[i + 1] : rows[i - 1])?.focus();
        }
    }

    icon(n: ResourceNode): string {
        switch (n.kind) {
            case 'dir':
                return 'heroicons_outline:folder';
            case 'bucket':
                return 'heroicons_outline:archive-box';
            case 'schema':
                return 'heroicons_outline:circle-stack';
            case 'table':
                return 'heroicons_outline:table-cells';
            case 'column':
                return 'heroicons_outline:view-columns';
            default:
                return 'heroicons_outline:document';
        }
    }

    size(bytes: number): string {
        if (bytes < 1024) return `${bytes} B`;
        const units = ['KB', 'MB', 'GB', 'TB'];
        let v = bytes / 1024;
        let i = 0;
        while (v >= 1024 && i < units.length - 1) {
            v /= 1024;
            i++;
        }
        return `${v.toFixed(1)} ${units[i]}`;
    }
}

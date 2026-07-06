import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { NavigationService } from 'app/core/navigation/navigation.service';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { MenuService } from 'app/inspecto/menu';
import { MenuArtifactComponent } from './menu-artifact.component';
import { MenuNodeDialog, MenuNodeDialogData, MenuNodeDialogResult } from './menu-node.dialog';
import { MenuTreeNodeComponent } from './menu-tree-node.component';

/**
 * Menu Builder (Settings → Menus) — curate the per-Space custom navigation: build a tree of menus /
 * sub-menus and place library reports (Widgets / Dashboards / saved views) under them. Two panes: the
 * editable tree on the left, a live preview of the selected item on the right (the same render surface a
 * business user sees). Edits persist via {@link MenuService} and re-fetch the sidebar. Only these custom
 * menus are editable here — the platform nav is untouched (plan §5a). See docs/superpower/menu-builder-plan.md (M4).
 */
@Component({
    selector: 'app-menu-builder',
    standalone: true,
    imports: [
        MatButtonModule,
        MatIconModule,
        InspectoEmptyStateComponent,
        MenuTreeNodeComponent,
        MenuArtifactComponent,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <div class="flex min-w-0 flex-auto flex-col p-6 md:p-8">
            <div class="mb-6">
                <h1 class="text-3xl font-extrabold leading-tight tracking-tight">Menus</h1>
                <p class="text-secondary mt-1 max-w-3xl">
                    Build your own menus and place reports under them for business users. These appear in the
                    sidebar alongside the platform navigation; the platform menus themselves aren’t changed.
                </p>
            </div>

            <div class="flex flex-col gap-4 lg:flex-row">
                <!-- Tree editor -->
                <div class="bg-card shrink-0 rounded-2xl p-4 shadow lg:w-96">
                    <div class="mb-2 flex items-center justify-between">
                        <h2 class="text-sm font-semibold uppercase tracking-wider opacity-60">Your menus</h2>
                        <button mat-stroked-button (click)="addMenu()">
                            <mat-icon class="icon-size-4" svgIcon="heroicons_outline:plus"></mat-icon>
                            <span class="ml-1">Add menu</span>
                        </button>
                    </div>

                    @if (nodes().length) {
                        @for (n of nodes(); track n.id) {
                            <app-menu-tree-node
                                [node]="n"
                                [parentId]="null"
                                [siblings]="nodes()"
                                [selectedId]="selectedId()"
                                (select)="selectedId.set($event)"
                                (changed)="onChanged()"
                            />
                        }
                    } @else {
                        <inspecto-empty-state
                            icon="heroicons_outline:queue-list"
                            title="No menus yet"
                            message="Add a top-level menu (e.g. Revenue), then add sub-menus and reports under it."
                        />
                    }
                </div>

                <!-- Live preview -->
                <div class="bg-card min-w-0 flex-auto rounded-2xl p-4 shadow">
                    <h2 class="mb-2 text-sm font-semibold uppercase tracking-wider opacity-60">Preview</h2>
                    @if (selectedNode(); as n) {
                        <div class="mb-3 text-lg font-bold">{{ n.title }}</div>
                        <app-menu-artifact
                            [binding]="n.binding"
                            emptyMessage="This is a group — select one of its reports to preview it."
                        />
                    } @else {
                        <inspecto-empty-state
                            icon="heroicons_outline:eye"
                            title="Nothing selected"
                            message="Select a menu item on the left to preview what business users will see."
                        />
                    }
                </div>
            </div>
        </div>
    `,
})
export class MenuBuilderComponent {
    private readonly menuApi = inject(MenuService);
    private readonly dialog = inject(MatDialog);
    private readonly navigation = inject(NavigationService);

    readonly nodes = this.menuApi.nodes;
    readonly selectedId = signal<string | null>(null);
    readonly selectedNode = computed(() => {
        const id = this.selectedId();
        return id ? this.menuApi.find(id) : undefined;
    });

    addMenu(): void {
        const data: MenuNodeDialogData = { heading: 'Add menu', takenTitles: this.nodes().map((n) => n.title) };
        this.dialog
            .open<MenuNodeDialog, MenuNodeDialogData, MenuNodeDialogResult>(MenuNodeDialog, {
                width: '420px',
                autoFocus: false,
                data,
            })
            .afterClosed()
            .subscribe((r) => {
                if (!r) return;
                const id = this.menuApi.mutate((s) => s.addMenu(r.title, r.icon));
                this.selectedId.set(id);
                this.onChanged();
            });
    }

    /** A tree edit landed — re-fetch the sidebar so the custom groups reflect the change. */
    onChanged(): void {
        this.navigation.get().subscribe();
    }
}

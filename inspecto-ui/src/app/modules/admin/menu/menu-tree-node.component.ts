import { ChangeDetectionStrategy, Component, computed, inject, input, output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatTooltipModule } from '@angular/material/tooltip';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { MenuNode, MenuService } from 'app/inspecto/menu';
import { MenuAttachDialog, MenuAttachResult } from './menu-attach.dialog';
import { MenuNodeDialog, MenuNodeDialogData, MenuNodeDialogResult } from './menu-node.dialog';

/**
 * One node in the Menu Builder tree — its row (icon + title + an actions menu) and, recursively, its
 * children. Edits go straight through {@link MenuService} (pure ops + persist); `(changed)` bubbles so the
 * builder can refresh the sidebar, `(select)` drives the live preview. Groups can gain sub-menus / reports;
 * leaves (bound to a report) cannot. Reorder is accessible move-up/down (no drag-drop dependency).
 */
@Component({
    selector: 'app-menu-tree-node',
    standalone: true,
    imports: [MatButtonModule, MatIconModule, MatMenuModule, MatTooltipModule, MenuTreeNodeComponent],
    changeDetection: ChangeDetectionStrategy.OnPush,
    host: {
        role: 'treeitem',
        '[attr.aria-selected]': 'selectedId() === node().id',
        '[attr.aria-expanded]': "(node().children?.length ?? 0) > 0 ? 'true' : null",
    },
    template: `
        <div
            class="group flex items-center gap-2 rounded-lg py-1.5 pr-1"
            [style.background]="selectedId() === node().id ? 'var(--gamma-bg-hover)' : null"
            [style.padding-left.rem]="0.25 + depth() * 1.1"
        >
            <button
                type="button"
                class="flex min-w-0 flex-auto items-center gap-2 text-left"
                (click)="select.emit(node().id)"
            >
                <mat-icon class="icon-size-5 text-secondary shrink-0" [svgIcon]="icon()"></mat-icon>
                <span class="truncate text-sm" [class.font-semibold]="!isLeaf()">{{ node().title }}</span>
                @if (isLeaf()) {
                    <span class="text-secondary text-xs">· {{ node().binding?.kind }}</span>
                }
            </button>

            @if (isLeaf()) {
                <button
                    mat-icon-button
                    type="button"
                    class="shrink-0"
                    [class.text-primary]="isFavorite()"
                    [attr.aria-pressed]="isFavorite()"
                    [attr.aria-label]="favoriteLabel()"
                    [matTooltip]="isFavorite() ? 'Remove from favorites' : 'Add to favorites'"
                    (click)="toggleFavorite()"
                >
                    <mat-icon
                        class="icon-size-5"
                        [svgIcon]="isFavorite() ? 'heroicons_solid:star' : 'heroicons_outline:star'"
                    ></mat-icon>
                </button>
            }

            <button
                mat-icon-button
                class="opacity-0 group-hover:opacity-100 focus:opacity-100"
                [matMenuTriggerFor]="menu"
                [attr.aria-label]="'Actions for ' + node().title"
            >
                <mat-icon class="icon-size-5" svgIcon="heroicons_outline:ellipsis-vertical"></mat-icon>
            </button>
            <mat-menu #menu="matMenu">
                <button mat-menu-item (click)="edit()">
                    <mat-icon svgIcon="heroicons_outline:pencil-square"></mat-icon><span>Rename / icon</span>
                </button>
                @if (!isLeaf()) {
                    <button mat-menu-item (click)="addSubMenu()">
                        <mat-icon svgIcon="heroicons_outline:folder-plus"></mat-icon><span>Add sub-menu</span>
                    </button>
                    <button mat-menu-item (click)="addReport()">
                        <mat-icon svgIcon="heroicons_outline:document-plus"></mat-icon><span>Add report</span>
                    </button>
                }
                <button mat-menu-item [disabled]="!canMoveUp()" (click)="move(-1)">
                    <mat-icon svgIcon="heroicons_outline:arrow-up"></mat-icon><span>Move up</span>
                </button>
                <button mat-menu-item [disabled]="!canMoveDown()" (click)="move(1)">
                    <mat-icon svgIcon="heroicons_outline:arrow-down"></mat-icon><span>Move down</span>
                </button>
                <button mat-menu-item (click)="remove()">
                    <mat-icon svgIcon="heroicons_outline:trash"></mat-icon><span>Delete</span>
                </button>
            </mat-menu>
        </div>

        @if ((node().children ?? []).length) {
            <div role="group">
                @for (child of node().children ?? []; track child.id) {
                    <app-menu-tree-node
                        [node]="child"
                        [parentId]="node().id"
                        [siblings]="node().children ?? []"
                        [depth]="depth() + 1"
                        [selectedId]="selectedId()"
                        (select)="select.emit($event)"
                        (changed)="changed.emit()"
                    />
                }
            </div>
        }
    `,
})
export class MenuTreeNodeComponent {
    private readonly menu = inject(MenuService);
    private readonly dialog = inject(MatDialog);
    private readonly confirm = inject(InspectoConfirmService);

    readonly node = input.required<MenuNode>();
    readonly parentId = input<string | null>(null);
    readonly siblings = input<MenuNode[]>([]);
    readonly depth = input(0);
    readonly selectedId = input<string | null>(null);

    readonly select = output<string>();
    readonly changed = output<void>();

    readonly isLeaf = computed(() => this.node().binding != null);
    readonly icon = computed(
        () => this.node().icon ?? (this.isLeaf() ? 'heroicons_outline:document-chart-bar' : 'heroicons_outline:folder'),
    );
    readonly isFavorite = computed(() => this.isLeaf() && this.menu.isFavorite(this.node().id));
    readonly favoriteLabel = computed(() =>
        this.isFavorite() ? `Remove ${this.node().title} from favorites` : `Add ${this.node().title} to favorites`,
    );

    private index = computed(() => this.siblings().findIndex((s) => s.id === this.node().id));
    readonly canMoveUp = computed(() => this.index() > 0);
    readonly canMoveDown = computed(() => this.index() >= 0 && this.index() < this.siblings().length - 1);

    edit(): void {
        const takenTitles = this.siblings()
            .filter((s) => s.id !== this.node().id)
            .map((s) => s.title);
        this.open({ heading: 'Rename', title: this.node().title, icon: this.node().icon, takenTitles }).subscribe((r) => {
            if (!r) return;
            this.menu.mutate((s) => s.rename(this.node().id, r.title).setIcon(this.node().id, r.icon));
            this.changed.emit();
        });
    }

    toggleFavorite(): void {
        this.menu.toggleFavorite(this.node().id);
        this.changed.emit();
    }

    addSubMenu(): void {
        const takenTitles = (this.node().children ?? []).map((c) => c.title);
        this.open({ heading: 'Add sub-menu', takenTitles }).subscribe((r) => {
            if (!r) return;
            this.menu.mutate((s) => s.addSubMenu(this.node().id, r.title, r.icon));
            this.changed.emit();
        });
    }

    addReport(): void {
        this.dialog
            .open(MenuAttachDialog, { width: '620px', autoFocus: false })
            .afterClosed()
            .subscribe((res?: MenuAttachResult) => {
                if (!res) return;
                this.menu.mutate((s) => s.attach(this.node().id, res.title, res.binding));
                this.changed.emit();
            });
    }

    move(delta: -1 | 1): void {
        const ids = this.siblings().map((s) => s.id);
        const i = this.index();
        const j = i + delta;
        if (i < 0 || j < 0 || j >= ids.length) return;
        [ids[i], ids[j]] = [ids[j], ids[i]];
        this.menu.mutate((s) => s.reorder(this.parentId(), ids));
        this.changed.emit();
    }

    async remove(): Promise<void> {
        const hasChildren = (this.node().children?.length ?? 0) > 0;
        const ok = await this.confirm.confirmDestructive(
            `Delete “${this.node().title}”${hasChildren ? ' and everything under it' : ''}?`,
        );
        if (!ok) return;
        this.menu.mutate((s) => s.remove(this.node().id));
        this.changed.emit();
    }

    private open(data: MenuNodeDialogData) {
        return this.dialog
            .open<MenuNodeDialog, MenuNodeDialogData, MenuNodeDialogResult>(MenuNodeDialog, {
                width: '420px',
                autoFocus: false,
                data,
            })
            .afterClosed();
    }
}

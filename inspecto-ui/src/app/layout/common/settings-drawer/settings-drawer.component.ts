import {
    ChangeDetectionStrategy,
    Component,
    OnDestroy,
    TemplateRef,
    ViewChild,
    ViewContainerRef,
    inject,
    signal,
} from '@angular/core';
import { A11yModule } from '@angular/cdk/a11y';
import { Overlay, OverlayModule, OverlayRef } from '@angular/cdk/overlay';
import { TemplatePortal } from '@angular/cdk/portal';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink, RouterLinkActive } from '@angular/router';

interface SettingsLink {
    readonly id: string;
    readonly title: string;
    readonly icon: string;
    readonly link: string;
}

/**
 * Toolbar "cog" that opens the Settings drawer — a right-side, full-height slide-out listing the
 * platform admin/config pages (moved out of the left nav so the tree stays focused on Operations +
 * Platform). Built on the shared CDK overlay (same primitive as {@link NotificationBellComponent}),
 * with a global right-edge position; a dark backdrop + Esc close it and the focus is trapped in the
 * panel while open. Selecting a link navigates and closes. Container component — no HTTP, pure nav.
 */
@Component({
    selector: 'inspecto-settings-drawer',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [OverlayModule, A11yModule, MatButtonModule, MatIconModule, MatTooltipModule, RouterLink, RouterLinkActive],
    template: `
        <button
            mat-icon-button
            type="button"
            matTooltip="Settings"
            aria-label="Open settings"
            [attr.aria-expanded]="open()"
            (click)="openDrawer()"
        >
            <mat-icon svgIcon="heroicons_outline:cog-8-tooth"></mat-icon>
        </button>

        <ng-template #panel>
            <div
                role="dialog"
                aria-label="Settings"
                cdkTrapFocus
                [cdkTrapFocusAutoCapture]="true"
                class="bg-card flex h-full w-80 max-w-[90vw] flex-col border-l shadow-lg"
            >
                <div class="flex h-16 flex-0 items-center justify-between border-b px-4">
                    <h2 class="text-lg font-semibold">Settings</h2>
                    <button mat-icon-button type="button" aria-label="Close settings" (click)="close()">
                        <mat-icon svgIcon="heroicons_outline:x-mark"></mat-icon>
                    </button>
                </div>
                <nav class="flex-auto overflow-y-auto py-2" aria-label="Settings">
                    @for (item of items; track item.id) {
                        <a
                            class="hover:bg-hover flex items-center gap-3 px-4 py-3"
                            [routerLink]="item.link"
                            routerLinkActive="bg-hover text-primary"
                            (click)="close()"
                        >
                            <mat-icon class="icon-size-5 text-hint" [svgIcon]="item.icon"></mat-icon>
                            <span>{{ item.title }}</span>
                        </a>
                    }
                </nav>
            </div>
        </ng-template>
    `,
})
export class SettingsDrawerComponent implements OnDestroy {
    @ViewChild('panel', { static: true }) private panel!: TemplateRef<unknown>;
    private overlay = inject(Overlay);
    private vcr = inject(ViewContainerRef);
    private ref?: OverlayRef;

    readonly open = signal(false);

    /** The former "Settings" nav group — one entry per admin/config page. */
    readonly items: readonly SettingsLink[] = [
        { id: 'menus',               title: 'Menus',          icon: 'heroicons_outline:bars-3',                 link: '/settings/menus' },
        { id: 'config',              title: 'Config',         icon: 'heroicons_outline:adjustments-horizontal', link: '/config' },
        { id: 'notification-center', title: 'Notifications',  icon: 'heroicons_outline:bell',                   link: '/notification-center' },
        { id: 'spaces',              title: 'Spaces',         icon: 'heroicons_outline:square-3-stack-3d',      link: '/spaces' },
        { id: 'model-settings',      title: 'Model Settings', icon: 'heroicons_outline:cpu-chip',               link: '/settings/models' },
        { id: 'icon-settings',       title: 'Processor Icons', icon: 'heroicons_outline:paint-brush',           link: '/settings/icons' },
        { id: 'map-settings',        title: 'Map Settings',   icon: 'heroicons_outline:map',                    link: '/settings/map' },
        { id: 'transfer',            title: 'Import & Export', icon: 'heroicons_outline:arrow-up-tray',         link: '/settings/transfer' },
        { id: 'design-system',       title: 'Design System',  icon: 'heroicons_outline:swatch',                 link: '/design' },
    ];

    openDrawer(): void {
        if (this.ref) return;
        this.ref = this.overlay.create({
            hasBackdrop: true,
            scrollStrategy: this.overlay.scrollStrategies.block(),
            positionStrategy: this.overlay.position().global().top('0').right('0'),
            height: '100%',
        });
        this.ref.attach(new TemplatePortal(this.panel, this.vcr));
        this.ref.backdropClick().subscribe(() => this.close());
        this.ref.keydownEvents().subscribe((e) => {
            if (e.key === 'Escape') this.close();
        });
        this.open.set(true);
    }

    close(): void {
        this.ref?.dispose();
        this.ref = undefined;
        this.open.set(false);
    }

    ngOnDestroy(): void {
        this.ref?.dispose();
    }
}

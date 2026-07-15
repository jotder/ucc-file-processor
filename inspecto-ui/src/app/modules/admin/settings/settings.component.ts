import { NgComponentOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal, Type, ViewEncapsulation } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatIconModule } from '@angular/material/icon';
import { ActivatedRoute, Router } from '@angular/router';

import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';

import { AccessComponent } from 'app/modules/admin/access/access.component';
import { ConfigComponent } from 'app/modules/admin/config/config.component';
import { DesignSystemComponent } from 'app/modules/admin/design-system/design-system.component';
import { IconSettingsComponent } from 'app/modules/admin/icon-settings/icon-settings.component';
import { MapSettingsComponent } from 'app/modules/admin/map-settings/map-settings.component';
import { ModelSettingsComponent } from 'app/modules/admin/model-settings/model-settings.component';
import { NotificationCenterComponent } from 'app/modules/admin/notification-center/notification-center.component';
import { SpacesComponent } from 'app/modules/admin/spaces/spaces.component';
import { TransferComponent } from 'app/modules/admin/transfer/transfer.component';

interface SettingsDrawer {
    readonly id: string;
    readonly title: string;
    readonly description: string;
    readonly icon: string;
    readonly component: Type<unknown>;
}

/**
 * Settings — a single page that gathers every platform admin/config surface into a master-detail layout:
 * a menu list of options on the left, and one **common panel** on the right that renders the currently
 * selected option's existing standalone component via {@link NgComponentOutlet}. Only the selected
 * component is instantiated at a time. The individual `/config`, `/spaces`, … routes still exist and back
 * these same components; this page just co-locates them.
 */
@Component({
    selector: 'app-settings',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [NgComponentOutlet, MatIconModule, InspectoEmptyStateComponent],
    templateUrl: './settings.component.html',
    encapsulation: ViewEncapsulation.None,
})
export class SettingsComponent {
    readonly drawers: readonly SettingsDrawer[] = [
        { id: 'config',        title: 'Config',          icon: 'heroicons_outline:adjustments-horizontal', description: 'Author and validate pipeline / job configuration.', component: ConfigComponent },
        { id: 'notifications', title: 'Notifications',   icon: 'heroicons_outline:bell',                   description: 'Delivery channels and notification preferences.',   component: NotificationCenterComponent },
        { id: 'spaces',        title: 'Spaces',          icon: 'heroicons_outline:square-3-stack-3d',      description: 'Create and manage isolated project spaces.',        component: SpacesComponent },
        { id: 'access',        title: 'Access',          icon: 'heroicons_outline:key',                    description: 'Choose what each lens shows — menus and functionalities.', component: AccessComponent },
        { id: 'models',        title: 'Model Settings',  icon: 'heroicons_outline:cpu-chip',               description: 'Choose the AI provider and per-tier models.',        component: ModelSettingsComponent },
        { id: 'icons',         title: 'Processor Icons', icon: 'heroicons_outline:paint-brush',            description: 'Assign icons to processor / component kinds.',       component: IconSettingsComponent },
        { id: 'map',           title: 'Map Settings',    icon: 'heroicons_outline:map',                    description: 'Basemap and geo-analysis defaults.',                 component: MapSettingsComponent },
        { id: 'transfer',      title: 'Import & Export', icon: 'heroicons_outline:arrow-up-tray',          description: 'Move configuration bundles in and out.',             component: TransferComponent },
        { id: 'design',        title: 'Design System',   icon: 'heroicons_outline:swatch',                 description: 'The in-app component gallery and design tokens.',     component: DesignSystemComponent },
    ];

    private route = inject(ActivatedRoute);
    private router = inject(Router);
    private destroyRef = inject(DestroyRef);

    /** The option whose content is shown in the common panel; `null` until one is picked.
     *  Driven by the `:section` route param (R5) — deep-linkable, Back works, refresh keeps it. */
    readonly selected = signal<SettingsDrawer | null>(null);

    constructor() {
        this.route.paramMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((p) => {
            const id = p.get('section');
            this.selected.set(this.drawers.find((d) => d.id === id) ?? null);
        });
    }

    /** Selecting a section navigates — the paramMap subscription above applies it. */
    select(drawer: SettingsDrawer): void {
        this.router.navigate(['/settings', drawer.id]);
    }
}

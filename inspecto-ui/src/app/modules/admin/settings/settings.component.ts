import { NgComponentOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, Type, ViewEncapsulation } from '@angular/core';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatIconModule } from '@angular/material/icon';

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
 * Settings — a single page that gathers every platform admin/config surface into a stack of expandable
 * drawers (one per option). Each drawer lazily renders the option's existing standalone component via
 * {@link NgComponentOutlet} inside `matExpansionPanelContent`, so a component is only instantiated the
 * first time its drawer is opened. The individual `/config`, `/spaces`, … routes still exist and back
 * these same components; this page just co-locates them.
 */
@Component({
    selector: 'app-settings',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [NgComponentOutlet, MatExpansionModule, MatIconModule],
    templateUrl: './settings.component.html',
    encapsulation: ViewEncapsulation.None,
})
export class SettingsComponent {
    readonly drawers: readonly SettingsDrawer[] = [
        { id: 'config',        title: 'Config',          icon: 'heroicons_outline:adjustments-horizontal', description: 'Author and validate pipeline / job configuration.', component: ConfigComponent },
        { id: 'notifications', title: 'Notifications',   icon: 'heroicons_outline:bell',                   description: 'Delivery channels and notification preferences.',   component: NotificationCenterComponent },
        { id: 'spaces',        title: 'Spaces',          icon: 'heroicons_outline:square-3-stack-3d',      description: 'Create and manage isolated project spaces.',        component: SpacesComponent },
        { id: 'models',        title: 'Model Settings',  icon: 'heroicons_outline:cpu-chip',               description: 'Choose the AI provider and per-tier models.',        component: ModelSettingsComponent },
        { id: 'icons',         title: 'Processor Icons', icon: 'heroicons_outline:paint-brush',            description: 'Assign icons to processor / component kinds.',       component: IconSettingsComponent },
        { id: 'map',           title: 'Map Settings',    icon: 'heroicons_outline:map',                    description: 'Basemap and geo-analysis defaults.',                 component: MapSettingsComponent },
        { id: 'transfer',      title: 'Import & Export', icon: 'heroicons_outline:arrow-up-tray',          description: 'Move configuration bundles in and out.',             component: TransferComponent },
        { id: 'design',        title: 'Design System',   icon: 'heroicons_outline:swatch',                 description: 'The in-app component gallery and design tokens.',     component: DesignSystemComponent },
    ];
}

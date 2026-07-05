import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { ConditionGroup } from 'app/inspecto/query';
import { Widget } from '../widgets/widget-types';
import { Dataset } from '../datasets/dataset-types';
import { DrillEvent, WidgetHostComponent } from '../widgets/widget-host.component';

/**
 * One dashboard tile — a thin wrapper around the shared {@link WidgetHostComponent} (the one render path,
 * also used by the widget gallery's thumbnails and the standalone view route), passing the dashboard's
 * cross-filter through and re-emitting drill-down clicks. The host + dataset are already loaded by the
 * dashboard editor, so this stays in pre-loaded mode — no extra fetch per tile.
 */
@Component({
    selector: 'app-dashboard-tile',
    standalone: true,
    imports: [WidgetHostComponent],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `<app-widget-host [widget]="widget()" [dataset]="dataset()" [filter]="filter()" (drill)="drill.emit($event)" />`,
})
export class DashboardTileComponent {
    readonly widget = input.required<Widget>();
    /** Absent for view-bound widgets (geo-map / link-analysis) — their saved view is the binding. */
    readonly dataset = input<Dataset | undefined>(undefined);
    readonly filter = input<ConditionGroup | null>(null);
    readonly drill = output<DrillEvent>();
}

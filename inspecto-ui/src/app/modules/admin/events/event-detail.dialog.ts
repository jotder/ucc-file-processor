import { Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { EventRow } from 'app/inspecto/api';
import { StatusBadgeComponent } from 'app/inspecto/components/status-badge.component';
import { fmtDateTime } from 'app/inspecto/grid';

/** A filter the pane should apply after the dialog closes (drill-down by correlation id or type). */
export interface EventDrilldown {
    correlationId?: string;
    type?: string;
}

/**
 * Event detail — every field of one {@link EventRow} plus its structured `attributes` bag as a key/value
 * table. The "View related" / "Filter by type" actions close the dialog with an {@link EventDrilldown} the
 * Events pane uses to re-run the search scoped to that correlation id or event type.
 */
@Component({
    selector: 'app-event-detail-dialog',
    standalone: true,
    imports: [MatDialogModule, MatButtonModule, MatIconModule, MatTooltipModule, StatusBadgeComponent],
    template: `
        <h2 mat-dialog-title class="flex items-center gap-3">
            <inspecto-status-badge [value]="data.level" />
            <span class="font-mono text-base">{{ data.type }}</span>
        </h2>
        <mat-dialog-content class="space-y-4">
            <div class="text-secondary text-sm">{{ data.message || '— (no message)' }}</div>

            <div class="grid grid-cols-1 gap-x-6 gap-y-2 text-sm sm:grid-cols-2">
                <div><span class="text-secondary font-medium">time:</span> {{ fmt(data.ts) }}</div>
                <div><span class="text-secondary font-medium">event id:</span> <span class="font-mono text-xs">{{ data.eventId }}</span></div>
                <div><span class="text-secondary font-medium">source:</span> <span class="font-mono text-xs">{{ data.source || '—' }}</span></div>
                <div><span class="text-secondary font-medium">pipeline:</span> {{ data.pipeline ?? '— (service-wide)' }}</div>
                <div class="sm:col-span-2">
                    <span class="text-secondary font-medium">correlation id:</span>
                    @if (data.correlationId) {
                        <span class="font-mono text-xs">{{ data.correlationId }}</span>
                    } @else {
                        <span class="text-secondary">—</span>
                    }
                </div>
            </div>

            <!-- Structured attributes -->
            <div>
                <div class="text-secondary mb-1 text-sm font-medium">attributes</div>
                @if (attrKeys.length) {
                    <table class="w-full text-sm">
                        <tbody>
                            @for (k of attrKeys; track k) {
                                <tr class="border-b border-gray-100 dark:border-gray-800">
                                    <td class="text-secondary py-1 pr-4 align-top font-mono text-xs">{{ k }}</td>
                                    <td class="py-1 font-mono text-xs break-all">{{ data.attributes[k] }}</td>
                                </tr>
                            }
                        </tbody>
                    </table>
                } @else {
                    <div class="text-secondary text-sm">— (none)</div>
                }
            </div>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            @if (data.correlationId) {
                <button mat-stroked-button (click)="drillCorrelation()" matTooltip="Show all events with this correlation id">
                    <mat-icon class="icon-size-5" svgIcon="heroicons_outline:link"></mat-icon>
                    <span class="ml-2">View related</span>
                </button>
            }
            <button mat-stroked-button (click)="drillType()" matTooltip="Filter the list to this event type">
                <mat-icon class="icon-size-5" svgIcon="heroicons_outline:funnel"></mat-icon>
                <span class="ml-2">Filter by type</span>
            </button>
            <button mat-button mat-dialog-close>Close</button>
        </mat-dialog-actions>
    `,
})
export class EventDetailDialog {
    readonly data = inject<EventRow>(MAT_DIALOG_DATA);
    private ref = inject(MatDialogRef<EventDetailDialog, EventDrilldown>);

    readonly attrKeys = Object.keys(this.data.attributes ?? {});

    fmt(ts: number): string {
        return fmtDateTime(ts);
    }

    drillCorrelation(): void {
        if (this.data.correlationId) this.ref.close({ correlationId: this.data.correlationId });
    }

    drillType(): void {
        this.ref.close({ type: this.data.type });
    }
}

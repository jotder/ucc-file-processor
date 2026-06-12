import { ChangeDetectionStrategy, Component, EventEmitter, Input, Output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';

/**
 * Reusable empty-state panel: icon + message, optional title and action button.
 * Use for "nothing here yet" and recoverable-error placeholders instead of
 * ad-hoc dashed boxes or ag-Grid's default "No Rows" overlay.
 */
@Component({
    selector: 'inspecto-empty-state',
    standalone: true,
    imports: [MatButtonModule, MatIconModule],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <div
            class="text-secondary flex flex-col items-center gap-3 rounded-2xl border-2 border-dashed border-gray-300 p-8 text-center dark:border-gray-600"
        >
            <mat-icon class="icon-size-10" [svgIcon]="icon"></mat-icon>
            @if (title) {
                <div class="text-default text-lg font-medium">{{ title }}</div>
            }
            <div>{{ message }}</div>
            @if (actionLabel) {
                <button mat-stroked-button (click)="action.emit()">{{ actionLabel }}</button>
            }
        </div>
    `,
})
export class InspectoEmptyStateComponent {
    /** Material symbol shown above the message. */
    @Input() icon = 'heroicons_outline:inbox';
    /** Optional emphasized headline. */
    @Input() title = '';
    /** Explanatory text — what's empty and (ideally) how it gets filled. */
    @Input({ required: true }) message = '';
    /** When set, renders a stroked button that emits `action` on click. */
    @Input() actionLabel = '';
    @Output() action = new EventEmitter<void>();
}

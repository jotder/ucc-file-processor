import { booleanAttribute, ChangeDetectionStrategy, Component, EventEmitter, Input, Output } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';

/** Chip surface: a hollow bordered pill (`outline`) or a filled tint (`soft`). */
export type ChipVariant = 'outline' | 'soft';
/** Chip emphasis: `neutral` grey, or `primary` for a selected/active token. */
export type ChipTone = 'neutral' | 'primary';

const BASE = 'inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs';

const TONE_CLASSES: Record<ChipVariant, Record<ChipTone, string>> = {
    outline: {
        neutral: 'border border-gray-300 dark:border-gray-600',
        primary: 'border border-primary text-primary',
    },
    soft: {
        neutral: 'bg-gray-100 text-gray-700 dark:bg-gray-700 dark:text-gray-200',
        primary: 'bg-primary-100 text-primary-800 dark:bg-primary-900 dark:text-primary-200',
    },
};

/**
 * Small labelled pill — the shared primitive for tag / token / filter chips (widget tags &
 * type/tag toggles, the events correlation filter, query tokens, reconciliation match keys,
 * link-analysis summaries). Replaces the per-component hand-rolled `rounded-full … text-xs`
 * spans. Content is projected, so a leading `<mat-icon>` or `<span class="font-mono">` just
 * goes inside. Set `[removable]` to append an ✕ that emits `(removed)`.
 *
 * @example <inspecto-chip variant="soft">{{ tag }}</inspecto-chip>
 * @example <inspecto-chip [tone]="active() ? 'primary' : 'neutral'">{{ type }}</inspecto-chip>
 */
@Component({
    selector: 'inspecto-chip',
    standalone: true,
    imports: [MatIconModule],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <span [class]="classes">
            <ng-content />
            @if (removable) {
                <button
                    type="button"
                    class="-mr-0.5 ml-0.5 flex"
                    [attr.aria-label]="removeLabel"
                    (click)="removed.emit()"
                >
                    <mat-icon class="icon-size-3.5" svgIcon="heroicons_outline:x-mark" />
                </button>
            }
        </span>
    `,
})
export class ChipComponent {
    @Input() variant: ChipVariant = 'outline';
    @Input() tone: ChipTone = 'neutral';
    /** When true, renders a trailing ✕ button that emits {@link removed}. */
    @Input({ transform: booleanAttribute }) removable = false;
    /** Accessible label for the remove button. */
    @Input() removeLabel = 'Remove';

    @Output() readonly removed = new EventEmitter<void>();

    get classes(): string {
        return `${BASE} ${TONE_CLASSES[this.variant][this.tone]}`;
    }
}

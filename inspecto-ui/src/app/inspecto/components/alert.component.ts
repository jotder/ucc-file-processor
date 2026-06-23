import { ChangeDetectionStrategy, Component, Input } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';

/** Semantic banner tone — same four-tone vocabulary as the status palette. */
export type AlertVariant = 'info' | 'warning' | 'error' | 'success';

/**
 * Per-variant banner classes + default icon. The light `100/800` and dark `900/200` pairings match
 * {@link StatusBadgeComponent}'s palette so banners and pills read as the same design language, and are
 * the high-contrast (AA) pairs rather than the single-shade `text-*-600` that fails on tinted surfaces.
 * Class strings are literal so Tailwind's content scanner emits them.
 */
const VARIANTS: Record<AlertVariant, { classes: string; icon: string }> = {
    info: {
        classes: 'bg-blue-100 text-blue-800 dark:bg-blue-900 dark:text-blue-200',
        icon: 'heroicons_outline:information-circle',
    },
    warning: {
        classes: 'bg-amber-100 text-amber-800 dark:bg-amber-900 dark:text-amber-200',
        icon: 'heroicons_outline:exclamation-triangle',
    },
    error: {
        classes: 'bg-red-100 text-red-800 dark:bg-red-900 dark:text-red-200',
        icon: 'heroicons_outline:exclamation-circle',
    },
    success: {
        classes: 'bg-green-100 text-green-800 dark:bg-green-900 dark:text-green-200',
        icon: 'heroicons_outline:check-circle',
    },
};

/**
 * Inline contextual banner — icon + message in a tinted, scheme-aware panel. The shared home for
 * "writes disabled", "feature unavailable", "jar missing"-style notices, so features stop re-rolling
 * `bg-amber-100 …` boxes. The message is projected so callers can include `<code>`, links, etc.
 *
 * Distinct from `<inspecto-connectivity-banner>` (the always-mounted, app-wide offline/backend-down
 * strip) — this is per-screen and inline. Accessible: `role="status"` (info/success) or `role="alert"`
 * (warning/error) with a matching `aria-live`, so the notice is announced without hardcoding urgency.
 *
 * @example <inspecto-alert variant="warning" title="Read-only">Editing is disabled.</inspecto-alert>
 */
@Component({
    selector: 'inspecto-alert',
    standalone: true,
    imports: [MatIconModule],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <div
            [attr.role]="urgent ? 'alert' : 'status'"
            [attr.aria-live]="urgent ? 'assertive' : 'polite'"
            class="flex items-start gap-2 rounded-lg px-3 py-2 text-sm"
            [class]="tone.classes"
        >
            <mat-icon class="icon-size-5 mt-0.5 shrink-0" [svgIcon]="icon || tone.icon"></mat-icon>
            <div class="min-w-0 flex-auto">
                @if (title) {
                    <div class="font-semibold">{{ title }}</div>
                }
                <ng-content></ng-content>
            </div>
        </div>
    `,
})
export class InspectoAlertComponent {
    /** Semantic tone — drives color, default icon, and announce urgency. */
    @Input() variant: AlertVariant = 'info';
    /** Optional Material symbol override (defaults to the per-variant icon). */
    @Input() icon = '';
    /** Optional emphasized headline above the projected message. */
    @Input() title = '';

    get tone(): { classes: string; icon: string } {
        return VARIANTS[this.variant];
    }

    /** warning/error announce assertively as alerts; info/success are polite status messages. */
    get urgent(): boolean {
        return this.variant === 'warning' || this.variant === 'error';
    }
}

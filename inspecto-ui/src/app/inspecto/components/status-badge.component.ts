import { ChangeDetectionStrategy, Component, Input } from '@angular/core';

/**
 * The four semantic status tones (plus neutral) shared across Inspecto. This is the SINGLE
 * source of truth for status/severity/level colors — every event level, severity, reachability
 * flag and batch outcome maps to one of these. Do NOT hand-roll status colors in components or
 * cell renderers; classify with {@link statusTone} / {@link statusBadgeClasses} or render an
 * `<inspecto-status-badge>` so the palette stays consistent and WCAG-AA in both schemes.
 */
export type StatusTone = 'error' | 'warning' | 'info' | 'success' | 'neutral';

/**
 * Tailwind class set per tone. The light `100/800` and dark `900/200` pairings are deliberately
 * the high-contrast ones (≈6–8:1 in both schemes) rather than the single-shade `text-*-600` that
 * fails AA on cards. Class strings are literal here so Tailwind's content scanner emits them even
 * when only referenced via the {@link statusBadgeClasses} string builder (e.g. ag-Grid renderers).
 */
const TONE_CLASSES: Record<StatusTone, string> = {
    error: 'bg-red-100 text-red-800 dark:bg-red-900 dark:text-red-200',
    warning: 'bg-amber-100 text-amber-800 dark:bg-amber-900 dark:text-amber-200',
    info: 'bg-blue-100 text-blue-800 dark:bg-blue-900 dark:text-blue-200',
    success: 'bg-green-100 text-green-800 dark:bg-green-900 dark:text-green-200',
    neutral: 'bg-gray-100 text-gray-700 dark:bg-gray-700 dark:text-gray-200',
};

/** Shared pill geometry (kept here so the component and string-renderer paths render identically). */
export const STATUS_BADGE_BASE = 'inline-block shrink-0 rounded px-2 py-0.5 text-xs font-bold';

/** Map a free-form status / level / severity / outcome token to a semantic tone. */
export function statusTone(value: string | null | undefined): StatusTone {
    switch ((value ?? '').toUpperCase()) {
        case 'ERROR':
        case 'CRITICAL':
        case 'FATAL':
        case 'FAIL':
        case 'FAILED':
        case 'ERRORED':
        case 'REJECTED':
        case 'UNREACHABLE':
        case 'DENIED':
        case 'REVOKED':
            return 'error';
        case 'WARN':
        case 'WARNING':
        case 'HIGH':
        case 'MAJOR':
        case 'DIAGNOSING':
        case 'PAUSED':
        case 'QUARANTINE':
        case 'QUARANTINED':
        case 'EXPIRED':
            return 'warning';
        case 'INFO':
        case 'OPEN':
        case 'IDENTIFIED':
        case 'MINOR':
        case 'PENDING':
        case 'PROCESSING':
        case 'REQUESTED':
            return 'info';
        case 'SUCCESS':
        case 'SUCCEEDED':
        case 'OK':
        case 'READY':
        case 'HEALTHY':
        case 'REACHABLE':
        case 'RESOLVED':
        case 'CLOSED':
        case 'ACTIVE':
            return 'success';
        default:
            return 'neutral';
    }
}

/** Tailwind color classes (no geometry) for a status token — for ag-Grid/innerHTML renderers. */
export function statusBadgeClasses(value: string | null | undefined): string {
    return TONE_CLASSES[statusTone(value)];
}

/** Full class string (geometry + color) for a status token — for raw HTML renderers. */
export function statusBadgeHtml(value: string | null | undefined): string {
    const text = value ?? '';
    return `<span class="${STATUS_BADGE_BASE} ${statusBadgeClasses(value)}">${text}</span>`;
}

/**
 * Status pill — renders `value` (or `label`) with the shared semantic palette. Replaces the
 * per-component `levelClass()` helpers and hardcoded badge styles.
 *
 * @example <inspecto-status-badge [value]="event.level" />
 */
@Component({
    selector: 'inspecto-status-badge',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `<span [class]="base + ' ' + classes">{{ label || value }}</span>`,
})
export class StatusBadgeComponent {
    /** Raw status/level/severity token (case-insensitive); also the default visible text. */
    @Input({ required: true }) value: string | null | undefined = '';
    /** Optional display text override (defaults to `value`). */
    @Input() label = '';

    readonly base = STATUS_BADGE_BASE;

    get classes(): string {
        return statusBadgeClasses(this.value);
    }
}

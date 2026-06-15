import { ChangeDetectionStrategy, Component, Input } from '@angular/core';

/**
 * Skeleton placeholder — pulsing blocks that mirror the shape of content while it loads.
 *
 * Inspecto loading convention:
 *  - ag-Grid data tables show their built-in loader via `[loading]="loading"` (+ the shared
 *    {@link noRowsOverlay} for the empty case).
 *  - Page / card / form content uses `<inspecto-skeleton>` shaped like the real layout, instead
 *    of a centered spinner+text or popping content in with no feedback.
 *
 * Colors come from the gamma scheme tokens so it works in light and dark, and the pulse respects
 * `prefers-reduced-motion`. Decorative only — marked `aria-hidden`; put `aria-busy="true"` on the
 * region that is loading if you need to announce it.
 *
 * @example <inspecto-skeleton width="40%" height="0.875rem" />        <!-- a label -->
 * @example <inspecto-skeleton [lines]="4" />                          <!-- a paragraph -->
 * @example <inspecto-skeleton height="12rem" />                       <!-- a chart/block -->
 */
@Component({
    selector: 'inspecto-skeleton',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    host: { 'aria-hidden': 'true' },
    template: `
        @for (l of lineArray; track $index) {
            <span
                class="inspecto-skeleton-block"
                [style.height]="height"
                [style.width]="lines === 1 ? width : ($last ? lastLineWidth : '100%')"
            ></span>
        }
    `,
    styles: [
        `
            :host {
                display: block;
            }
            .inspecto-skeleton-block {
                display: block;
                border-radius: 0.375rem;
                background-color: rgba(var(--gamma-text-secondary-rgb), 0.18);
                animation: inspecto-skeleton-pulse 1.5s ease-in-out infinite;
            }
            .inspecto-skeleton-block:not(:first-child) {
                margin-top: 0.5rem;
            }
            @keyframes inspecto-skeleton-pulse {
                0%,
                100% {
                    opacity: 1;
                }
                50% {
                    opacity: 0.45;
                }
            }
            @media (prefers-reduced-motion: reduce) {
                .inspecto-skeleton-block {
                    animation: none;
                }
            }
        `,
    ],
})
export class InspectoSkeletonComponent {
    /** Number of stacked bars. 1 = a single block sized by `width`/`height`. */
    @Input() lines = 1;
    /** Width when `lines === 1` (multi-line bars are full width except the last). */
    @Input() width = '100%';
    /** Height of each bar. */
    @Input() height = '1rem';
    /** Width of the final bar in multi-line mode (gives a ragged paragraph edge). */
    @Input() lastLineWidth = '60%';

    get lineArray(): unknown[] {
        return Array.from({ length: Math.max(1, this.lines) });
    }
}

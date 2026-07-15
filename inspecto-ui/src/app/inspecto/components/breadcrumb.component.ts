import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { RouterLink } from '@angular/router';

/**
 * **Breadcrumb strip** for routed detail pages (ui-design-review R5 / angular-ui skill §4): the
 * one-level `list → id` trail every detail carries. Consolidates the hand-rolled copies that lived
 * in object-detail / run-detail / job-detail.
 *
 * ```html
 * <inspecto-breadcrumb [listLink]="['/incidents']" listLabel="Incidents" [current]="id" />
 * ```
 */
@Component({
    selector: 'inspecto-breadcrumb',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [RouterLink],
    template: `
        <nav class="text-secondary mb-1 flex items-center gap-1 text-xs" aria-label="Breadcrumb">
            <a class="hover:underline" [routerLink]="listLink()">{{ listLabel() }}</a>
            <span aria-hidden="true">/</span>
            <span class="truncate font-mono">{{ current() }}</span>
        </nav>
    `,
})
export class InspectoBreadcrumbComponent {
    /** Router link back to the list (e.g. `['/incidents']`). */
    readonly listLink = input.required<string | readonly string[]>();
    readonly listLabel = input.required<string>();
    /** The current item's id / name (the non-link tail). */
    readonly current = input.required<string>();
}

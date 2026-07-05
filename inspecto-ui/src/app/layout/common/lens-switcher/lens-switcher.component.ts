import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Lens, LensService } from 'app/inspecto/api';

/**
 * Header control ("View as") that shows the active persona lens and switches between them —
 * mirrors {@link SpaceSwitcherComponent}'s shape, minus the reload: a lens is purely a UI-side
 * annotation (visibility/read-only, never a permission), so components just re-render reactively
 * off {@link LensService.currentLens} / {@link LensService.readOnly}.
 */
@Component({
    selector: 'inspecto-lens-switcher',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [MatButtonModule, MatIconModule, MatMenuModule, MatTooltipModule],
    template: `
        <button
            mat-stroked-button
            [matMenuTriggerFor]="menu"
            class="min-w-0"
            matTooltip="View as"
            aria-label="Switch lens"
        >
            <mat-icon class="icon-size-5" [svgIcon]="iconFor(lens.currentLens())"></mat-icon>
            <!-- label collapses to the icon on phones so the header row fits 375px (Wave 5 P2) -->
            <span class="ml-2 hidden sm:inline">{{ labelFor(lens.currentLens()) }}</span>
            <mat-icon class="icon-size-5" svgIcon="heroicons_outline:chevron-down"></mat-icon>
        </button>
        <mat-menu #menu="matMenu">
            @for (l of lenses; track l.id) {
                <button
                    mat-menu-item
                    (click)="lens.selectLens(l.id)"
                    [attr.aria-current]="l.id === lens.currentLens() ? 'true' : null"
                >
                    <mat-icon [svgIcon]="l.id === lens.currentLens() ? 'heroicons_outline:check-circle' : l.icon"></mat-icon>
                    <span>{{ l.label }}</span>
                </button>
            }
        </mat-menu>
    `,
})
export class LensSwitcherComponent {
    readonly lens = inject(LensService);
    readonly lenses = LensService.LENSES;

    iconFor(id: Lens): string {
        return this.lenses.find((l) => l.id === id)?.icon ?? this.lenses[0].icon;
    }
    labelFor(id: Lens): string {
        return this.lenses.find((l) => l.id === id)?.label ?? id;
    }
}

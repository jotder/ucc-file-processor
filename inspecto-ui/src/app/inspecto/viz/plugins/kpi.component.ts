import { ChangeDetectionStrategy, Component, computed, input, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';

type KpiMode = 'mini' | 'standard' | 'max';

/**
 * KPI tile — the `kpi` plugin's component escape hatch. One headline metric value with **3 in-place render
 * modes** (mini → standard → max) toggled by a single button, per the Studio design (chosen over a separate
 * KPI builder). Mounted by `viz-render` via `NgComponentOutlet`. No hardcoded colours (text tone only).
 */
@Component({
    selector: 'inspecto-kpi',
    standalone: true,
    imports: [MatButtonModule, MatIconModule],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <div class="bg-card relative flex h-full flex-col justify-center rounded-2xl p-4 shadow" [class.items-center]="mode() !== 'standard'">
            <button
                mat-icon-button
                class="absolute right-1 top-1"
                (click)="cycle()"
                [attr.aria-label]="'KPI size: ' + mode() + ' (click to change)'"
            >
                <mat-icon class="icon-size-4" svgIcon="heroicons_outline:arrows-pointing-out"></mat-icon>
            </button>
            <div class="text-secondary text-xs font-semibold uppercase tracking-wider">{{ label() }}</div>
            <div
                class="font-extrabold leading-none"
                [class.text-2xl]="mode() === 'mini'"
                [class.text-4xl]="mode() === 'standard'"
                [class.text-6xl]="mode() === 'max'"
            >
                {{ display() }}
            </div>
            @if (mode() !== 'mini') {
                <div class="text-secondary mt-1 text-xs">offline aggregate</div>
            }
        </div>
    `,
})
export class KpiComponent {
    readonly value = input<number>(0);
    readonly label = input<string>('Value');
    readonly mode = signal<KpiMode>('standard');

    readonly display = computed(() => new Intl.NumberFormat().format(this.value()));

    cycle(): void {
        const order: KpiMode[] = ['mini', 'standard', 'max'];
        this.mode.set(order[(order.indexOf(this.mode()) + 1) % order.length]);
    }
}

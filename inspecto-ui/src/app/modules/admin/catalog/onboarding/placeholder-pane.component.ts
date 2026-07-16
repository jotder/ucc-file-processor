import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { OnboardingStateService } from './onboarding-state.service';

/**
 * Stand-in pane for the stages whose guided editors land in a later phase (Schema & Mapping,
 * Enrichment, Keys & Load, Dataset & Go-live). The rail shows the whole journey from day one;
 * this pane is honest about what is guided today and where the block is authored meanwhile.
 */
@Component({
    selector: 'app-onboarding-placeholder-pane',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [InspectoEmptyStateComponent],
    template: `
        <inspecto-empty-state
            icon="heroicons_outline:wrench-screwdriver"
            [message]="message()"
        />
        <p class="text-secondary mx-auto mt-2 max-w-lg text-center text-sm">
            Until then this stage's config block is authored in the pipeline TOON directly
            (Settings ▸ Config); the readiness chip on the left already reflects it.
        </p>
    `,
})
export class OnboardingPlaceholderPaneComponent {
    protected readonly state = inject(OnboardingStateService);

    readonly message = computed(() => {
        const stage = this.state.stages().find((s) => s.id === this.state.activeStageId());
        return `${stage?.label ?? 'This stage'} — the guided editor for this stage arrives in a later update.`;
    });
}

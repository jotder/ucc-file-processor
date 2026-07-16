import { NgComponentOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, Type, computed, effect, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ActivatedRoute, Router } from '@angular/router';
import { ToastrService } from 'ngx-toastr';
import { LensService, apiErrorMessage } from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { StatusBadgeComponent } from 'app/inspecto/components/status-badge.component';
import { InspectoBreadcrumbComponent } from 'app/inspecto/components/breadcrumb.component';
import { InspectoSplitDirective } from 'app/inspecto/components/split.directive';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { OnboardingCollectionPaneComponent } from './collection-pane.component';
import { OnboardingParsingPaneComponent } from './parsing-pane.component';
import { OnboardingPlaceholderPaneComponent } from './placeholder-pane.component';
import { OnboardingPublishPaneComponent } from './publish-pane.component';
import { OnboardingSamplePanelComponent } from './sample-panel.component';
import { OnboardingSchemaMappingPaneComponent } from './schema-mapping-pane.component';
import { OnboardingStage, OnboardingStageId, OnboardingStateService } from './onboarding-state.service';

/**
 * Stream/Reference onboarding shell (`/catalog/onboard/:name/:stage?`) — a stage RAIL over the
 * server-held pipeline draft, not a locked stepper: the rail mirrors the data path (Collect →
 * Parse → Shape → Publish), every stage is jumpable, readiness is computed from the config
 * blocks, and the whole session is resumable because the draft IS the server state (D3).
 * Opening without a stage lands on the first incomplete one. The right panel threads ONE
 * captured sample through the stages (§4.3).
 */
@Component({
    selector: 'app-onboarding-shell',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    providers: [OnboardingStateService],
    imports: [
        NgComponentOutlet,
        MatButtonModule,
        MatIconModule,
        MatProgressSpinnerModule,
        MatTooltipModule,
        InspectoAlertComponent,
        InspectoEmptyStateComponent,
        InspectoBreadcrumbComponent,
        StatusBadgeComponent,
        InspectoSplitDirective,
        OnboardingSamplePanelComponent,
    ],
    templateUrl: './onboarding-shell.component.html',
})
export class OnboardingShellComponent {
    protected readonly state = inject(OnboardingStateService);
    protected readonly lens = inject(LensService);
    private route = inject(ActivatedRoute);
    private router = inject(Router);
    private confirm = inject(InspectoConfirmService);
    private toastr = inject(ToastrService);
    private destroyRef = inject(DestroyRef);

    /** The `:stage` URL param (null = land on the first incomplete stage once loaded). */
    private readonly stageParam = signal<string | null>(null);
    /** Landing happens ONCE per opened draft — a stage save must never yank the user elsewhere. */
    private landed = false;

    readonly activeStage = computed<OnboardingStage>(() => {
        const stages = this.state.stages();
        return stages.find((s) => s.id === this.state.activeStageId()) ?? stages[0];
    });

    readonly activeComponent = computed<Type<unknown>>(() => {
        switch (this.activeStage().id) {
            case 'collection':
                return OnboardingCollectionPaneComponent;
            case 'parsing':
                return OnboardingParsingPaneComponent;
            case 'schema':
                return OnboardingSchemaMappingPaneComponent;
            case 'publish':
                return OnboardingPublishPaneComponent;
            default:
                return OnboardingPlaceholderPaneComponent;
        }
    });

    constructor() {
        this.route.paramMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((p) => {
            const name = p.get('name') ?? '';
            const stage = p.get('stage');
            this.stageParam.set(stage);
            if (name && name !== this.state.name()) {
                this.landed = false;
                this.state.load(name);
            }
            if (stage && this.isStage(stage)) this.state.activeStageId.set(stage);
        });
        // No :stage in the URL → land on the first incomplete stage ONCE the draft has loaded
        // (once only — later config saves must not re-run the landing and move the user).
        effect(() => {
            const cfg = this.state.config();
            if (!cfg || this.stageParam() || this.landed) return;
            this.landed = true;
            this.state.activeStageId.set(this.state.firstOpenStage());
        });
    }

    private isStage(id: string): id is OnboardingStageId {
        return this.state.stages().some((s) => s.id === id);
    }

    /** Rail click: guarded by the active pane's unsaved changes; the URL is the source of truth. */
    async select(stage: OnboardingStage): Promise<void> {
        if (stage.id === this.state.activeStageId()) return;
        if (this.state.isDirty()) {
            const ok = await this.confirm.confirm(
                'This stage has unsaved changes — switch anyway and discard them?',
                'Unsaved changes',
            );
            if (!ok) return;
        }
        this.router.navigate(['/catalog', 'onboard', this.state.name(), stage.id]);
    }

    /** Route CanDeactivate — same guard when leaving the shell entirely. */
    canLeave(): Promise<boolean> | boolean {
        if (!this.state.isDirty()) return true;
        return this.confirm.confirm('Leave onboarding and discard the unsaved stage changes?', 'Unsaved changes');
    }

    async discard(): Promise<void> {
        if (!this.lens.canAuthorWorkbench() || this.state.active()) return;
        const name = this.state.name();
        const ok = await this.confirm.confirmDestructive(
            `Delete the draft "${name}" and its config file? This cannot be undone.`,
            { title: 'Discard draft', confirmText: 'Discard draft' },
        );
        if (!ok) return;
        this.state.discardDraft().subscribe({
            next: () => {
                this.toastr.success(`Draft "${name}" discarded`);
                this.router.navigate(['/catalog']);
            },
            error: (e) => this.toastr.error(apiErrorMessage(e, 'Could not discard the draft.')),
        });
    }
}

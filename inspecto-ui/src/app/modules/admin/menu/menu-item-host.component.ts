import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute } from '@angular/router';
import { map } from 'rxjs';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { MenuService } from 'app/inspecto/menu';
import { MenuArtifactComponent } from './menu-artifact.component';

/**
 * Dynamic host for a Menu item — the single parameterized route (`/w/:nodeId`) every custom menu leaf
 * links to. Resolves the node from {@link MenuService} and renders its binding via the shared
 * {@link MenuArtifactComponent}. An unknown node falls back to a not-found empty state. See
 * docs/superpower/menu-builder-plan.md (M3).
 */
@Component({
    selector: 'app-menu-item-host',
    standalone: true,
    imports: [InspectoEmptyStateComponent, MenuArtifactComponent],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <div class="flex min-w-0 flex-auto flex-col p-6 md:p-8">
            @if (node(); as n) {
                <h1 class="mb-4 text-2xl font-extrabold leading-tight tracking-tight">{{ n.title }}</h1>
                <app-menu-artifact
                    [binding]="n.binding"
                    emptyMessage="This menu is a group — pick one of its items, or link a report to it in the Menu Builder."
                />
            } @else {
                <inspecto-empty-state
                    icon="heroicons_outline:question-mark-circle"
                    title="Menu item not found"
                    message="This link points to a menu item that no longer exists."
                />
            }
        </div>
    `,
})
export class MenuItemHostComponent {
    private readonly route = inject(ActivatedRoute);
    private readonly menu = inject(MenuService);

    private readonly nodeId = toSignal(this.route.paramMap.pipe(map((p) => p.get('nodeId') ?? '')), {
        initialValue: '',
    });

    readonly node = computed(() => this.menu.find(this.nodeId()));
}

import { Routes, UrlSegment } from '@angular/router';
import { CatalogComponent } from 'app/modules/admin/catalog/catalog.component';
import type { OnboardingShellComponent } from './onboarding/onboarding-shell.component';
// Side-effect: register Studio's ComponentKinds on the unified component model when Catalog loads.
import 'app/modules/admin/studio/datasets/dataset.kind';

export default [
    {
        path: '',
        component: CatalogComponent,
    },
    // Datasets are a data asset (Catalog's home); component files stay under studio/datasets/.
    { path: 'datasets', loadChildren: () => import('app/modules/admin/studio/datasets/datasets.routes') },
    // Stream/Reference onboarding — ONE matcher for /onboard/:name and /onboard/:name/:stage
    // (same config ⇒ the shell survives stage navigation; detail-over-list idiom, R5).
    {
        matcher: (segments: UrlSegment[]) => {
            if (segments.length >= 2 && segments.length <= 3 && segments[0].path === 'onboard') {
                const posParams: Record<string, UrlSegment> = { name: segments[1] };
                if (segments.length === 3) posParams['stage'] = segments[2];
                return { consumed: segments, posParams };
            }
            return null;
        },
        loadComponent: () =>
            import('./onboarding/onboarding-shell.component').then((m) => m.OnboardingShellComponent),
        canDeactivate: [(cmp: OnboardingShellComponent) => cmp.canLeave()],
    },
] as Routes;

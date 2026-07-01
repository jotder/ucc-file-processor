import { Routes } from '@angular/router';
// Side-effect: register Studio's ComponentKinds on the unified component model when Studio loads.
import './datasets/dataset.kind';

/**
 * Studio shell routes. Nested `loadChildren` so adding widgets/dashboards (P1/P2) never re-touches
 * `app.routes.ts`. Datasets is the landing surface; widgets/dashboards arrive in later phases.
 */
export default [
    { path: '', pathMatch: 'full', redirectTo: 'datasets' },
    { path: 'datasets', loadChildren: () => import('./datasets/datasets.routes') },
    { path: 'widgets', loadChildren: () => import('./widgets/widgets.routes') },
    { path: 'dashboards', loadChildren: () => import('./dashboards/dashboards.routes') },
] as Routes;

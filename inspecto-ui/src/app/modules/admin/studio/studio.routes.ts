import { Routes } from '@angular/router';

/**
 * Studio shell routes. Nested `loadChildren` so adding widgets/dashboards (P1/P2) never re-touches
 * `app.routes.ts`. Datasets moved to `/catalog/datasets` (Phase B.2, it's a Catalog data asset);
 * this redirect keeps the old `/studio/datasets` bookmark/link working.
 */
export default [
    { path: '', pathMatch: 'full', redirectTo: '/catalog/datasets' },
    { path: 'datasets', redirectTo: '/catalog/datasets' },
    { path: 'queries', loadChildren: () => import('./queries/queries.routes') },
    { path: 'widgets', loadChildren: () => import('./widgets/widgets.routes') },
    { path: 'dashboards', loadChildren: () => import('./dashboards/dashboards.routes') },
    { path: 'link-analysis', loadChildren: () => import('./link-analysis/link-analysis.routes') },
    { path: 'geo-map', loadChildren: () => import('./geo-map/geo-map.routes') },
] as Routes;

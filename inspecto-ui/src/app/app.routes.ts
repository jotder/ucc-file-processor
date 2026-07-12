import { inject } from '@angular/core';
import { Route } from '@angular/router';
import { initialDataResolver } from 'app/app.resolvers';
import { authGuard, Lens, LensService } from 'app/inspecto/api';
import { LayoutComponent } from 'app/layout/layout.component';

/** The default landing route per persona lens (W4 — plan §1's per-lens home page). Each target is the
 *  first-listed pane for that lens in the persona→surface map: Business raises KPI/Report requirements,
 *  Builder authors pipelines, Ops monitors events. Nav itself is never filtered — every lens can reach
 *  every route — this only decides where "/" lands. */
export const LENS_HOME: Record<Lens, string> = {
    business: 'kpi-reports',
    builder: 'pipelines',
    ops: 'events',
};

/** Route-level redirect target for `''` — reads the persisted lens (no route data/resolver needed since
 *  `LensService` restores synchronously from `localStorage` in its constructor). */
export function lensHomeRedirect(): string {
    return LENS_HOME[inject(LensService).currentLens()];
}

// @formatter:off
/* eslint-disable max-len */
/* eslint-disable @typescript-eslint/explicit-function-return-type */
export const appRoutes: Route[] = [

    // Default landing route — per-lens home page (W4).
    { path: '', pathMatch: 'full', redirectTo: lensHomeRedirect },

    // Standard-edition OIDC guest routes (W6d) — shown only when authMode==='oidc' and there's no live
    // session (authGuard bounces here). No app shell, no guard. On Personal/offline these are simply
    // never navigated to. sign-in kicks off Auth-Code+PKCE; auth/callback redeems the returned code.
    { path: 'sign-in', loadComponent: () => import('app/modules/admin/session/sign-in.component').then((m) => m.SignInComponent) },
    { path: 'auth/callback', loadComponent: () => import('app/modules/admin/session/callback.component').then((m) => m.CallbackComponent) },

    // Public dashboard embed (BI-6) — the share token IS the credential; no shell, no guard, read-only.
    { path: 'share/:token', loadComponent: () => import('app/modules/admin/share/share-viewer.component').then((m) => m.ShareViewerComponent) },

    // Template OAuth flow, kept for reference (Inspecto uses operator tokens instead):
    // { path: 'signed-in-redirect', pathMatch: 'full', redirectTo: 'dashboard' },
    // {
    //     path: 'redirect/oauth/pronto',
    //     component: DefaultCallbackComponent,
    //     data: { title: 'Callback' }
    // },
    // // Auth routes for guests
    // {
    //     path: '',
    //     canActivate: [UserPermissionService],
    //     canActivateChild: [UserPermissionService],
    //     component: LayoutComponent,
    //     data: {
    //         layout: 'empty'
    //     },
    //     children: [
    //         // {path: 'confirmation-required', loadChildren: () => import('app/modules/auth/confirmation-required/confirmation-required.routes')},
    //         // {path: 'forgot-password', loadChildren: () => import('app/modules/auth/forgot-password/forgot-password.routes')},
    //         // {path: 'reset-password', loadChildren: () => import('app/modules/auth/reset-password/reset-password.routes')},
    //         // {path: 'sign-in', loadChildren: () => import('app/modules/auth/sign-in/sign-in.routes')},
    //         // {path: 'sign-up', loadChildren: () => import('app/modules/auth/sign-up/sign-up.routes')}
    //     ]
    // },

    // Inspecto inspector routes. Personal/core edition is auth-free (authGuard is a pass-through there);
    // the Standard edition (authMode==='oidc') gates entry on a live OIDC session, redirecting to
    // /sign-in when absent (W6d). Session state is resolved by SessionService.init before routing.
    {
        path: '',
        component: LayoutComponent,
        canActivate: [authGuard],
        resolve: {
            initialData: initialDataResolver
        },
        children: [
            { path: 'overview', loadChildren: () => import('app/modules/admin/dashboard/dashboard.routes') },
            // Ops Overview moved /dashboard → /overview so it no longer collides with Studio's
            // /studio/dashboards (audit C2). Redirect keeps old links/bookmarks working.
            { path: 'dashboard', redirectTo: 'overview', pathMatch: 'full' },
            { path: 'diagnoses', loadChildren: () => import('app/modules/admin/diagnoses/diagnoses.routes') },
            { path: 'events', loadChildren: () => import('app/modules/admin/events/events.routes') },
            { path: 'audit', loadChildren: () => import('app/modules/admin/audit-logs/audit-logs.routes') },
            { path: 'alerts', loadChildren: () => import('app/modules/admin/alerts/alerts.routes') },
            { path: 'cases', loadChildren: () => import('app/modules/admin/objects/cases.routes') },
            { path: 'incidents', loadChildren: () => import('app/modules/admin/objects/incidents.routes') },
            { path: 'runs', loadChildren: () => import('app/modules/admin/runs/runs.routes') },
            { path: 'runs/:name', loadChildren: () => import('app/modules/admin/run-detail/run-detail.routes') },
            { path: 'pipelines', loadChildren: () => import('app/modules/admin/pipelines/pipelines.routes') },
            { path: 'components', loadChildren: () => import('app/modules/admin/components/components.routes') },
            { path: 'sources', loadChildren: () => import('app/modules/admin/sources/sources.routes') },
            { path: 'kpi-reports', loadChildren: () => import('app/modules/admin/kpi-reports/kpi-reports.routes') },
            { path: 'requirements', loadChildren: () => import('app/modules/admin/requirements/requirements.routes') },
            { path: 'reconciliation', loadChildren: () => import('app/modules/admin/reconciliation/reconciliation.routes') },
            { path: 'connections', loadChildren: () => import('app/modules/admin/connections/connections.routes') },
            { path: 'expectations', loadChildren: () => import('app/modules/admin/expectations/expectations.routes') },
            { path: 'decision-rules', loadChildren: () => import('app/modules/admin/decision-rules/decision-rules.routes') },
            { path: 'maintenance', loadChildren: () => import('app/modules/admin/maintenance/maintenance.routes') },
            { path: 'jobs', loadChildren: () => import('app/modules/admin/jobs/jobs.routes') },
            { path: 'jobs/:name', loadChildren: () => import('app/modules/admin/jobs/job-detail/job-detail.routes') },
            { path: 'enrichment', loadChildren: () => import('app/modules/admin/enrichment/enrichment.routes') },
            { path: 'catalog', loadChildren: () => import('app/modules/admin/catalog/catalog.routes') },
            { path: 'processing-status', loadChildren: () => import('app/modules/admin/processing-status/processing-status.routes') },
            { path: 'studio', loadChildren: () => import('app/modules/admin/studio/studio.routes') },
            // Registry folded into Catalog's Usage tab (IA reorg phase B.4).
            { path: 'registry', redirectTo: 'catalog' },
            { path: 'config', loadChildren: () => import('app/modules/admin/config/config.routes') },
            { path: 'spaces', loadChildren: () => import('app/modules/admin/spaces/spaces.routes') },
            { path: 'design', loadChildren: () => import('app/modules/admin/design-system/design-system.routes') },
            { path: 'assist', loadChildren: () => import('app/modules/admin/assist/assist.routes') },
            { path: 'settings/models', loadChildren: () => import('app/modules/admin/model-settings/model-settings.routes') },
            { path: 'settings/icons', loadChildren: () => import('app/modules/admin/icon-settings/icon-settings.routes') },
            { path: 'settings/map', loadChildren: () => import('app/modules/admin/map-settings/map-settings.routes') },
            { path: 'settings/transfer', loadChildren: () => import('app/modules/admin/transfer/transfer.routes') },
            { path: 'notification-center', loadChildren: () => import('app/modules/admin/notification-center/notification-center.routes') },
            // Menu Builder: the authoring pane + the dynamic host every custom menu leaf links to (`/w/<nodeId>`).
            { path: 'settings/menus', loadComponent: () => import('app/modules/admin/menu/menu-builder.component').then((m) => m.MenuBuilderComponent) },
            { path: 'w/:nodeId', loadComponent: () => import('app/modules/admin/menu/menu-item-host.component').then((m) => m.MenuItemHostComponent) },
            // The old standalone prefs pane moved into the center as a tab (C4).
            { path: 'settings/notifications', redirectTo: 'notification-center' },
            // Settings landing: every config option as expandable drawers (registered AFTER the
            // specific settings/* routes so those match first).
            { path: 'settings', loadChildren: () => import('app/modules/admin/settings/settings.routes') },
        ]
    }
];

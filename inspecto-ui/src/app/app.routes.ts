import { Route } from '@angular/router';
import { initialDataResolver } from 'app/app.resolvers';
import { LayoutComponent } from 'app/layout/layout.component';

// @formatter:off
/* eslint-disable max-len */
/* eslint-disable @typescript-eslint/explicit-function-return-type */
export const appRoutes: Route[] = [

    // Default to the dashboard overview
    { path: '', pathMatch: 'full', redirectTo: 'dashboard' },

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

    // Inspecto inspector routes. The backend ControlApi is fully open (no auth) — the app is
    // single-user with no login, no token, and no route guard.
    {
        path: '',
        component: LayoutComponent,
        resolve: {
            initialData: initialDataResolver
        },
        children: [
            { path: 'dashboard', loadChildren: () => import('app/modules/admin/dashboard/dashboard.routes') },
            { path: 'diagnoses', loadChildren: () => import('app/modules/admin/diagnoses/diagnoses.routes') },
            { path: 'events', loadChildren: () => import('app/modules/admin/events/events.routes') },
            { path: 'alerts', loadChildren: () => import('app/modules/admin/alerts/alerts.routes') },
            { path: 'cases', loadChildren: () => import('app/modules/admin/objects/cases.routes') },
            { path: 'issues', loadChildren: () => import('app/modules/admin/objects/issues.routes') },
            { path: 'pipelines', loadChildren: () => import('app/modules/admin/pipelines/pipelines.routes') },
            { path: 'pipelines/:name', loadChildren: () => import('app/modules/admin/pipeline-detail/pipeline-detail.routes') },
            { path: 'flows', loadChildren: () => import('app/modules/admin/flows/flows.routes') },
            { path: 'components', loadChildren: () => import('app/modules/admin/components/components.routes') },
            { path: 'sources', loadChildren: () => import('app/modules/admin/sources/sources.routes') },
            { path: 'connections', loadChildren: () => import('app/modules/admin/connections/connections.routes') },
            { path: 'jobs', loadChildren: () => import('app/modules/admin/jobs/jobs.routes') },
            { path: 'enrichment', loadChildren: () => import('app/modules/admin/enrichment/enrichment.routes') },
            { path: 'catalog', loadChildren: () => import('app/modules/admin/catalog/catalog.routes') },
            { path: 'config', loadChildren: () => import('app/modules/admin/config/config.routes') },
            { path: 'spaces', loadChildren: () => import('app/modules/admin/spaces/spaces.routes') },
            { path: 'design', loadChildren: () => import('app/modules/admin/design-system/design-system.routes') },
            { path: 'assist', loadChildren: () => import('app/modules/admin/assist/assist.routes') },
            { path: 'settings/models', loadChildren: () => import('app/modules/admin/model-settings/model-settings.routes') },
        ]
    }
];

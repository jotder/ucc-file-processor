import { Route } from '@angular/router';
import { inspectoAuthGuard } from 'app/inspecto/auth.service';
import { initialDataResolver } from 'app/app.resolvers';
import { LayoutComponent } from 'app/layout/layout.component';
import { DefaultCallbackComponent } from './modules/auth/default-callback/default-callback.component';
import { UserPermissionService } from './modules/auth/user-permission/user-permission.service';

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

    // Auth routes for authenticated users
    {
        path: '',
        canActivate: [UserPermissionService],
        canActivateChild: [UserPermissionService],
        component: LayoutComponent,
        data: {
            layout: 'empty'
        },
        children: [
            // {path: 'sign-out', loadChildren: () => import('app/modules/auth/sign-out/sign-out.routes')},
            // {path: 'unlock-session', loadChildren: () => import('app/modules/auth/unlock-session/unlock-session.routes')}
        ]
    },

    // Inspecto inspector routes. ControlApi uses operator tokens (X-Api-Token), not the Pronto OAuth
    // flow the template's guard redirects to — so these use the Inspecto token guard instead.
    {
        path: 'connect',
        component: LayoutComponent,
        data: { layout: 'empty' },
        loadChildren: () => import('app/modules/admin/connect/connect.routes')
    },
    {
        path: '',
        canActivate: [inspectoAuthGuard],
        canActivateChild: [inspectoAuthGuard],
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
            { path: 'sources', loadChildren: () => import('app/modules/admin/sources/sources.routes') },
            { path: 'connections', loadChildren: () => import('app/modules/admin/connections/connections.routes') },
            { path: 'jobs', loadChildren: () => import('app/modules/admin/jobs/jobs.routes') },
            { path: 'enrichment', loadChildren: () => import('app/modules/admin/enrichment/enrichment.routes') },
            { path: 'catalog', loadChildren: () => import('app/modules/admin/catalog/catalog.routes') },
            { path: 'config', loadChildren: () => import('app/modules/admin/config/config.routes') },
            { path: 'assist', loadChildren: () => import('app/modules/admin/assist/assist.routes') },
            { path: 'settings/models', loadChildren: () => import('app/modules/admin/model-settings/model-settings.routes') },
        ]
    }
];

import { Routes } from '@angular/router';

/** Template gallery route (BI-8) — a single pane; lazy-loaded under the Studio shell. */
export default [
    { path: '', loadComponent: () => import('./template-gallery.component').then((m) => m.TemplateGalleryComponent) },
] as Routes;

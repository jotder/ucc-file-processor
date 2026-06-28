import { Routes } from '@angular/router';
import { DashboardsComponent } from './dashboards.component';
import { DashboardEditorComponent } from './dashboard-editor.component';

export default [
    { path: '', component: DashboardsComponent },
    { path: 'new', component: DashboardEditorComponent },
    { path: ':id', component: DashboardEditorComponent },
] as Routes;

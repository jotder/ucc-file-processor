import { Routes } from '@angular/router';
import { WidgetsComponent } from './widgets.component';
import { ExploreComponent } from './explore.component';
import { WidgetViewComponent } from './widget-view.component';

export default [
    { path: '', component: WidgetsComponent },
    { path: 'new', component: ExploreComponent },
    { path: ':id/view', component: WidgetViewComponent },
    { path: ':id', component: ExploreComponent },
] as Routes;

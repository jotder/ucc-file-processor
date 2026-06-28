import { Routes } from '@angular/router';
import { ChartsComponent } from './charts.component';
import { ExploreComponent } from './explore.component';

export default [
    { path: '', component: ChartsComponent },
    { path: 'new', component: ExploreComponent },
    { path: ':id', component: ExploreComponent },
] as Routes;

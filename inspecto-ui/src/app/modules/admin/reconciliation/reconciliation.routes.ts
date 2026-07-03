import { Routes } from '@angular/router';
import { ReconciliationsComponent } from './reconciliations.component';
import { ReconciliationDetailComponent } from './reconciliation-detail.component';

export default [
    { path: '', component: ReconciliationsComponent },
    { path: ':id', component: ReconciliationDetailComponent },
] as Routes;

import { Routes } from '@angular/router';
import { ReconciliationsComponent } from './reconciliations.component';
import { ReconBoardComponent } from './recon-board.component';
import { ReconciliationDetailComponent } from './reconciliation-detail.component';

export default [
    { path: '', component: ReconciliationsComponent },
    { path: ':id', component: ReconBoardComponent },
    { path: ':id/breaks', component: ReconciliationDetailComponent },
] as Routes;

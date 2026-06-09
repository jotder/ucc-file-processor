import { Routes } from '@angular/router';
import { ConnectFormComponent } from './shared/components';
import { AuthGuardService } from './shared/services';
import { DashboardComponent } from './pages/dashboard/dashboard.component';
import { PipelinesComponent } from './pages/pipelines/pipelines.component';
import { PipelineDetailComponent } from './pages/pipeline-detail/pipeline-detail.component';
import { JobsComponent } from './pages/jobs/jobs.component';
import { EnrichmentComponent } from './pages/enrichment/enrichment.component';
import { CatalogComponent } from './pages/catalog/catalog.component';
import { ConfigComponent } from './pages/config/config.component';
import { DiagnosesComponent } from './pages/diagnoses/diagnoses.component';
import { AssistComponent } from './pages/assist/assist.component';

export const routes: Routes = [
  { path: 'connect', component: ConnectFormComponent, canActivate: [AuthGuardService] },
  { path: 'dashboard', component: DashboardComponent, canActivate: [AuthGuardService] },
  { path: 'pipelines', component: PipelinesComponent, canActivate: [AuthGuardService] },
  { path: 'pipelines/:name', component: PipelineDetailComponent, canActivate: [AuthGuardService] },
  { path: 'jobs', component: JobsComponent, canActivate: [AuthGuardService] },
  { path: 'enrichment', component: EnrichmentComponent, canActivate: [AuthGuardService] },
  { path: 'catalog', component: CatalogComponent, canActivate: [AuthGuardService] },
  { path: 'config', component: ConfigComponent, canActivate: [AuthGuardService] },
  { path: 'diagnoses', component: DiagnosesComponent, canActivate: [AuthGuardService] },
  { path: 'assist', component: AssistComponent, canActivate: [AuthGuardService] },
  { path: '', redirectTo: 'dashboard', pathMatch: 'full' },
  { path: '**', redirectTo: 'dashboard' },
];

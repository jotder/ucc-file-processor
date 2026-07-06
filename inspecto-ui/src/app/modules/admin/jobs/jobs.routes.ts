import { Routes } from '@angular/router';
import { JobsComponent } from 'app/modules/admin/jobs/jobs.component';
// Side-effect: register the `job` ComponentKind when the feature loads (the studio kinds' pattern).
import './job.kind';

export default [
    {
        path: '',
        component: JobsComponent,
    },
] as Routes;

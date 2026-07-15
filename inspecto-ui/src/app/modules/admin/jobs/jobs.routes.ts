import { Routes, UrlSegment } from '@angular/router';
import { JobsComponent } from 'app/modules/admin/jobs/jobs.component';
// Side-effect: register the `job` ComponentKind when the feature loads (the studio kinds' pattern).
import './job.kind';

// One route config for BOTH `/jobs` and `/jobs/<name>` (ui-design-review R5): the router reuses the
// same JobsComponent instance across the two URLs, so the list survives opening a detail — the
// `name` param drives the embedded side panel while deep links keep working.
export default [
    {
        matcher: (segments: UrlSegment[]) =>
            segments.length === 0 ? { consumed: [] }
            : segments.length === 1 ? { consumed: segments, posParams: { name: segments[0] } }
            : null,
        component: JobsComponent,
    },
] as Routes;

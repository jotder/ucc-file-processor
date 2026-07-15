import { Routes, UrlSegment } from '@angular/router';
import { RunsComponent } from 'app/modules/admin/runs/runs.component';

// One route config for BOTH `/runs` and `/runs/<name>` (ui-design-review R5): the router reuses the
// same RunsComponent instance across the two URLs, so the list survives opening a detail — the
// `name` param drives the embedded side panel while deep links keep working.
export default [
    {
        matcher: (segments: UrlSegment[]) =>
            segments.length === 0 ? { consumed: [] }
            : segments.length === 1 ? { consumed: segments, posParams: { name: segments[0] } }
            : null,
        component: RunsComponent,
    },
] as Routes;

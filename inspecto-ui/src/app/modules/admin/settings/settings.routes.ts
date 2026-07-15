import { Routes, UrlSegment } from '@angular/router';
import { SettingsComponent } from 'app/modules/admin/settings/settings.component';

export default [
    {
        // One config matches BOTH /settings and /settings/<section> (ui-design-review R5): the same
        // route config means Angular reuses the component across selection changes — deep-linkable
        // sections, working Back, and a refresh that keeps the open section, with no re-instantiation.
        matcher: (segments: UrlSegment[]) =>
            segments.length === 0
                ? { consumed: [] }
                : segments.length === 1
                  ? { consumed: segments, posParams: { section: segments[0] } }
                  : null,
        component: SettingsComponent,
    },
] as Routes;

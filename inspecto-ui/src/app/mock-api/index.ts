import { inject, Injectable } from '@angular/core';
import { AuthMockApi } from 'app/mock-api/common/auth/api';
import { NavigationMockApi } from 'app/mock-api/common/navigation/api';
import { ShortcutsMockApi } from 'app/mock-api/common/shortcuts/api';
import { UserMockApi } from 'app/mock-api/common/user/api';

/**
 * The remaining Fuse-template mock APIs. The demo trees (apps/dashboards/pages/ui + common/search)
 * were removed in the M4 dead-weight sweep — the app's real domain mocking lives in inspecto/mock/.
 * What stays is only what the classic shell still fetches over the Fuse mock interceptor:
 * common navigation (api/common/navigation), user menu (api/common/user), shortcuts, and the
 * vendored auth mock. These come out in the follow-up increment that re-plumbs the shell nav/auth
 * off the Fuse layer.
 */
@Injectable({ providedIn: 'root' })
export class MockApiService {
    authMockApi = inject(AuthMockApi);
    navigationMockApi = inject(NavigationMockApi);
    shortcutsMockApi = inject(ShortcutsMockApi);
    userMockApi = inject(UserMockApi);
}

// permission-utils.ts
import { inject, Injectable, signal, computed } from '@angular/core';
import { environment } from 'environments/environment';

import { SecurityPrincipal } from 'app/modules/commons/security-principal';

@Injectable({
    providedIn: 'root',
})
export class PermissionUtils {
    private securityPrincipal = inject(SecurityPrincipal);

    private readonly allowedRoutes = signal<string[]>([]);
    private initialized = false;

    private ensureInitialized(): void {
        if (this.initialized) return;

        const guiRoles = this.securityPrincipal.getGuiRoleDetailsIfAny() ?? [];
        const routes: string[] = [];

        for (const role of guiRoles) {
            for (const pageMeta of (role['details'] ?? [])) {
                if (pageMeta['appName'] === environment.appName) {
                    routes.push(pageMeta['pageUrl']);
                }
            }
        }

        this.allowedRoutes.set(routes);
        this.initialized = true;
    }

    public isRoutePermitted(route: string): boolean {
        this.ensureInitialized();
        return this.allowedRoutes().includes(route);
    }
}
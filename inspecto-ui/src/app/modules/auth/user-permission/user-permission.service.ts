// import { Injectable } from '@angular/core';
// import { ActivatedRouteSnapshot, CanActivate, Router, RouterStateSnapshot } from '@angular/router';
// import { AppProperties } from 'app/modules/commons/app.properties';
// import { AppUtils } from 'app/modules/commons/app-utils';
// import { PageManager } from 'app/modules/commons/page.manager';
// import { SecurityPrincipal } from 'app/modules/commons/security-principal';


// @Injectable({
//     providedIn: 'root',
// })
// export class UserPermissionService implements CanActivate {

//     constructor(private router: Router, private _props: AppProperties, private pageManager: PageManager,
//         private securityPrincipal: SecurityPrincipal) {
//     }

//     canActivate(route: ActivatedRouteSnapshot, state: RouterStateSnapshot): boolean {
//         if (this.securityPrincipal.isLoggedIn()) {
//             // logged in so return true
//             //  this.router.navigate(['/dashboard']);
//             return true;
//         }
//         AppUtils.redirectToAuthServer(this._props, this.pageManager);
//         return false;
//     }
// }


// user-permission.guard.ts
import { inject } from '@angular/core';
import { CanActivateFn } from '@angular/router';
import { AppProperties } from 'app/modules/commons/app.properties';
import { AppUtils } from 'app/modules/commons/app-utils';
import { PageManager } from 'app/modules/commons/page.manager';
import { SecurityPrincipal } from 'app/modules/commons/security-principal';

export const UserPermissionService: CanActivateFn = (_route, _state) => {
    const securityPrincipal = inject(SecurityPrincipal);
    const props = inject(AppProperties);
    const pageManager = inject(PageManager);
    if (securityPrincipal.isLoggedIn()) {
        return true;
    }

    AppUtils.redirectToAuthServer(props, pageManager);
    return false;
};
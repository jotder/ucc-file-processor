import { BooleanInput } from '@angular/cdk/coercion';
import {
    ChangeDetectionStrategy,
    ChangeDetectorRef,
    Component,
    inject,
    Input,
    OnDestroy,
    OnInit,
    ViewEncapsulation,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDividerModule } from '@angular/material/divider';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { NavigationExtras, Router } from '@angular/router';
import { UserService } from 'app/core/user/user.service';
import { User } from 'app/core/user/user.types';
import { SecurityPrincipal } from 'app/modules/commons/security-principal';
import { environment } from 'environments/environment';
import { Subject, takeUntil } from 'rxjs';

@Component({
    selector: 'user',
    templateUrl: './user.component.html',
    encapsulation: ViewEncapsulation.None,
    changeDetection: ChangeDetectionStrategy.OnPush,
    exportAs: 'user',
    imports: [
        MatButtonModule,
        MatMenuModule,
        MatIconModule,
        MatDividerModule,
    ],
})
export class UserComponent implements OnInit, OnDestroy {
    /* eslint-disable @typescript-eslint/naming-convention */
    static ngAcceptInputType_showAvatar: BooleanInput;
    /* eslint-enable @typescript-eslint/naming-convention */
    private readonly securityPrincipal = inject(SecurityPrincipal);
    private  router = inject(Router);

    @Input() showAvatar: boolean = true;
    user: User;
user_name: string;
    private _unsubscribeAll: Subject<any> = new Subject<any>();

    /**
     * Constructor
     */
    constructor(
        private _changeDetectorRef: ChangeDetectorRef,
        private _router: Router,
        private _userService: UserService
    ) {}

    // -----------------------------------------------------------------------------------------------------
    // @ Lifecycle hooks
    // -----------------------------------------------------------------------------------------------------

    /**
     * On init
     */
    ngOnInit(): void {
        // Subscribe to user changes
        this.user_name = this.securityPrincipal.getPrincipalName();

        this._userService.user$
            .pipe(takeUntil(this._unsubscribeAll))
            .subscribe((user: User) => {
                this.user = user;

                // Mark for check
                this._changeDetectorRef.markForCheck();
            });
    }

    /**
     * On destroy
     */
    ngOnDestroy(): void {
        // Unsubscribe from all subscriptions
        this._unsubscribeAll.next(null);
        this._unsubscribeAll.complete();
    }

    // -----------------------------------------------------------------------------------------------------
    // @ Public methods
    // -----------------------------------------------------------------------------------------------------

    /**
     * Update the user status
     *
     * @param status
     */
    updateUserStatus(status: string): void {
        // Return if user is not available
        if (!this.user) {
            return;
        }

        // Update the user
        this._userService
            .update({
                ...this.user,
                status,
            })
            .subscribe();
    }

    /**
     * Sign out
     */


    signOut(): void {
        // this._router.navigate(['/sign-out']);
        const userName = this.securityPrincipal.getPrincipalName();
        this.securityPrincipal.clear();
        const navigationExtras: NavigationExtras = {
            queryParams: {
                fromApp: environment.appName,
                loggedUser: userName,
                type: 'implicit'
            },
        };
        this.router.navigate(['/logout'], navigationExtras);
    }

    onclicklogout() {
        localStorage.clear();
        sessionStorage.clear();
        let currentUrl = window.location.href
        let url = environment.authServerUrl + '/confirm-logout?redirectUrl=' + environment.gatewayUrl + environment.appLogoutUri;
        window.open(url, '_self')
    }

    onMyProfileClick(): any {
        let url = environment.gatewayUrl + "/apps/profile"
        window.open(url, "_blank");
    }

    onMyNotificationClick(): any {
        let url = environment.gatewayUrl + '/apps/manageNotification/userNotifications';
        window.open(url, "_blank");
    }

}

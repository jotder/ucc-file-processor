import { Component, inject, OnInit } from '@angular/core';
import { Router } from '@angular/router';
import { AppProperties } from 'app/modules/commons/app.properties';
import { PageManager } from 'app/modules/commons/page.manager';
import { SecurityPrincipal } from 'app/modules/commons/security-principal';
import { AuthService } from '../auth-service';
import { AppUtils } from 'app/modules/commons/app.utils';

@Component({
    selector: 'default-redirect',
    template: '',
    standalone: true,
})
export class DefaultCallbackComponent implements OnInit {
    private readonly router = inject(Router);
    private readonly pageManager = inject(PageManager);
    private readonly authService = inject(AuthService);
    private readonly securityPrincipal = inject(SecurityPrincipal);
    private readonly props = inject(AppProperties);

    ngOnInit(): void {
        const url = window.location.href;
        const codeIndex = url.indexOf('code=');

        if (codeIndex === -1) return;

        const code = url.substring(codeIndex + 5);

        this.authService.retrieveToken(code).subscribe({
            next: (data) => this.handleAuthorizationCodeTokenOutput(data),
            error: (err) => console.error('Token retrieval failed:', err),
        });
    }

    private handleAuthorizationCodeTokenOutput(data: any): void {
        this.authService.saveTokens(data);
        this.securityPrincipal.loadPrincipalData(data);

        const redirectUri = this.pageManager.redirectPath;
        const baseContext = this.props.appBaseContext;

        if (!redirectUri.includes(baseContext)) return;

        const routePath = redirectUri.substring(baseContext.length);

        if (routePath.includes('?')) {
            const commandPath = routePath.substring(0, routePath.indexOf('?'));
            const navigationExtras = AppUtils.getNavigationExtrasFromPath(routePath);
            this.router.navigate([commandPath], navigationExtras);
        } else {
            this.router.navigate([routePath]);
        }
    }
}
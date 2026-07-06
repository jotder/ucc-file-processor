import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { Router } from '@angular/router';
import { SessionService } from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';

/**
 * Standard-edition sign-in screen (W6d). A single "Sign in with SSO" action that kicks off the
 * Authorization-Code + PKCE redirect to the IAM (Keycloak/WSO2) via {@link SessionService.beginLogin}.
 * Never reached on Personal / offline: {@link authGuard} only routes here when OIDC is on and there is
 * no live session, and this component itself bounces back to the app if a session already exists (e.g.
 * a returning user whose refresh cookie was resumed at startup).
 */
@Component({
    selector: 'inspecto-sign-in',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [MatButtonModule, MatIconModule, MatProgressSpinnerModule, InspectoAlertComponent],
    template: `
        <div class="flex min-h-screen items-center justify-center p-6">
            <div class="w-full max-w-sm rounded-2xl bg-card p-8 shadow-lg text-center">
                <img class="mx-auto h-10" src="assets/images/logo/inspecto-logo.svg" alt="Inspecto" />
                <h1 class="mt-6 text-2xl font-semibold">Sign in</h1>
                <p class="mt-2 text-secondary">
                    This is a secured (Standard-edition) workspace. Continue with your organization's
                    single sign-on to access it.
                </p>

                @if (failed()) {
                    <inspecto-alert class="mt-4 block text-left" variant="error" title="Sign-in failed">
                        We couldn't complete sign-in. Please try again.
                    </inspecto-alert>
                }

                <button
                    mat-flat-button
                    color="primary"
                    class="mt-6 w-full"
                    [disabled]="busy()"
                    (click)="signIn()"
                >
                    @if (busy()) {
                        <mat-progress-spinner diameter="20" mode="indeterminate" aria-label="Signing in" />
                    } @else {
                        <mat-icon svgIcon="heroicons_outline:lock-closed" />
                        <span>Sign in with SSO</span>
                    }
                </button>
            </div>
        </div>
    `,
})
export class SignInComponent implements OnInit {
    private session = inject(SessionService);
    private router = inject(Router);

    readonly busy = signal(false);
    readonly failed = signal(false);

    ngOnInit(): void {
        // Already signed in (or Personal/offline where login is never required) → straight into the app.
        if (!this.session.loginRequired()) {
            this.router.navigate(['/']);
            return;
        }
        this.failed.set(sessionStorage.getItem('inspecto.signInFailed') === '1');
        sessionStorage.removeItem('inspecto.signInFailed');
    }

    async signIn(): Promise<void> {
        this.busy.set(true);
        await this.session.beginLogin();
    }
}

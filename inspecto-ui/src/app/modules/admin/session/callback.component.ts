import { ChangeDetectionStrategy, Component, Input, OnInit, inject } from '@angular/core';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { Router } from '@angular/router';
import { SessionService } from 'app/inspecto/api';

/**
 * OIDC redirect landing (W6d): the IAM sends the browser back here with `?code=&state=`. This hands
 * both to {@link SessionService.completeLogin} (which verifies the state and redeems the code via the
 * backend BFF), then routes into the app on success or back to sign-in on failure. `code`/`state` are
 * bound from the query string via the router's component-input binding.
 */
@Component({
    selector: 'inspecto-auth-callback',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [MatProgressSpinnerModule],
    template: `
        <div class="flex min-h-screen flex-col items-center justify-center gap-4 p-6" role="status" aria-live="polite">
            <mat-progress-spinner diameter="40" mode="indeterminate" aria-label="Signing you in" />
            <h1 class="text-lg font-medium">Signing you in…</h1>
        </div>
    `,
})
export class CallbackComponent implements OnInit {
    private session = inject(SessionService);
    private router = inject(Router);

    @Input() code = '';
    @Input() state = '';

    ngOnInit(): void {
        this.session.completeLogin(this.code, this.state).subscribe((ok) => {
            if (ok) {
                this.router.navigate(['/']);
            } else {
                sessionStorage.setItem('inspecto.signInFailed', '1');
                this.router.navigate(['/sign-in']);
            }
        });
    }
}

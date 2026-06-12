import { Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { ToastrService } from 'ngx-toastr';
import { UccAuthService } from 'app/ucc/auth.service';

/**
 * Connect screen — the inspector's "login" (ported from inspector-ui's connect-form). The operator
 * pastes the scoped bearer token(s) configured on the server; there is no username/password.
 */
@Component({
    selector: 'app-connect',
    standalone: true,
    imports: [FormsModule, MatButtonModule, MatFormFieldModule, MatInputModule],
    template: `
        <div class="flex min-w-0 flex-auto flex-col items-center justify-center p-6">
            <div class="bg-card w-full max-w-100 rounded-2xl p-8 shadow sm:p-10">
                <div class="text-3xl font-extrabold leading-tight tracking-tight">UCC Inspector</div>
                <div class="text-secondary mt-1">Connect with your operator token(s)</div>

                <form class="mt-6 flex flex-col" (submit)="onSubmit($event)">
                    <mat-form-field>
                        <mat-label>Control token</mat-label>
                        <input matInput type="password" name="control" [(ngModel)]="controlToken"
                               placeholder="Control token" [disabled]="loading" />
                    </mat-form-field>
                    <mat-form-field>
                        <mat-label>Assist token (optional)</mat-label>
                        <input matInput type="password" name="assist" [(ngModel)]="assistToken"
                               placeholder="Assist token" [disabled]="loading" />
                    </mat-form-field>
                    <button mat-flat-button color="primary" class="mt-2" type="submit" [disabled]="loading">
                        Connect
                    </button>
                </form>

                <p class="text-secondary mt-6 text-sm">
                    Paste the token(s) configured on the server (<code>-Dcontrol.token</code> /
                    <code>-Dassist.read.token</code>). A control token unlocks the full console; an
                    assist token alone enables the catalog and AI assist. Tokens are kept only for
                    this browser session.
                </p>
            </div>
        </div>
    `,
})
export class ConnectComponent {
    private auth = inject(UccAuthService);
    private toastr = inject(ToastrService);

    controlToken = '';
    assistToken = '';
    loading = false;

    onSubmit(e: Event): void {
        e.preventDefault();
        const result = this.auth.connect(this.controlToken || null, this.assistToken || null);
        if (!result.isOk) {
            this.toastr.error(result.message);
        }
    }
}

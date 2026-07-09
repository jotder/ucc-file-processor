import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage } from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { DashboardShareLink, DashboardsService } from './dashboards.service';

export interface ShareDashboardData {
    /** The saved dashboard id to mint a link for. */
    id: string;
}

/**
 * "Share dashboard" (BI-6) — mints a public, expiring link for a saved dashboard via
 * {@link DashboardsService.share} (`POST /dashboards/{id}/share`) and shows it for copy. The link
 * points at the anonymous embed viewer route (`/share/{token}`), not the API resolve path. Sharing
 * disabled server-side (no `-Dbi.share.secret`) → a 503 the dialog surfaces as a writes-disabled-style
 * notice, mirroring how the authoring forms handle the same gap.
 */
@Component({
    standalone: true,
    imports: [
        MatButtonModule,
        MatDialogModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatProgressSpinnerModule,
        InspectoAlertComponent,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <h2 mat-dialog-title>Share dashboard</h2>
        <mat-dialog-content class="flex w-[28rem] max-w-full flex-col gap-3">
            @if (loading()) {
                <div class="flex items-center gap-3 py-2 text-sm">
                    <mat-spinner diameter="20"></mat-spinner>
                    <span>Generating a share link…</span>
                </div>
            } @else if (disabledMessage(); as msg) {
                <inspecto-alert variant="warning" icon="heroicons_outline:lock-closed">{{ msg }}</inspecto-alert>
            } @else if (link(); as l) {
                <div class="text-secondary text-sm">
                    Anyone with this link can view <strong>{{ data.id }}</strong> read-only until it expires.
                </div>
                <mat-form-field subscriptSizing="dynamic">
                    <mat-label>Share link</mat-label>
                    <input matInput readonly [value]="shareUrl()" (focus)="selectAll($event)" />
                    <button
                        matSuffix
                        mat-icon-button
                        aria-label="Copy share link"
                        matTooltip="Copy link"
                        (click)="copy(shareUrl())"
                    >
                        <mat-icon svgIcon="heroicons_outline:clipboard-document"></mat-icon>
                    </button>
                </mat-form-field>
                <div class="text-secondary text-xs">Expires {{ expires(l) }}.</div>
            }
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button mat-dialog-close>Close</button>
            @if (link()) {
                <button mat-flat-button color="primary" (click)="copy(shareUrl())">Copy link</button>
            }
        </mat-dialog-actions>
    `,
})
export class ShareDashboardDialog {
    readonly data = inject<ShareDashboardData>(MAT_DIALOG_DATA);
    private dashboards = inject(DashboardsService);
    private toastr = inject(ToastrService);

    readonly loading = signal(true);
    readonly link = signal<DashboardShareLink | null>(null);
    readonly disabledMessage = signal<string | null>(null);

    /** The copyable link = the app embed-viewer route, resolved against the app base href. */
    readonly shareUrl = computed(() => {
        const l = this.link();
        return l ? new URL('share/' + l.token, document.baseURI).href : '';
    });

    constructor() {
        this.dashboards.share(this.data.id).subscribe({
            next: (l) => {
                this.link.set(l);
                this.loading.set(false);
            },
            error: (e) => {
                this.loading.set(false);
                if (e?.status === 503) {
                    this.disabledMessage.set(
                        'Sharing is disabled on this server. Set -Dbi.share.secret (≥ 16 chars) to enable public links.',
                    );
                } else {
                    this.toastr.error(apiErrorMessage(e, 'Could not create a share link.'));
                }
            },
        });
    }

    expires(l: DashboardShareLink): string {
        const d = new Date(l.expiresAt);
        return isNaN(d.getTime()) ? l.expiresAt : d.toLocaleString();
    }

    selectAll(ev: Event): void {
        (ev.target as HTMLInputElement).select();
    }

    copy(text: string): void {
        if (!text) return;
        navigator.clipboard?.writeText(text).then(
            () => this.toastr.success('Copied to clipboard'),
            () => this.toastr.warning('Clipboard unavailable'),
        );
    }
}

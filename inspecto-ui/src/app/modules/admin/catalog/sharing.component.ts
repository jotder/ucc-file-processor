import { ChangeDetectionStrategy, Component, computed, inject, input, OnInit, signal } from '@angular/core';
import { MatDialog } from '@angular/material/dialog';
import { ColDef } from 'ag-grid-community';
import {
    apiErrorMessage,
    ExchangeGrant,
    ExchangeOffer,
    ExchangeService,
    SpacesService,
} from 'app/inspecto/api';
import { statusBadgeHtml } from 'app/inspecto/components/status-badge.component';
import { DataTableComponent } from 'app/inspecto/data-table';
import { fmtDateTime, InspectoRowAction } from 'app/inspecto/grid';
import { ToastrService } from 'ngx-toastr';
import { forkJoin } from 'rxjs';
import { RequestShareDialog, RequestShareResult } from './request-share.dialog';

/**
 * The Catalog's cross-Space sharing lens (Exchange, §3.6) — one pane, two views selected by the tab
 * that hosts it:
 *
 * - **with-me** (consumer): the grants where the active Space consumes, plus the catalog of offers
 *   from other Spaces it can request access to.
 * - **by-me** (owner): inbound requests + grants on the active Space's offers (approve / deny /
 *   revoke), plus the offers it has listed.
 *
 * Grant transitions are request→refetch (they are one-way state changes, not reversible toggles).
 */
@Component({
    selector: 'catalog-sharing',
    standalone: true,
    imports: [DataTableComponent],
    changeDetection: ChangeDetectionStrategy.OnPush,
    templateUrl: './sharing.component.html',
})
export class SharingComponent implements OnInit {
    private exchange = inject(ExchangeService);
    private spaces = inject(SpacesService);
    private dialog = inject(MatDialog);
    private toastr = inject(ToastrService);

    readonly view = input.required<'with-me' | 'by-me'>();

    readonly loading = signal(false);
    readonly grants = signal<ExchangeGrant[]>([]);
    readonly offers = signal<ExchangeOffer[]>([]);

    /** The active space id — the "me" every filter below is relative to. */
    private readonly me = computed(() => this.spaces.currentSpaceId() ?? 'default');

    /** with-me: grants my space consumes. by-me: grants on my space's offers. */
    readonly myGrants = computed(() =>
        this.grants().filter((g) =>
            this.view() === 'with-me' ? g.consumer === this.me() : g.owner === this.me(),
        ),
    );
    /** with-me: other spaces' offers (the requestable catalog). by-me: my listed offers. */
    readonly myOffers = computed(() =>
        this.offers().filter((o) => (this.view() === 'with-me' ? o.owner !== this.me() : o.owner === this.me())),
    );

    readonly grantColumns: ColDef[] = [
        { field: 'item', headerName: 'Item', flex: 1 },
        { field: 'kind', headerName: 'Kind', width: 110 },
        { field: 'owner', headerName: 'Owner', width: 150 },
        { field: 'consumer', headerName: 'Consumer', width: 150 },
        { field: 'mode', headerName: 'Mode', width: 110 },
        { field: 'status', headerName: 'Status', width: 120, cellRenderer: (p: { value: string }) => statusBadgeHtml(p.value) },
        { field: 'purpose', headerName: 'Purpose', flex: 1 },
        { field: 'pin', headerName: 'Pinned', width: 100, valueFormatter: (p) => p.value ?? '—' },
        { field: 'expiresAt', headerName: 'Expires', width: 170, valueFormatter: (p) => (p.value ? fmtDateTime(p.value) : '—') },
        { field: 'requestedAt', headerName: 'Requested', width: 170, valueFormatter: (p) => fmtDateTime(p.value) },
    ];

    readonly offerColumns: ColDef[] = [
        { field: 'item', headerName: 'Item', flex: 1 },
        { field: 'kind', headerName: 'Kind', width: 110 },
        { field: 'owner', headerName: 'Owner', width: 150 },
        { field: 'description', headerName: 'Description', flex: 2 },
        { field: 'freshness.version', headerName: 'Snapshot', width: 110, valueFormatter: (p) => p.value ?? '—' },
        { field: 'freshness.rows', headerName: 'Rows', width: 100, valueFormatter: (p) => (p.value == null ? '—' : Number(p.value).toLocaleString()) },
        { field: 'freshness.refreshedAt', headerName: 'Refreshed', width: 170, valueFormatter: (p) => (p.value ? fmtDateTime(p.value) : '—') },
    ];

    /** by-me: the owner's grant lifecycle actions (approve/deny a request, revoke an active grant). */
    readonly grantActions: InspectoRowAction<ExchangeGrant>[] = [
        {
            icon: 'heroicons_outline:check',
            hint: 'Approve this request',
            visible: (g) => this.view() === 'by-me' && g.status === 'requested',
            onClick: (g) => this.act(g, 'approve'),
        },
        {
            icon: 'heroicons_outline:x-mark',
            hint: 'Deny this request',
            visible: (g) => this.view() === 'by-me' && g.status === 'requested',
            onClick: (g) => this.act(g, 'deny'),
        },
        {
            icon: 'heroicons_outline:no-symbol',
            hint: 'Revoke this grant',
            visible: (g) => this.view() === 'by-me' && g.status === 'active',
            onClick: (g) => this.act(g, 'revoke'),
        },
    ];

    /** with-me: request access to an offer (hidden once a request/grant is already in flight). */
    readonly offerActions: InspectoRowAction<ExchangeOffer>[] = [
        {
            icon: 'heroicons_outline:paper-airplane',
            hint: 'Request access',
            visible: (o) => this.view() === 'with-me' && !this.inFlight(o),
            onClick: (o) => this.requestAccess(o),
        },
    ];

    ngOnInit(): void {
        this.reload();
    }

    reload(): void {
        this.loading.set(true);
        forkJoin({
            grants: this.exchange.grants(this.me()),
            offers: this.exchange.offers(),
        }).subscribe({
            next: ({ grants, offers }) => {
                this.grants.set(grants);
                this.offers.set(offers);
                this.loading.set(false);
            },
            error: (e) => {
                this.grants.set([]);
                this.offers.set([]);
                this.loading.set(false);
                this.toastr.error(apiErrorMessage(e, 'Could not load the sharing catalog.'));
            },
        });
    }

    private act(g: ExchangeGrant, action: 'approve' | 'deny' | 'revoke'): void {
        this.exchange.actOnGrant(g.id, action).subscribe({
            next: () => {
                this.toastr.success(`${action === 'approve' ? 'Approved' : action === 'deny' ? 'Denied' : 'Revoked'} ${g.kind} ${g.item} for ${g.consumer}.`);
                this.reload();
            },
            error: (e) => this.toastr.error(apiErrorMessage(e, `Could not ${action} the grant.`)),
        });
    }

    private requestAccess(o: ExchangeOffer): void {
        this.dialog
            .open(RequestShareDialog, { data: { offer: o, consumer: this.me() } })
            .afterClosed()
            .subscribe((r: RequestShareResult | undefined) => {
                if (!r) return;
                this.exchange
                    .request({ kind: o.kind, owner: o.owner, consumer: this.me(), item: o.item, purpose: r.purpose, mode: r.mode })
                    .subscribe({
                        next: () => {
                            this.toastr.success(`Requested ${o.kind} ${o.owner}/${o.item} — awaiting the owner's approval.`);
                            this.reload();
                        },
                        error: (e) => this.toastr.error(apiErrorMessage(e, 'Could not send the request.')),
                    });
            });
    }

    /** A request or active grant already exists for me on this offer — nothing further to request. */
    private inFlight(o: ExchangeOffer): boolean {
        return this.grants().some(
            (g) =>
                g.consumer === this.me() && g.owner === o.owner && g.kind === o.kind && g.item === o.item &&
                (g.status === 'requested' || g.status === 'active'),
        );
    }
}

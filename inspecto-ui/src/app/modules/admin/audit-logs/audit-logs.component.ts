import { ChangeDetectionStrategy, Component, OnInit, ViewEncapsulation, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { ColDef } from 'ag-grid-community';
import { forkJoin } from 'rxjs';
import { EventRow, EventsService } from 'app/inspecto/api';
import { DataTableComponent } from 'app/inspecto/data-table';
import { fmtDateTime } from 'app/inspecto/grid';

/** A flattened audit row — the audit anatomy lifted out of the event's `attributes` bag. */
interface AuditRow {
    eventId: string;
    ts: number;
    actor: string;
    action: string;
    category: string;
    target: string;
    ip: string;
    userAgent: string;
    message: string;
}

/**
 * Audit log — the immutable "who did what, when, and from where" trail. Audit entries are append-only
 * {@link EventRow}s ({@code type = AUDIT}/{@code ACCESS_DENIED}) whose actor/action/target/ip ride in
 * {@code attributes}; this view flattens them into columns and hands them to the **pro** data-table, whose
 * offline SQL editor + filter builder cover ad-hoc filtering by actor/action/category. Read-only by design.
 */
@Component({
    selector: 'app-audit-logs',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    encapsulation: ViewEncapsulation.None,
    imports: [MatButtonModule, MatIconModule, DataTableComponent],
    template: `
        <div class="flex flex-auto flex-col gap-4 p-6">
            <div class="flex items-start justify-between gap-4">
                <div>
                    <h1 class="text-2xl font-semibold">Audit log</h1>
                    <p class="text-secondary mt-1">
                        Immutable, append-only record of who did what, when, and from where.
                    </p>
                </div>
                <button mat-stroked-button type="button" (click)="load()" aria-label="Refresh audit log">
                    <mat-icon svgIcon="heroicons_outline:arrow-path"></mat-icon>
                    <span class="ml-2">Refresh</span>
                </button>
            </div>

            <inspecto-data-table
                [tier]="'pro'"
                [serverPage]="true"
                [hasMore]="hasMore()"
                [rows]="rows()"
                [columns]="columnDefs"
                [loading]="loading()"
                sourceName="audit"
                exportName="audit-log"
                stateKey="audit-logs"
                noRowsTitle="No audit events yet"
                noRowsHint="Actions like creating, triggering, or deleting resources will appear here."
                (loadMore)="loadMore()"
            />
        </div>
    `,
})
export class AuditLogsComponent implements OnInit {
    private api = inject(EventsService);

    readonly rows = signal<AuditRow[]>([]);
    readonly loading = signal(false);
    /** True once either audit type's fetch returned a full page — there may be more (R6a). */
    readonly hasMore = signal(false);

    /** Page size per audit type — Load more appends the next offset page (R6 true offset paging). */
    private static readonly LIMIT = 1000;
    /** Rows fetched so far per type — the next page's offset. A full reload resets both. */
    private auditCount = 0;
    private deniedCount = 0;

    readonly columnDefs: ColDef<AuditRow>[] = [
        { headerName: 'Time', width: 180, valueGetter: (p) => p.data?.ts, valueFormatter: (p) => fmtDateTime(p.value) },
        { field: 'actor', headerName: 'Actor', width: 130 },
        { field: 'action', headerName: 'Action', width: 190 },
        { field: 'category', headerName: 'Category', width: 150 },
        { field: 'target', headerName: 'Target', width: 190, valueFormatter: (p) => p.value || '—' },
        { field: 'ip', headerName: 'IP', width: 130, valueFormatter: (p) => p.value || '—' },
        { field: 'message', headerName: 'Message', flex: 2, minWidth: 220 },
        { field: 'userAgent', headerName: 'User agent', flex: 1, minWidth: 160, hide: true },
    ];

    ngOnInit(): void {
        this.load();
    }

    load(): void {
        this.loading.set(true);
        const lim = AuditLogsComponent.LIMIT;
        forkJoin([
            this.api.search({ type: 'AUDIT', limit: lim }),
            this.api.search({ type: 'ACCESS_DENIED', limit: lim }),
        ]).subscribe({
            next: ([audit, denied]) => {
                this.auditCount = audit.length;
                this.deniedCount = denied.length;
                this.hasMore.set(audit.length >= lim || denied.length >= lim);
                this.rows.set(
                    [...audit, ...denied]
                        .sort((a, b) => b.ts - a.ts)
                        .map((e) => AuditLogsComponent.toRow(e)),
                );
                this.loading.set(false);
            },
            error: () => {
                this.rows.set([]);
                this.hasMore.set(false);
                this.loading.set(false);
            },
        });
    }

    /** Fetch the NEXT offset page per type and append — true offset paging (R6; no refetch from 0). */
    loadMore(): void {
        this.loading.set(true);
        const lim = AuditLogsComponent.LIMIT;
        forkJoin([
            this.api.search({ type: 'AUDIT', limit: lim, offset: this.auditCount }),
            this.api.search({ type: 'ACCESS_DENIED', limit: lim, offset: this.deniedCount }),
        ]).subscribe({
            next: ([audit, denied]) => {
                this.auditCount += audit.length;
                this.deniedCount += denied.length;
                this.hasMore.set(audit.length >= lim || denied.length >= lim);
                const page = [...audit, ...denied]
                    .sort((a, b) => b.ts - a.ts)
                    .map((e) => AuditLogsComponent.toRow(e));
                this.rows.update((r) => [...r, ...page]);
                this.loading.set(false);
            },
            error: () => this.loading.set(false),
        });
    }

    private static toRow(e: EventRow): AuditRow {
        const a = e.attributes ?? {};
        const target = [a['target_type'], a['target_id']].filter(Boolean).join(':');
        return {
            eventId: e.eventId,
            ts: e.ts,
            actor: a['actor'] ?? '',
            action: a['action'] ?? '',
            category: a['action_category'] ?? '',
            target,
            ip: a['ip'] ?? '',
            userAgent: a['user_agent'] ?? '',
            message: e.message,
        };
    }
}

import { Component, OnInit, ViewEncapsulation, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ToastrService } from 'ngx-toastr';
import { ConnectionProfile, ConnectionTestResult, ConnectionsService } from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { ConnectionFormDialog, ConnectionFormResult } from './connection-form.dialog';

/**
 * Connections — reusable remote-system connection profiles (Data Acquisition). Lists the profiles loaded
 * from `*_connection.toon` (GET /connections, secret-masked) and tests reachability of each endpoint — or
 * its tunnel hop — via POST /connections/{id}/test. Credentials are `${…}` references resolved at runtime,
 * never shown here.
 */
@Component({
    selector: 'app-connections',
    standalone: true,
    imports: [MatButtonModule, MatIconModule, MatProgressSpinnerModule, MatTooltipModule],
    templateUrl: './connections.component.html',
    encapsulation: ViewEncapsulation.None,
})
export class ConnectionsComponent implements OnInit {
    private api = inject(ConnectionsService);
    private toastr = inject(ToastrService);
    private dialog = inject(MatDialog);
    private confirm = inject(InspectoConfirmService);

    connections: ConnectionProfile[] = [];
    loading = false;
    testing: Record<string, boolean> = {};
    results: Record<string, ConnectionTestResult> = {};
    /** Flipped true once a mutate (create/update/delete) returns 503 — hides the mutate actions. */
    writesDisabled = false;

    ngOnInit(): void {
        this.load();
    }

    load(): void {
        this.loading = true;
        this.api.list().subscribe({
            next: (c) => {
                this.connections = c;
                this.loading = false;
            },
            error: () => {
                this.connections = [];
                this.loading = false;
                this.toastr.warning('Could not load connections — is ControlApi running?');
            },
        });
    }

    test(id: string): void {
        this.testing[id] = true;
        this.api.test(id).subscribe({
            next: (r) => {
                this.testing[id] = false;
                this.results[id] = r;
                if (r.reachable) {
                    this.toastr.success(`${id}: reachable${r.latencyMs != null ? ` (${r.latencyMs} ms)` : ''}`);
                } else {
                    this.toastr.warning(`${id}: ${r.detail}`);
                }
            },
            error: (e) => {
                this.testing[id] = false;
                this.toastr.warning(e?.error?.error ?? `Test failed for ${id}`);
            },
        });
    }

    /** The host:port a test probes — the tunnel hop when present, else the target. */
    endpoint(c: ConnectionProfile): string {
        if (c.tunnel?.host) {
            return `${c.tunnel.host}:${c.tunnel.port ?? '?'} (tunnel)`;
        }
        if (!c.host) {
            return 'local';
        }
        return `${c.host}:${c.port ?? '?'}`;
    }

    optionEntries(c: ConnectionProfile): { key: string; value: string }[] {
        return Object.entries(c.options ?? {}).map(([key, value]) => ({ key, value }));
    }

    create(): void {
        this.dialog
            .open(ConnectionFormDialog, { data: {}, width: '720px', maxHeight: '85vh' })
            .afterClosed()
            .subscribe((r?: ConnectionFormResult) => this.afterForm(r));
    }

    edit(c: ConnectionProfile): void {
        this.dialog
            .open(ConnectionFormDialog, { data: { profile: c }, width: '720px', maxHeight: '85vh' })
            .afterClosed()
            .subscribe((r?: ConnectionFormResult) => this.afterForm(r));
    }

    private afterForm(r?: ConnectionFormResult): void {
        if (r?.writesDisabled) this.writesDisabled = true;
        if (r?.saved) this.load();
    }

    async remove(c: ConnectionProfile): Promise<void> {
        if (!(await this.confirm.confirm(`Delete connection "${c.id}"?`, 'Delete connection'))) return;
        this.api.remove(c.id).subscribe({
            next: () => {
                this.toastr.success(`Connection "${c.id}" deleted`);
                this.load();
            },
            error: (e) => {
                if (e?.status === 503) this.writesDisabled = true;
                const msg =
                    e?.status === 503
                        ? 'Writes are disabled (no write root configured).'
                        : e?.status === 409
                          ? `"${c.id}" is in use and can't be deleted.`
                          : (e?.error?.error ?? `Could not delete "${c.id}".`);
                this.toastr.error(msg);
            },
        });
    }
}

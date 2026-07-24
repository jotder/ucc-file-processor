import { Component, inject, OnInit, ViewEncapsulation } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatTabsModule } from '@angular/material/tabs';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ColDef, ICellRendererParams } from 'ag-grid-community';
import { ToastrService } from 'ngx-toastr';
import {
    apiErrorMessage,
    ChannelDelivery,
    NotificationChannel,
    NotificationRule,
    NotificationsService,
    optimisticMutate,
} from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { statusBadgeHtml } from 'app/inspecto/components/status-badge.component';
import { DataTableComponent } from 'app/inspecto/data-table';
import { fmtDateTime, InspectoRowAction } from 'app/inspecto/grid';
import { NotificationPreferencesComponent } from 'app/modules/admin/notification-preferences/notification-preferences.component';
import { ChannelFormDialog, ChannelFormResult } from './channel-form.dialog';
import { RuleFormDialog, RuleFormResult } from './rule-form.dialog';

/**
 * Notification center (C4) — one Ops surface for the whole notification story: **Channels**
 * (author the email/webhook endpoints alert & incident notifications fan out to — delivery is
 * mocked, nothing is really contacted), **Rules** (authored event→notification mappings, checked
 * ahead of the server's built-in defaults — override a built-in's copy, cover a new event type,
 * or mute one), **Deliveries** (the append-only ledger of what was handed to which channel and
 * why), and **Preferences** (the per-user category × channel grid, moved in from its old
 * standalone pane whose route now redirects here).
 */
@Component({
    selector: 'app-notification-center',
    standalone: true,
    imports: [
        MatButtonModule,
        MatIconModule,
        MatTabsModule,
        MatTooltipModule,
        DataTableComponent,
        NotificationPreferencesComponent,
    ],
    templateUrl: './notification-center.component.html',
    encapsulation: ViewEncapsulation.None,
})
export class NotificationCenterComponent implements OnInit {
    private api = inject(NotificationsService);
    private dialog = inject(MatDialog);
    private confirm = inject(InspectoConfirmService);
    private toastr = inject(ToastrService);

    channels: NotificationChannel[] = [];
    deliveries: ChannelDelivery[] = [];
    rules: NotificationRule[] = [];
    loadingChannels = false;
    loadingDeliveries = false;
    loadingRules = false;

    readonly channelCols: ColDef<NotificationChannel>[] = [
        { field: 'id', headerName: 'Channel', flex: 1 },
        { field: 'kind', headerName: 'Kind', width: 120 },
        { field: 'target', headerName: 'Target', flex: 2, minWidth: 200 },
        {
            field: 'enabled',
            headerName: 'State',
            width: 120,
            cellRenderer: (p: ICellRendererParams<NotificationChannel>) =>
                statusBadgeHtml(p.value ? 'ENABLED' : 'DISABLED'),
        },
        { field: 'description', headerName: 'Description', flex: 2, valueFormatter: (p) => p.value || '—' },
        { field: 'createdAt', headerName: 'Created', width: 170, valueFormatter: (p) => fmtDateTime(p.value) },
    ];

    readonly channelActions: InspectoRowAction<NotificationChannel>[] = [
        { icon: 'heroicons_outline:pencil-square', hint: 'Edit channel', onClick: (c) => this.openForm(c) },
        {
            icon: 'heroicons_outline:power',
            hint: (c) => (c.enabled ? 'Disable' : 'Enable'),
            onClick: (c) => this.toggle(c),
        },
        { icon: 'heroicons_outline:trash', hint: 'Delete channel', onClick: (c) => this.remove(c) },
    ];

    readonly deliveryCols: ColDef<ChannelDelivery>[] = [
        { field: 'ts', headerName: 'When', width: 180, sort: 'desc', valueFormatter: (p) => fmtDateTime(p.value) },
        { field: 'channelId', headerName: 'Channel', width: 150 },
        { field: 'channelKind', headerName: 'Kind', width: 110 },
        { field: 'target', headerName: 'Target', flex: 1, minWidth: 180 },
        { field: 'trigger', headerName: 'Trigger', width: 170 },
        { field: 'subject', headerName: 'Subject', flex: 2, minWidth: 220 },
        {
            field: 'status',
            headerName: 'Status',
            width: 110,
            cellRenderer: (p: ICellRendererParams<ChannelDelivery>) => statusBadgeHtml(p.value as string),
        },
    ];

    readonly ruleCols: ColDef<NotificationRule>[] = [
        { field: 'id', headerName: 'Rule', flex: 1 },
        { field: 'eventType', headerName: 'Event type', flex: 1, minWidth: 160 },
        { field: 'minLevel', headerName: 'Min severity', width: 130, valueFormatter: (p) => p.value || 'Any' },
        { field: 'category', headerName: 'Category', width: 120 },
        { field: 'titleTemplate', headerName: 'Title', flex: 2, minWidth: 180, valueFormatter: (p) => p.value || '—' },
        {
            field: 'enabled',
            headerName: 'State',
            width: 120,
            cellRenderer: (p: ICellRendererParams<NotificationRule>) =>
                statusBadgeHtml(p.value ? 'ENABLED' : 'DISABLED'),
        },
    ];

    readonly ruleActions: InspectoRowAction<NotificationRule>[] = [
        { icon: 'heroicons_outline:pencil-square', hint: 'Edit rule', onClick: (r) => this.openRuleForm(r) },
        {
            icon: 'heroicons_outline:power',
            hint: (r) => (r.enabled ? 'Disable' : 'Enable'),
            onClick: (r) => this.toggleRule(r),
        },
        { icon: 'heroicons_outline:trash', hint: 'Delete rule', onClick: (r) => this.removeRule(r) },
    ];

    ngOnInit(): void {
        this.loadChannels();
        this.loadDeliveries();
        this.loadRules();
    }

    loadChannels(): void {
        this.loadingChannels = true;
        this.api.channels().subscribe({
            next: (c) => {
                this.channels = c;
                this.loadingChannels = false;
            },
            error: () => {
                this.channels = [];
                this.loadingChannels = false;
                this.toastr.error('Failed to load channels');
            },
        });
    }

    loadDeliveries(): void {
        this.loadingDeliveries = true;
        this.api.deliveries().subscribe({
            next: (d) => {
                this.deliveries = d;
                this.loadingDeliveries = false;
            },
            error: () => {
                this.deliveries = [];
                this.loadingDeliveries = false;
            },
        });
    }

    openForm(channel?: NotificationChannel): void {
        this.dialog
            .open(ChannelFormDialog, {
                data: { channel, existingIds: this.channels.map((c) => c.id) },
                width: '560px',
                maxHeight: '85vh',
            })
            .afterClosed()
            .subscribe((r?: ChannelFormResult) => {
                if (r?.saved) this.loadChannels();
            });
    }

    /** Reversible flip → optimistic (§10). */
    toggle(channel: NotificationChannel): void {
        const prev = channel.enabled;
        optimisticMutate({
            apply: () => {
                channel.enabled = !prev;
                this.channels = [...this.channels];
            },
            commit: this.api.updateChannel(channel.id, { enabled: !prev }),
            reconcile: (r) => {
                channel.enabled = r.enabled;
                this.channels = [...this.channels];
            },
            rollback: () => {
                channel.enabled = prev;
                this.channels = [...this.channels];
            },
            onError: (e) => this.toastr.error(apiErrorMessage(e, 'Toggle failed')),
        });
    }

    loadRules(): void {
        this.loadingRules = true;
        this.api.rules().subscribe({
            next: (r) => {
                this.rules = r;
                this.loadingRules = false;
            },
            error: () => {
                this.rules = [];
                this.loadingRules = false;
                this.toastr.error('Failed to load rules');
            },
        });
    }

    openRuleForm(rule?: NotificationRule): void {
        this.dialog
            .open(RuleFormDialog, {
                data: { rule, existingIds: this.rules.map((r) => r.id) },
                width: '560px',
                maxHeight: '85vh',
            })
            .afterClosed()
            .subscribe((r?: RuleFormResult) => {
                if (r?.saved) this.loadRules();
            });
    }

    /** Reversible flip → optimistic (§10). The server PUT is a full replace, so send the whole rule. */
    toggleRule(rule: NotificationRule): void {
        const prev = rule.enabled;
        optimisticMutate({
            apply: () => {
                rule.enabled = !prev;
                this.rules = [...this.rules];
            },
            commit: this.api.updateRule(rule.id, { ...rule, enabled: !prev }),
            reconcile: (r) => {
                rule.enabled = r.enabled;
                this.rules = [...this.rules];
            },
            rollback: () => {
                rule.enabled = prev;
                this.rules = [...this.rules];
            },
            onError: (e) => this.toastr.error(apiErrorMessage(e, 'Toggle failed')),
        });
    }

    async removeRule(rule: NotificationRule): Promise<void> {
        if (!(await this.confirm.confirmDestructive(`Delete rule "${rule.id}"?`, { title: 'Delete rule' }))) {
            return;
        }
        this.api.deleteRule(rule.id).subscribe({
            next: () => {
                this.toastr.success(`Deleted "${rule.id}"`);
                this.loadRules();
            },
            error: (e) => this.toastr.error(apiErrorMessage(e, 'Delete failed')),
        });
    }

    async remove(channel: NotificationChannel): Promise<void> {
        if (!(await this.confirm.confirmDestructive(`Delete channel "${channel.id}"?`, { title: 'Delete channel' }))) {
            return;
        }
        this.api.deleteChannel(channel.id).subscribe({
            next: () => {
                this.toastr.success(`Deleted "${channel.id}"`);
                this.loadChannels();
            },
            error: (e) => this.toastr.error(apiErrorMessage(e, 'Delete failed')),
        });
    }
}

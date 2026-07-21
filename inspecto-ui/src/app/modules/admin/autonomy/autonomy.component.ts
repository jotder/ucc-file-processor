import { Component, OnInit, ViewEncapsulation, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { ColDef, ICellRendererParams } from 'ag-grid-community';
import { ToastrService } from 'ngx-toastr';
import {
    apiErrorMessage,
    AutonomyAction,
    AutonomyMode,
    AutonomyPolicy,
    AutonomyService,
    ClassPolicy,
    LensService,
} from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { statusBadgeHtml } from 'app/inspecto/components/status-badge.component';
import { DataTableComponent } from 'app/inspecto/data-table';
import { fmtDateTime } from 'app/inspecto/grid';

/** An editable working-copy row for one action class. */
interface ClassRow {
    name: string;
    mode: AutonomyMode;
    maxPerHour: number;
    maxPerDay: number;
}

/** Pilot action classes always surfaced so an operator can configure them even before first use. */
const PILOT_CLASSES = ['batch_rerun', 'alert_triage'];

/**
 * Autonomy Dashboard (AGT-5 P4, autonomy L3) — the operator surface over the bounded-autonomy policy
 * and its action ledger. Shows the kill switch, per-action-class mode + budget editors, and the "what
 * the agent did, why, and spend" ledger (`GET /agent/actions`). Editing (kill switch + policy save) is
 * Ops-gated (`canOperateRuns`); every lens can read. Degrades to a disabled state + toast when the
 * intelligence module is absent (policy 503) or its autonomy tier is unconfigured.
 */
@Component({
    selector: 'app-autonomy',
    standalone: true,
    imports: [FormsModule, MatButtonModule, MatIconModule, MatProgressSpinnerModule, DataTableComponent],
    templateUrl: './autonomy.component.html',
    encapsulation: ViewEncapsulation.None,
})
export class AutonomyComponent implements OnInit {
    private api = inject(AutonomyService);
    private confirm = inject(InspectoConfirmService);
    private toastr = inject(ToastrService);
    protected lens = inject(LensService);

    readonly modes: AutonomyMode[] = ['OFF', 'SHADOW', 'AUTO'];

    policy: AutonomyPolicy | null = null;
    rows: ClassRow[] = [];
    actions: AutonomyAction[] = [];
    loading = false;
    saving = false;
    /** True once a policy read has failed (module absent / no L3 tier) — the editors are disabled then. */
    unavailable = false;

    readonly columnDefs: ColDef<AutonomyAction>[] = [
        { field: 'at', headerName: 'When', width: 180, sort: 'desc', valueFormatter: (p) => fmtDateTime(p.value) },
        { field: 'actionClass', headerName: 'Action', width: 150 },
        {
            field: 'status',
            headerName: 'Outcome',
            width: 130,
            cellRenderer: (p: ICellRendererParams<AutonomyAction>) => statusBadgeHtml(p.value as string),
        },
        { field: 'decision', headerName: 'Verdict', width: 110 },
        {
            colId: 'subject',
            headerName: 'Subject',
            width: 220,
            valueGetter: (p) => this.subjectLabel(p.data?.subject),
        },
        { field: 'reason', headerName: 'Reason', flex: 1, minWidth: 180, wrapText: true, autoHeight: true },
        { field: 'detail', headerName: 'Detail', flex: 1, minWidth: 180, wrapText: true, autoHeight: true },
    ];

    get canOperate(): boolean {
        return this.lens.canOperateRuns();
    }

    ngOnInit(): void {
        this.load();
    }

    load(): void {
        this.loading = true;
        this.api.policy().subscribe({
            next: (p) => {
                this.policy = p;
                this.unavailable = false;
                this.rows = this.buildRows(p);
                this.loading = false;
            },
            error: () => {
                // 503 (module absent) or connectivity — the editors disable and read as unavailable.
                this.policy = null;
                this.unavailable = true;
                this.rows = [];
                this.loading = false;
                this.toastr.error('Autonomy policy is not available');
            },
        });
        this.api.actions(100).subscribe({
            next: (a) => (this.actions = a),
            error: () => (this.actions = []),
        });
    }

    /** Merge configured classes with the pilot list so every class an operator might set is visible. */
    private buildRows(p: AutonomyPolicy): ClassRow[] {
        const names = new Set<string>([...PILOT_CLASSES, ...Object.keys(p.classes ?? {})]);
        return [...names].sort().map((name) => {
            const c = p.classes?.[name];
            return {
                name,
                mode: c?.mode ?? 'OFF',
                maxPerHour: c?.maxPerHour ?? 0,
                maxPerDay: c?.maxPerDay ?? 0,
            };
        });
    }

    /** Engage/disengage the kill switch. Disengaging (re-enabling autonomy) is the destructive confirm. */
    async toggleKillSwitch(): Promise<void> {
        if (!this.policy || !this.canOperate) return;
        const engaging = !this.policy.killSwitch;
        const ok = engaging
            ? await this.confirm.confirm(
                  'Engaging the kill switch immediately halts every autonomous action, regardless of mode.',
                  'Engage kill switch?',
              )
            : await this.confirm.confirmDestructive(
                  'Disengaging the kill switch re-enables autonomous actions per their configured modes.',
                  { title: 'Disengage kill switch?', confirmText: 'Disengage' },
              );
        if (!ok) return;
        this.api.setKillSwitch(engaging).subscribe({
            next: (p) => {
                this.policy = p;
                this.rows = this.buildRows(p);
                this.toastr.success(engaging ? 'Kill switch engaged' : 'Kill switch disengaged');
            },
            error: (err) => this.toastr.error(apiErrorMessage(err, 'Could not change the kill switch')),
        });
    }

    /** Persist the per-class editor state as a full policy replacement (PUT). Ops-gated. */
    savePolicy(): void {
        if (!this.policy || !this.canOperate) return;
        const classes: Record<string, ClassPolicy> = {};
        for (const r of this.rows) {
            classes[r.name] = {
                mode: r.mode,
                maxPerHour: Number(r.maxPerHour) || 0,
                maxPerDay: Number(r.maxPerDay) || 0,
            };
        }
        this.saving = true;
        this.api.updatePolicy({ killSwitch: this.policy.killSwitch, classes }).subscribe({
            next: (p) => {
                this.policy = p;
                this.rows = this.buildRows(p);
                this.saving = false;
                this.toastr.success('Autonomy policy saved');
            },
            error: (err) => {
                this.saving = false;
                this.toastr.error(apiErrorMessage(err, 'Could not save the policy'));
            },
        });
    }

    /** A compact one-line label for a ledger row's subject map (e.g. `orders / batch-42`). */
    subjectLabel(subject: Record<string, unknown> | undefined): string {
        if (!subject) return '';
        return Object.values(subject)
            .map((v) => String(v))
            .join(' / ');
    }
}

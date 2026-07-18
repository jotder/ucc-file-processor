import { Component, inject, OnInit, ViewEncapsulation } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ColDef, ICellRendererParams } from 'ag-grid-community';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage, AssistService, DbBrowserService, DecisionRule, DecisionRulesService, LensService } from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { statusBadgeHtml } from 'app/inspecto/components/status-badge.component';
import { DataTableComponent } from 'app/inspecto/data-table';
import { fmtDateTime, InspectoRowAction } from 'app/inspecto/grid';
import { Condition, ConditionGroup } from 'app/inspecto/query/query-types';
import { type Consequence, describeConsequence } from 'app/inspecto/decision';
import { DecisionRuleFormData, DecisionRuleFormDialog, DecisionRuleFormResult } from './decision-rule-form.dialog';

/** One-line human summary of a condition tree, e.g. `tariff startsWith EMEA_ AND cost_usd > 100`. */
export function summarizeWhen(g: ConditionGroup): string {
    if (!g.items.length) return 'always';
    const parts = g.items.map((it) =>
        it.kind === 'group' ? `(${summarizeWhen(it)})` : summarizeCondition(it as Condition),
    );
    return parts.join(` ${g.op} `);
}

function summarizeCondition(c: Condition): string {
    if (c.operator === 'isNull' || c.operator === 'isNotNull') return `${c.field} ${c.operator}`;
    if (c.operator === 'between') return `${c.field} between ${c.value ?? '?'} and ${c.value2 ?? '?'}`;
    return `${c.field} ${c.operator} ${c.value ?? ''}`.trim();
}

/** One-line summary of a rule's consequences, e.g. `Route to emea · Tag "high_risk" · Emit signal REVIEW`. */
export function summarizeConsequences(r: DecisionRule): string {
    return r.consequences.map(describeConsequence).join(' · ');
}

/**
 * Decision Rules (C3) — business routing rules (WHEN condition tree → THEN route/tag/quarantine/drop)
 * over Pipeline/Job records, surfaced first-class (previously buried in the `transform.route` node's
 * edge metadata). "Simulate" is a dry-run preview of the matched/total counts — routing is not a
 * failure, so nothing raises an Incident. Authoring is Builder-lens (canAuthorWorkbench).
 */
@Component({
    selector: 'app-decision-rules',
    standalone: true,
    imports: [
        MatButtonModule,
        MatIconModule,
        MatProgressSpinnerModule,
        MatTooltipModule,
        DataTableComponent,
        InspectoEmptyStateComponent,
    ],
    templateUrl: './decision-rules.component.html',
    encapsulation: ViewEncapsulation.None,
})
export class DecisionRulesComponent implements OnInit {
    private api = inject(DecisionRulesService);
    private db = inject(DbBrowserService);
    private assist = inject(AssistService);
    private dialog = inject(MatDialog);
    private confirm = inject(InspectoConfirmService);
    private toastr = inject(ToastrService);
    protected lens = inject(LensService);

    rows: DecisionRule[] = [];
    loading = false;

    readonly columnDefs: ColDef<DecisionRule>[] = [
        { field: 'priority', headerName: 'Prio', width: 90 },
        { field: 'name', headerName: 'Rule', flex: 1, minWidth: 180 },
        {
            headerName: 'Target',
            width: 180,
            valueGetter: (p) => (p.data ? `${p.data.targetType}: ${p.data.target}` : ''),
        },
        { headerName: 'When', flex: 1, minWidth: 220, valueGetter: (p) => (p.data ? summarizeWhen(p.data.when) : '') },
        { headerName: 'Then', flex: 1, minWidth: 180, valueGetter: (p) => (p.data ? summarizeConsequences(p.data) : '') },
        {
            field: 'enabled',
            headerName: 'Enabled',
            width: 110,
            cellRenderer: (p: ICellRendererParams<DecisionRule>) => statusBadgeHtml(p.value ? 'enabled' : 'disabled'),
        },
        {
            headerName: 'Last simulation',
            width: 150,
            valueGetter: (p) => p.data?.lastSimulation ?? null,
            cellRenderer: (p: ICellRendererParams<DecisionRule>) =>
                p.data?.lastSimulation
                    ? `${p.data.lastSimulation.matched} / ${p.data.lastSimulation.total} matched`
                    : '—',
        },
        {
            headerName: 'Checked',
            width: 170,
            valueGetter: (p) => p.data?.lastSimulation?.checkedAt ?? null,
            valueFormatter: (p) => (p.value ? fmtDateTime(p.value) : '—'),
        },
    ];

    /** Simulate is a read-only dry run (every lens); edit/delete author config (Builder lens only). */
    get rowActions(): InspectoRowAction<DecisionRule>[] {
        const ops: InspectoRowAction<DecisionRule>[] = [
            { icon: 'heroicons_outline:beaker', hint: 'Simulate (dry run)', onClick: (r) => this.simulate(r) },
        ];
        if (!this.lens.canAuthorWorkbench()) return ops;
        return [
            ...ops,
            { icon: 'heroicons_outline:bolt', hint: 'Apply consequences', onClick: (r) => this.apply(r) },
            { icon: 'heroicons_outline:pencil-square', hint: 'Edit', onClick: (r) => this.edit(r) },
            { icon: 'heroicons_outline:trash', hint: 'Delete', onClick: (r) => this.remove(r) },
        ];
    }

    ngOnInit(): void {
        this.load();
    }

    load(): void {
        this.loading = true;
        this.api.list().subscribe({
            next: (rows) => {
                this.rows = rows;
                this.loading = false;
            },
            error: (e) => {
                this.loading = false;
                this.toastr.warning(apiErrorMessage(e, 'Could not load decision rules.'));
            },
        });
    }

    newRule(): void {
        const data: DecisionRuleFormData = { existingNames: this.rows.map((r) => r.name) };
        this.dialog
            .open(DecisionRuleFormDialog, { data, width: '760px', maxHeight: '88vh' })
            .afterClosed()
            .subscribe((r?: DecisionRuleFormResult) => {
                if (r?.saved) {
                    this.toastr.success(`Decision rule "${r.saved.name}" created`);
                    this.load();
                }
            });
    }

    /**
     * Assist as a **decision engine** (R5): the AI proposes {@link Consequence}s; we open the Decision
     * Rule form pre-filled with them so the human reviews and saves — approval is the consequence gate.
     */
    proposeWithAi(): void {
        this.assist
            .run('propose-decision', {
                screenContext: { pane: 'decision-rules' },
                userText: 'Propose a decision rule for high-cost fraud review',
            })
            .subscribe({
                next: (res) => {
                    const consequences = (res.data?.['consequences'] as Consequence[] | undefined) ?? [];
                    if (!consequences.length) {
                        this.toastr.warning('The assistant returned no consequences to propose.');
                        return;
                    }
                    const prefill: Partial<DecisionRule> = {
                        targetType: 'pipeline', target: 'cdr_ingest', consequences, description: res.answer,
                    };
                    const data: DecisionRuleFormData = { existingNames: this.rows.map((r) => r.name), prefill };
                    this.dialog
                        .open(DecisionRuleFormDialog, { data, width: '760px', maxHeight: '88vh' })
                        .afterClosed()
                        .subscribe((r?: DecisionRuleFormResult) => {
                            if (r?.saved) {
                                this.toastr.success(`Decision rule "${r.saved.name}" created from the AI proposal`);
                                this.load();
                            }
                        });
                },
                error: (e) =>
                    this.toastr.error(
                        e?.status === 503 ? 'Assist agent is not available (agent absent).' : 'Could not get a proposal.',
                    ),
            });
    }

    edit(rule: DecisionRule): void {
        const data: DecisionRuleFormData = { rule };
        this.dialog
            .open(DecisionRuleFormDialog, { data, width: '760px', maxHeight: '88vh' })
            .afterClosed()
            .subscribe((r?: DecisionRuleFormResult) => {
                if (r?.saved) {
                    this.toastr.success(`Decision rule "${r.saved.name}" saved`);
                    this.load();
                }
            });
    }

    /** How many records to pull from the target's store as the simulation sample. */
    private static readonly SAMPLE_LIMIT = 500;

    /**
     * Dry-run the rule: fetch a bounded sample of the target's records and evaluate the when-clause
     * over them (the sample is the row source — a rule's target is a pipeline/job, not a queryable
     * dataset). A target with no browsable store simulates over an empty sample (0/0).
     */
    simulate(rule: DecisionRule): void {
        this.db.table({ name: rule.target, limit: DecisionRulesComponent.SAMPLE_LIMIT }).subscribe({
            next: (res) => this.runSimulate(rule, res.rows),
            error: () => this.runSimulate(rule, []),
        });
    }

    private runSimulate(rule: DecisionRule, sampleRows: Record<string, unknown>[]): void {
        this.api.simulate(rule.name, sampleRows).subscribe({
            next: (res) => {
                this.rows = this.rows.map((r) => (r.name === res.name ? res : r));
                const s = res.lastSimulation!;
                const from = sampleRows.length
                    ? ` sampled from "${rule.target}"`
                    : ` (no records found for "${rule.target}")`;
                this.toastr.info(`"${res.name}" would match ${s.matched} of ${s.total} record(s)${from}.`);
            },
            error: (err) => this.toastr.error(apiErrorMessage(err, `Could not simulate "${rule.name}".`)),
        });
    }

    /** Execute the rule's consequences (R5) — emit-signal / create-alert land on the Signal Ledger. */
    apply(rule: DecisionRule): void {
        this.api.apply(rule.name).subscribe({
            next: (res) => {
                const ran = res.executed.filter((e) => e.status === 'executed').length;
                this.toastr.success(`Applied "${res.rule}": ${ran} consequence(s) executed — see the Signal Ledger.`);
            },
            error: (err) => this.toastr.error(apiErrorMessage(err, `Could not apply "${rule.name}".`)),
        });
    }

    async remove(rule: DecisionRule): Promise<void> {
        if (!(await this.confirm.confirmDestructive(`Delete decision rule "${rule.name}"?`))) return;
        this.api.remove(rule.name).subscribe({
            next: () => {
                this.toastr.success(`Decision rule "${rule.name}" deleted`);
                this.rows = this.rows.filter((r) => r.name !== rule.name);
            },
            error: (err) => this.toastr.error(apiErrorMessage(err, `Could not delete "${rule.name}".`)),
        });
    }
}

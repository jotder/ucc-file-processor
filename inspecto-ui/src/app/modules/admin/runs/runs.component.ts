import { Component, DestroyRef, inject, OnInit, ViewEncapsulation } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Router } from '@angular/router';
import { ColDef } from 'ag-grid-community';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage, DEFAULT_REFRESH_MS, LensService, optimisticMutate, RunsService, RunView, visibleInterval } from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { DataTableComponent } from 'app/inspecto/data-table';
import { InspectoRowAction } from 'app/inspecto/grid';
import { ReprocessDialog } from './reprocess.dialog';

/**
 * Runs — every configured ingest run with lifecycle actions (trigger / pause / resume /
 * reprocess) and a "Run all" toolbar (ported from inspector-ui onto the gamma shell).
 */
@Component({
    selector: 'app-runs',
    standalone: true,
    imports: [
        FormsModule,
        MatButtonModule,
        MatIconModule,
        MatSlideToggleModule,
        MatTooltipModule,
        DataTableComponent,
    ],
    templateUrl: './runs.component.html',
    encapsulation: ViewEncapsulation.None,
})
export class RunsComponent implements OnInit {
    private api = inject(RunsService);
    private router = inject(Router);
    private dialog = inject(MatDialog);
    private confirm = inject(InspectoConfirmService);
    private toastr = inject(ToastrService);
    private destroyRef = inject(DestroyRef);
    /** Business lens = read-only observe on Runs (plan §1) — hides trigger/pause/reprocess. */
    protected lens = inject(LensService);

    runs: RunView[] = [];
    loading = false;
    autoRefresh = true;
    private dialogOpen = false;

    readonly columnDefs: ColDef<RunView>[] = [
        { field: 'name', headerName: 'Run', flex: 1 },
        { field: 'configPath', headerName: 'Config', flex: 2 },
        { field: 'paused', headerName: 'Paused', width: 100 },
        { field: 'committedBatches', headerName: 'Committed', width: 120 },
    ];

    /** Business lens is read-only observe (plan §1) — only "Open detail" stays; every other action here
     *  mutates a run (trigger/pause/resume/reprocess), unlike Jobs' run-now/toggle which are kept
     *  available to every lens as operational. */
    get rowActions(): InspectoRowAction<RunView>[] {
        const detail: InspectoRowAction<RunView> = {
            icon: 'heroicons_outline:chevron-right',
            hint: 'Open detail',
            onClick: (p) => this.openDetail(p.name),
        };
        if (!this.lens.canOperateRuns()) return [detail];
        return [
            {
                icon: 'heroicons_outline:play',
                hint: 'Trigger',
                onClick: (p) => this.trigger(p.name),
            },
            {
                icon: (p) => (p.paused ? 'heroicons_outline:play-circle' : 'heroicons_outline:pause-circle'),
                hint: (p) => (p.paused ? 'Resume' : 'Pause'),
                onClick: (p) => this.togglePause(p),
            },
            {
                icon: 'heroicons_outline:arrow-path',
                hint: 'Reprocess batch',
                onClick: (p) => this.openReprocess(p.name),
            },
            detail,
        ];
    }

    ngOnInit(): void {
        this.load();
        visibleInterval(DEFAULT_REFRESH_MS)
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe(() => {
                if (this.autoRefresh && !this.dialogOpen) this.load();
            });
    }

    load(): void {
        this.loading = true;
        this.api.list().subscribe({
            next: (p) => {
                this.runs = p;
                this.loading = false;
            },
            error: (e) => {
                this.loading = false;
                this.toastr.error(apiErrorMessage(e, 'Failed to load runs'));
            },
        });
    }

    async trigger(name: string): Promise<void> {
        if (!this.lens.canOperateRuns()) return; // Business lens: read-only observe
        if (!(await this.confirm.confirm(`Trigger run "${name}" now?`, 'Trigger run'))) return;
        this.api.trigger(name).subscribe({
            next: (r) => {
                const msg = `${name}: ${r.total} processed, ${r.failed} failed`;
                r.failed ? this.toastr.warning(msg) : this.toastr.success(msg);
                this.load();
            },
            error: (e) => this.toastr.error(apiErrorMessage(e, `Trigger failed for ${name}`)),
        });
    }

    async runAll(): Promise<void> {
        if (!this.lens.canOperateRuns()) return; // Business lens: read-only observe
        if (!(await this.confirm.confirm('Trigger all runs now?', 'Run all'))) return;
        this.loading = true;
        this.api.runAll().subscribe({
            next: (res) => {
                const total = Object.values(res).reduce((s, r) => s + (r.total || 0), 0);
                const failed = Object.values(res).reduce((s, r) => s + (r.failed || 0), 0);
                const msg = `Run all: ${total} processed across ${Object.keys(res).length} runs, ${failed} failed`;
                failed ? this.toastr.warning(msg) : this.toastr.success(msg);
                this.load();
            },
            error: (e) => {
                this.loading = false;
                this.toastr.error(apiErrorMessage(e, 'Run all failed'));
            },
        });
    }

    async togglePause(p: RunView): Promise<void> {
        if (!this.lens.canOperateRuns()) return; // Business lens: read-only observe
        const wasPaused = p.paused;
        const verb = wasPaused ? 'Resume' : 'Pause';
        if (!(await this.confirm.confirm(`${verb} run "${p.name}"?`, `${verb} run`))) return;
        // Optimistic: flip the local paused state now (snappy toggle, no refetch); the call selection
        // is based on the pre-flip value, and we roll back + toast only on failure.
        const call = wasPaused ? this.api.resume(p.name) : this.api.pause(p.name);
        const render = () => (this.runs = [...this.runs]); // new ref so the grid re-renders
        optimisticMutate({
            apply: () => {
                p.paused = !wasPaused;
                render();
            },
            commit: call,
            reconcile: (r) => {
                p.paused = r.paused;
                render();
            },
            rollback: () => {
                p.paused = wasPaused;
                render();
            },
            onError: (e) => this.toastr.error(apiErrorMessage(e, `${verb} failed for ${p.name}`)),
        });
    }

    openReprocess(name: string): void {
        if (!this.lens.canOperateRuns()) return; // Business lens: read-only observe
        this.dialogOpen = true;
        const ref = this.dialog.open(ReprocessDialog, { data: { pipeline: name }, width: '420px' });
        ref.afterClosed().subscribe((batchId: string | undefined) => {
            this.dialogOpen = false;
            if (!batchId?.trim()) return;
            this.api.reprocess(name, batchId.trim()).subscribe({
                next: () => {
                    this.toastr.success(`Reprocess requested for ${name} / ${batchId.trim()}`);
                    this.load();
                },
                error: (e) => this.toastr.error(apiErrorMessage(e, `Reprocess failed for ${name}`)),
            });
        });
    }

    openDetail(name: string): void {
        this.router.navigate(['/runs', name]);
    }
}

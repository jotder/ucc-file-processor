import { Component, DestroyRef, inject, OnInit, ViewEncapsulation } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { Router } from '@angular/router';
import { AgGridAngular } from 'ag-grid-angular';
import { ColDef } from 'ag-grid-community';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage, DEFAULT_REFRESH_MS, optimisticMutate, PipelinesService, PipelineView, visibleInterval } from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { actionsColumn, refreshActionsCells, INSPECTO_DEFAULT_COL_DEF, InspectoGridThemeService, noRowsOverlay } from 'app/inspecto/grid';
import { ReprocessDialog } from './reprocess.dialog';

/**
 * Pipelines — every configured pipeline with lifecycle actions (trigger / pause / resume /
 * reprocess) and a "Run all" toolbar (ported from inspector-ui onto the gamma shell).
 */
@Component({
    selector: 'app-pipelines',
    standalone: true,
    imports: [
        FormsModule,
        MatButtonModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatSlideToggleModule,
        AgGridAngular,
    ],
    templateUrl: './pipelines.component.html',
    encapsulation: ViewEncapsulation.None,
})
export class PipelinesComponent implements OnInit {
    private api = inject(PipelinesService);
    private router = inject(Router);
    private dialog = inject(MatDialog);
    private confirm = inject(InspectoConfirmService);
    private toastr = inject(ToastrService);
    private destroyRef = inject(DestroyRef);
    readonly themeSvc = inject(InspectoGridThemeService);

    pipelines: PipelineView[] = [];
    loading = false;
    autoRefresh = true;
    quickFilter = '';
    private dialogOpen = false;

    /** Empty-state overlay shown when no pipelines are configured. */
    readonly noRows = noRowsOverlay(
        'No pipelines configured',
        'Pipelines are defined by *_pipeline.toon configs on the server.',
    );

    readonly defaultColDef = INSPECTO_DEFAULT_COL_DEF;
    readonly columnDefs: ColDef<PipelineView>[] = [
        { field: 'name', headerName: 'Pipeline', flex: 1 },
        { field: 'configPath', headerName: 'Config', flex: 2 },
        { field: 'paused', headerName: 'Paused', width: 100 },
        { field: 'committedBatches', headerName: 'Committed', width: 120 },
        actionsColumn<PipelineView>([
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
            {
                icon: 'heroicons_outline:chevron-right',
                hint: 'Open detail',
                onClick: (p) => this.openDetail(p.name),
            },
        ], 200),
    ];

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
                this.pipelines = p;
                this.loading = false;
            },
            error: (e) => {
                this.loading = false;
                this.toastr.error(apiErrorMessage(e, 'Failed to load pipelines'));
            },
        });
    }

    async trigger(name: string): Promise<void> {
        if (!(await this.confirm.confirm(`Trigger pipeline "${name}" now?`, 'Trigger pipeline'))) return;
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
        if (!(await this.confirm.confirm('Trigger all pipelines now?', 'Run all'))) return;
        this.loading = true;
        this.api.runAll().subscribe({
            next: (res) => {
                const total = Object.values(res).reduce((s, r) => s + (r.total || 0), 0);
                const failed = Object.values(res).reduce((s, r) => s + (r.failed || 0), 0);
                const msg = `Run all: ${total} processed across ${Object.keys(res).length} pipelines, ${failed} failed`;
                failed ? this.toastr.warning(msg) : this.toastr.success(msg);
                this.load();
            },
            error: (e) => {
                this.loading = false;
                this.toastr.error(apiErrorMessage(e, 'Run all failed'));
            },
        });
    }

    async togglePause(p: PipelineView): Promise<void> {
        const wasPaused = p.paused;
        const verb = wasPaused ? 'Resume' : 'Pause';
        if (!(await this.confirm.confirm(`${verb} pipeline "${p.name}"?`, `${verb} pipeline`))) return;
        // Optimistic: flip the local paused state now (snappy toggle, no refetch); the call selection
        // is based on the pre-flip value, and we roll back + toast only on failure.
        const call = wasPaused ? this.api.resume(p.name) : this.api.pause(p.name);
        const render = () => (this.pipelines = [...this.pipelines]); // new ref so the grid re-renders
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
        this.router.navigate(['/pipelines', name]);
    }

    readonly refreshActions = refreshActionsCells;
}

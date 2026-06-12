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
import { DEFAULT_REFRESH_MS, PipelinesService, PipelineView, visibleInterval } from 'app/ucc/api';
import { UccAuthService } from 'app/ucc/auth.service';
import { UccConfirmService } from 'app/ucc/confirm.service';
import { actionsColumn, refreshActionsCells, UCC_DEFAULT_COL_DEF, UccGridThemeService } from 'app/ucc/grid';
import { ReprocessDialog } from './reprocess.dialog';

/**
 * Pipelines — every configured pipeline with lifecycle actions (trigger / pause / resume /
 * reprocess) and a "Run all" toolbar (ported from inspector-ui onto the gamma shell).
 * CONTROL-scoped actions are disabled when only an assist token is held.
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
    private auth = inject(UccAuthService);
    private router = inject(Router);
    private dialog = inject(MatDialog);
    private confirm = inject(UccConfirmService);
    private toastr = inject(ToastrService);
    private destroyRef = inject(DestroyRef);
    readonly themeSvc = inject(UccGridThemeService);

    pipelines: PipelineView[] = [];
    loading = false;
    autoRefresh = true;
    quickFilter = '';
    private dialogOpen = false;

    get canControl(): boolean {
        return this.auth.hasControl();
    }

    readonly defaultColDef = UCC_DEFAULT_COL_DEF;
    readonly columnDefs: ColDef<PipelineView>[] = [
        { field: 'name', headerName: 'Pipeline', flex: 1 },
        { field: 'configPath', headerName: 'Config', flex: 2 },
        { field: 'paused', headerName: 'Paused', width: 100 },
        { field: 'committedBatches', headerName: 'Committed', width: 120 },
        actionsColumn<PipelineView>([
            {
                icon: 'heroicons_outline:play',
                hint: 'Trigger',
                disabled: () => !this.canControl,
                onClick: (p) => this.trigger(p.name),
            },
            {
                icon: (p) => (p.paused ? 'heroicons_outline:play-circle' : 'heroicons_outline:pause-circle'),
                hint: (p) => (p.paused ? 'Resume' : 'Pause'),
                disabled: () => !this.canControl,
                onClick: (p) => this.togglePause(p),
            },
            {
                icon: 'heroicons_outline:arrow-path',
                hint: 'Reprocess batch',
                disabled: () => !this.canControl,
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
            error: () => {
                this.loading = false;
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
            error: (e) => this.toastr.error(e?.error?.error ?? `Trigger failed for ${name}`),
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
                this.toastr.error(e?.error?.error ?? 'Run all failed');
            },
        });
    }

    async togglePause(p: PipelineView): Promise<void> {
        const verb = p.paused ? 'Resume' : 'Pause';
        if (!(await this.confirm.confirm(`${verb} pipeline "${p.name}"?`, `${verb} pipeline`))) return;
        const call = p.paused ? this.api.resume(p.name) : this.api.pause(p.name);
        call.subscribe({
            next: (r) => {
                this.toastr.success(`${p.name} ${r.paused ? 'paused' : 'resumed'}`);
                this.load();
            },
            error: (e) => this.toastr.error(e?.error?.error ?? `${verb} failed for ${p.name}`),
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
                error: (e) => this.toastr.error(e?.error?.error ?? `Reprocess failed for ${name}`),
            });
        });
    }

    openDetail(name: string): void {
        this.router.navigate(['/pipelines', name]);
    }

    readonly refreshActions = refreshActionsCells;
}

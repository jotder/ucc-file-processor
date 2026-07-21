import { Component, OnInit, ViewEncapsulation, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { ColDef, ICellRendererParams } from 'ag-grid-community';
import { ToastrService } from 'ngx-toastr';
import { CaseFeedback, LearningService } from 'app/inspecto/api';
import { statusBadgeHtml } from 'app/inspecto/components/status-badge.component';
import { DataTableComponent } from 'app/inspecto/data-table';
import { fmtDateTime } from 'app/inspecto/grid';

/**
 * Learning dashboard (AGT-5 P5) — how useful the agent's investigation Cases have been, aggregated from
 * the operator-feedback corpus (`GET /agent/feedback`). Shows the helpful-rate KPIs the learning tier
 * tunes against, plus the recent-feedback ledger. Read-only + degrades to an empty state on failure
 * (module absent / no feedback yet), mirroring the other agent panes.
 */
@Component({
    selector: 'app-learning',
    standalone: true,
    imports: [MatButtonModule, MatIconModule, MatProgressSpinnerModule, DataTableComponent],
    templateUrl: './learning.component.html',
    encapsulation: ViewEncapsulation.None,
})
export class LearningComponent implements OnInit {
    private api = inject(LearningService);
    private toastr = inject(ToastrService);

    feedback: CaseFeedback[] = [];
    loading = false;

    readonly columnDefs: ColDef<CaseFeedback>[] = [
        { field: 'at', headerName: 'When', width: 180, sort: 'desc', valueFormatter: (p) => fmtDateTime(p.value) },
        { field: 'caseId', headerName: 'Case', width: 200 },
        {
            field: 'rating',
            headerName: 'Rating',
            width: 140,
            cellRenderer: (p: ICellRendererParams<CaseFeedback>) => statusBadgeHtml(p.value as string),
        },
        { field: 'submittedBy', headerName: 'By', width: 160 },
        { field: 'note', headerName: 'Note', flex: 1, minWidth: 200, wrapText: true, autoHeight: true },
    ];

    get total(): number {
        return this.feedback.length;
    }

    get helpful(): number {
        return this.feedback.filter((f) => f.rating === 'HELPFUL').length;
    }

    get notHelpful(): number {
        return this.total - this.helpful;
    }

    /** Helpful-rate as a whole-number percentage, or null when there is no feedback yet. */
    get helpfulRate(): number | null {
        return this.total === 0 ? null : Math.round((this.helpful / this.total) * 100);
    }

    ngOnInit(): void {
        this.load();
    }

    load(): void {
        this.loading = true;
        this.api.feedback(500).subscribe({
            next: (f) => {
                this.feedback = f;
                this.loading = false;
            },
            error: () => {
                this.feedback = [];
                this.loading = false;
                this.toastr.error('Failed to load feedback');
            },
        });
    }
}

import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatTooltipModule } from '@angular/material/tooltip';
import { AgGridAngular } from 'ag-grid-angular';
import { ColDef, GridApi } from 'ag-grid-community';
import { ToastrService } from 'ngx-toastr';

import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { InspectoSkeletonComponent } from 'app/inspecto/components/skeleton.component';
import {
    statusBadgeHtml,
    StatusBadgeComponent,
    StatusTone,
} from 'app/inspecto/components/status-badge.component';
import {
    actionsColumn,
    INSPECTO_DEFAULT_COL_DEF,
    InspectoGridThemeService,
    noRowsOverlay,
    refreshActionsCells,
} from 'app/inspecto/grid';

interface DemoRow {
    pipeline: string;
    status: string;
    files: number;
}

/**
 * Living design-system gallery (UI/UX audit — Long-term #1b). A dev/reference page that renders
 * each shared Inspecto pattern with a live example and a copy-paste snippet, so new panes reuse the
 * canonical components instead of re-rolling status colors, empty states, skeletons, grids or forms.
 * Because it imports and renders the real components, it can't drift from them. Route: `/design`.
 */
@Component({
    selector: 'inspecto-design-system',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatTooltipModule,
        AgGridAngular,
        StatusBadgeComponent,
        InspectoAlertComponent,
        InspectoEmptyStateComponent,
        InspectoSkeletonComponent,
    ],
    templateUrl: './design-system.component.html',
})
export class DesignSystemComponent {
    private fb = inject(FormBuilder);
    private toast = inject(ToastrService);
    readonly themeSvc = inject(InspectoGridThemeService);

    // ── Status badges ────────────────────────────────────────────────────────────────────────
    readonly tones: StatusTone[] = ['error', 'warning', 'info', 'success', 'neutral'];
    /** A few real tokens to show the case-insensitive token → tone classification. */
    readonly tokenExamples = ['FAILED', 'PAUSED', 'PENDING', 'HEALTHY', 'QUARANTINED', 'UNKNOWN'];

    // ── Grid / table ─────────────────────────────────────────────────────────────────────────
    readonly defaultColDef: ColDef = INSPECTO_DEFAULT_COL_DEF;
    readonly columnDefs: ColDef<DemoRow>[] = [
        { field: 'pipeline', headerName: 'Pipeline', flex: 1 },
        {
            field: 'status',
            headerName: 'Status',
            width: 140,
            // Sanctioned status-color path for a cell renderer — the shared builder, no hand-rolled colors.
            cellRenderer: (p: { value: string }) => statusBadgeHtml(p.value),
        },
        { field: 'files', headerName: 'Files', width: 110 },
        actionsColumn<DemoRow>([
            {
                icon: 'heroicons_outline:eye',
                hint: 'View (demo)',
                onClick: (r) => this.toast.info(`View ${r.pipeline}`),
            },
        ]),
    ];
    readonly fullRows: DemoRow[] = [
        { pipeline: 'orders-daily', status: 'HEALTHY', files: 128 },
        { pipeline: 'inventory-sync', status: 'PAUSED', files: 0 },
        { pipeline: 'returns-feed', status: 'FAILED', files: 3 },
    ];
    readonly emptyOverlay = noRowsOverlay('No data to display', 'This is the shared empty-grid overlay.');
    showEmpty = false;
    get gridRows(): DemoRow[] {
        return this.showEmpty ? [] : this.fullRows;
    }
    refreshActions(e: { api: GridApi }): void {
        refreshActionsCells(e);
        // Static rowData renders synchronously, hitting the same ag-Grid + Angular 21 initial-render
        // skip that affects the actions column — force the string-renderer status cells too.
        setTimeout(() => {
            if (e.api.isDestroyed()) return;
            e.api.refreshCells({ force: true, columns: ['status'] });
        });
    }

    // ── Reactive form + inline mat-error ─────────────────────────────────────────────────────
    readonly form = this.fb.group({
        id: ['', [Validators.required, Validators.pattern(/^[A-Za-z0-9][A-Za-z0-9._-]*$/)]],
    });
    submitForm(): void {
        if (this.form.invalid) {
            this.form.markAllAsTouched();
            this.toast.warning('Fix the highlighted field.');
            return;
        }
        this.toast.success(`Valid id: ${this.form.value.id}`);
    }

    // ── Snippets (copy-paste) ────────────────────────────────────────────────────────────────
    readonly snippets = {
        badge: `<inspecto-status-badge [value]="event.level" />\n// in an ag-Grid cellRenderer:\ncellRenderer: (p) => statusBadgeHtml(p.value)`,
        alert: `<inspecto-alert variant="warning" title="Read-only">\n  Editing is disabled (no write root configured).\n</inspecto-alert>`,
        empty: `<inspecto-empty-state\n  icon="heroicons_outline:queue-list"\n  title="Nothing yet"\n  message="No events match the current filters."\n  actionLabel="Clear filters"\n  (action)="reset()" />`,
        skeleton: `<inspecto-skeleton width="40%" height="0.875rem" />   <!-- a label -->\n<inspecto-skeleton [lines]="4" />                     <!-- a paragraph -->\n<inspecto-skeleton height="12rem" />                  <!-- a block -->`,
        grid: `<ag-grid-angular\n  class="h-[42rem] w-full"\n  [theme]="themeSvc.theme()"\n  [rowData]="rows"\n  [columnDefs]="columnDefs"\n  [defaultColDef]="defaultColDef"\n  [loading]="loading"\n  [overlayNoRowsTemplate]="emptyOverlay"\n  (firstDataRendered)="refreshActions($event)"\n  (rowDataUpdated)="refreshActions($event)" />`,
        form: `form = this.fb.group({\n  id: ['', [Validators.required, Validators.pattern(/^[A-Za-z0-9][A-Za-z0-9._-]*$/)]],\n});\nsubmit() {\n  if (this.form.invalid) { this.form.markAllAsTouched(); return; }\n  // ...\n}`,
    };
    copy(text: string): void {
        navigator.clipboard?.writeText(text).then(
            () => this.toast.success('Snippet copied'),
            () => this.toast.error('Copy failed'),
        );
    }
}

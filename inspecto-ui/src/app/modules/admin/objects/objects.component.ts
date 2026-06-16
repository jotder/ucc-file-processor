import { Component, inject, OnInit, ViewEncapsulation } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { ActivatedRoute, Router } from '@angular/router';
import { AgGridAngular } from 'ag-grid-angular';
import { ColDef } from 'ag-grid-community';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage, ObjectsService, OperationalObject } from 'app/inspecto/api';
import { InspectoAuthService } from 'app/inspecto/auth.service';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import {
    actionsColumn,
    fmtDateTime,
    INSPECTO_DEFAULT_COL_DEF,
    InspectoGridThemeService,
    noRowsOverlay,
    refreshActionsCells,
} from 'app/inspecto/grid';
import { ObjectCreateDialog } from './object-create.dialog';

/**
 * Operational-object list pane (Phase 2–4) — one reusable grid driven by route data: `/cases`
 * (type CASE) and `/issues` (type ISSUE) both render this with a different `type`/`title`. Lists the
 * objects of that type, with a create dialog and a one-click "advance lifecycle" action (the backend
 * still validates each transition). The detail pane (graph + comments/attachments) is a separate route.
 */
@Component({
    selector: 'app-objects',
    standalone: true,
    imports: [
        FormsModule,
        MatButtonModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        AgGridAngular,
    ],
    templateUrl: './objects.component.html',
    encapsulation: ViewEncapsulation.None,
})
export class ObjectsComponent implements OnInit {
    private api = inject(ObjectsService);
    private auth = inject(InspectoAuthService);
    private dialog = inject(MatDialog);
    private confirm = inject(InspectoConfirmService);
    private toastr = inject(ToastrService);
    private route = inject(ActivatedRoute);
    private router = inject(Router);
    readonly themeSvc = inject(InspectoGridThemeService);

    /** Object type this pane manages ('CASE' | 'ISSUE'), from route data. */
    readonly type = (this.route.snapshot.data['type'] as string) ?? 'ISSUE';
    readonly title = (this.route.snapshot.data['title'] as string) ?? 'Objects';
    readonly subtitle = (this.route.snapshot.data['subtitle'] as string) ?? '';

    objects: OperationalObject[] = [];
    loading = false;
    quickFilter = '';

    /** Empty-state overlay shown when the grid has no rows. */
    readonly noRows = noRowsOverlay(
        `No ${this.createLabel}s yet`,
        `New ${this.createLabel}s will appear here once they're created.`,
    );

    get canControl(): boolean {
        return this.auth.hasControl();
    }

    get createLabel(): string {
        return this.type === 'CASE' ? 'case' : this.type === 'ISSUE' ? 'issue' : 'object';
    }

    /** The next happy-path workflow action from a given status, per object type (backend re-validates). */
    private static readonly NEXT: Record<string, Record<string, string>> = {
        ISSUE: { OPEN: 'assign', ASSIGNED: 'start', IN_PROGRESS: 'resolve', RESOLVED: 'close' },
        CASE: { OPEN: 'investigate', INVESTIGATING: 'escalate', ESCALATED: 'resolve', RESOLVED: 'close' },
        ALERT: { OPEN: 'ack', ACKNOWLEDGED: 'resolve' },
    };

    nextAction(o: OperationalObject): string | undefined {
        return ObjectsComponent.NEXT[o.objectType]?.[(o.status ?? '').toUpperCase()];
    }

    readonly defaultColDef = INSPECTO_DEFAULT_COL_DEF;
    readonly columnDefs: ColDef<OperationalObject>[] = [
        { field: 'title', headerName: 'Title', flex: 1 },
        { field: 'status', headerName: 'Status', width: 140 },
        { field: 'severity', headerName: 'Severity', width: 110 },
        { field: 'priority', headerName: 'Priority', width: 100 },
        { field: 'assignee', headerName: 'Assignee', width: 130 },
        { field: 'correlationId', headerName: 'Pipeline / corr.', width: 160 },
        { field: 'createdAt', headerName: 'Created', width: 170, valueFormatter: (p) => fmtDateTime(p.value) },
        { field: 'updatedAt', headerName: 'Updated', width: 170, valueFormatter: (p) => fmtDateTime(p.value) },
        actionsColumn<OperationalObject>(
            [
                {
                    icon: 'heroicons_outline:eye',
                    hint: 'Open details',
                    onClick: (o) => this.open(o),
                },
                {
                    icon: 'heroicons_outline:arrow-right-circle',
                    hint: (o) => {
                        const a = this.nextAction(o);
                        return a ? `Advance: ${a}` : 'No further action';
                    },
                    visible: (o) => this.canControl && !!this.nextAction(o),
                    onClick: (o) => this.advance(o),
                },
            ],
            150,
        ),
    ];

    /** Open the detail pane for an object (also the grid's row-click target). */
    open(o: OperationalObject): void {
        this.router.navigate([this.type === 'CASE' ? '/cases' : '/issues', o.id]);
    }

    onRowClicked(e: { data?: OperationalObject }): void {
        if (e.data?.id) this.open(e.data);
    }

    ngOnInit(): void {
        this.load();
    }

    load(): void {
        this.loading = true;
        this.api.list({ type: this.type, limit: 500 }).subscribe({
            next: (o) => {
                this.objects = o;
                this.loading = false;
            },
            error: () => {
                this.objects = [];
                this.loading = false;
            },
        });
    }

    openCreate(): void {
        this.dialog
            .open(ObjectCreateDialog, {
                data: { type: this.type, label: this.createLabel },
                width: '560px',
                maxHeight: '85vh',
            })
            .afterClosed()
            .subscribe((created) => {
                if (created) this.load();
            });
    }

    async advance(o: OperationalObject): Promise<void> {
        const action = this.nextAction(o);
        if (!action) return;
        if (!(await this.confirm.confirm(`${action} "${o.title}"?`, 'Advance lifecycle'))) return;
        this.api.transition(o.id, action).subscribe({
            next: (u) => {
                this.toastr.success(`${o.title}: ${u.status}`);
                this.load();
            },
            error: (e) => this.toastr.error(apiErrorMessage(e, 'Transition failed')),
        });
    }

    readonly refreshActions = refreshActionsCells;
}

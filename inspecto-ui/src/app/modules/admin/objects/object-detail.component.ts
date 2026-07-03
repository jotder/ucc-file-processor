import { Component, inject, OnInit, ViewEncapsulation } from '@angular/core';
import { FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatTabsModule } from '@angular/material/tabs';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage, EventRow, EventsService, NodeKind, ObjectGraph, ObjectNote, ObjectsService, OperationalObject } from 'app/inspecto/api';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { InspectoSkeletonComponent } from 'app/inspecto/components/skeleton.component';
import { StatusBadgeComponent } from 'app/inspecto/components/status-badge.component';
import { fmtDateTime } from 'app/inspecto/grid';
import { G6GraphData } from 'app/modules/admin/catalog/catalog-graph';
import { GraphViewComponent } from 'app/modules/admin/catalog/graph-view.component';
import { ObjectLinkDialog } from './object-link.dialog';

type TabKey = 'overview' | 'graph' | 'events' | 'comments' | 'attachments';

/**
 * Operational-object detail (Phase 2–4) — one object with its lifecycle actions, its correlation
 * graph (reusing the catalog G6 view fed by GET /objects/{id}/graph), and its append-only note thread
 * (comments + attachment references), plus a one-click RCA skeleton. Reused for {@code /cases/:id} and
 * {@code /incidents/:id}; type-agnostic (it reads the object's own {@code objectType}).
 */
@Component({
    selector: 'app-object-detail',
    standalone: true,
    imports: [
        MatButtonModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatTabsModule,
        MatTooltipModule,
        ReactiveFormsModule,
        RouterLink,
        GraphViewComponent,
        InspectoEmptyStateComponent,
        InspectoSkeletonComponent,
        StatusBadgeComponent,
    ],
    templateUrl: './object-detail.component.html',
    encapsulation: ViewEncapsulation.None,
})
export class ObjectDetailComponent implements OnInit {
    private api = inject(ObjectsService);
    private eventsApi = inject(EventsService);
    private route = inject(ActivatedRoute);
    private router = inject(Router);
    private dialog = inject(MatDialog);
    private toastr = inject(ToastrService);
    private fb = inject(FormBuilder);

    id = '';
    obj: OperationalObject | null = null;
    loading = false;

    readonly tabs: { id: TabKey; label: string }[] = [
        { id: 'overview', label: 'Overview' },
        { id: 'graph', label: 'Graph' },
        { id: 'events', label: 'Events' },
        { id: 'comments', label: 'Comments' },
        { id: 'attachments', label: 'Attachments' },
    ];
    selectedIndex = 0;
    get activeTab(): TabKey {
        return this.tabs[this.selectedIndex].id;
    }

    comments: ObjectNote[] = [];
    attachments: ObjectNote[] = [];
    relatedEvents: EventRow[] = [];
    eventsLoaded = false;
    g6: G6GraphData | null = null;

    readonly commentForm: FormGroup = this.fb.group({
        body: ['', Validators.required],
    });
    readonly attachForm: FormGroup = this.fb.group({
        name: ['', Validators.required],
        uri: ['', Validators.required],
    });

    readonly fmt = fmtDateTime;

    /** Legal next workflow actions from the current status, per object type (backend re-validates). */
    private static readonly TRANSITIONS: Record<string, Record<string, string[]>> = {
        INCIDENT: { OPEN: ['assign'], ASSIGNED: ['start'], IN_PROGRESS: ['resolve'], RESOLVED: ['close'] },
        CASE: { OPEN: ['investigate'], INVESTIGATING: ['escalate', 'resolve'], ESCALATED: ['resolve'], RESOLVED: ['close'] },
        ALERT: { OPEN: ['ack', 'resolve'], ACKNOWLEDGED: ['resolve'] },
    };

    get actions(): string[] {
        if (!this.obj) return [];
        return ObjectDetailComponent.TRANSITIONS[this.obj.objectType]?.[(this.obj.status ?? '').toUpperCase()] ?? [];
    }

    /** The object's attributes as display rows. */
    get attributeRows(): { key: string; value: string }[] {
        const a = this.obj?.attributes ?? {};
        return Object.keys(a).map((k) => ({ key: k, value: a[k] }));
    }

    ngOnInit(): void {
        this.id = this.route.snapshot.paramMap.get('id') ?? '';
        this.loadObject();
    }

    loadObject(): void {
        this.loading = true;
        this.api.get(this.id).subscribe({
            next: (o) => {
                this.obj = o;
                this.loading = false;
            },
            error: () => {
                this.obj = null;
                this.loading = false;
                this.toastr.error(`Object ${this.id} not found`);
            },
        });
    }

    onTabChange(): void {
        if (this.activeTab === 'graph') this.loadGraph();
        else if (this.activeTab === 'events') this.loadEvents();
        else if (this.activeTab === 'comments') this.loadComments();
        else if (this.activeTab === 'attachments') this.loadAttachments();
    }

    /** Events sharing this object's correlation id — the engine-level timeline behind the object. */
    loadEvents(): void {
        this.eventsLoaded = false;
        const cid = this.obj?.correlationId;
        if (!cid) {
            this.relatedEvents = [];
            this.eventsLoaded = true;
            return;
        }
        this.eventsApi.search({ correlationId: cid, limit: 200 }).subscribe({
            next: (e) => {
                this.relatedEvents = e;
                this.eventsLoaded = true;
            },
            error: () => {
                this.relatedEvents = [];
                this.eventsLoaded = true;
            },
        });
    }

    loadGraph(): void {
        this.api.graph(this.id, 2).subscribe({
            next: (g) => (this.g6 = this.toG6(g)),
            error: () => (this.g6 = { nodes: [], edges: [] }),
        });
    }

    loadComments(): void {
        this.api.comments(this.id).subscribe({
            next: (c) => (this.comments = c),
            error: () => (this.comments = []),
        });
    }

    loadAttachments(): void {
        this.api.attachments(this.id).subscribe({
            next: (a) => (this.attachments = a),
            error: () => (this.attachments = []),
        });
    }

    transition(action: string): void {
        this.api.transition(this.id, action).subscribe({
            next: (o) => {
                this.obj = o;
                this.toastr.success(`${o.title}: ${o.status}`);
            },
            error: (e) => this.toastr.error(apiErrorMessage(e, 'Transition failed')),
        });
    }

    addComment(): void {
        if (this.commentForm.invalid) {
            this.commentForm.markAllAsTouched();
            return;
        }
        const body = (this.commentForm.value.body as string).trim();
        if (!body) return;
        this.api.addComment(this.id, body).subscribe({
            next: () => {
                this.commentForm.reset({ body: '' });
                this.loadComments();
            },
            error: (e) => this.toastr.error(apiErrorMessage(e, 'Could not add comment')),
        });
    }

    addAttachment(): void {
        if (this.attachForm.invalid) {
            this.attachForm.markAllAsTouched();
            return;
        }
        const name = (this.attachForm.value.name as string).trim();
        const uri = (this.attachForm.value.uri as string).trim();
        if (!name || !uri) return;
        this.api.addAttachment(this.id, { name, uri }).subscribe({
            next: () => {
                this.attachForm.reset({ name: '', uri: '' });
                this.loadAttachments();
            },
            error: (e) => this.toastr.error(apiErrorMessage(e, 'Could not add attachment')),
        });
    }

    applyRca(): void {
        const sections = ['Summary', 'Timeline', 'Root cause', 'Impact', 'Remediation'];
        this.api.applyRca(this.id, { sections }).subscribe({
            next: () => {
                this.toastr.success('RCA skeleton added to comments');
                this.selectedIndex = this.tabs.findIndex((t) => t.id === 'comments');
                this.loadComments();
            },
            error: (e) => this.toastr.error(apiErrorMessage(e, 'Could not apply RCA')),
        });
    }

    openLink(): void {
        if (!this.obj) return;
        this.dialog
            .open(ObjectLinkDialog, {
                data: { fromId: this.id, fromType: this.obj.objectType },
                width: '520px',
                maxHeight: '85vh',
            })
            .afterClosed()
            .subscribe((created) => {
                if (created) this.loadGraph();
            });
    }

    onNodeClick(nodeId: string): void {
        if (!nodeId || nodeId === this.id) return;
        const base = this.router.url.split('/')[1] || 'incidents';
        this.router.navigate(['/' + base, nodeId]);
    }

    /** The owning list route (`incidents` / `cases`) this detail was opened from. */
    get listBase(): string {
        return this.router.url.split('/')[1] || 'incidents';
    }

    /** Title-cased label for the breadcrumb (e.g. `Incidents`). */
    get listLabel(): string {
        const b = this.listBase;
        return b.charAt(0).toUpperCase() + b.slice(1);
    }

    back(): void {
        this.router.navigate(['/' + this.listBase]);
    }

    private toG6(g: ObjectGraph): G6GraphData {
        return {
            nodes: g.nodes.map((n) => ({
                id: n.id,
                data: { label: n.title || n.id, kind: n.objectType as unknown as NodeKind },
            })),
            edges: g.edges.map((e, i) => ({
                id: `${e.from}->${e.to}:${e.relationship}:${i}`,
                source: e.from,
                target: e.to,
                data: { kind: e.relationship },
            })),
        };
    }
}

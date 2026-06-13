import { Component, inject, OnInit, ViewEncapsulation } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatTabsModule } from '@angular/material/tabs';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ActivatedRoute, Router } from '@angular/router';
import { ToastrService } from 'ngx-toastr';
import { NodeKind, ObjectGraph, ObjectNote, ObjectsService, OperationalObject } from 'app/inspecto/api';
import { InspectoAuthService } from 'app/inspecto/auth.service';
import { fmtDateTime } from 'app/inspecto/grid';
import { G6GraphData } from 'app/modules/admin/catalog/catalog-graph';
import { GraphViewComponent } from 'app/modules/admin/catalog/graph-view.component';

type TabKey = 'overview' | 'graph' | 'comments' | 'attachments';

/**
 * Operational-object detail (Phase 2–4) — one object with its lifecycle actions, its correlation
 * graph (reusing the catalog G6 view fed by GET /objects/{id}/graph), and its append-only note thread
 * (comments + attachment references), plus a one-click RCA skeleton. Reused for {@code /cases/:id} and
 * {@code /issues/:id}; type-agnostic (it reads the object's own {@code objectType}).
 */
@Component({
    selector: 'app-object-detail',
    standalone: true,
    imports: [
        FormsModule,
        MatButtonModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatTabsModule,
        MatTooltipModule,
        GraphViewComponent,
    ],
    templateUrl: './object-detail.component.html',
    encapsulation: ViewEncapsulation.None,
})
export class ObjectDetailComponent implements OnInit {
    private api = inject(ObjectsService);
    private route = inject(ActivatedRoute);
    private router = inject(Router);
    private auth = inject(InspectoAuthService);
    private toastr = inject(ToastrService);

    id = '';
    obj: OperationalObject | null = null;
    loading = false;

    readonly tabs: { id: TabKey; label: string }[] = [
        { id: 'overview', label: 'Overview' },
        { id: 'graph', label: 'Graph' },
        { id: 'comments', label: 'Comments' },
        { id: 'attachments', label: 'Attachments' },
    ];
    selectedIndex = 0;
    get activeTab(): TabKey {
        return this.tabs[this.selectedIndex].id;
    }

    comments: ObjectNote[] = [];
    attachments: ObjectNote[] = [];
    g6: G6GraphData | null = null;

    newComment = '';
    newAttName = '';
    newAttUri = '';

    readonly fmt = fmtDateTime;

    get canControl(): boolean {
        return this.auth.hasControl();
    }

    /** Legal next workflow actions from the current status, per object type (backend re-validates). */
    private static readonly TRANSITIONS: Record<string, Record<string, string[]>> = {
        ISSUE: { OPEN: ['assign'], ASSIGNED: ['start'], IN_PROGRESS: ['resolve'], RESOLVED: ['close'] },
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
        else if (this.activeTab === 'comments') this.loadComments();
        else if (this.activeTab === 'attachments') this.loadAttachments();
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
            error: (e) => this.toastr.error(e?.error?.error ?? 'Transition failed'),
        });
    }

    addComment(): void {
        const body = this.newComment.trim();
        if (!body) return;
        this.api.addComment(this.id, body).subscribe({
            next: () => {
                this.newComment = '';
                this.loadComments();
            },
            error: (e) => this.toastr.error(e?.error?.error ?? 'Could not add comment'),
        });
    }

    addAttachment(): void {
        const name = this.newAttName.trim();
        const uri = this.newAttUri.trim();
        if (!name || !uri) return;
        this.api.addAttachment(this.id, { name, uri }).subscribe({
            next: () => {
                this.newAttName = '';
                this.newAttUri = '';
                this.loadAttachments();
            },
            error: (e) => this.toastr.error(e?.error?.error ?? 'Could not add attachment'),
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
            error: (e) => this.toastr.error(e?.error?.error ?? 'Could not apply RCA'),
        });
    }

    onNodeClick(nodeId: string): void {
        if (!nodeId || nodeId === this.id) return;
        const base = this.router.url.split('/')[1] || 'issues';
        this.router.navigate(['/' + base, nodeId]);
    }

    back(): void {
        const base = this.router.url.split('/')[1] || 'issues';
        this.router.navigate(['/' + base]);
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

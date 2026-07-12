import { ChangeDetectionStrategy, Component, computed, inject, OnInit, signal, ViewEncapsulation } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatMenuModule } from '@angular/material/menu';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ActivatedRoute } from '@angular/router';
import { ColDef, ICellRendererParams, ValueGetterParams } from 'ag-grid-community';
import { forkJoin, Observable, of } from 'rxjs';
import { switchMap } from 'rxjs/operators';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage, ObjectsService, OperationalObject, Tag, TagRule, UpdateObject, WorkflowDef } from 'app/inspecto/api';
import {
    STATUS_BADGE_BASE,
    statusBadgeClasses,
    statusBadgeHtml,
} from 'app/inspecto/components/status-badge.component';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { DataTableComponent } from 'app/inspecto/data-table';
import {
    caseFoldersFrom,
    currentOperator,
    DEFAULT_CASE_WORKFLOW,
    displayStatus,
    INCIDENT_FOLDERS,
    INCIDENT_PRIORITIES,
    isEscalated,
    MailFolder,
    objectCategory,
    objectTags,
    postmortemGaps,
    stateLabel,
} from './mail-model';
import { CaseAnalyticsDialog } from './case-analytics.dialog';
import { CaseRulesDialog } from './case-rules.dialog';
import { CategorizeDialog } from './categorize.dialog';
import { MergeCasesDialog } from './merge-cases.dialog';
import { ObjectCreateDialog } from './object-create.dialog';
import { PostmortemPanelComponent } from './postmortem-panel.component';
import { ResolveDialog } from './resolve.dialog';
import { TagChange, TagDialog } from './tag.dialog';
import { TagRulesDialog } from './tag-rules.dialog';

const NAV_WIDTH_KEY = 'inspecto.mail.navWidth';
const NAV_COLLAPSED_KEY = 'inspecto.mail.navCollapsed';
const NAV_MIN = 170;
const NAV_MAX = 420;

/** Escape user-entered text before it enters an innerHTML cell renderer. */
function esc(s: string): string {
    return s.replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]!);
}

/** Mail-style date: time today, "Jul 3" this year, full date otherwise. */
function mailDate(ms: number | undefined): string {
    if (!ms) return '';
    const d = new Date(ms);
    const now = new Date();
    if (d.toDateString() === now.toDateString()) {
        return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    }
    if (d.getFullYear() === now.getFullYear()) {
        return d.toLocaleDateString([], { month: 'short', day: 'numeric' });
    }
    return d.toLocaleDateString();
}

/**
 * Mail-like 3-pane Incidents / Case Manager (design: docs/superpower/incidents-mail-ui-design.md):
 * a resizable + collapsible folder nav (state folders + Tags), a checkbox-selectable list with the
 * lifecycle toolbar (Accept · Prioritize · Escalate · Resolve · Archive/Close · Reopen), and the
 * postmortem detail panel. Driven by route `data.type` — `/incidents` (INCIDENT) and `/cases`
 * (CASE) both render this, exactly like the list pane it replaces.
 */
@Component({
    selector: 'app-object-mail',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatMenuModule,
        MatTooltipModule,
        DataTableComponent,
        PostmortemPanelComponent,
    ],
    templateUrl: './object-mail.component.html',
    encapsulation: ViewEncapsulation.None,
})
export class ObjectMailComponent implements OnInit {
    private api = inject(ObjectsService);
    private dialog = inject(MatDialog);
    private confirm = inject(InspectoConfirmService);
    private toastr = inject(ToastrService);
    private route = inject(ActivatedRoute);

    readonly type = (this.route.snapshot.data['type'] as string) ?? 'INCIDENT';
    readonly title = (this.route.snapshot.data['title'] as string) ?? 'Incidents';
    readonly isIncident = this.type === 'INCIDENT';
    readonly priorities = INCIDENT_PRIORITIES;
    readonly me = currentOperator();

    /** The effective CASE lifecycle (C6) — folders + toolbar verbs derive from it; TOON overrides win. */
    readonly workflowDef = signal<WorkflowDef>(DEFAULT_CASE_WORKFLOW);
    readonly folders = computed<MailFolder[]>(() =>
        this.isIncident ? INCIDENT_FOLDERS : caseFoldersFrom(this.workflowDef()),
    );

    get createLabel(): string {
        return this.isIncident ? 'incident' : 'case';
    }

    // ── state ─────────────────────────────────────────────────────────────────────
    readonly objects = signal<OperationalObject[]>([]);
    readonly loading = signal(false);
    readonly folderId = signal(this.isIncident ? 'identified' : 'open');
    readonly tagFilter = signal<string | null>(null);
    readonly selected = signal<OperationalObject[]>([]);
    readonly detail = signal<OperationalObject | null>(null);

    readonly navCollapsed = signal(localStorage.getItem(NAV_COLLAPSED_KEY) === 'true');
    readonly navWidth = signal(
        Math.min(NAV_MAX, Math.max(NAV_MIN, Number(localStorage.getItem(NAV_WIDTH_KEY)) || 240)),
    );

    // Tag registry + Tag Rules (user-created; loaded independently, degrade gracefully offline).
    readonly tagRegistry = signal<Tag[]>([]);
    readonly tagRulesList = signal<TagRule[]>([]);
    readonly navTagOpen = signal(false);
    readonly navNewTag = new FormControl('');

    // ── derived ───────────────────────────────────────────────────────────────────
    readonly rows = computed<OperationalObject[]>(() => {
        const tag = this.tagFilter();
        if (tag) return this.objects().filter((o) => objectTags(o).includes(tag));
        const f = this.folders().find((x) => x.id === this.folderId());
        return f ? this.objects().filter((o) => f.match(o, this.me)) : this.objects();
    });

    readonly counts = computed<Map<string, number>>(() => {
        const all = this.objects();
        return new Map(this.folders().map((f) => [f.id, all.filter((o) => f.match(o, this.me)).length]));
    });

    /** Registry tags (zero-count ones stay visible) merged with tags found on the loaded rows. */
    readonly tags = computed<{ tag: string; count: number }[]>(() => {
        const byTag = new Map<string, number>();
        for (const t of this.tagRegistry()) byTag.set(t.name, 0);
        for (const o of this.objects()) for (const t of objectTags(o)) byTag.set(t, (byTag.get(t) ?? 0) + 1);
        return [...byTag.entries()]
            .map(([tag, count]) => ({ tag, count }))
            .sort((a, b) => b.count - a.count || a.tag.localeCompare(b.tag));
    });

    readonly activeLabel = computed(() => {
        const tag = this.tagFilter();
        if (tag) return `Tag: ${tag}`;
        return this.folders().find((f) => f.id === this.folderId())?.label ?? '';
    });

    /**
     * C6: the case toolbar's action verbs — the union of legal workflow actions across the
     * selection (each button applies to the selected cases it is legal for). Incidents keep the
     * fixed mail-metaphor toolbar.
     */
    readonly caseActions = computed<string[]>(() => {
        if (this.isIncident) return [];
        const wf = this.workflowDef();
        const out: string[] = [];
        for (const o of this.selected()) {
            for (const t of wf.transitions) {
                if (t.from === displayStatus(o) && !out.includes(t.action)) out.push(t.action);
            }
        }
        return out;
    });
    readonly stateLabel = stateLabel;

    // Incident toolbar enablement (fixed mail-metaphor verbs; cases use the dynamic caseActions()).
    readonly canAccept = computed(() => this.isIncident && this.selected().some((o) => displayStatus(o) === 'IDENTIFIED'));
    readonly canResolve = computed(() =>
        this.isIncident && this.selected().some((o) => ['IDENTIFIED', 'DIAGNOSING'].includes(displayStatus(o))),
    );
    readonly canArchive = computed(() => this.isIncident && this.selected().some((o) => displayStatus(o) !== 'ARCHIVED'));
    readonly canReopen = computed(() =>
        this.isIncident && this.selected().some((o) => ['RESOLVED', 'ARCHIVED'].includes(displayStatus(o))),
    );
    readonly canEscalate = computed(() => this.isIncident && this.selected().length > 0);
    readonly escalateLabel = computed(() =>
        this.isIncident && this.selected().length > 0 && this.selected().every(isEscalated) ? 'De-escalate' : 'Escalate',
    );

    // ── grid ──────────────────────────────────────────────────────────────────────
    // Untyped ColDef[]: escalated/category/tags are derived (attribute-bag) columns, not model fields.
    readonly columnDefs: ColDef[] = [
        {
            field: 'escalated',
            headerName: 'Escalated', // narrow column: visually truncated, present for AT (axe empty-table-header)
            headerTooltip: 'Escalated',
            width: 60,
            valueGetter: (p: ValueGetterParams<OperationalObject>) => (p.data ? isEscalated(p.data) : false),
            cellRenderer: (p: ICellRendererParams<OperationalObject>) =>
                p.value
                    ? `<span class="${STATUS_BADGE_BASE} ${statusBadgeClasses('CRITICAL')}" title="Escalated">!</span>`
                    : '',
        },
        {
            field: 'priority',
            headerName: 'Priority',
            width: 116,
            cellRenderer: (p: ICellRendererParams<OperationalObject>) => (p.value ? statusBadgeHtml(p.value as string) : '—'),
        },
        {
            field: 'category',
            headerName: 'Category',
            width: 210,
            valueGetter: (p: ValueGetterParams<OperationalObject>) => (p.data ? objectCategory(p.data) : ''),
            tooltipValueGetter: (p) => (p.data ? objectCategory(p.data) : ''),
        },
        {
            field: 'tags',
            headerName: 'Tags',
            width: 170,
            valueGetter: (p: ValueGetterParams<OperationalObject>) => (p.data ? objectTags(p.data).join(', ') : ''),
            cellRenderer: (p: ICellRendererParams<OperationalObject>) =>
                p.data ? objectTags(p.data).map((t) => statusBadgeHtml(esc(t))).join(' ') : '',
        },
        {
            field: 'title',
            headerName: 'Description',
            flex: 1,
            minWidth: 260,
            cellRenderer: (p: ICellRendererParams<OperationalObject>) => {
                const o = p.data;
                if (!o) return '';
                const desc = o.description ? `<span class="text-secondary"> — ${esc(o.description)}</span>` : '';
                return `<span class="font-semibold">${esc(o.title)}</span>${desc}`;
            },
        },
        {
            field: 'status',
            headerName: 'Status',
            width: 130,
            valueGetter: (p: ValueGetterParams<OperationalObject>) => (p.data ? displayStatus(p.data) : ''),
            cellRenderer: (p: ICellRendererParams<OperationalObject>) => statusBadgeHtml(p.value as string),
        },
        {
            field: 'updatedAt',
            headerName: 'Date',
            width: 100,
            valueFormatter: (p) => mailDate(p.value as number),
        },
    ];

    ngOnInit(): void {
        if (!this.isIncident) {
            // C6: fetch the effective (possibly TOON-overridden) lifecycle; the built-in stays the fallback.
            this.api.workflow('CASE').subscribe({
                next: (wf) => {
                    this.workflowDef.set(wf);
                    if (!this.folders().some((f) => f.id === this.folderId()))
                        this.folderId.set(wf.initial.toLowerCase());
                },
                error: () => undefined,
            });
        }
        this.reload();
    }

    /** Refresh the tag registry + rules; failures leave the previous value (offline-tolerant). */
    loadTags(): void {
        this.api.tags().subscribe({ next: (t) => this.tagRegistry.set(t), error: () => undefined });
        this.api.tagRules().subscribe({ next: (r) => this.tagRulesList.set(r), error: () => undefined });
    }

    reload(): void {
        this.loadTags();
        this.loading.set(true);
        this.api.list({ type: this.type, limit: 500 }).subscribe({
            next: (o) => {
                this.objects.set(o);
                this.loading.set(false);
                this.selected.set([]);
                const open = this.detail();
                if (open) this.detail.set(o.find((x) => x.id === open.id) ?? null);
            },
            error: () => {
                this.objects.set([]);
                this.loading.set(false);
            },
        });
    }

    // ── nav interactions ──────────────────────────────────────────────────────────
    selectFolder(id: string): void {
        this.folderId.set(id);
        this.tagFilter.set(null);
    }

    selectTag(tag: string): void {
        this.tagFilter.set(this.tagFilter() === tag ? null : tag);
    }

    /** Create a tag from the nav's inline input (Gmail's "create new label"). */
    createNavTag(): void {
        const name = (this.navNewTag.value ?? '').trim();
        if (!name) return;
        this.api.createTag(name).subscribe({
            next: () => {
                this.navNewTag.setValue('');
                this.navTagOpen.set(false);
                this.loadTags();
            },
            error: (e) => this.toastr.error(apiErrorMessage(e, 'Could not create tag')),
        });
    }

    isActiveFolder(id: string): boolean {
        return !this.tagFilter() && this.folderId() === id;
    }

    toggleNav(): void {
        this.navCollapsed.update((v) => !v);
        localStorage.setItem(NAV_COLLAPSED_KEY, String(this.navCollapsed()));
    }

    startResize(e: PointerEvent): void {
        e.preventDefault();
        const startX = e.clientX;
        const startW = this.navWidth();
        const move = (ev: PointerEvent): void => this.setNavWidth(startW + (ev.clientX - startX));
        const up = (): void => {
            window.removeEventListener('pointermove', move);
            window.removeEventListener('pointerup', up);
        };
        window.addEventListener('pointermove', move);
        window.addEventListener('pointerup', up);
    }

    onHandleKeydown(e: KeyboardEvent): void {
        if (e.key === 'ArrowLeft') this.setNavWidth(this.navWidth() - 16);
        if (e.key === 'ArrowRight') this.setNavWidth(this.navWidth() + 16);
    }

    private setNavWidth(px: number): void {
        const w = Math.min(NAV_MAX, Math.max(NAV_MIN, Math.round(px)));
        this.navWidth.set(w);
        localStorage.setItem(NAV_WIDTH_KEY, String(w));
    }

    // ── list interactions ─────────────────────────────────────────────────────────
    onSelection(rows: Record<string, unknown>[]): void {
        this.selected.set(rows as unknown as OperationalObject[]);
    }

    onRowClicked(o: OperationalObject): void {
        if (o?.id) this.detail.set(o);
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
                if (created) this.reload();
            });
    }

    onPanelAction(id: string): void {
        const o = this.detail();
        if (!o) return;
        const one = [o];
        if (!this.isIncident && id !== 'resolve') {
            void this.runCaseAction(id, one);   // case panel verbs are workflow actions (C6)
            return;
        }
        switch (id) {
            case 'accept': return this.accept(one);
            case 'resolve': void this.resolve(one); return;
            case 'archive': void this.archive(one); return;
            case 'reopen': return this.reopen(one);
            case 'escalate': return this.escalate(one);
        }
    }

    // ── lifecycle actions (bulk over the selection, or an explicit target list) ────
    /**
     * Accept: Identified → Diagnosing. The 3-layer categorization is enforced here — when any
     * target has no category yet, one categorize dialog collects it (applied to the uncategorized
     * targets); unassigned targets are assigned to me.
     */
    accept(targets = this.selected().filter((o) => displayStatus(o) === 'IDENTIFIED')): void {
        targets = targets.filter((o) => displayStatus(o) === 'IDENTIFIED');
        if (!targets.length) return;
        const missing = targets.filter((o) => !objectCategory(o));
        const run = (category: string | null): void =>
            this.bulk(
                targets,
                (o) => {
                    const patch: UpdateObject = {};
                    if (!o.assignee) patch.assignee = this.me;
                    if (!objectCategory(o) && category) patch.attributes = { category };
                    const needsPatch = patch.assignee !== undefined || patch.attributes !== undefined;
                    return (needsPatch ? this.api.update(o.id, patch) : of(o)).pipe(
                        switchMap(() => this.api.transition(o.id, 'accept', this.me)),
                    );
                },
                `Accepted ${targets.length} — now Diagnosing`,
            );
        if (missing.length) {
            this.dialog
                .open(CategorizeDialog, {
                    width: '480px',
                    data: {
                        hint: `${missing.length} of the selected incidents ${missing.length === 1 ? 'has' : 'have'} no category — Accept requires the 3-layer categorization.`,
                    },
                })
                .afterClosed()
                .subscribe((category?: string | null) => {
                    if (category) run(category);
                });
        } else {
            run(null);
        }
    }

    prioritize(priority: string, targets = this.selected()): void {
        this.bulk(targets, (o) => this.api.update(o.id, { priority }), `Priority set to ${priority}`);
    }

    /** Manual tagging: the tri-state tag dialog decides what to add/remove on every target. */
    tagSelection(targets = this.selected()): void {
        if (!targets.length) return;
        this.dialog
            .open(TagDialog, { width: '420px', data: { targets, registry: this.tagRegistry() } })
            .afterClosed()
            .subscribe((change?: TagChange | null) => {
                // Even on cancel a tag may have been created inside the dialog — refresh the registry.
                if (!change || (!change.add.length && !change.remove.length)) {
                    this.loadTags();
                    return;
                }
                this.bulk(
                    targets,
                    (o) => {
                        const tags = new Set(objectTags(o));
                        change.add.forEach((t) => tags.add(t));
                        change.remove.forEach((t) => tags.delete(t));
                        return this.api.update(o.id, { attributes: { tags: [...tags].join(',') } });
                    },
                    'Tags updated',
                );
            });
    }

    /** Case Rules manager (C5) — saved searches that auto-group incidents into cases. */
    openCaseRules(): void {
        this.dialog
            .open(CaseRulesDialog, { width: '680px', maxHeight: '85vh' })
            .afterClosed()
            .subscribe((changed?: boolean) => {
                if (changed) this.reload();
            });
    }

    /** Case analytics (C4) — cycle time / backlog / impact rollup. */
    openAnalytics(): void {
        this.dialog.open(CaseAnalyticsDialog, {
            width: '640px',
            maxHeight: '85vh',
            data: { type: this.type, typeLabel: this.title },
        });
    }

    /** Merge the selected cases into one survivor (C2, GLOSSARY §9 — the dialog picks the survivor). */
    mergeSelection(): void {
        const cases = this.selected();
        if (cases.length < 2) return;
        this.dialog
            .open(MergeCasesDialog, { width: '520px', data: { cases } })
            .afterClosed()
            .subscribe((merged?: boolean) => {
                if (merged) this.reload();
            });
    }

    /** Manage Tag Rules (saved searches that tag automatically + in bulk, Gmail-filter style). */
    openTagRules(): void {
        this.dialog
            .open(TagRulesDialog, {
                width: '640px',
                maxHeight: '85vh',
                data: {
                    type: this.type,
                    typeLabel: this.title,
                    registry: this.tagRegistry(),
                    rules: this.tagRulesList().filter((r) => (r.filter?.type ?? this.type).toUpperCase() === this.type),
                },
            })
            .afterClosed()
            .subscribe((changed?: boolean) => {
                if (changed) this.reload();
                else this.loadTags();
            });
    }

    /** Incidents toggle the `escalated` flag (the mail "star"); case escalation is a workflow action. */
    escalate(targets = this.selected()): void {
        const clear = targets.length > 0 && targets.every(isEscalated);
        this.bulk(
            targets,
            (o) => this.api.update(o.id, { attributes: { escalated: clear ? 'false' : 'true' } }),
            clear ? 'De-escalated' : 'Escalated',
        );
    }

    /**
     * Resolved requires a resolution comment — appended to each object before the transition.
     * I1 soft gate: incidents whose mandatory resolution pattern (timeline · cause analysis ·
     * corrective actions · SLA) is incomplete warn first — never block.
     */
    async resolve(targets?: OperationalObject[]): Promise<void> {
        const caseLegal = new Set(
            this.workflowDef().transitions.filter((t) => t.action === 'resolve').map((t) => t.from),
        );
        const list = (targets ?? this.selected()).filter((o) =>
            this.isIncident ? ['IDENTIFIED', 'DIAGNOSING'].includes(displayStatus(o)) : caseLegal.has(displayStatus(o)),
        );
        if (!list.length) return;
        if (this.isIncident) {
            const incomplete = list.map((o) => postmortemGaps(o)).filter((gaps) => gaps.length);
            if (incomplete.length) {
                const missing = [...new Set(incomplete.flat())].join(', ');
                const ok = await this.confirm.confirm(
                    `${incomplete.length} of ${list.length} selected incident${list.length === 1 ? '' : 's'} ` +
                        `${incomplete.length === 1 ? 'has' : 'have'} an incomplete resolution pattern ` +
                        `(missing: ${missing}) — resolve anyway?`,
                    'Resolution pattern',
                );
                if (!ok) return;
            }
        }
        this.dialog
            .open(ResolveDialog, { width: '560px', data: { count: list.length, label: this.createLabel } })
            .afterClosed()
            .subscribe((comment?: string | null) => {
                if (!comment) return;
                this.bulk(
                    list,
                    (o) =>
                        this.api
                            .addComment(o.id, comment, this.me)
                            .pipe(switchMap(() => this.api.transition(o.id, 'resolve', this.me))),
                    `Resolved ${list.length}`,
                );
            });
    }

    /** C6: run one workflow action over the selected cases it is legal for (the dynamic toolbar). */
    async runCaseAction(action: string, targets = this.selected()): Promise<void> {
        const wf = this.workflowDef();
        const moves = wf.transitions.filter((t) => t.action === action);
        const legalFrom = new Set(moves.map((t) => t.from));
        const list = targets.filter((o) => legalFrom.has(displayStatus(o)));
        if (!list.length) return;
        if (action === 'resolve') return this.resolve(list);
        const landsTerminal = moves.length > 0 && moves.every((t) => wf.terminal.includes(t.to));
        if (landsTerminal) {
            const ok = await this.confirm.confirm(
                `${stateLabel(action)} ${list.length} case${list.length === 1 ? '' : 's'}? This is a terminal move.`,
                stateLabel(action),
            );
            if (!ok) return;
        }
        this.bulk(list, (o) => this.api.transition(o.id, action, this.me), `${stateLabel(action)}: ${list.length}`);
    }

    async archive(targets?: OperationalObject[]): Promise<void> {
        const list = (targets ?? this.selected()).filter((o) => displayStatus(o) !== 'ARCHIVED');
        if (!list.length) return;
        if (!(await this.confirm.confirm(`Archive ${list.length} incident${list.length === 1 ? '' : 's'}?`, 'Archive'))) return;
        this.bulk(list, (o) => this.api.transition(o.id, 'archive', this.me), `Archived ${list.length}`);
    }

    reopen(targets?: OperationalObject[]): void {
        const list = (targets ?? this.selected()).filter((o) => ['RESOLVED', 'ARCHIVED'].includes(displayStatus(o)));
        this.bulk(list, (o) => this.api.transition(o.id, 'reopen', this.me), 'Reopened — back to Diagnosing');
    }

    private bulk(
        targets: OperationalObject[],
        op: (o: OperationalObject) => Observable<unknown>,
        done: string,
    ): void {
        if (!targets.length) return;
        forkJoin(targets.map(op)).subscribe({
            next: () => {
                this.toastr.success(done);
                this.reload();
            },
            error: (e) => {
                this.toastr.error(apiErrorMessage(e, 'Action failed'));
                this.reload();
            },
        });
    }
}

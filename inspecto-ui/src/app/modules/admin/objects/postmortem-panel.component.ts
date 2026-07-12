import { ChangeDetectionStrategy, Component, effect, inject, input, output, signal } from '@angular/core';
import { FormArray, FormBuilder, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink } from '@angular/router';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage, ObjectsService, OperationalObject, WorkflowDef } from 'app/inspecto/api';
import { StatusBadgeComponent } from 'app/inspecto/components/status-badge.component';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { fmtDateTime } from 'app/inspecto/grid';
import { CaseContentsComponent, MemberRollup } from './case-contents.component';
import {
    CASE_DISPOSITIONS,
    DEFAULT_CASE_WORKFLOW,
    displayStatus,
    emptyFindings,
    emptyPostmortem,
    isEscalated,
    isTargetOverdue,
    objectCategory,
    objectTags,
    objectTeam,
    parseFindings,
    parsePostmortem,
    Postmortem,
    PostmortemAction,
    PostmortemTimelineEntry,
    stateLabel,
    targetDate,
} from './mail-model';

/**
 * Right-pane detail of the mail view: object header (badges · category · tags · quick actions)
 * plus the **Incident Postmortem** template form (Summary · Timeline · 5 Whys · Corrective
 * actions), stored as `attributes.postmortem` JSON via PATCH /objects/{id}. Lifecycle actions are
 * emitted to the shell (`act`), which owns the transition logic. Full detail (graph / comments /
 * attachments) stays on the `:id` route.
 */
@Component({
    selector: 'app-postmortem-panel',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        ReactiveFormsModule,
        RouterLink,
        MatButtonModule,
        MatCheckboxModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatSelectModule,
        MatTooltipModule,
        StatusBadgeComponent,
        CaseContentsComponent,
    ],
    templateUrl: './postmortem-panel.component.html',
})
export class PostmortemPanelComponent {
    private api = inject(ObjectsService);
    private toastr = inject(ToastrService);
    private fb = inject(FormBuilder);
    private confirm = inject(InspectoConfirmService);

    /** Member roll-up from the Contents section (cases only) — feeds the soft close-gate (C1). */
    readonly memberRollup = signal<MemberRollup>({ total: 0, open: 0 });

    readonly object = input.required<OperationalObject>();
    /** The effective CASE workflow (C6) — case quick actions derive from it; the built-in is the fallback. */
    readonly workflow = input<WorkflowDef | null>(null);

    readonly closed = output<void>();
    /** A lifecycle action for this object (accept / resolve / archive / reopen / escalate / …). */
    readonly act = output<string>();
    /** The object changed on the server (postmortem saved) — the shell reloads. */
    readonly changed = output<void>();

    saving = false;

    readonly form = this.fb.group({
        commander: [''],
        incidentDate: [''],
        downtime: [''],
        businessImpact: [''],
        causeMethod: [''],
        timeline: this.fb.array<FormGroup>([]),
        causeAnalysis: this.fb.array<FormGroup>([]),
        actions: this.fb.array<FormGroup>([]),
    });

    /** C3 + C6: the case's Findings (disposition/impact/summary) + team + loose-SLA target date. */
    readonly findingsForm = this.fb.group({
        disposition: [''],
        impactAmount: [''],
        recordsAffected: [''],
        summary: [''],
        team: [''],
        targetDate: [''],
    });
    readonly dispositions = CASE_DISPOSITIONS;

    constructor() {
        effect(() => this.rebuild(this.object()));
    }

    // ── header helpers ────────────────────────────────────────────────────────────
    readonly displayStatus = displayStatus;
    readonly isEscalated = isEscalated;
    readonly objectCategory = objectCategory;
    readonly objectTags = objectTags;
    readonly objectTeam = objectTeam;
    readonly targetDateOf = targetDate;
    readonly isTargetOverdue = isTargetOverdue;
    readonly fmtDateTime = fmtDateTime;

    get isIncident(): boolean {
        return this.object().objectType === 'INCIDENT';
    }

    get detailLink(): string[] {
        return [this.object().objectType === 'CASE' ? '/cases' : '/incidents', this.object().id];
    }

    /**
     * Quick lifecycle actions for the open object (the shell validates + executes). Incidents keep
     * the fixed mail-metaphor verbs; case verbs derive from the effective workflow (C6), so a
     * TOON-overridden lifecycle changes the panel's buttons too.
     */
    get quickActions(): { id: string; label: string }[] {
        const s = displayStatus(this.object());
        if (this.isIncident) {
            const out: { id: string; label: string }[] = [];
            if (s === 'IDENTIFIED') out.push({ id: 'accept', label: 'Accept' });
            if (s === 'IDENTIFIED' || s === 'DIAGNOSING') out.push({ id: 'resolve', label: 'Resolve' });
            if (s !== 'ARCHIVED') out.push({ id: 'archive', label: 'Archive' });
            if (s === 'RESOLVED' || s === 'ARCHIVED') out.push({ id: 'reopen', label: 'Reopen' });
            out.push({ id: 'escalate', label: isEscalated(this.object()) ? 'De-escalate' : 'Escalate' });
            return out;
        }
        const wf = this.workflow() ?? DEFAULT_CASE_WORKFLOW;
        const out: { id: string; label: string }[] = [];
        for (const t of wf.transitions) {
            if (t.from === s && !out.some((a) => a.id === t.action)) {
                out.push({ id: t.action, label: stateLabel(t.action) });
            }
        }
        return out;
    }

    /**
     * Quick-action click — the soft gates (warn, never block): resolving/closing a case that still
     * has open member incidents (C1), or resolving one with no disposition recorded (C3).
     */
    async onAct(id: string): Promise<void> {
        if (!this.isIncident && (id === 'resolve' || id === 'close')) {
            const warnings: string[] = [];
            const open = this.memberRollup().open;
            if (open > 0) warnings.push(`${open} open member incident${open === 1 ? '' : 's'}`);
            const disposition = this.findingsForm.controls.disposition.value
                || parseFindings(this.object())?.disposition;
            if (id === 'resolve' && !disposition) warnings.push('no disposition recorded (Findings)');
            if (warnings.length) {
                const ok = await this.confirm.confirm(
                    `This case still has ${warnings.join(' and ')} — ${id} anyway?`,
                    'Case check',
                );
                if (!ok) return;
            }
        }
        this.act.emit(id);
    }

    // ── postmortem form ───────────────────────────────────────────────────────────
    get timeline(): FormArray<FormGroup> {
        return this.form.controls.timeline;
    }
    get causeAnalysis(): FormArray<FormGroup> {
        return this.form.controls.causeAnalysis;
    }
    get actionsArr(): FormArray<FormGroup> {
        return this.form.controls.actions;
    }

    private rebuild(o: OperationalObject): void {
        const p = parsePostmortem(o) ?? emptyPostmortem();
        this.form.patchValue({
            commander: p.commander,
            incidentDate: p.incidentDate,
            downtime: p.downtime,
            businessImpact: p.businessImpact,
            causeMethod: p.causeMethod,
        });
        this.timeline.clear();
        p.timeline.forEach((t) => this.timeline.push(this.timelineRow(t)));
        this.causeAnalysis.clear();
        p.causeAnalysis.forEach((w) => this.causeAnalysis.push(this.fb.group({ why: [w] })));
        this.actionsArr.clear();
        p.actions.forEach((a) => this.actionsArr.push(this.actionRow(a)));
        this.form.markAsPristine();

        const f = parseFindings(o) ?? emptyFindings();
        this.findingsForm.patchValue({
            ...f,
            team: objectTeam(o).join(', '),
            targetDate: targetDate(o),
        });
        this.findingsForm.markAsPristine();
    }

    private timelineRow(t: PostmortemTimelineEntry = { time: '', text: '' }): FormGroup {
        return this.fb.group({ time: [t.time], text: [t.text] });
    }

    private actionRow(a: PostmortemAction = { done: false, text: '', owner: '', due: '' }): FormGroup {
        return this.fb.group({ done: [a.done], text: [a.text], owner: [a.owner], due: [a.due] });
    }

    addTimeline(): void {
        this.timeline.push(this.timelineRow());
        this.form.markAsDirty();
    }
    removeTimeline(i: number): void {
        this.timeline.removeAt(i);
        this.form.markAsDirty();
    }
    addCause(): void {
        this.causeAnalysis.push(this.fb.group({ why: [''] }));
        this.form.markAsDirty();
    }
    removeCause(i: number): void {
        this.causeAnalysis.removeAt(i);
        this.form.markAsDirty();
    }
    addAction(): void {
        this.actionsArr.push(this.actionRow());
        this.form.markAsDirty();
    }
    removeAction(i: number): void {
        this.actionsArr.removeAt(i);
        this.form.markAsDirty();
    }

    save(): void {
        const v = this.form.getRawValue();
        const p: Postmortem = {
            commander: v.commander ?? '',
            incidentDate: v.incidentDate ?? '',
            downtime: v.downtime ?? '',
            businessImpact: v.businessImpact ?? '',
            causeMethod: v.causeMethod ?? '',
            timeline: (v.timeline as PostmortemTimelineEntry[]).filter((t) => t.time || t.text),
            causeAnalysis: (v.causeAnalysis as { why: string }[]).map((w) => w.why),
            actions: (v.actions as PostmortemAction[]).filter((a) => a.text),
        };
        this.saving = true;
        this.api.update(this.object().id, { attributes: { postmortem: JSON.stringify(p) } }).subscribe({
            next: () => {
                this.saving = false;
                this.form.markAsPristine();
                this.toastr.success('Postmortem saved');
                this.changed.emit();
            },
            error: (e) => {
                this.saving = false;
                this.toastr.error(apiErrorMessage(e, 'Save failed'));
            },
        });
    }

    /** C3 + C6: persist the case's Findings + team + target date as an attributes patch. */
    saveFindings(): void {
        const v = this.findingsForm.getRawValue();
        const team = (v.team ?? '')
            .split(',')
            .map((t) => t.trim())
            .filter(Boolean)
            .join(',');
        this.saving = true;
        this.api.update(this.object().id, {
            attributes: {
                findings: JSON.stringify({
                    disposition: v.disposition ?? '',
                    impactAmount: v.impactAmount ?? '',
                    recordsAffected: v.recordsAffected ?? '',
                    summary: v.summary ?? '',
                }),
                // Flat, queryable copies so case analytics (C4) can sum impact without parsing the blob.
                impactAmount: (v.impactAmount ?? '').trim(),
                recordsAffected: (v.recordsAffected ?? '').trim(),
                assignees: team,
                targetDate: (v.targetDate ?? '').trim(),
            },
        }).subscribe({
            next: () => {
                this.saving = false;
                this.findingsForm.markAsPristine();
                this.toastr.success('Findings saved');
                this.changed.emit();
            },
            error: (e) => {
                this.saving = false;
                this.toastr.error(apiErrorMessage(e, 'Save failed'));
            },
        });
    }
}

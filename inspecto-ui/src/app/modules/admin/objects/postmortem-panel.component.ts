import { ChangeDetectionStrategy, Component, effect, inject, input, output, signal } from '@angular/core';
import { FormArray, FormBuilder, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink } from '@angular/router';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage, ObjectsService, OperationalObject } from 'app/inspecto/api';
import { StatusBadgeComponent } from 'app/inspecto/components/status-badge.component';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { fmtDateTime } from 'app/inspecto/grid';
import { CaseContentsComponent, MemberRollup } from './case-contents.component';
import {
    displayStatus,
    emptyPostmortem,
    isEscalated,
    objectCategory,
    objectTags,
    parsePostmortem,
    Postmortem,
    PostmortemAction,
    PostmortemTimelineEntry,
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
        timeline: this.fb.array<FormGroup>([]),
        fiveWhys: this.fb.array<FormGroup>([]),
        actions: this.fb.array<FormGroup>([]),
    });

    constructor() {
        effect(() => this.rebuild(this.object()));
    }

    // ── header helpers ────────────────────────────────────────────────────────────
    readonly displayStatus = displayStatus;
    readonly isEscalated = isEscalated;
    readonly objectCategory = objectCategory;
    readonly objectTags = objectTags;
    readonly fmtDateTime = fmtDateTime;

    get isIncident(): boolean {
        return this.object().objectType === 'INCIDENT';
    }

    get detailLink(): string[] {
        return [this.object().objectType === 'CASE' ? '/cases' : '/incidents', this.object().id];
    }

    /** Quick lifecycle actions for the open object (the shell validates + executes). */
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
        switch (s) {
            case 'OPEN':
                return [{ id: 'investigate', label: 'Investigate' }];
            case 'INVESTIGATING':
                return [{ id: 'escalate', label: 'Escalate' }, { id: 'resolve', label: 'Resolve' }];
            case 'ESCALATED':
                return [{ id: 'resolve', label: 'Resolve' }];
            case 'RESOLVED':
                return [{ id: 'close', label: 'Close' }];
            default:
                return [];
        }
    }

    /**
     * Quick-action click — the C1 soft gate: resolving/closing a case that still has open member
     * incidents asks for confirmation first (never blocks; the operator decides).
     */
    async onAct(id: string): Promise<void> {
        const open = this.memberRollup().open;
        if (!this.isIncident && (id === 'resolve' || id === 'close') && open > 0) {
            const ok = await this.confirm.confirm(
                `This case still has ${open} open member incident${open === 1 ? '' : 's'} — ${id} anyway?`,
                'Open members',
            );
            if (!ok) return;
        }
        this.act.emit(id);
    }

    // ── postmortem form ───────────────────────────────────────────────────────────
    get timeline(): FormArray<FormGroup> {
        return this.form.controls.timeline;
    }
    get fiveWhys(): FormArray<FormGroup> {
        return this.form.controls.fiveWhys;
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
        });
        this.timeline.clear();
        p.timeline.forEach((t) => this.timeline.push(this.timelineRow(t)));
        this.fiveWhys.clear();
        p.fiveWhys.forEach((w) => this.fiveWhys.push(this.fb.group({ why: [w] })));
        this.actionsArr.clear();
        p.actions.forEach((a) => this.actionsArr.push(this.actionRow(a)));
        this.form.markAsPristine();
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
            timeline: (v.timeline as PostmortemTimelineEntry[]).filter((t) => t.time || t.text),
            fiveWhys: (v.fiveWhys as { why: string }[]).map((w) => w.why),
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
}

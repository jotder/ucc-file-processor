import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage, CaseRule, ObjectsService } from 'app/inspecto/api';
import { INCIDENT_PRIORITIES, INCIDENT_STATUSES } from './mail-model';

/**
 * Case Rules manager (GLOSSARY §9, C5) — saved searches that auto-group Incidents into a Case: at
 * least {@link CaseRule.threshold} incidents matching the filter within the window get grouped under
 * one case. **Evaluate now** runs the grouping on demand (a scheduler hook is the documented
 * follow-up). Closes with `true` when anything changed so the shell reloads.
 */
@Component({
    selector: 'app-case-rules-dialog',
    standalone: true,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatDialogModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatSelectModule,
        MatTooltipModule,
    ],
    template: `
        <h2 mat-dialog-title>Case rules</h2>
        <mat-dialog-content class="flex flex-col gap-4 pt-2">
            <section aria-label="Saved case rules">
                @for (r of rules(); track r.name) {
                    <div class="mb-1 flex items-center gap-2 rounded border px-3 py-2" style="border-color: var(--gamma-border)">
                        <div class="min-w-0 flex-auto">
                            <div class="truncate text-sm font-semibold">{{ r.name }}</div>
                            <div class="text-secondary truncate text-xs">
                                ≥{{ r.threshold }} incidents in {{ r.windowMinutes }}m where {{ describe(r) }} → "{{ r.title }}"
                            </div>
                        </div>
                        <button mat-stroked-button [disabled]="busy()" (click)="evaluate(r)" matTooltip="Group matching incidents now">
                            Evaluate now
                        </button>
                        <button mat-icon-button [disabled]="busy()" (click)="remove(r)" matTooltip="Delete rule"
                                [attr.aria-label]="'Delete rule ' + r.name">
                            <mat-icon class="icon-size-5" svgIcon="heroicons_outline:trash"></mat-icon>
                        </button>
                    </div>
                } @empty {
                    <p class="text-secondary text-sm">No case rules yet — save the first one below.</p>
                }
            </section>

            <section aria-label="New case rule">
                <h3 class="mb-2 text-xs font-bold uppercase tracking-wider opacity-60">New rule</h3>
                <form [formGroup]="form" class="flex flex-col gap-3">
                    <div class="flex gap-3">
                        <mat-form-field class="flex-1" subscriptSizing="dynamic">
                            <mat-label>Rule name</mat-label>
                            <input matInput formControlName="name" placeholder="e.g. critical-cluster" required />
                            @if (form.controls.name.hasError('required') && form.controls.name.touched) {
                                <mat-error>Required.</mat-error>
                            }
                        </mat-form-field>
                        <mat-form-field class="flex-1" subscriptSizing="dynamic">
                            <mat-label>Raised case title</mat-label>
                            <input matInput formControlName="title" placeholder="e.g. Critical incident cluster" required />
                            @if (form.controls.title.hasError('required') && form.controls.title.touched) {
                                <mat-error>Required.</mat-error>
                            }
                        </mat-form-field>
                    </div>
                    <div class="flex gap-3">
                        <mat-form-field class="w-28" subscriptSizing="dynamic">
                            <mat-label>Threshold</mat-label>
                            <input matInput type="number" formControlName="threshold" min="1" />
                        </mat-form-field>
                        <mat-form-field class="w-36" subscriptSizing="dynamic">
                            <mat-label>Window (min)</mat-label>
                            <input matInput type="number" formControlName="windowMinutes" min="0" />
                        </mat-form-field>
                        <mat-form-field class="flex-1" subscriptSizing="dynamic">
                            <mat-label>Priority</mat-label>
                            <mat-select formControlName="priority">
                                <mat-option value="">any</mat-option>
                                @for (p of priorities; track p) {
                                    <mat-option [value]="p">{{ p }}</mat-option>
                                }
                            </mat-select>
                        </mat-form-field>
                    </div>
                    <div class="flex gap-3">
                        <mat-form-field class="flex-1" subscriptSizing="dynamic">
                            <mat-label>Incident status</mat-label>
                            <mat-select formControlName="status">
                                <mat-option value="">any</mat-option>
                                @for (s of statuses; track s) {
                                    <mat-option [value]="s">{{ s }}</mat-option>
                                }
                            </mat-select>
                        </mat-form-field>
                        <mat-form-field class="flex-1" subscriptSizing="dynamic">
                            <mat-label>Search text</mat-label>
                            <input matInput formControlName="q" placeholder="title / description contains…" />
                        </mat-form-field>
                        <mat-form-field class="flex-1" subscriptSizing="dynamic">
                            <mat-label>Category starts with</mat-label>
                            <input matInput formControlName="category" placeholder="e.g. Pipeline / Ingest" />
                        </mat-form-field>
                    </div>
                    @if (criteriaMissing()) {
                        <p class="text-secondary text-sm" role="alert">Set at least one incident criterion (priority / status / text / category).</p>
                    }
                </form>
            </section>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button [mat-dialog-close]="changed()">Close</button>
            <button mat-stroked-button [disabled]="busy()" (click)="save(false)">Save</button>
            <button mat-flat-button color="primary" [disabled]="busy()" (click)="save(true)">Save &amp; evaluate</button>
        </mat-dialog-actions>
    `,
})
export class CaseRulesDialog {
    private api = inject(ObjectsService);
    private ref = inject(MatDialogRef<CaseRulesDialog>);
    private toastr = inject(ToastrService);
    private fb = inject(FormBuilder);

    readonly rules = signal<CaseRule[]>([]);
    readonly busy = signal(false);
    readonly changed = signal(false);
    readonly criteriaMissing = signal(false);
    readonly statuses = INCIDENT_STATUSES;
    readonly priorities = INCIDENT_PRIORITIES;

    readonly form = this.fb.group({
        name: ['', Validators.required],
        title: ['', Validators.required],
        threshold: [2],
        windowMinutes: [1440],
        priority: [''],
        status: [''],
        q: [''],
        category: [''],
    });

    constructor() {
        this.api.caseRules().subscribe({ next: (r) => this.rules.set(r), error: () => this.rules.set([]) });
    }

    describe(r: CaseRule): string {
        const f = r.filter ?? {};
        const parts = [
            f.priority ? `priority = ${f.priority}` : '',
            f.status ? `status = ${f.status}` : '',
            f.q ? `text ~ "${f.q}"` : '',
            f.category ? `category ^ "${f.category}"` : '',
        ].filter(Boolean);
        return parts.join(' and ') || 'any incident';
    }

    save(evaluate: boolean): void {
        if (this.form.invalid) {
            this.form.markAllAsTouched();
            return;
        }
        const v = this.form.getRawValue();
        if (!v.priority && !v.status && !v.q && !v.category) {
            this.criteriaMissing.set(true);
            return;
        }
        this.criteriaMissing.set(false);
        const rule: CaseRule = {
            name: v.name!.trim(),
            title: v.title!.trim(),
            threshold: Number(v.threshold) || 2,
            windowMinutes: Number(v.windowMinutes) || 0,
            filter: {
                type: 'INCIDENT',
                ...(v.priority ? { priority: v.priority } : {}),
                ...(v.status ? { status: v.status } : {}),
                ...(v.q ? { q: v.q } : {}),
                ...(v.category ? { category: v.category } : {}),
            },
        };
        this.busy.set(true);
        this.api.saveCaseRule(rule).subscribe({
            next: (saved) => {
                this.rules.update((all) => [...all.filter((r) => r.name !== saved.name), saved]
                    .sort((a, b) => a.name.localeCompare(b.name)));
                this.form.reset({ name: '', title: '', threshold: 2, windowMinutes: 1440, priority: '', status: '', q: '', category: '' });
                this.changed.set(true);
                if (evaluate) {
                    this.evaluate(saved);
                } else {
                    this.busy.set(false);
                    this.toastr.success(`Rule "${saved.name}" saved`);
                }
            },
            error: (e) => {
                this.busy.set(false);
                this.toastr.error(apiErrorMessage(e, 'Could not save rule'));
            },
        });
    }

    evaluate(rule: CaseRule): void {
        this.busy.set(true);
        this.api.evaluateCaseRule(rule.name).subscribe({
            next: (r) => {
                this.busy.set(false);
                this.changed.set(true);
                if (r.grouped > 0) {
                    this.toastr.success(`"${rule.name}": ${r.grouped} incident(s) grouped ${r.opened ? 'into a new case' : 'into the open case'} (${r.caseId})`);
                } else {
                    this.toastr.info(`"${rule.name}": ${r.matched} matched, none newly grouped`);
                }
            },
            error: (e) => {
                this.busy.set(false);
                this.toastr.error(apiErrorMessage(e, 'Evaluate failed'));
            },
        });
    }

    remove(rule: CaseRule): void {
        this.busy.set(true);
        this.api.deleteCaseRule(rule.name).subscribe({
            next: () => {
                this.busy.set(false);
                this.changed.set(true);
                this.rules.update((all) => all.filter((r) => r.name !== rule.name));
            },
            error: (e) => {
                this.busy.set(false);
                this.toastr.error(apiErrorMessage(e, 'Delete failed'));
            },
        });
    }
}

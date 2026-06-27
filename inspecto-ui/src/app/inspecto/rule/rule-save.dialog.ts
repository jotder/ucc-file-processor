import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage } from 'app/inspecto/api';
import { QueryModel } from 'app/inspecto/query';
import { buildRuleTemplate, RuleTemplate } from './rule-types';
import { RulesService } from './rules.service';

/** Dialog data: the finished query to save + the source name + the generated SQL (for preview). */
export interface RuleSaveData {
    model: QueryModel;
    sql: string;
    sourceName: string;
}

/**
 * Save-as-rule dialog (Pro Max). Names the template and persists it via {@link RulesService} (the mock-backed
 * `rule` component type). Mirrors the parser-config grammar-save ergonomics. Closes with the saved
 * {@link RuleTemplate}.
 */
@Component({
    selector: 'app-rule-save-dialog',
    standalone: true,
    imports: [
        ReactiveFormsModule,
        MatDialogModule,
        MatButtonModule,
        MatFormFieldModule,
        MatInputModule,
        MatProgressSpinnerModule,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <h2 mat-dialog-title>Save as rule</h2>
        <mat-dialog-content class="space-y-2">
            <p class="text-secondary text-xs">Saves this query as a reusable rule template over <span class="font-mono">{{ data.sourceName }}</span>.</p>
            <form [formGroup]="form" (ngSubmit)="save()">
                <mat-form-field class="w-full" subscriptSizing="dynamic">
                    <mat-label>Rule id</mat-label>
                    <input matInput formControlName="name" placeholder="e.g. high_error_rate" />
                    @if (form.get('name')?.hasError('pattern')) {
                        <mat-error>Letters, digits, dot, dash, underscore; start alphanumeric.</mat-error>
                    }
                </mat-form-field>
            </form>
            <pre class="max-h-40 overflow-auto rounded border p-2 font-mono text-xs"
                 style="background: var(--gamma-bg-default); border-color: var(--gamma-border)">{{ data.sql }}</pre>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button type="button" mat-button mat-dialog-close>Cancel</button>
            <button type="button" mat-flat-button color="primary" (click)="save()" [disabled]="saving()">
                @if (saving()) { <mat-spinner diameter="16" class="mr-2"></mat-spinner> }
                <span>Save rule</span>
            </button>
        </mat-dialog-actions>
    `,
})
export class RuleSaveDialog {
    private fb = inject(FormBuilder);
    private rules = inject(RulesService);
    private toastr = inject(ToastrService);
    private ref = inject(MatDialogRef<RuleSaveDialog, RuleTemplate>);
    readonly data = inject<RuleSaveData>(MAT_DIALOG_DATA);

    readonly saving = signal(false);
    readonly form = this.fb.group({
        name: ['', [Validators.required, Validators.pattern(/^[A-Za-z0-9][A-Za-z0-9._-]*$/)]],
    });

    save(): void {
        const ctrl = this.form.get('name')!;
        const name = String(ctrl.value ?? '').trim();
        if (!name || ctrl.invalid) {
            this.form.markAllAsTouched();
            return;
        }
        const rule = buildRuleTemplate(name, this.data.sourceName, this.data.model);
        this.saving.set(true);
        this.rules.save(rule).subscribe({
            next: (saved) => {
                this.saving.set(false);
                this.toastr.success(`Rule "${name}" saved`);
                this.ref.close(saved);
            },
            error: (e) => {
                this.saving.set(false);
                this.toastr.error(
                    e?.status === 503 ? 'Writes are disabled (no write root configured).' : apiErrorMessage(e, `Could not save "${name}"`),
                );
            },
        });
    }
}

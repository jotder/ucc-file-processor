import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { ExchangeGrant, ExchangeService } from 'app/inspecto/api';
import { uniqueNameValidator } from 'app/inspecto/investigation';

export interface BindSharedDatasetData {
    /** The active space id — grants where it is the consumer are the bindable ones. */
    me: string;
    /** Existing dataset ids, for the inline duplicate-name guard. */
    existingNames: string[];
}

/** The chosen grant + local name — the caller builds a `physical` dataset with `physicalRef = shared/<owner>/<item>`. */
export interface BindSharedDatasetResult {
    name: string;
    owner: string;
    item: string;
}

/** A component id: starts alphanumeric, then alphanumeric / dot / dash / underscore (mirrors the backend `SAFE_ID`). */
const SAFE_ID = /^[A-Za-z0-9][A-Za-z0-9._-]*$/;

/**
 * "Bind shared dataset" — the consumer side of the Exchange (§3.6). Turns an **active** dataset grant into
 * a local `dataset` component whose `physicalRef` is `shared/<owner>/<item>`; the backend `SharedRefResolver`
 * then resolves reads against the owner's data. Lists only the active dataset grants for this space and asks
 * only for a local name (pre-filled `<owner>_<item>`, uniqueness-guarded).
 */
@Component({
    standalone: true,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatDialogModule,
        MatFormFieldModule,
        MatInputModule,
        MatSelectModule,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <h2 mat-dialog-title>Bind shared dataset</h2>
        <mat-dialog-content class="flex w-96 max-w-full flex-col gap-4">
            <div class="text-secondary text-sm">
                Create a local dataset backed by another space's shared data. Only datasets that space has
                <strong>approved</strong> for you appear here.
            </div>
            @if (grants().length === 0) {
                <div class="text-secondary text-sm">
                    No active dataset grants yet — request access to an offered dataset in the Catalog's
                    “Shared with me” tab, and bind it here once the owner approves.
                </div>
            } @else {
                <form [formGroup]="form" class="flex flex-col gap-2">
                    <mat-form-field subscriptSizing="dynamic">
                        <mat-label>Shared dataset</mat-label>
                        <mat-select formControlName="grantId" cdkFocusInitial>
                            @for (g of grants(); track g.id) {
                                <mat-option [value]="g.id">{{ g.owner }} / {{ g.item }}</mat-option>
                            }
                        </mat-select>
                        @if (form.controls.grantId.touched && form.controls.grantId.invalid) {
                            <mat-error>Choose a shared dataset.</mat-error>
                        }
                    </mat-form-field>
                    <mat-form-field subscriptSizing="dynamic">
                        <mat-label>Local dataset name</mat-label>
                        <input matInput formControlName="name" placeholder="e.g. analytics-hub_fx_rates_daily" />
                        @if (form.controls.name.hasError('required')) {
                            <mat-error>A name is required.</mat-error>
                        } @else if (form.controls.name.hasError('pattern')) {
                            <mat-error>Letters, digits, “.”, “-”, “_” only; must start alphanumeric.</mat-error>
                        } @else if (form.controls.name.hasError('duplicate')) {
                            <mat-error>A dataset with this name already exists.</mat-error>
                        }
                    </mat-form-field>
                </form>
            }
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button mat-dialog-close>Cancel</button>
            <button
                mat-flat-button
                color="primary"
                [disabled]="grants().length === 0"
                (click)="submit()"
            >
                Bind
            </button>
        </mat-dialog-actions>
    `,
})
export class BindSharedDatasetDialog {
    readonly data = inject<BindSharedDatasetData>(MAT_DIALOG_DATA);
    private ref = inject(MatDialogRef<BindSharedDatasetDialog>);
    private exchange = inject(ExchangeService);
    private fb = inject(FormBuilder);

    /** The active dataset grants this space can bind (fail-closed: active only). */
    readonly grants = signal<ExchangeGrant[]>([]);

    readonly form = this.fb.nonNullable.group({
        grantId: ['', Validators.required],
        name: [
            '',
            [Validators.required, Validators.pattern(SAFE_ID)],
            // async slot unused; sync validators only
        ],
    });

    /** True once the user edits the name — stops the grant selection from overwriting it. */
    private nameEdited = signal(false);

    constructor() {
        this.form.controls.name.addValidators(uniqueNameValidator(() => this.data.existingNames));
        this.form.controls.name.valueChanges.subscribe(() => {
            if (this.form.controls.name.dirty) this.nameEdited.set(true);
        });
        // Pre-fill the local name from the chosen grant until the user types their own.
        this.form.controls.grantId.valueChanges.subscribe((id) => {
            if (this.nameEdited()) return;
            const g = this.grants().find((x) => x.id === id);
            if (g) this.form.controls.name.setValue(suggestName(g), { emitEvent: false });
        });

        this.exchange.grants(this.data.me).subscribe({
            next: (all) => {
                const mine = all.filter(
                    (g) => g.kind === 'dataset' && g.status === 'active' && g.consumer === this.data.me,
                );
                this.grants.set(mine);
                if (mine.length === 1) this.form.controls.grantId.setValue(mine[0].id);
            },
            error: () => this.grants.set([]),
        });
    }

    submit(): void {
        if (this.form.invalid) {
            this.form.markAllAsTouched();
            return;
        }
        const { grantId, name } = this.form.getRawValue();
        const g = this.grants().find((x) => x.id === grantId);
        if (!g) return;
        this.ref.close({ name: name.trim(), owner: g.owner, item: g.item } satisfies BindSharedDatasetResult);
    }
}

/** `<owner>_<item>` with any non-id characters folded to “_” (a valid, unique-ish default local name). */
function suggestName(g: ExchangeGrant): string {
    return `${g.owner}_${g.item}`.replace(/[^A-Za-z0-9._-]/g, '_');
}

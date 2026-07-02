import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, inject, Input, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { AbstractControl, FormBuilder, FormGroup, ReactiveFormsModule, ValidatorFn, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatTooltipModule } from '@angular/material/tooltip';
import { AttributeSpec, byTier, defaultsFor } from '../component-model';

/**
 * The shared spec-driven form renderer (Wave 0, W2): renders an {@link AttributeSpec} list as a
 * reactive form with the product's three-tier disclosure — **required** always visible, **optional**
 * in a collapsed group, **advanced** behind the gear toggle. `dependsOn` attributes show (and
 * validate) only while their controlling attribute matches. Hosts embed it, patch `initial`, and on
 * submit call `validate()` + read `value()` — bespoke sections (key/value arrays, canvases) stay in
 * the host below it.
 */
@Component({
    selector: 'inspecto-schema-form',
    standalone: true,
    imports: [
        NgTemplateOutlet,
        ReactiveFormsModule,
        MatButtonModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatSelectModule,
        MatSlideToggleModule,
        MatTooltipModule,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <form [formGroup]="form" class="flex flex-col gap-1">
            @if (tiers().advanced.length) {
                <div class="flex justify-end">
                    <button
                        mat-icon-button
                        type="button"
                        matTooltip="Advanced settings"
                        [attr.aria-label]="showAdvanced() ? 'Hide advanced settings' : 'Show advanced settings'"
                        [attr.aria-expanded]="showAdvanced()"
                        (click)="showAdvanced.set(!showAdvanced())"
                    >
                        <mat-icon svgIcon="heroicons_outline:cog-6-tooth"></mat-icon>
                    </button>
                </div>
            }

            @for (spec of tiers().required; track spec.key) {
                <ng-container *ngTemplateOutlet="field; context: { spec }"></ng-container>
            }

            @if (tiers().optional.length) {
                <button
                    type="button"
                    class="text-secondary flex items-center gap-1 self-start py-1 text-sm font-medium"
                    [attr.aria-expanded]="showOptional()"
                    (click)="showOptional.set(!showOptional())"
                >
                    <mat-icon class="icon-size-4" [svgIcon]="showOptional() ? 'heroicons_outline:chevron-down' : 'heroicons_outline:chevron-right'"></mat-icon>
                    Optional settings ({{ tiers().optional.length }})
                </button>
                @if (showOptional()) {
                    @for (spec of tiers().optional; track spec.key) {
                        <ng-container *ngTemplateOutlet="field; context: { spec }"></ng-container>
                    }
                }
            }

            @if (showAdvanced()) {
                <div class="text-secondary py-1 text-sm font-medium" role="heading" aria-level="3">Advanced</div>
                @for (spec of tiers().advanced; track spec.key) {
                    <ng-container *ngTemplateOutlet="field; context: { spec }"></ng-container>
                }
            }

            <ng-template #field let-spec="spec">
                @if (isVisible(spec)) {
                    @switch (spec.type) {
                        @case ('boolean') {
                            <mat-slide-toggle class="py-2" [formControlName]="spec.key">{{ spec.label }}</mat-slide-toggle>
                        }
                        @case ('select') {
                            <mat-form-field class="w-full" subscriptSizing="dynamic">
                                <mat-label>{{ spec.label }}</mat-label>
                                <mat-select [formControlName]="spec.key">
                                    @for (opt of spec.options ?? []; track opt.value) {
                                        <mat-option [value]="opt.value">{{ opt.label }}</mat-option>
                                    }
                                </mat-select>
                                @if (spec.help) { <mat-hint>{{ spec.help }}</mat-hint> }
                                <mat-error>{{ spec.label }} is required</mat-error>
                            </mat-form-field>
                        }
                        @case ('multiline') {
                            <mat-form-field class="w-full" subscriptSizing="dynamic">
                                <mat-label>{{ spec.label }}</mat-label>
                                <textarea matInput rows="4" [formControlName]="spec.key" [placeholder]="spec.placeholder ?? ''"></textarea>
                                @if (spec.help) { <mat-hint>{{ spec.help }}</mat-hint> }
                                <mat-error>{{ errorFor(spec) }}</mat-error>
                            </mat-form-field>
                        }
                        @default {
                            <mat-form-field class="w-full" subscriptSizing="dynamic">
                                <mat-label>{{ spec.label }}</mat-label>
                                <input
                                    matInput
                                    [type]="spec.type === 'number' ? 'number' : 'text'"
                                    [formControlName]="spec.key"
                                    [placeholder]="spec.placeholder ?? ''"
                                />
                                @if (spec.help) { <mat-hint>{{ spec.help }}</mat-hint> }
                                <mat-error>{{ errorFor(spec) }}</mat-error>
                            </mat-form-field>
                        }
                    }
                }
            </ng-template>
        </form>
    `,
})
export class InspectoSchemaFormComponent {
    private fb = inject(FormBuilder);
    private destroyRef = inject(DestroyRef);

    readonly form: FormGroup = this.fb.group({});
    readonly showOptional = signal(false);
    readonly showAdvanced = signal(false);
    readonly tiers = signal<ReturnType<typeof byTier>>({ required: [], optional: [], advanced: [] });

    private allSpecs: AttributeSpec[] = [];
    private formValue = signal<Record<string, unknown>>({});

    /** The attribute declarations to render. Set once (rebuilds the form when reassigned). */
    @Input({ required: true }) set specs(specs: AttributeSpec[]) {
        this.allSpecs = specs ?? [];
        this.tiers.set(byTier(this.allSpecs));
        for (const key of Object.keys(this.form.controls)) this.form.removeControl(key, { emitEvent: false });
        const defaults = defaultsFor(this.allSpecs);
        for (const s of this.allSpecs) {
            this.form.addControl(s.key, this.fb.control(defaults[s.key] ?? null, this.validatorsFor(s)), { emitEvent: false });
        }
        this.syncVisibility(this.form.getRawValue());
    }

    /** Existing values to edit (patched over the declared defaults). */
    @Input() set initial(value: Record<string, unknown> | null | undefined) {
        if (value) this.form.patchValue(value, { emitEvent: false });
        this.syncVisibility(this.form.getRawValue());
    }

    constructor() {
        this.form.valueChanges.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => {
            this.syncVisibility(this.form.getRawValue());
        });
    }

    /** True while `spec` should render (its `dependsOn` matches the current values). */
    isVisible(spec: AttributeSpec): boolean {
        const v = this.formValue();
        return !spec.dependsOn || v[spec.dependsOn.key] === spec.dependsOn.equals;
    }

    /** Mark everything touched (house rule on invalid submit) and report validity. */
    validate(): boolean {
        if (this.form.invalid) this.form.markAllAsTouched();
        return this.form.valid;
    }

    /** The visible values only — hidden (`dependsOn`-suppressed) controls are disabled and excluded. */
    value(): Record<string, unknown> {
        return this.form.value as Record<string, unknown>;
    }

    /** The first matching error message for a control, from its spec. */
    errorFor(spec: AttributeSpec): string {
        const c = this.form.get(spec.key);
        if (!c || !c.errors) return '';
        if (c.errors['required']) return `${spec.label} is required`;
        if (c.errors['duplicate']) return `${spec.label} already exists`;
        if (c.errors['pattern']) return `${spec.label} has an invalid format`;
        if (c.errors['min']) return `${spec.label} must be ≥ ${spec.min}`;
        if (c.errors['max']) return `${spec.label} must be ≤ ${spec.max}`;
        return `${spec.label} is invalid`;
    }

    private validatorsFor(s: AttributeSpec): ValidatorFn[] {
        const v: ValidatorFn[] = [];
        if (s.tier === 'required') v.push(Validators.required);
        if (s.type === 'identifier') v.push(Validators.pattern(/^[A-Za-z][A-Za-z0-9_-]*$/));
        if (s.pattern) v.push(Validators.pattern(`^(?:${s.pattern})$`));
        if (s.type === 'number') {
            if (s.min !== undefined) v.push(Validators.min(s.min));
            if (s.max !== undefined) v.push(Validators.max(s.max));
        }
        return v;
    }

    /** Enable/disable controls to match `dependsOn` visibility so hidden fields never block validity. */
    private syncVisibility(value: Record<string, unknown>): void {
        this.formValue.set(value);
        for (const s of this.allSpecs) {
            if (!s.dependsOn) continue;
            const visible = value[s.dependsOn.key] === s.dependsOn.equals;
            const control: AbstractControl | null = this.form.get(s.key);
            if (!control) continue;
            if (visible && control.disabled) control.enable({ emitEvent: false });
            if (!visible && control.enabled) control.disable({ emitEvent: false });
        }
    }
}

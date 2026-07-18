import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, inject, Input, output, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { AbstractControl, FormBuilder, FormGroup, ReactiveFormsModule, ValidatorFn, Validators } from '@angular/forms';
import { MatAutocompleteModule } from '@angular/material/autocomplete';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatTooltipModule } from '@angular/material/tooltip';
import { AttributeOption, AttributeSpec, byTier, defaultsFor, dependsOnMatches, isRequired } from '../component-model';

/**
 * Supplies the suggestion list for a `type: 'autocomplete'` attribute. Receives the current raw form
 * value so a suggestion set can follow a sibling field (e.g. `target` follows `targetType`). Called on
 * focus — return the fresh list (sync or async); failures degrade to no suggestions.
 */
export type AttributeOptionLoader = (
    value: Record<string, unknown>,
) => AttributeOption[] | Promise<AttributeOption[]>;

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
        MatAutocompleteModule,
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
        <form [formGroup]="form" class="flex flex-col gap-1" (ngSubmit)="submitted.emit()">
            <!-- Invisible submit target so Enter in any field triggers ngSubmit (implicit submission
                 requires a rendered submit control — display:none is skipped by Chrome, so sr-only,
                 not "hidden"; hosts keep their visible Save button outside). -->
            <button type="submit" class="sr-only" aria-hidden="true" tabindex="-1"></button>
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

            @for (spec of tiers().required; track spec.key; let i = $index) {
                <ng-container *ngTemplateOutlet="field; context: { spec, first: i === 0 }"></ng-container>
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

            <ng-template #field let-spec="spec" let-first="first">
                @if (isVisible(spec)) {
                    @switch (spec.type) {
                        @case ('boolean') {
                            <mat-slide-toggle class="py-2" [formControlName]="spec.key" [attr.cdkFocusInitial]="first ? '' : null">{{ spec.label }}</mat-slide-toggle>
                        }
                        @case ('select') {
                            <mat-form-field class="w-full" subscriptSizing="dynamic">
                                <mat-label>{{ spec.label }}</mat-label>
                                <mat-select [formControlName]="spec.key" [attr.cdkFocusInitial]="first ? '' : null">
                                    @for (opt of spec.options ?? []; track opt.value) {
                                        <mat-option [value]="opt.value">{{ opt.label }}</mat-option>
                                    }
                                </mat-select>
                                @if (spec.help) { <mat-hint>{{ spec.help }}</mat-hint> }
                                <mat-error>{{ spec.label }} is required</mat-error>
                            </mat-form-field>
                        }
                        @case ('autocomplete') {
                            <mat-form-field class="w-full" subscriptSizing="dynamic">
                                <mat-label>{{ spec.label }}</mat-label>
                                <input
                                    matInput
                                    [formControlName]="spec.key"
                                    [placeholder]="spec.placeholder ?? ''"
                                    [matAutocomplete]="ac"
                                    [attr.cdkFocusInitial]="first ? '' : null"
                                    (focus)="loadOptionsFor(spec)"
                                />
                                <mat-autocomplete #ac="matAutocomplete">
                                    @for (opt of filteredOptions(spec); track opt.value) {
                                        <mat-option [value]="opt.value">{{ opt.label }}</mat-option>
                                    }
                                </mat-autocomplete>
                                @if (spec.help) { <mat-hint>{{ spec.help }}</mat-hint> }
                                <mat-error>{{ errorFor(spec) }}</mat-error>
                            </mat-form-field>
                        }
                        @case ('multiline') {
                            <mat-form-field class="w-full" subscriptSizing="dynamic">
                                <mat-label>{{ spec.label }}</mat-label>
                                <textarea matInput rows="4" [formControlName]="spec.key" [placeholder]="spec.placeholder ?? ''" [attr.cdkFocusInitial]="first ? '' : null"></textarea>
                                @if (spec.help) { <mat-hint>{{ spec.help }}</mat-hint> }
                                <mat-error>{{ errorFor(spec) }}</mat-error>
                            </mat-form-field>
                        }
                        @case ('number') {
                            <!-- Static type="number" so Angular's NumberValueAccessor attaches (a [type]
                                 binding would leave the default accessor → string values). -->
                            <mat-form-field class="w-full" subscriptSizing="dynamic">
                                <mat-label>{{ spec.label }}</mat-label>
                                <input
                                    matInput
                                    type="number"
                                    [formControlName]="spec.key"
                                    [placeholder]="spec.placeholder ?? ''"
                                    [attr.cdkFocusInitial]="first ? '' : null"
                                />
                                @if (spec.help) { <mat-hint>{{ spec.help }}</mat-hint> }
                                <mat-error>{{ errorFor(spec) }}</mat-error>
                            </mat-form-field>
                        }
                        @default {
                            <mat-form-field class="w-full" subscriptSizing="dynamic">
                                <mat-label>{{ spec.label }}</mat-label>
                                <input
                                    matInput
                                    type="text"
                                    [formControlName]="spec.key"
                                    [placeholder]="spec.placeholder ?? ''"
                                    [attr.cdkFocusInitial]="first ? '' : null"
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
    /** Fires on Enter in any field (native form submission). Hosts bind their save action here so
     *  keyboard submit and the visible Save button share one path. */
    readonly submitted = output<void>();
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

    /** Suggestion sources for `type: 'autocomplete'` attributes, keyed by attribute key. */
    @Input() optionLoaders: Record<string, AttributeOptionLoader> | undefined;

    /** Loaded suggestions per attribute key (refreshed on field focus). */
    private readonly loadedOptions = signal<Record<string, AttributeOption[]>>({});

    /** Refresh an autocomplete field's suggestions — called on focus so sets that depend on sibling
     *  fields (e.g. `target` on `targetType`) stay current. Best-effort: a failed load = no list. */
    loadOptionsFor(spec: AttributeSpec): void {
        const loader = this.optionLoaders?.[spec.key];
        if (!loader) return;
        Promise.resolve(loader(this.form.getRawValue())).then(
            (opts) => this.loadedOptions.update((m) => ({ ...m, [spec.key]: opts })),
            () => undefined,
        );
    }

    /** Suggestions narrowed by the field's current text (matches value or label, case-insensitive). */
    filteredOptions(spec: AttributeSpec): AttributeOption[] {
        const all = this.loadedOptions()[spec.key] ?? spec.options ?? [];
        const q = String(this.formValue()[spec.key] ?? '').trim().toLowerCase();
        if (!q) return all;
        return all.filter(
            (o) => o.value.toLowerCase().includes(q) || o.label.toLowerCase().includes(q),
        );
    }

    constructor() {
        this.form.valueChanges.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => {
            this.syncVisibility(this.form.getRawValue());
        });
    }

    /** True while `spec` should render (its `dependsOn` matches the current values). */
    isVisible(spec: AttributeSpec): boolean {
        return !spec.dependsOn || dependsOnMatches(spec.dependsOn, this.formValue());
    }

    /** Whether the user changed anything — drives the shared discard-on-close guard. */
    isDirty(): boolean {
        return this.form.dirty;
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
        if (isRequired(s)) v.push(Validators.required);
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
            const visible = dependsOnMatches(s.dependsOn, value);
            const control: AbstractControl | null = this.form.get(s.key);
            if (!control) continue;
            if (visible && control.disabled) control.enable({ emitEvent: false });
            if (!visible && control.enabled) control.disable({ emitEvent: false });
        }
    }
}

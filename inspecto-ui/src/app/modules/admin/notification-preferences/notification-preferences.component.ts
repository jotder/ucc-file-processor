import { ChangeDetectionStrategy, Component, OnInit, ViewEncapsulation, inject, signal } from '@angular/core';
import { FormArray, FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { ToastrService } from 'ngx-toastr';
import { NotificationPrefRow, NotificationsService } from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoSkeletonComponent } from 'app/inspecto/components/skeleton.component';

/**
 * Notification preferences — the category × channel grid for the single {@code appUser}. Each category
 * maps to in-app / email delivery. Critical categories (e.g. Security) bypass opt-out: their toggles are
 * locked on. Reactive-forms driven; persists the whole grid via {@code PUT /notifications/preferences}.
 */
@Component({
    selector: 'app-notification-preferences',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    encapsulation: ViewEncapsulation.None,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatSlideToggleModule,
        InspectoAlertComponent,
        InspectoSkeletonComponent,
    ],
    template: `
        <div class="flex max-w-3xl flex-col gap-4 p-6">
            <div>
                <!-- h2: this pane is embedded as a Notification-center tab (the page h1 lives there). -->
                <h2 class="text-2xl font-semibold">Notification preferences</h2>
                <p class="text-secondary mt-1">
                    Choose how you're notified for each category. Settings apply to the current user.
                </p>
            </div>

            <inspecto-alert variant="info" title="Security alerts are always on">
                Critical security notifications can't be turned off and are delivered on every channel.
            </inspecto-alert>

            @if (loading()) {
                <inspecto-skeleton [lines]="6" />
            } @else {
                <form [formGroup]="form" (ngSubmit)="save()">
                    <table class="w-full text-left">
                        <caption class="sr-only">Notification channel preferences by category</caption>
                        <thead>
                            <tr class="border-b">
                                <th scope="col" class="py-2">Category</th>
                                <th scope="col" class="w-28 py-2 text-center">In-app</th>
                                <th scope="col" class="w-28 py-2 text-center">Email</th>
                            </tr>
                        </thead>
                        <tbody formArrayName="rows">
                            @for (row of rows.controls; track row.value.category; let i = $index) {
                                <tr [formGroupName]="i" class="border-b">
                                    <th scope="row" class="py-3 font-normal">
                                        <div class="flex items-center gap-2">
                                            <span>{{ row.value.label }}</span>
                                            @if (row.value.critical) {
                                                <span class="text-secondary text-xs">Always on</span>
                                            } @else if (!row.value.available) {
                                                <span class="text-secondary text-xs">Coming soon</span>
                                            }
                                        </div>
                                    </th>
                                    <td class="py-3 text-center">
                                        <mat-slide-toggle formControlName="inApp">
                                            <span class="sr-only">In-app notifications for {{ row.value.label }}</span>
                                        </mat-slide-toggle>
                                    </td>
                                    <td class="py-3 text-center">
                                        <mat-slide-toggle formControlName="email">
                                            <span class="sr-only">Email notifications for {{ row.value.label }}</span>
                                        </mat-slide-toggle>
                                    </td>
                                </tr>
                            }
                        </tbody>
                    </table>

                    <div class="mt-4 flex gap-2">
                        <button mat-flat-button color="primary" type="submit" [disabled]="form.pristine || saving()">
                            Save
                        </button>
                        <button mat-stroked-button type="button" (click)="load()" [disabled]="saving()">Reset</button>
                    </div>
                </form>
            }
        </div>
    `,
})
export class NotificationPreferencesComponent implements OnInit {
    private fb = inject(FormBuilder);
    private svc = inject(NotificationsService);
    private toastr = inject(ToastrService);

    readonly loading = signal(true);
    readonly saving = signal(false);

    readonly form = this.fb.group({ rows: this.fb.array([]) });

    get rows(): FormArray {
        return this.form.get('rows') as FormArray;
    }

    ngOnInit(): void {
        this.load();
    }

    load(): void {
        this.loading.set(true);
        this.svc.preferences().subscribe({
            next: (grid) => {
                this.buildForm(grid);
                this.loading.set(false);
            },
            error: () => {
                this.loading.set(false);
                this.toastr.error('Failed to load preferences');
            },
        });
    }

    save(): void {
        this.saving.set(true);
        const rows: NotificationPrefRow[] = this.rows.getRawValue().map((r) => ({
            category: r.category,
            label: r.label,
            critical: r.critical,
            available: r.available,
            channels: { inApp: r.inApp, email: r.email },
        }));
        this.svc.savePreferences(rows).subscribe({
            next: (grid) => {
                this.buildForm(grid);
                this.saving.set(false);
                this.toastr.success('Preferences saved');
            },
            error: () => {
                this.saving.set(false);
                this.toastr.error('Save failed');
            },
        });
    }

    /** Rebuild the form array from a grid; critical rows are locked on (disabled controls). */
    private buildForm(grid: NotificationPrefRow[]): void {
        this.rows.clear();
        for (const r of grid) {
            this.rows.push(
                this.fb.group({
                    category: [r.category],
                    label: [r.label],
                    critical: [r.critical],
                    available: [r.available],
                    inApp: [{ value: r.channels.inApp, disabled: r.critical }],
                    email: [{ value: r.channels.email, disabled: r.critical }],
                }),
            );
        }
        this.form.markAsPristine();
    }
}

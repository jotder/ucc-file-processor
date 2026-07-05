import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { AbstractControl, FormBuilder, ReactiveFormsModule, ValidationErrors } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { ToastrService } from 'ngx-toastr';
import { GeoSettingsService, apiErrorMessage } from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';

/** Empty is fine (bundled offline basemap); a value must be a {z}/{x}/{y} raster template or a pmtiles:// archive. */
function tileTemplateValidator(c: AbstractControl): ValidationErrors | null {
    const v = String(c.value ?? '').trim();
    if (!v) return null;
    if (v.startsWith('pmtiles://')) return null;
    return v.includes('{z}') && v.includes('{x}') && v.includes('{y}') ? null : { tileTemplate: true };
}

/**
 * Settings → Map — the Phase 4 **tile-server config seam**. By default the map studios render the
 * fully-bundled offline basemap (decision D2); a customer with their own tile server (satellite /
 * terrain imagery) points Inspecto at it here. Persisted per space via `GET|PUT /settings/geo`
 * (mock-served); every `MapViewComponent` host picks it up through its `tileServerUrl` input.
 */
@Component({
    selector: 'app-map-settings',
    standalone: true,
    imports: [ReactiveFormsModule, MatButtonModule, MatFormFieldModule, MatIconModule, MatInputModule, InspectoAlertComponent],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <div class="flex min-w-0 flex-auto flex-col p-6 md:p-8">
            <h1 class="text-3xl font-extrabold leading-tight tracking-tight">Map settings</h1>
            <p class="text-secondary mt-1 max-w-xl text-sm">
                Maps render a fully-bundled offline basemap by default. Point Inspecto at your own tile server to
                add imagery (satellite, terrain) — used by the Geo Map studio and geo widgets.
            </p>

            @if (writesDisabled()) {
                <inspecto-alert class="mt-4 block" variant="warning" icon="heroicons_outline:lock-closed">
                    Saving settings is disabled on this server.
                </inspecto-alert>
            }

            <form [formGroup]="form" (ngSubmit)="save()" class="mt-6 flex max-w-xl flex-col gap-2">
                <mat-form-field subscriptSizing="dynamic">
                    <mat-label>Tile server URL (optional)</mat-label>
                    <input matInput formControlName="tileServerUrl" placeholder="https://tiles.example.com/{z}/{x}/{y}.png" />
                    <mat-hint>A raster template with {{ '{' }}z{{ '}' }}/{{ '{' }}x{{ '}' }}/{{ '{' }}y{{ '}' }}, or a pmtiles:// archive. Leave empty for the offline basemap.</mat-hint>
                    @if (form.controls.tileServerUrl.hasError('tileTemplate')) {
                        <mat-error>Must contain {{ '{' }}z{{ '}' }}, {{ '{' }}x{{ '}' }} and {{ '{' }}y{{ '}' }} placeholders, or start with pmtiles://</mat-error>
                    }
                </mat-form-field>
                <div>
                    <button mat-flat-button color="primary" type="submit" [disabled]="saving()">
                        <mat-icon svgIcon="heroicons_outline:bookmark"></mat-icon>
                        <span class="ml-2">Save</span>
                    </button>
                </div>
            </form>
        </div>
    `,
})
export class MapSettingsComponent implements OnInit {
    private fb = inject(FormBuilder);
    private api = inject(GeoSettingsService);
    private toastr = inject(ToastrService);

    readonly saving = signal(false);
    readonly writesDisabled = signal(false);

    readonly form = this.fb.group({
        tileServerUrl: ['', [tileTemplateValidator]],
    });

    ngOnInit(): void {
        this.api.get().subscribe({
            next: (s) => this.form.controls.tileServerUrl.setValue(s.tileServerUrl ?? ''),
            error: () => undefined, // degrade gracefully — the form just starts empty
        });
    }

    save(): void {
        if (this.form.invalid) {
            this.form.markAllAsTouched();
            return;
        }
        const tileServerUrl = String(this.form.controls.tileServerUrl.value ?? '').trim() || null;
        this.saving.set(true);
        this.api.save({ tileServerUrl }).subscribe({
            next: () => {
                this.saving.set(false);
                this.toastr.success('Map settings saved');
            },
            error: (e) => {
                this.saving.set(false);
                if (e?.status === 503) this.writesDisabled.set(true);
                this.toastr.error(e?.status === 503 ? 'Writes are disabled.' : apiErrorMessage(e, 'Could not save map settings'));
            },
        });
    }
}

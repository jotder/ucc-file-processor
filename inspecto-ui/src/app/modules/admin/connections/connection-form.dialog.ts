import { Component, inject } from '@angular/core';
import { FormArray, FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage, ConnectionProfile, ConnectionsService, ConnectionTestResult } from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';

/** Dialog data: `profile` set ⇒ edit mode (id locked); absent ⇒ create. */
interface ConnectionFormData {
    profile?: ConnectionProfile;
}

/** Dialog close payload: the saved (masked) profile, or a 503 signal so the caller can hide mutate actions. */
export interface ConnectionFormResult {
    saved?: ConnectionProfile;
    writesDisabled?: boolean;
}

/**
 * Create/edit a connection profile. Secrets are authored as `${ENV:…}` references, never raw values;
 * on edit the masked `'***'` is preserved server-side when re-submitted unchanged. Submits to
 * POST /connections (create) or PUT /connections/{id} (edit).
 */
@Component({
    selector: 'app-connection-form-dialog',
    standalone: true,
    imports: [
        ReactiveFormsModule,
        MatDialogModule,
        MatButtonModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatProgressSpinnerModule,
        MatSelectModule,
        MatSlideToggleModule,
        InspectoAlertComponent,
    ],
    template: `
        <h2 mat-dialog-title>{{ isEdit ? 'Edit connection' : 'New connection' }}</h2>
        <form [formGroup]="form" (ngSubmit)="submit()">
            <mat-dialog-content class="space-y-2">
                <div class="grid grid-cols-1 gap-x-4 sm:grid-cols-2">
                    <mat-form-field subscriptSizing="dynamic">
                        <mat-label>Id</mat-label>
                        <input matInput formControlName="id" required />
                        @if (form.get('id'); as c) {
                            @if (c.hasError('required')) {
                                <mat-error>Id is required.</mat-error>
                            } @else if (c.hasError('pattern')) {
                                <mat-error>Start with a letter or digit; then letters, digits, <code>. _ -</code> only.</mat-error>
                            }
                        }
                    </mat-form-field>
                    <mat-form-field subscriptSizing="dynamic">
                        <mat-label>Connector</mat-label>
                        <mat-select formControlName="connector" required>
                            @for (c of connectors; track c) {
                                <mat-option [value]="c">{{ c }}</mat-option>
                            }
                        </mat-select>
                        @if (form.get('connector')?.hasError('required')) {
                            <mat-error>Choose a connector.</mat-error>
                        }
                    </mat-form-field>
                    <mat-form-field subscriptSizing="dynamic">
                        <mat-label>Host</mat-label>
                        <input matInput formControlName="host" />
                    </mat-form-field>
                    <mat-form-field subscriptSizing="dynamic">
                        <mat-label>Port</mat-label>
                        <input matInput type="number" formControlName="port" />
                    </mat-form-field>
                    <mat-form-field subscriptSizing="dynamic">
                        <mat-label>Database</mat-label>
                        <input matInput formControlName="database" />
                    </mat-form-field>
                    <mat-form-field subscriptSizing="dynamic">
                        <mat-label>Base path</mat-label>
                        <input matInput formControlName="basePath" />
                    </mat-form-field>
                    <mat-form-field subscriptSizing="dynamic">
                        <mat-label>Username</mat-label>
                        <input matInput formControlName="username" />
                    </mat-form-field>
                    <mat-form-field subscriptSizing="dynamic">
                        <mat-label>Password</mat-label>
                        <input matInput formControlName="password" />
                        <mat-hint>a $&#123;ENV:VAR&#125; reference, not a raw secret</mat-hint>
                    </mat-form-field>
                </div>

                <!-- Test the entered connection (no save) -->
                <div class="pt-1">
                    <button type="button" mat-stroked-button (click)="testConnection()" [disabled]="testing">
                        @if (testing) { <mat-spinner diameter="16" class="mr-2"></mat-spinner> }
                        <mat-icon class="icon-size-5" svgIcon="heroicons_outline:bolt"></mat-icon>
                        <span class="ml-1">Test connection</span>
                    </button>
                    @if (testResult; as r) {
                        <inspecto-alert
                            class="mt-2 block"
                            [variant]="r.reachable ? 'success' : 'error'"
                            [icon]="r.reachable ? 'heroicons_outline:check-circle' : 'heroicons_outline:x-circle'"
                        >
                            <span class="font-semibold">{{ r.reachable ? 'Reachable' : 'Unreachable' }}</span>@if (r.latencyMs != null) {
                                · {{ r.latencyMs }} ms
                            } · secrets {{ r.secretsResolved ? 'resolved' : 'unresolved' }}
                            <div class="text-secondary mt-0.5">{{ r.endpoint }} — {{ r.detail }}</div>
                        </inspecto-alert>
                    }
                </div>

                <!-- Options key/value editor -->
                <div class="pt-2">
                    <div class="mb-1 flex items-center justify-between">
                        <span class="font-medium">Options</span>
                        <button type="button" mat-stroked-button (click)="addOption()">
                            <mat-icon svgIcon="heroicons_outline:plus"></mat-icon>
                            <span class="ml-1">Add</span>
                        </button>
                    </div>
                    <div formArrayName="options" class="space-y-2">
                        @for (o of options.controls; track o; let i = $index) {
                            <div [formGroupName]="i" class="flex items-center gap-2">
                                <mat-form-field class="flex-1" subscriptSizing="dynamic">
                                    <mat-label>Key</mat-label>
                                    <input matInput formControlName="key" />
                                </mat-form-field>
                                <mat-form-field class="flex-1" subscriptSizing="dynamic">
                                    <mat-label>Value</mat-label>
                                    <input matInput formControlName="value" />
                                </mat-form-field>
                                <button type="button" mat-icon-button (click)="removeOption(i)" aria-label="Remove option">
                                    <mat-icon svgIcon="heroicons_outline:trash"></mat-icon>
                                </button>
                            </div>
                        }
                    </div>
                </div>

                <!-- Optional tunnel -->
                <div class="pt-2">
                    <mat-slide-toggle [checked]="tunnelEnabled" (change)="toggleTunnel($event.checked)">
                        SSH tunnel (bastion hop)
                    </mat-slide-toggle>
                    @if (tunnelEnabled) {
                        <div formGroupName="tunnel" class="mt-2 grid grid-cols-1 gap-x-4 sm:grid-cols-2">
                            <mat-form-field subscriptSizing="dynamic">
                                <mat-label>Tunnel host</mat-label>
                                <input matInput formControlName="host" />
                            </mat-form-field>
                            <mat-form-field subscriptSizing="dynamic">
                                <mat-label>Tunnel port</mat-label>
                                <input matInput type="number" formControlName="port" />
                            </mat-form-field>
                            <mat-form-field subscriptSizing="dynamic">
                                <mat-label>Tunnel username</mat-label>
                                <input matInput formControlName="username" />
                            </mat-form-field>
                            <mat-form-field subscriptSizing="dynamic">
                                <mat-label>Tunnel password</mat-label>
                                <input matInput formControlName="password" />
                                <mat-hint>a $&#123;ENV:VAR&#125; reference</mat-hint>
                            </mat-form-field>
                        </div>
                        <div class="mt-2">
                            <button type="button" mat-stroked-button (click)="testTunnel()" [disabled]="tunnelTesting">
                                @if (tunnelTesting) { <mat-spinner diameter="16" class="mr-2"></mat-spinner> }
                                <mat-icon class="icon-size-5" svgIcon="heroicons_outline:bolt"></mat-icon>
                                <span class="ml-1">Test tunnel</span>
                            </button>
                            @if (tunnelResult; as r) {
                                <inspecto-alert
                                    class="mt-2 block"
                                    [variant]="r.reachable ? 'success' : 'error'"
                                    [icon]="r.reachable ? 'heroicons_outline:check-circle' : 'heroicons_outline:x-circle'"
                                >
                                    <span class="font-semibold">{{ r.reachable ? 'Reachable' : 'Unreachable' }}</span>@if (r.latencyMs != null) {
                                        · {{ r.latencyMs }} ms
                                    }
                                    <div class="text-secondary mt-0.5">{{ r.endpoint }} — {{ r.detail }}</div>
                                </inspecto-alert>
                            }
                        </div>
                    }
                </div>
            </mat-dialog-content>
            <mat-dialog-actions align="end">
                <button type="button" mat-button mat-dialog-close>Cancel</button>
                <button type="submit" mat-flat-button color="primary" [disabled]="form.invalid || saving">
                    {{ isEdit ? 'Save' : 'Create' }}
                </button>
            </mat-dialog-actions>
        </form>
    `,
})
export class ConnectionFormDialog {
    private fb = inject(FormBuilder);
    private api = inject(ConnectionsService);
    private toastr = inject(ToastrService);
    private ref = inject(MatDialogRef<ConnectionFormDialog, ConnectionFormResult>);
    readonly data = inject<ConnectionFormData>(MAT_DIALOG_DATA);

    readonly connectors = ['local', 'sftp', 'ftp', 'ftps', 'db'];
    readonly isEdit = !!this.data.profile;
    tunnelEnabled = false;
    saving = false;
    testing = false;
    testResult: ConnectionTestResult | null = null;
    tunnelTesting = false;
    tunnelResult: ConnectionTestResult | null = null;

    form: FormGroup = this.fb.group({
        id: [
            { value: '', disabled: this.isEdit },
            [Validators.required, Validators.pattern(/^[A-Za-z0-9][A-Za-z0-9._-]*$/)],
        ],
        connector: ['local', Validators.required],
        host: [''],
        port: [null as number | null],
        database: [''],
        basePath: [''],
        username: [''],
        password: [''],
        options: this.fb.array([] as FormGroup[]),
        tunnel: this.fb.group({
            host: [''],
            port: [null as number | null],
            username: [''],
            password: [''],
        }),
    });

    constructor() {
        const p = this.data.profile;
        if (p) {
            this.form.patchValue({
                id: p.id,
                connector: p.connector,
                host: p.host ?? '',
                port: p.port ?? null,
                database: p.database ?? '',
                basePath: p.basePath ?? '',
                username: p.username ?? '',
                password: p.password ?? '',
            });
            for (const [key, value] of Object.entries(p.options ?? {})) {
                this.options.push(this.fb.group({ key: [key], value: [value] }));
            }
            if (p.tunnel?.host) {
                this.tunnelEnabled = true;
                this.form.get('tunnel')!.patchValue({
                    host: p.tunnel.host,
                    port: p.tunnel.port ?? null,
                    username: p.tunnel.username ?? '',
                    password: p.tunnel.password ?? '',
                });
            }
        }
    }

    get options(): FormArray {
        return this.form.get('options') as FormArray;
    }

    addOption(): void {
        this.options.push(this.fb.group({ key: [''], value: [''] }));
    }

    removeOption(i: number): void {
        this.options.removeAt(i);
    }

    toggleTunnel(on: boolean): void {
        this.tunnelEnabled = on;
    }

    /** Test the entered connection endpoint without saving (build the profile from the form, probe it). */
    testConnection(): void {
        this.testing = true;
        this.testResult = null;
        this.api.testProfile(this.build(), 'connection').subscribe({
            next: (r) => {
                this.testing = false;
                this.testResult = r;
            },
            error: (e) => {
                this.testing = false;
                this.toastr.warning(apiErrorMessage(e, 'Test failed'));
            },
        });
    }

    /** Test the SSH tunnel/bastion hop without saving. */
    testTunnel(): void {
        this.tunnelTesting = true;
        this.tunnelResult = null;
        this.api.testProfile(this.build(), 'tunnel').subscribe({
            next: (r) => {
                this.tunnelTesting = false;
                this.tunnelResult = r;
            },
            error: (e) => {
                this.tunnelTesting = false;
                this.toastr.warning(apiErrorMessage(e, 'Tunnel test failed'));
            },
        });
    }

    private build(): ConnectionProfile {
        const v = this.form.getRawValue();
        const options: Record<string, string> = {};
        for (const o of v.options as { key: string; value: string }[]) {
            if (o.key?.trim()) options[o.key.trim()] = o.value ?? '';
        }
        const profile: ConnectionProfile = {
            id: v.id,
            connector: v.connector,
        };
        if (v.host) profile.host = v.host;
        if (v.port != null) profile.port = Number(v.port);
        if (v.database) profile.database = v.database;
        if (v.basePath) profile.basePath = v.basePath;
        if (v.username) profile.username = v.username;
        if (v.password) profile.password = v.password;
        if (Object.keys(options).length) profile.options = options;
        if (this.tunnelEnabled && v.tunnel?.host) {
            profile.tunnel = {
                host: v.tunnel.host,
                port: v.tunnel.port != null ? Number(v.tunnel.port) : undefined,
                username: v.tunnel.username || undefined,
                password: v.tunnel.password || undefined,
            };
        }
        return profile;
    }

    submit(): void {
        if (this.form.invalid) {
            this.form.markAllAsTouched(); // surface inline mat-error messages
            return;
        }
        const profile = this.build();
        this.saving = true;
        const req$ = this.isEdit ? this.api.update(profile.id, profile) : this.api.create(profile);
        req$.subscribe({
            next: (saved) => {
                this.saving = false;
                this.toastr.success(`Connection "${profile.id}" ${this.isEdit ? 'updated' : 'created'}`);
                this.ref.close({ saved });
            },
            error: (e) => {
                this.saving = false;
                const msg =
                    e?.status === 503
                        ? 'Writes are disabled (no write root configured).'
                        : e?.status === 409
                          ? `A connection "${profile.id}" already exists.`
                          : apiErrorMessage(e, `Could not save "${profile.id}".`);
                this.toastr.error(msg);
                if (e?.status === 503) this.ref.close({ writesDisabled: true });
            },
        });
    }
}

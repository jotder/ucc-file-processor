import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage, ConnectionProfile, ConnectionsService, ConnectionTestResult } from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { CONNECTION_TYPES, attrsFor, connTypeDef, typeForConnector } from './connection-types';

/** Dialog data: `profile` set ⇒ edit mode (name/id locked); absent ⇒ create. */
interface ConnectionFormData {
    profile?: ConnectionProfile;
    /** Existing connection ids (create mode) — the save step validates name uniqueness against these. */
    existingIds?: string[];
}

/** Dialog close payload: the saved (masked) profile, or a 503 signal so the caller can hide mutate actions. */
export interface ConnectionFormResult {
    saved?: ConnectionProfile;
    writesDisabled?: boolean;
}

/**
 * Create/edit a connection profile — a schema-driven, typed form (mirrors the parser-config dialog). A
 * connection-type dropdown (Database / FTP / FTPS / Local / SFTP) drives the per-type attribute sheet.
 * Progressive disclosure ("ask only what's necessary"):
 *
 * - **Create is two steps**: the config step asks only type + per-type attributes; the save step then asks
 *   the connection **name** (= the profile id, pre-filled `<type>_<host>`, unique) + an optional
 *   description. Name/description are asked ONLY at save time.
 * - **Routing** (SSH tunnel/bastion + proxy) always starts collapsed — including on edit — since it's
 *   rarely needed; the Routing button shows which hops are configured.
 *
 * Secrets are authored as `${ENV:…}` references, never raw values; on edit a masked `'***'` is preserved
 * server-side. Submits to POST /connections (create) or PUT /connections/{id} (edit).
 */
@Component({
    selector: 'app-connection-form-dialog',
    standalone: true,
    imports: [
        ReactiveFormsModule,
        MatDialogModule,
        MatButtonModule,
        MatCheckboxModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatProgressSpinnerModule,
        MatSelectModule,
        MatSlideToggleModule,
        MatTooltipModule,
        InspectoAlertComponent,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    templateUrl: './connection-form.dialog.html',
})
export class ConnectionFormDialog {
    private fb = inject(FormBuilder);
    private api = inject(ConnectionsService);
    private toastr = inject(ToastrService);
    private ref = inject(MatDialogRef<ConnectionFormDialog, ConnectionFormResult>);
    readonly data = inject<ConnectionFormData>(MAT_DIALOG_DATA);

    readonly types = CONNECTION_TYPES;
    readonly isEdit = !!this.data.profile;
    readonly secretHint = 'a ${ENV:VAR} reference, not a raw secret';

    /** Selected connection type → drives the per-type attribute sheet + the saved `connector`. */
    readonly connType = signal<string>(this.data.profile ? typeForConnector(this.data.profile.connector) : 'sftp');
    readonly attrs = computed(() => attrsFor(this.connType()));
    readonly connTypeLabel = computed(() => connTypeDef(this.connType()).label);

    /** The routing panel (SSH tunnel + proxy) is hidden behind a routing icon; this pops it open. */
    readonly routingOpen = signal(false);
    /** SSH tunnel (bastion) and proxy are both unselected initially. */
    readonly tunnelEnabled = signal(false);
    readonly proxyEnabled = signal(false);

    readonly saving = signal(false);
    readonly testing = signal(false);
    readonly testResult = signal<ConnectionTestResult | null>(null);
    readonly tunnelTesting = signal(false);
    readonly tunnelResult = signal<ConnectionTestResult | null>(null);
    readonly proxyTesting = signal(false);
    readonly proxyResult = signal<ConnectionTestResult | null>(null);

    /** Create flow: `config` (type + attributes) → `save` (name + optional description). Edit stays on `config`. */
    readonly step = signal<'config' | 'save'>('config');

    /** The per-type attribute form (rebuilt whenever the connection type changes). */
    readonly attrsForm = signal<FormGroup>(this.fb.group({}));

    /** Save-step fields (create only): the connection name IS the unique profile id; description optional. */
    readonly saveForm = this.fb.group({
        name: [
            '',
            [
                Validators.required,
                Validators.pattern(/^[A-Za-z0-9][A-Za-z0-9._-]*$/),
                (c: { value: unknown }): { duplicate: true } | null =>
                    (this.data.existingIds ?? []).includes(String(c.value ?? '').trim()) ? { duplicate: true } : null,
            ],
        ],
        description: [''],
    });

    readonly form = this.fb.group({
        tunnel: this.fb.group({
            host: [''],
            port: [22 as number | null],
            username: [''],
            auth: ['password'],
            password: [''],
        }),
        proxy: this.fb.group({
            type: ['HTTP'],
            host: [''],
            port: [null as number | null],
            username: [''],
            password: [''],
        }),
    });

    constructor() {
        const p = this.data.profile;
        this.rebuildAttrs(this.connType(), p);
        if (p) {
            // On edit, reflect the stored routing (a new connection keeps the default-on tunnel).
            this.tunnelEnabled.set(!!p.tunnel?.host);
            if (p.tunnel?.host) {
                this.form.controls.tunnel.patchValue({
                    host: p.tunnel.host,
                    port: p.tunnel.port ?? 22,
                    username: p.tunnel.username ?? '',
                    password: p.tunnel.password ?? '',
                });
            }
            if (p.proxy?.host) {
                this.proxyEnabled.set(true);
                this.form.controls.proxy.patchValue({
                    type: p.proxy.type ?? 'HTTP',
                    host: p.proxy.host,
                    port: p.proxy.port ?? null,
                    username: p.proxy.username ?? '',
                    password: p.proxy.password ?? '',
                });
            }
            // Routing stays COLLAPSED even when configured — it's rarely edited; the Routing
            // button's suffix chips (· SSH tunnel · proxy) say what's set without expanding.
        }
    }

    /** Switch connection type → rebuild the attribute sheet to that type's defaults; clear the stale test. */
    onTypeChange(type: string): void {
        this.connType.set(type);
        this.rebuildAttrs(type);
        this.testResult.set(null);
    }

    /** (Re)build the per-type attribute form, seeding from {@code profile} (edit) or the type defaults. */
    private rebuildAttrs(type: string, profile?: ConnectionProfile): void {
        const fields = profile as unknown as Record<string, unknown> | undefined;
        const group: Record<string, unknown[]> = {};
        for (const a of attrsFor(type)) {
            const fallback = a.default ?? (a.control === 'checkbox' ? false : '');
            const stored = profile ? (a.target === 'option' ? profile.options?.[a.key] : fields?.[a.target]) : undefined;
            let init: unknown = stored ?? fallback;
            if (a.control === 'checkbox') init = init === true || init === 'true';
            group[a.key] = [init, a.required ? [Validators.required] : []];
        }
        this.attrsForm.set(this.fb.group(group));
    }

    /** The suggested connection name: `<type>_<host>` (base path for local), sanitized to the id charset. */
    suggestedName(): string {
        const raw = this.attrsForm().getRawValue() as Record<string, unknown>;
        const hostish = String(raw['host'] ?? raw['basePath'] ?? '').trim();
        const base = hostish ? `${this.connType()}_${hostish}` : this.connType();
        return base.replace(/[^A-Za-z0-9._-]+/g, '_').replace(/^[^A-Za-z0-9]+/, '');
    }

    /** Assemble the ConnectionProfile from the form (type-mapped fields + options + tunnel + proxy). */
    private build(): ConnectionProfile {
        const def = connTypeDef(this.connType());
        const id = this.isEdit
            ? this.data.profile!.id
            : String(this.saveForm.getRawValue().name ?? '').trim();
        const profile: ConnectionProfile = { id, connector: def.connector };
        const description = this.isEdit
            ? this.data.profile!.description
            : String(this.saveForm.getRawValue().description ?? '').trim() || undefined;
        if (description) profile.description = description;
        const fields = profile as unknown as Record<string, unknown>;
        const raw = this.attrsForm().getRawValue() as Record<string, unknown>;
        const options: Record<string, string> = {};
        for (const a of def.attrs) {
            let v = raw[a.key];
            if (a.control === 'number') v = v === '' || v == null ? null : Number(v);
            if (a.target === 'option') {
                if (a.control === 'checkbox') options[a.key] = v ? 'true' : 'false';
                else if (v !== '' && v != null) options[a.key] = String(v);
            } else if (v !== '' && v != null) {
                fields[a.target] = v;
            }
        }
        if (Object.keys(options).length) profile.options = options;

        const t = this.form.getRawValue().tunnel;
        if (this.tunnelEnabled() && t?.host) {
            profile.tunnel = {
                host: t.host,
                port: t.port != null ? Number(t.port) : undefined,
                username: t.username || undefined,
                password: t.password || undefined,
            };
        }
        const px = this.form.getRawValue().proxy;
        if (this.proxyEnabled() && px?.host) {
            profile.proxy = {
                type: px.type ?? 'HTTP',
                host: px.host,
                port: px.port != null ? Number(px.port) : undefined,
                username: px.username || undefined,
                password: px.password || undefined,
            };
        }
        return profile;
    }

    /** Test the entered connection endpoint without saving. */
    testConnection(): void {
        this.testing.set(true);
        this.testResult.set(null);
        this.api.testProfile(this.build(), 'connection').subscribe({
            next: (r) => {
                this.testing.set(false);
                this.testResult.set(r);
            },
            error: (e) => {
                this.testing.set(false);
                this.toastr.warning(apiErrorMessage(e, 'Test failed'));
            },
        });
    }

    /** Test the SSH tunnel / bastion hop without saving. */
    testTunnel(): void {
        this.tunnelTesting.set(true);
        this.tunnelResult.set(null);
        this.api.testProfile(this.build(), 'tunnel').subscribe({
            next: (r) => {
                this.tunnelTesting.set(false);
                this.tunnelResult.set(r);
            },
            error: (e) => {
                this.tunnelTesting.set(false);
                this.toastr.warning(apiErrorMessage(e, 'Tunnel test failed'));
            },
        });
    }

    /** Test the proxy hop without saving. */
    testProxy(): void {
        this.proxyTesting.set(true);
        this.proxyResult.set(null);
        this.api.testProfile(this.build(), 'proxy').subscribe({
            next: (r) => {
                this.proxyTesting.set(false);
                this.proxyResult.set(r);
            },
            error: (e) => {
                this.proxyTesting.set(false);
                this.toastr.warning(apiErrorMessage(e, 'Proxy test failed'));
            },
        });
    }

    /** Create flow only: leave the save step back to the config step (name/description are kept). */
    backToConfig(): void {
        this.step.set('config');
    }

    submit(): void {
        const af = this.attrsForm();
        if (this.form.invalid || af.invalid) {
            this.form.markAllAsTouched();
            af.markAllAsTouched();
            return;
        }
        // Create asks name + description only now, at save time — config valid ⇒ advance to the save step.
        if (!this.isEdit && this.step() === 'config') {
            if (this.saveForm.controls.name.pristine) {
                this.saveForm.patchValue({ name: this.suggestedName() });
            }
            this.step.set('save');
            return;
        }
        if (!this.isEdit && this.saveForm.invalid) {
            this.saveForm.markAllAsTouched();
            return;
        }
        const profile = this.build();
        this.saving.set(true);
        const req$ = this.isEdit ? this.api.update(profile.id, profile) : this.api.create(profile);
        req$.subscribe({
            next: (saved) => {
                this.saving.set(false);
                this.toastr.success(`Connection "${profile.id}" ${this.isEdit ? 'updated' : 'created'}`);
                this.ref.close({ saved });
            },
            error: (e) => {
                this.saving.set(false);
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

import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { ToastrService } from 'ngx-toastr';
import { describe, expect, it, vi } from 'vitest';
import { ConnectionProfile, ConnectionsService, ConnectionTestResult } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ConnectionFormDialog } from './connection-form.dialog';

const TEST_OK: ConnectionTestResult = {
    id: '(unsaved)', connector: 'sftp', endpoint: 'h:22', reachable: true, secretsResolved: true, detail: 'ok',
};

function create(profile?: ConnectionProfile, existingIds?: string[]) {
    const close = vi.fn();
    const api = {
        create: vi.fn((p: ConnectionProfile) => of(p)),
        update: vi.fn((_id: string, p: ConnectionProfile) => of(p)),
        testProfile: vi.fn(() => of(TEST_OK)),
    };
    TestBed.configureTestingModule({
        imports: [ConnectionFormDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MatDialogRef, useValue: { close } },
            { provide: MAT_DIALOG_DATA, useValue: { profile, existingIds } },
            { provide: ConnectionsService, useValue: api },
            { provide: ToastrService, useValue: { success: () => {}, error: () => {}, warning: () => {} } },
        ],
    });
    const fixture = TestBed.createComponent(ConnectionFormDialog);
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance, close, api };
}

describe('ConnectionFormDialog', () => {
    it('defaults to SFTP on the config step with routing collapsed and both hops unselected', () => {
        const { c } = create();
        expect(c.connType()).toBe('sftp');
        expect(c.step()).toBe('config');
        expect(c.routingOpen()).toBe(false);
        expect(c.tunnelEnabled()).toBe(false);
        expect(c.proxyEnabled()).toBe(false);
        expect(c.attrsForm().get('host')).toBeTruthy();
        expect(c.attrsForm().get('auth_method')!.value).toBe('password');
    });

    it('rebuilds the attribute sheet on connection-type change', () => {
        const { c } = create();
        c.onTypeChange('database');
        expect(c.connType()).toBe('database');
        expect(c.attrsForm().get('database')).toBeTruthy();
        expect(c.attrsForm().get('port')!.value).toBe(5432);
    });

    it('asks name + description only at save time, pre-filling <type>_<host>', () => {
        const { c, api, close } = create();
        c.onTypeChange('database');
        c.attrsForm().patchValue({ host: 'pg.example.com', database: 'warehouse', sslmode: 'require' });
        c.submit(); // config valid → advances to the save step, does NOT create yet
        expect(c.step()).toBe('save');
        expect(api.create).not.toHaveBeenCalled();
        expect(c.saveForm.controls.name.value).toBe('database_pg.example.com');

        c.saveForm.patchValue({ name: 'pg_main', description: 'main warehouse' });
        c.saveForm.controls.name.markAsDirty();
        c.submit();
        expect(api.create).toHaveBeenCalledWith(
            expect.objectContaining({
                id: 'pg_main',
                connector: 'db',
                host: 'pg.example.com',
                database: 'warehouse',
                description: 'main warehouse',
                options: expect.objectContaining({ sslmode: 'require', db_type: 'postgres' }),
            }),
        );
        expect(close).toHaveBeenCalledWith({ saved: expect.objectContaining({ id: 'pg_main' }) });
    });

    it('rejects a duplicate name on the save step', () => {
        const { c, api } = create(undefined, ['sftp_box']);
        c.attrsForm().patchValue({ host: 'h', username: 'u' });
        c.submit();
        expect(c.step()).toBe('save');
        c.saveForm.patchValue({ name: 'sftp_box' });
        c.submit();
        expect(api.create).not.toHaveBeenCalled();
        expect(c.saveForm.controls.name.hasError('duplicate')).toBe(true);
    });

    it('keeps a hand-edited name when going Back and Continue again', () => {
        const { c } = create();
        c.attrsForm().patchValue({ host: 'h', username: 'u' });
        c.submit();
        c.saveForm.patchValue({ name: 'my_own_name' });
        c.saveForm.controls.name.markAsDirty();
        c.backToConfig();
        expect(c.step()).toBe('config');
        c.submit();
        expect(c.saveForm.controls.name.value).toBe('my_own_name');
    });

    it('includes the SSH tunnel + proxy when enabled with a host', () => {
        const { c, api } = create();
        c.attrsForm().patchValue({ host: 'sftp.example.com', username: 'u' });
        c.tunnelEnabled.set(true);
        c.form.controls.tunnel.patchValue({ host: 'bastion', port: 22, username: 'jump' });
        c.proxyEnabled.set(true);
        c.form.controls.proxy.patchValue({ type: 'SOCKS5', host: 'proxy.example.com', port: 1080 });
        c.submit(); // → save step
        c.submit(); // pre-filled name is valid → create
        const p = api.create.mock.calls[0][0] as ConnectionProfile;
        expect(p.id).toBe('sftp_sftp.example.com');
        expect(p.tunnel?.host).toBe('bastion');
        expect(p.proxy).toEqual(expect.objectContaining({ type: 'SOCKS5', host: 'proxy.example.com', port: 1080 }));
    });

    it('tests the proxy hop without saving', () => {
        const { c, api } = create();
        c.proxyEnabled.set(true);
        c.form.controls.proxy.patchValue({ type: 'HTTP', host: 'proxy.example.com', port: 8080 });
        c.testProxy();
        expect(api.testProfile).toHaveBeenCalledWith(expect.objectContaining({ proxy: expect.objectContaining({ host: 'proxy.example.com' }) }), 'proxy');
        expect(c.proxyResult()?.reachable).toBe(true);
    });

    it('keeps routing collapsed on edit even when a tunnel is configured', () => {
        const { c } = create({ id: 'x', connector: 'sftp', tunnel: { host: 'bastion' } });
        expect(c.routingOpen()).toBe(false); // collapsed — rarely needed; expand on demand
        expect(c.tunnelEnabled()).toBe(true); // …but the stored hop is reflected once expanded
    });

    it('does not advance to the save step when a required attribute is missing', () => {
        const { c, api } = create();
        c.onTypeChange('database'); // host + database required
        c.submit();
        expect(c.step()).toBe('config');
        expect(api.create).not.toHaveBeenCalled();
    });

    it('edits an existing profile directly — no save step, id + description preserved', () => {
        const { c, api } = create({
            id: 'cdr_sftp', connector: 'sftp', host: 'h', description: 'prod drops',
            tunnel: { host: 'bastion' }, options: { auth_method: 'key' },
        });
        expect(c.connType()).toBe('sftp');
        expect(c.isEdit).toBe(true);
        expect(c.tunnelEnabled()).toBe(true);
        expect(c.attrsForm().get('auth_method')!.value).toBe('key');
        c.attrsForm().patchValue({ username: 'u2' });
        c.submit();
        expect(api.update).toHaveBeenCalledWith(
            'cdr_sftp',
            expect.objectContaining({ id: 'cdr_sftp', description: 'prod drops', username: 'u2' }),
        );
    });

    it('tests the entered connection without saving', () => {
        const { c, api } = create();
        c.attrsForm().patchValue({ host: 'sftp.example.com', username: 'u' });
        c.testConnection();
        expect(api.testProfile).toHaveBeenCalled();
        expect(c.testResult()?.reachable).toBe(true);
    });

    it('has no a11y violations on either step', async () => {
        const { fixture, c } = create();
        await expectNoA11yViolations(fixture.nativeElement);
        c.attrsForm().patchValue({ host: 'h', username: 'u' });
        c.submit();
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});

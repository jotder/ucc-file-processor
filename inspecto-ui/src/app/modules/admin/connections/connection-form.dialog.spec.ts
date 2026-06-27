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

function create(profile?: ConnectionProfile) {
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
            { provide: MAT_DIALOG_DATA, useValue: { profile } },
            { provide: ConnectionsService, useValue: api },
            { provide: ToastrService, useValue: { success: () => {}, error: () => {}, warning: () => {} } },
        ],
    });
    const fixture = TestBed.createComponent(ConnectionFormDialog);
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance, close, api };
}

describe('ConnectionFormDialog', () => {
    it('defaults to SFTP with routing collapsed and both hops unselected', () => {
        const { c } = create();
        expect(c.connType()).toBe('sftp');
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

    it('saves a new connection mapping type → connector + fields + options', () => {
        const { c, api, close } = create();
        c.onTypeChange('database');
        c.form.get('id')!.setValue('pg_main');
        c.attrsForm().patchValue({ host: 'pg.example.com', database: 'warehouse', sslmode: 'require' });
        c.tunnelEnabled.set(false);
        c.submit();
        expect(api.create).toHaveBeenCalledWith(
            expect.objectContaining({
                id: 'pg_main',
                connector: 'db',
                host: 'pg.example.com',
                database: 'warehouse',
                options: expect.objectContaining({ sslmode: 'require', db_type: 'postgres' }),
            }),
        );
        expect(close).toHaveBeenCalledWith({ saved: expect.objectContaining({ id: 'pg_main' }) });
    });

    it('includes the SSH tunnel + proxy when enabled with a host', () => {
        const { c, api } = create();
        c.form.get('id')!.setValue('sftp_box');
        c.attrsForm().patchValue({ host: 'sftp.example.com', username: 'u' });
        c.tunnelEnabled.set(true);
        c.form.controls.tunnel.patchValue({ host: 'bastion', port: 22, username: 'jump' });
        c.proxyEnabled.set(true);
        c.form.controls.proxy.patchValue({ type: 'SOCKS5', host: 'proxy.example.com', port: 1080 });
        c.submit();
        const p = api.create.mock.calls[0][0] as ConnectionProfile;
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

    it('reveals the routing panel for an edited profile that already uses a tunnel', () => {
        const { c } = create({ id: 'x', connector: 'sftp', tunnel: { host: 'bastion' } });
        expect(c.routingOpen()).toBe(true);
    });

    it('does not save when a required attribute is missing', () => {
        const { c, api } = create();
        c.onTypeChange('database'); // host + database required
        c.form.get('id')!.setValue('pg_x');
        c.submit();
        expect(api.create).not.toHaveBeenCalled();
    });

    it('edits an existing profile (type from connector, id locked, tunnel reflected)', () => {
        const { c } = create({ id: 'cdr_sftp', connector: 'sftp', host: 'h', tunnel: { host: 'bastion' }, options: { auth_method: 'key' } });
        expect(c.connType()).toBe('sftp');
        expect(c.isEdit).toBe(true);
        expect(c.form.get('id')!.disabled).toBe(true);
        expect(c.tunnelEnabled()).toBe(true);
        expect(c.attrsForm().get('auth_method')!.value).toBe('key');
    });

    it('tests the entered connection without saving', () => {
        const { c, api } = create();
        c.attrsForm().patchValue({ host: 'sftp.example.com', username: 'u' });
        c.testConnection();
        expect(api.testProfile).toHaveBeenCalled();
        expect(c.testResult()?.reachable).toBe(true);
    });

    it('has no a11y violations', async () => {
        const { fixture } = create();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});

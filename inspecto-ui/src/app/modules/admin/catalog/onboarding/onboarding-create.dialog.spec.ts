import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ToastrService } from 'ngx-toastr';
import { ConfigService, SpacesService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { OnboardingCreateData, OnboardingCreateDialog } from './onboarding-create.dialog';

const TOASTR = { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() };

function create(data: OnboardingCreateData, api: Partial<ConfigService> = {}) {
    const ref = { close: vi.fn(), disableClose: false };
    TestBed.configureTestingModule({
        imports: [OnboardingCreateDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MatDialogRef, useValue: ref },
            { provide: MAT_DIALOG_DATA, useValue: data },
            {
                provide: ConfigService,
                useValue: {
                    write: vi.fn(() => of({ path: 'x.toon', name: 'x' })),
                    registerPipeline: vi.fn(() => of({ registered: true })),
                    ...api,
                },
            },
            { provide: SpacesService, useValue: { currentSpaceId: () => 'demo' } },
            { provide: ToastrService, useValue: TOASTR },
        ],
    });
    const fixture = TestBed.createComponent(OnboardingCreateDialog);
    fixture.detectChanges();
    return { fixture, ref, api: TestBed.inject(ConfigService) };
}

describe('OnboardingCreateDialog', () => {
    beforeEach(() => localStorage.removeItem('inspecto.currentLens'));

    it('writes a minimal inactive draft, registers it, and closes with the name', () => {
        const { fixture, ref, api } = create({ kind: 'stream' });
        const c = fixture.componentInstance;
        c.form.controls.name.setValue('orders_feed');
        c.create();
        const [type, config] = (api.write as ReturnType<typeof vi.fn>).mock.calls[0] as [string, Record<string, unknown>];
        expect(type).toBe('pipeline');
        expect(config['name']).toBe('orders_feed');
        expect(config['active']).toBe(false);
        expect(config['produces']).toBeUndefined();
        expect(config['dirs']).toEqual({
            poll: 'spaces/demo/data/inbox/orders_feed',
            database: 'spaces/demo/data/orders_feed/database',
        });
        expect(api.registerPipeline).toHaveBeenCalledWith('x.toon');
        expect(ref.close).toHaveBeenCalledWith({ name: 'orders_feed' });
    });

    it('a Reference draft declares produces: reference', () => {
        const { fixture, api } = create({ kind: 'reference' });
        const c = fixture.componentInstance;
        c.form.controls.name.setValue('region_dim');
        c.create();
        const config = (api.write as ReturnType<typeof vi.fn>).mock.calls[0][1] as Record<string, unknown>;
        expect(config['produces']).toBe('reference');
    });

    it('rejects a duplicate name inline', () => {
        const { fixture, api } = create({ kind: 'stream', existingNames: ['orders_feed'] });
        const c = fixture.componentInstance;
        c.form.controls.name.setValue('orders_feed');
        c.create();
        expect(c.form.controls.name.hasError('duplicate')).toBe(true);
        expect(api.write).not.toHaveBeenCalled();
    });

    it('a 503 write shows the writes-disabled notice instead of closing', () => {
        const { fixture, ref } = create(
            { kind: 'stream' },
            { write: vi.fn(() => throwError(() => ({ status: 503 }))) },
        );
        const c = fixture.componentInstance;
        c.form.controls.name.setValue('x');
        c.create();
        fixture.detectChanges();
        expect(c.writesDisabled()).toBe(true);
        expect(ref.close).not.toHaveBeenCalled();
        expect(fixture.nativeElement.textContent).toContain('writes are disabled');
    });

    it('has no a11y violations (advanced open)', async () => {
        const { fixture } = create({ kind: 'stream' });
        fixture.componentInstance.advancedOpen.set(true);
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});

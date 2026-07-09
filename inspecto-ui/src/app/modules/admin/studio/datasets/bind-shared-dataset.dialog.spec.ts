import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ExchangeGrant, ExchangeService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { BindSharedDatasetDialog } from './bind-shared-dataset.dialog';

function grant(over: Partial<ExchangeGrant>): ExchangeGrant {
    return {
        id: 'default~analytics-hub~dataset~fx_rates_daily',
        kind: 'dataset',
        item: 'fx_rates_daily',
        owner: 'analytics-hub',
        consumer: 'default',
        mode: 'snapshot',
        status: 'active',
        requestedBy: 'appUser',
        requestedAt: 0,
        purpose: '',
        approvedBy: 'analyst',
        approvedAt: 0,
        pin: null,
        expiresAt: null,
        ...over,
    };
}

function create(opts: { grants: ExchangeGrant[]; existingNames?: string[] }) {
    const close = vi.fn();
    const grants = vi.fn(() => of(opts.grants));
    TestBed.configureTestingModule({
        imports: [BindSharedDatasetDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MatDialogRef, useValue: { close } },
            { provide: MAT_DIALOG_DATA, useValue: { me: 'default', existingNames: opts.existingNames ?? [] } },
            { provide: ExchangeService, useValue: { grants } },
        ],
    });
    const f = TestBed.createComponent(BindSharedDatasetDialog);
    f.detectChanges();
    return { f, c: f.componentInstance, close, grants };
}

describe('BindSharedDatasetDialog', () => {
    it('lists only the active dataset grants where this space is the consumer', () => {
        const { c } = create({
            grants: [
                grant({ id: 'a', status: 'active' }),
                grant({ id: 'b', status: 'requested' }), // pending — excluded
                grant({ id: 'c', kind: 'widget' }), // widget — excluded
                grant({ id: 'd', consumer: 'other-space' }), // not mine — excluded
            ],
        });
        expect(c.grants().map((g) => g.id)).toEqual(['a']);
    });

    it('auto-selects the sole grant and pre-fills the local name', () => {
        const { c } = create({ grants: [grant({ id: 'a' })] });
        expect(c.form.controls.grantId.value).toBe('a');
        expect(c.form.controls.name.value).toBe('analytics-hub_fx_rates_daily');
    });

    it('blocks submit on a duplicate name and does not close', () => {
        const { c, close } = create({
            grants: [grant({ id: 'a' })],
            existingNames: ['analytics-hub_fx_rates_daily'],
        });
        expect(c.form.controls.name.hasError('duplicate')).toBe(true);
        c.submit();
        expect(close).not.toHaveBeenCalled();
    });

    it('closes with the chosen grant and local name on a valid submit', () => {
        const { c, close } = create({ grants: [grant({ id: 'a' })] });
        c.submit();
        expect(close).toHaveBeenCalledWith({
            name: 'analytics-hub_fx_rates_daily',
            owner: 'analytics-hub',
            item: 'fx_rates_daily',
        });
    });

    it('shows the empty state and cannot submit when no active grants exist', () => {
        const { c, close } = create({ grants: [] });
        expect(c.grants().length).toBe(0);
        c.submit();
        expect(close).not.toHaveBeenCalled();
    });

    it('has no a11y violations', async () => {
        const { f } = create({ grants: [grant({ id: 'a' })] });
        await expectNoA11yViolations(f.nativeElement);
    });
});

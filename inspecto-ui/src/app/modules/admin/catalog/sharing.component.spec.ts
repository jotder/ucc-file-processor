import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { GammaConfigService } from '@gamma/services/config';
import { ExchangeGrant, ExchangeOffer, ExchangeService, SpacesService } from 'app/inspecto/api';
import { InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ToastrService } from 'ngx-toastr';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { SharingComponent } from './sharing.component';

const OFFERS: ExchangeOffer[] = [
    {
        kind: 'dataset', item: 'fx_rates_daily', owner: 'analytics-hub', description: 'FX rates',
        resultSet: {}, offeredBy: 'analyst', offeredAt: 1, dataset: null,
        freshness: { version: 'v3', rows: 42, refreshedAt: '2026-07-08T00:00:00Z', columns: [] },
    },
    {
        kind: 'dataset', item: 'customer_segments', owner: 'analytics-hub', description: 'Segments',
        resultSet: {}, offeredBy: 'analyst', offeredAt: 2, dataset: null,
    },
    {
        kind: 'dataset', item: 'billing_summary', owner: 'default', description: 'Billing',
        resultSet: {}, offeredBy: 'appUser', offeredAt: 3, dataset: null,
    },
];

const GRANTS: ExchangeGrant[] = [
    {
        id: 'default~analytics-hub~dataset~fx_rates_daily',
        kind: 'dataset', item: 'fx_rates_daily', owner: 'analytics-hub', consumer: 'default',
        mode: 'snapshot', status: 'active', requestedBy: 'appUser', requestedAt: 1, purpose: '',
        approvedBy: 'analyst', approvedAt: 2, pin: null, expiresAt: null,
    },
    {
        id: 'analytics-hub~default~dataset~billing_summary',
        kind: 'dataset', item: 'billing_summary', owner: 'default', consumer: 'analytics-hub',
        mode: 'snapshot', status: 'requested', requestedBy: 'analyst', requestedAt: 3, purpose: 'x',
        approvedBy: null, approvedAt: 0, pin: null, expiresAt: null,
    },
];

async function create(view: 'with-me' | 'by-me', exchange: Partial<ExchangeService> = {}) {
    const api = {
        grants: () => of(GRANTS),
        offers: () => of(OFFERS),
        actOnGrant: vi.fn(() => of(GRANTS[1])),
        request: vi.fn(() => of(GRANTS[0])),
        ...exchange,
    } as unknown as ExchangeService;
    TestBed.configureTestingModule({
        imports: [SharingComponent],
        providers: [
            provideNoopAnimations(),
            { provide: ExchangeService, useValue: api },
            { provide: SpacesService, useValue: { currentSpaceId: () => 'default' } },
            { provide: MatDialog, useValue: {} },
            { provide: ToastrService, useValue: { success: vi.fn(), error: vi.fn() } },
            { provide: InspectoGridThemeService, useValue: { theme: () => ({}) } },
            { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } },
        ],
    });
    await TestBed.compileComponents(); // the embedded data-table carries a @defer block
    const fixture = TestBed.createComponent(SharingComponent);
    fixture.componentRef.setInput('view', view);
    fixture.detectChanges(); // ngOnInit → reload()
    return { fixture, api };
}

describe('SharingComponent', () => {
    it('with-me: shows the grants I consume and the offers I can request', async () => {
        const { fixture } = await create('with-me');
        const c = fixture.componentInstance;
        expect(c.myGrants().map((g) => g.item)).toEqual(['fx_rates_daily']);
        // Other spaces' offers only — my own billing_summary offer is excluded.
        expect(c.myOffers().map((o) => o.item)).toEqual(['fx_rates_daily', 'customer_segments']);
    });

    it('with-me: the request action hides where a grant is already requested or active', async () => {
        const { fixture } = await create('with-me');
        const c = fixture.componentInstance;
        const visible = c.offerActions[0].visible!;
        expect(visible(c.myOffers()[0])).toBe(false); // fx_rates_daily — active grant in flight
        expect(visible(c.myOffers()[1])).toBe(true); // customer_segments — nothing requested yet
    });

    it('by-me: shows inbound grants with approve/deny on requested and revoke on active', async () => {
        const { fixture } = await create('by-me');
        const c = fixture.componentInstance;
        expect(c.myGrants().map((g) => g.item)).toEqual(['billing_summary']);
        const requested = c.myGrants()[0];
        expect(c.grantActions[0].visible!(requested)).toBe(true); // approve
        expect(c.grantActions[1].visible!(requested)).toBe(true); // deny
        expect(c.grantActions[2].visible!(requested)).toBe(false); // revoke needs active
        const active = { ...requested, status: 'active' as const };
        expect(c.grantActions[0].visible!(active)).toBe(false);
        expect(c.grantActions[2].visible!(active)).toBe(true);
    });

    it('by-me: approving a request calls the service and reloads the ledgers', async () => {
        const { fixture, api } = await create('by-me');
        const c = fixture.componentInstance;
        const grantsSpy = vi.spyOn(api, 'grants');
        c.grantActions[0].onClick(c.myGrants()[0]);
        expect(api.actOnGrant).toHaveBeenCalledWith('analytics-hub~default~dataset~billing_summary', 'approve');
        expect(grantsSpy).toHaveBeenCalled(); // reload after the transition
    });

    it('renders with no a11y violations', async () => {
        const { fixture } = await create('with-me');
        await expectNoA11yViolations(fixture.nativeElement);
    });
});

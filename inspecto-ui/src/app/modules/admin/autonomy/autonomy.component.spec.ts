import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { AutonomyAction, AutonomyPolicy, AutonomyService, LensService } from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ToastrService } from 'ngx-toastr';
import { AutonomyComponent } from './autonomy.component';

const POLICY: AutonomyPolicy = {
    killSwitch: false,
    classes: { batch_rerun: { mode: 'AUTO', maxPerHour: 3, maxPerDay: 20 } },
    updatedAt: '2026-07-21T10:00:00Z',
    updatedBy: 'operator',
};

const ACTION: AutonomyAction = {
    id: 'act-1',
    actionClass: 'batch_rerun',
    subject: { pipeline: 'orders', batchId: 'b-42' },
    decision: 'ALLOW',
    reason: 'within budget',
    status: 'SUCCEEDED',
    detail: 'reprocessed batch b-42 of pipeline orders',
    at: '2026-07-21T10:01:00Z',
};

async function create(
    overrides: Partial<Record<keyof AutonomyService, unknown>> = {},
    { canOperate = true, confirmed = true } = {},
) {
    const toastr = { info: vi.fn(), error: vi.fn(), warning: vi.fn(), success: vi.fn() };
    const api = {
        policy: () => of(structuredClone(POLICY)),
        updatePolicy: vi.fn((p) => of({ ...structuredClone(POLICY), ...p })),
        setKillSwitch: vi.fn((engaged: boolean) => of({ ...structuredClone(POLICY), killSwitch: engaged })),
        actions: () => of([ACTION]),
        ...overrides,
    } as unknown as AutonomyService;
    TestBed.configureTestingModule({
        imports: [AutonomyComponent],
        providers: [
            provideNoopAnimations(),
            { provide: AutonomyService, useValue: api },
            { provide: ToastrService, useValue: toastr },
            {
                provide: InspectoConfirmService,
                useValue: { confirm: vi.fn(async () => confirmed), confirmDestructive: vi.fn(async () => confirmed) },
            },
            { provide: LensService, useValue: { canOperateRuns: () => canOperate } },
            { provide: InspectoGridThemeService, useValue: { theme: () => ({}) } },
            { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } },
        ],
    });
    await TestBed.compileComponents(); // data-table @defer block
    const fixture = TestBed.createComponent(AutonomyComponent);
    fixture.detectChanges(); // ngOnInit → load()
    return { fixture, api, toastr };
}

describe('AutonomyComponent', () => {
    it('loads the policy and action ledger on init', async () => {
        const { fixture } = await create();
        const c = fixture.componentInstance;
        expect(c.policy?.killSwitch).toBe(false);
        expect(c.actions).toEqual([ACTION]);
        expect(c.unavailable).toBe(false);
        expect(fixture.nativeElement.textContent).toContain('Autonomy');
    });

    it('surfaces the pilot class even when unconfigured, and reflects configured values', async () => {
        const { fixture } = await create({ policy: () => of({ ...structuredClone(POLICY), classes: {} }) });
        const rows = fixture.componentInstance.rows;
        expect(rows.some((r) => r.name === 'batch_rerun')).toBe(true);
        expect(rows.find((r) => r.name === 'batch_rerun')?.mode).toBe('OFF'); // default when unset
    });

    it('engaging the kill switch confirms then calls the service', async () => {
        const { fixture, api } = await create();
        await fixture.componentInstance.toggleKillSwitch();
        expect(api.setKillSwitch).toHaveBeenCalledWith(true);
        expect(fixture.componentInstance.policy?.killSwitch).toBe(true);
    });

    it('a declined kill-switch confirmation calls nothing', async () => {
        const { fixture, api } = await create({}, { confirmed: false });
        await fixture.componentInstance.toggleKillSwitch();
        expect(api.setKillSwitch).not.toHaveBeenCalled();
    });

    it('editing is Ops-gated: a read-only lens cannot operate and mutations no-op', async () => {
        const { fixture, api } = await create({}, { canOperate: false });
        const c = fixture.componentInstance;
        expect(c.canOperate).toBe(false);
        await c.toggleKillSwitch();
        c.savePolicy();
        expect(api.setKillSwitch).not.toHaveBeenCalled();
        expect(api.updatePolicy).not.toHaveBeenCalled();
    });

    it('savePolicy PUTs the edited per-class state', async () => {
        const { fixture, api } = await create();
        const c = fixture.componentInstance;
        const row = c.rows.find((r) => r.name === 'batch_rerun')!;
        row.mode = 'SHADOW';
        row.maxPerHour = 5;
        c.savePolicy();
        expect(api.updatePolicy).toHaveBeenCalledWith({
            killSwitch: false,
            classes: expect.objectContaining({ batch_rerun: { mode: 'SHADOW', maxPerHour: 5, maxPerDay: 20 } }),
        });
    });

    it('degrades to an unavailable state + toast when the policy read fails', async () => {
        const { fixture, toastr } = await create({ policy: () => throwError(() => ({ status: 503 })) });
        const c = fixture.componentInstance;
        expect(c.unavailable).toBe(true);
        expect(c.policy).toBeNull();
        expect(toastr.error).toHaveBeenCalledWith('Autonomy policy is not available');
    });

    it('renders the loaded state with no a11y violations', async () => {
        const { fixture } = await create();
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});

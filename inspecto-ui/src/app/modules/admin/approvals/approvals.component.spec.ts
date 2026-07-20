import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { AgentApproval, ApprovalsService, LensService } from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ToastrService } from 'ngx-toastr';
import { ApprovalsComponent } from './approvals.component';

const PENDING: AgentApproval = {
    id: 'a1',
    tool: 'job_run',
    agentActor: 'agent:run-1',
    summary: 'run job nightly-rollup',
    arguments: { job: 'nightly-rollup' },
    preview: { action: 'run-job', target: 'nightly-rollup', jobExists: true },
    status: 'PENDING',
    requestedAt: '2026-07-20T10:00:00Z',
    decidedAt: null,
    decidedBy: null,
};

const APPROVED: AgentApproval = { ...PENDING, status: 'APPROVED', decidedBy: 'operator', decidedAt: '2026-07-20T10:01:00Z' };

async function create(
    overrides: Partial<Record<keyof ApprovalsService, unknown>> = {},
    { canOperate = true, confirmed = true } = {},
) {
    const toastr = { info: vi.fn(), error: vi.fn(), warning: vi.fn(), success: vi.fn() };
    const api = {
        list: () => of([PENDING]),
        get: vi.fn(),
        decide: vi.fn(() => of(APPROVED)),
        ...overrides,
    } as unknown as ApprovalsService;
    TestBed.configureTestingModule({
        imports: [ApprovalsComponent],
        providers: [
            provideNoopAnimations(),
            { provide: ApprovalsService, useValue: api },
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
    const fixture = TestBed.createComponent(ApprovalsComponent);
    fixture.detectChanges(); // ngOnInit → load()
    return { fixture, api, toastr };
}

describe('ApprovalsComponent', () => {
    it('loads the inbox on init', async () => {
        const { fixture } = await create();
        const c = fixture.componentInstance;
        expect(c.approvals).toEqual([PENDING]);
        expect(c.loading).toBe(false);
        expect(fixture.nativeElement.textContent).toContain('Approvals Inbox');
    });

    it('deciding is Ops-gated: no row actions for a read-only lens', async () => {
        const { fixture } = await create({}, { canOperate: false });
        expect(fixture.componentInstance.rowActions).toEqual([]);
    });

    it('row actions target only PENDING requests', async () => {
        const { fixture } = await create();
        const actions = fixture.componentInstance.rowActions;
        expect(actions.length).toBe(2);
        expect(actions[0].visible?.(PENDING)).toBe(true);
        expect(actions[0].visible?.(APPROVED)).toBe(false);
    });

    it('approve: confirms, calls decide, and reflects the terminal status in place', async () => {
        const { fixture, api } = await create();
        const c = fixture.componentInstance;
        await c.approve(PENDING);
        expect(api.decide).toHaveBeenCalledWith('a1', 'approve');
        expect(c.approvals[0].status).toBe('APPROVED');
    });

    it('a declined confirmation leaves the request pending and calls nothing', async () => {
        const { fixture, api } = await create({}, { confirmed: false });
        const c = fixture.componentInstance;
        await c.decline(PENDING);
        expect(api.decide).not.toHaveBeenCalled();
        expect(c.approvals[0].status).toBe('PENDING');
    });

    it('a decide failure surfaces a toast and leaves the row unchanged', async () => {
        const { fixture, toastr } = await create({ decide: () => throwError(() => ({ status: 404 })) });
        const c = fixture.componentInstance;
        await c.approve(PENDING);
        expect(toastr.error).toHaveBeenCalled();
        expect(c.approvals[0].status).toBe('PENDING');
    });

    it('degrades to an empty inbox + plain failure toast when the load fails', async () => {
        const { fixture, toastr } = await create({ list: () => throwError(() => ({ status: 503 })) });
        const c = fixture.componentInstance;
        expect(c.approvals).toEqual([]);
        expect(c.loading).toBe(false);
        expect(toastr.error).toHaveBeenCalledWith('Failed to load approvals');
    });

    it('renders the loaded state with no a11y violations', async () => {
        const { fixture } = await create();
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});

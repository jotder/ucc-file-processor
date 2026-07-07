import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { AlertRule, AlertsService, FiredAlert, LensService } from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ToastrService } from 'ngx-toastr';
import { AlertsComponent } from './alerts.component';

const FIRED: FiredAlert = {
    rule: 'failed_batches',
    severity: 'CRITICAL',
    pipeline: 'cdr_ingest',
    metric: 'failed_batches',
    value: 3,
    comparator: '>',
    threshold: 0,
    window: '15m',
    epochMillis: 1,
    message: 'failed_batches > 0 breached',
};

const RULE: AlertRule = {
    name: 'failed_batches',
    metric: 'failed_batches',
    comparator: '>',
    threshold: 0,
    window: '15m',
    severity: 'CRITICAL',
};

async function create(
    overrides: Partial<Record<keyof AlertsService, unknown>> = {},
    { canAuthor = true, confirmed = true } = {},
) {
    const toastr = { info: vi.fn(), error: vi.fn(), warning: vi.fn(), success: vi.fn() };
    const api = {
        recent: () => of([FIRED]),
        rules: () => of([RULE]),
        evaluate: vi.fn(() => of([FIRED])),
        removeRule: vi.fn(() => of(void 0)),
        ...overrides,
    } as unknown as AlertsService;
    TestBed.configureTestingModule({
        imports: [AlertsComponent],
        providers: [
            provideNoopAnimations(),
            { provide: AlertsService, useValue: api },
            { provide: ToastrService, useValue: toastr },
            { provide: InspectoConfirmService, useValue: { confirmDestructive: vi.fn(async () => confirmed) } },
            { provide: LensService, useValue: { canAuthorAlertRules: () => canAuthor } },
            { provide: InspectoGridThemeService, useValue: { theme: () => ({}) } },
            { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } },
        ],
    });
    await TestBed.compileComponents(); // data-table @defer block
    const fixture = TestBed.createComponent(AlertsComponent);
    // A TestBed `{provide: MatDialog}` override would be shadowed here: DataTableComponent imports
    // MatDialogModule, so the component's *standalone injector* provides the real MatDialog closer
    // than the testing module. Spy on the instance the component actually got instead.
    const open = vi
        .spyOn((fixture.componentInstance as unknown as { dialog: MatDialog }).dialog, 'open')
        .mockReturnValue({ afterClosed: () => of({ saved: RULE }) } as never);
    fixture.detectChanges(); // ngOnInit → load()
    return { fixture, api, toastr, open };
}

describe('AlertsComponent', () => {
    it('loads fired alerts and the armed rules on init', async () => {
        const { fixture } = await create();
        const c = fixture.componentInstance;
        expect(c.alerts).toEqual([FIRED]);
        expect(c.rules).toEqual([RULE]);
        expect(c.loading).toBe(false);
        expect(fixture.nativeElement.textContent).toContain('Alert Rules');
    });

    it('a manual sweep reports fired count and reloads', async () => {
        const { fixture, api, toastr } = await create();
        fixture.componentInstance.evaluate();
        expect(api.evaluate).toHaveBeenCalled();
        expect(toastr.info).toHaveBeenCalledWith('1 alert(s) fired');
        expect(fixture.componentInstance.evaluating).toBe(false);
    });

    it('degrades to an empty grid + plain failure toast when the load fails', async () => {
        const { fixture, toastr } = await create({ recent: () => throwError(() => ({ status: 500 })) });
        const c = fixture.componentInstance;
        expect(c.alerts).toEqual([]);
        expect(c.loading).toBe(false);
        expect(toastr.error).toHaveBeenCalledWith('Failed to load alerts');
    });

    it('authoring is capability-gated: rule actions and New rule vanish for the read-only lens', async () => {
        const { fixture } = await create({}, { canAuthor: false });
        const c = fixture.componentInstance;
        expect(c.ruleActions).toEqual([]);
        expect(fixture.nativeElement.textContent).not.toContain('New rule');
    });

    it('New rule opens the form dialog and a save reloads + toasts', async () => {
        const { fixture, open, toastr } = await create();
        fixture.componentInstance.newRule();
        expect(open).toHaveBeenCalled();
        expect(toastr.success).toHaveBeenCalledWith('Alert rule "failed_batches" armed');
    });

    it('delete asks for confirmation, calls the API, and drops the row', async () => {
        const { fixture, api } = await create();
        const c = fixture.componentInstance;
        await c.removeRule(RULE);
        expect(api.removeRule).toHaveBeenCalledWith('failed_batches');
        expect(c.rules).toEqual([]);
    });

    it('a declined confirmation leaves the rule untouched', async () => {
        const { fixture, api } = await create({}, { confirmed: false });
        const c = fixture.componentInstance;
        await c.removeRule(RULE);
        expect(api.removeRule).not.toHaveBeenCalled();
        expect(c.rules).toEqual([RULE]);
    });

    it('renders the loaded state with no a11y violations', async () => {
        const { fixture } = await create();
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});

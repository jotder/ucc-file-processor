import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { AlertRule, AlertsService, FiredAlert } from 'app/inspecto/api';
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

async function create(overrides: Partial<Record<keyof AlertsService, unknown>> = {}) {
    const toastr = { info: vi.fn(), error: vi.fn(), warning: vi.fn() };
    const api = {
        recent: () => of([FIRED]),
        rules: () => of([RULE]),
        evaluate: vi.fn(() => of([FIRED])),
        ...overrides,
    } as unknown as AlertsService;
    TestBed.configureTestingModule({
        imports: [AlertsComponent],
        providers: [
            provideNoopAnimations(),
            { provide: AlertsService, useValue: api },
            { provide: ToastrService, useValue: toastr },
            { provide: InspectoGridThemeService, useValue: { theme: () => ({}) } },
            { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } },
        ],
    });
    await TestBed.compileComponents(); // data-table @defer block
    const fixture = TestBed.createComponent(AlertsComponent);
    fixture.detectChanges(); // ngOnInit → load()
    return { fixture, api, toastr };
}

describe('AlertsComponent', () => {
    it('loads fired alerts and the armed-rules summary on init', async () => {
        const { fixture } = await create();
        const c = fixture.componentInstance;
        expect(c.alerts).toEqual([FIRED]);
        expect(c.rules).toEqual([RULE]);
        expect(c.loading).toBe(false);
        expect(fixture.nativeElement.textContent).toContain('1 rule(s) armed');
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

    it('renders the loaded state with no a11y violations', async () => {
        const { fixture } = await create();
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});

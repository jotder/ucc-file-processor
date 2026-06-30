import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { INSPECTO_GRID_DARK, InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { AuditLogsComponent } from './audit-logs.component';

const AUDIT_EVENT = {
    eventId: 'e1',
    ts: 1000,
    timestamp: '2026-01-01T00:00:01.000Z',
    level: 'INFO',
    type: 'AUDIT',
    source: 'audit',
    pipeline: null,
    correlationId: null,
    message: 'appUser run.deleted orders',
    attributes: {
        actor: 'appUser',
        action: 'run.deleted',
        action_category: 'destructive',
        target_type: 'run',
        target_id: 'orders',
        ip: '127.0.0.1',
    },
};

describe('AuditLogsComponent', () => {
    beforeEach(async () => {
        TestBed.configureTestingModule({
            imports: [AuditLogsComponent],
            providers: [
                provideHttpClient(),
                provideHttpClientTesting(),
                provideNoopAnimations(),
                // Mock the grid theme (mirrors data-table.component.spec) so no gamma config is needed.
                { provide: InspectoGridThemeService, useValue: { theme: () => INSPECTO_GRID_DARK } },
            ],
        });
        await TestBed.compileComponents(); // data-table pro tier uses @defer
    });

    /** Flush both audit searches; the AUDIT one gets `rows`, ACCESS_DENIED gets none. */
    function flush(rows: unknown[]): void {
        const http = TestBed.inject(HttpTestingController);
        http.match((r) => r.url.includes('/events/search')).forEach((r) =>
            r.flush(r.request.params.get('type') === 'AUDIT' ? rows : []),
        );
    }

    it('flattens audit attributes into rows', () => {
        const fixture = TestBed.createComponent(AuditLogsComponent);
        const cmp = fixture.componentInstance;
        fixture.detectChanges(); // ngOnInit → load() (two searches)
        flush([AUDIT_EVENT]);
        fixture.detectChanges();

        expect(cmp.rows().length).toBe(1);
        const row = cmp.rows()[0];
        expect(row.actor).toBe('appUser');
        expect(row.action).toBe('run.deleted');
        expect(row.target).toBe('run:orders');
    });

    // audit-logs uses the data-table pro tier (CodeMirror @defer + ag-Grid); axe over it is heavy and
    // can cross vitest's 5s default under multi-worker contention. Give it headroom (see dataset-editor).
    it(
        'renders with no accessibility violations',
        async () => {
            const fixture = TestBed.createComponent(AuditLogsComponent);
            fixture.detectChanges();
            flush([AUDIT_EVENT]); // rows present so the grid renders its required children
            fixture.detectChanges();
            await fixture.whenStable();

            await expectNoA11yViolations(fixture.nativeElement);
        },
        15_000,
    );
});

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
    message: 'appUser pipeline.deleted orders',
    attributes: {
        actor: 'appUser',
        action: 'pipeline.deleted',
        action_category: 'destructive',
        target_type: 'pipeline',
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
        expect(row.action).toBe('pipeline.deleted');
        expect(row.target).toBe('pipeline:orders');
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

    it('offers Load more once a fetch returns a full page, and appends the next offset page on click (R6)', () => {
        const fullPage = Array.from({ length: 1000 }, (_, i) => ({ ...AUDIT_EVENT, eventId: `e${i}` }));
        const fixture = TestBed.createComponent(AuditLogsComponent);
        const c = fixture.componentInstance;
        fixture.detectChanges();
        flush(fullPage);
        fixture.detectChanges();
        expect(c.hasMore()).toBe(true);

        c.loadMore();
        const http = TestBed.inject(HttpTestingController);
        // The next AUDIT page is fetched at offset = rows already fetched for that type — no refetch from 0.
        const reqs = http.match((r) => r.url.includes('/events/search') && r.params.get('type') === 'AUDIT');
        expect(reqs[0].request.params.get('limit')).toBe('1000');
        expect(reqs[0].request.params.get('offset')).toBe('1000');
        reqs.forEach((r) => r.flush([{ ...AUDIT_EVENT, eventId: 'extra' }]));
        http.match((r) => r.url.includes('/events/search')).forEach((r) => r.flush([]));
        fixture.detectChanges();
        // The page is appended to the already-loaded rows, and a short page clears hasMore.
        expect(c.rows().length).toBe(1001);
        expect(c.hasMore()).toBe(false);
    });
});

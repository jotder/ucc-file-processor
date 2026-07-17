import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { EventFilter, EventRow, EventsService, SavedEventView } from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ToastrService } from 'ngx-toastr';
import { EventsComponent } from './events.component';

const EVENT: EventRow = {
    eventId: 'evt-1',
    ts: 1,
    timestamp: '2026-01-01T00:00:00Z',
    level: 'ERROR',
    type: 'BATCH_FAILED',
    source: 'engine',
    pipeline: 'cdr_ingest',
    correlationId: 'corr-1',
    message: 'BATCH_FAILED on cdr_ingest',
    attributes: {},
};

const VIEW: SavedEventView = {
    name: 'errors',
    filters: { level: 'ERROR', pipeline: 'cdr_ingest' },
    createdAt: 1,
};

async function create(overrides: Partial<Record<keyof EventsService, unknown>> = {}) {
    const search = vi.fn((_f: EventFilter) => of([EVENT]));
    const api = {
        search,
        views: () => of([VIEW]),
        saveView: vi.fn(() => of(VIEW)),
        deleteView: vi.fn(() => of({})),
        exportCsv: () => of('timestamp,level\n'),
        ...overrides,
    } as unknown as EventsService;
    TestBed.configureTestingModule({
        imports: [EventsComponent],
        providers: [
            provideNoopAnimations(),
            { provide: EventsService, useValue: api },
            { provide: MatDialog, useValue: {} },
            { provide: InspectoConfirmService, useValue: { confirmDestructive: () => Promise.resolve(true) } },
            { provide: ToastrService, useValue: { success: vi.fn(), error: vi.fn() } },
            { provide: InspectoGridThemeService, useValue: { theme: () => ({}) } },
            { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } },
        ],
    });
    await TestBed.compileComponents(); // data-table @defer block
    const fixture = TestBed.createComponent(EventsComponent);
    fixture.detectChanges(); // ngOnInit → load() + loadViews()
    return { fixture, api };
}

describe('EventsComponent', () => {
    it('loads events and saved views on init', async () => {
        const { fixture } = await create();
        const c = fixture.componentInstance;
        expect(c.events).toEqual([EVENT]);
        expect(c.views).toEqual([VIEW]);
        expect(c.loading).toBe(false);
    });

    it('sends only the set filters in the search query', async () => {
        const { fixture, api } = await create();
        const c = fixture.componentInstance;
        c.fLevel = 'WARN';
        c.fq = '  batch  ';
        c.load();
        expect(api.search).toHaveBeenLastCalledWith({
            level: 'WARN',
            type: undefined,
            pipeline: undefined,
            correlationId: undefined,
            q: 'batch',
            limit: 100,
        });
    });

    it('applies a saved view back onto the filter toolbar and re-queries', async () => {
        const { fixture, api } = await create();
        const c = fixture.componentInstance;
        c.applyView('errors');
        expect(c.fLevel).toBe('ERROR');
        expect(c.fPipeline).toBe('cdr_ingest');
        expect(api.search).toHaveBeenLastCalledWith(expect.objectContaining({ level: 'ERROR', pipeline: 'cdr_ingest' }));
    });

    it('clearing the correlation chip re-runs the query without it', async () => {
        const { fixture, api } = await create();
        const c = fixture.componentInstance;
        c.fCorrelation = 'corr-1';
        c.clearCorrelation();
        expect(c.fCorrelation).toBe('');
        expect(api.search).toHaveBeenLastCalledWith(expect.objectContaining({ correlationId: undefined }));
    });

    it('degrades to an empty grid + toast when the search fails', async () => {
        const { fixture } = await create({ search: () => throwError(() => ({ status: 500 })) });
        const c = fixture.componentInstance;
        expect(c.events).toEqual([]);
        expect(c.loading).toBe(false);
    });

    it('live-tail polls at the selected cadence and re-arms when the cadence changes', async () => {
        const { fixture, api } = await create();
        const c = fixture.componentInstance;
        vi.useFakeTimers();
        try {
            (api.search as ReturnType<typeof vi.fn>).mockClear();

            c.liveSeconds = 2;
            c.toggleLive(true);
            vi.advanceTimersByTime(2000);
            expect(api.search).toHaveBeenCalledTimes(1); // first tick at 2s

            // slow it down — the old 2s timer is torn down, no poll until the new 10s elapses
            c.liveSeconds = 10;
            c.restartLiveTail();
            vi.advanceTimersByTime(2000);
            expect(api.search).toHaveBeenCalledTimes(1);
            vi.advanceTimersByTime(8000);
            expect(api.search).toHaveBeenCalledTimes(2);

            c.toggleLive(false); // off → no further polling
            vi.advanceTimersByTime(30000);
            expect(api.search).toHaveBeenCalledTimes(2);
        } finally {
            vi.useRealTimers();
        }
    });

    it('renders the empty state with no a11y violations', async () => {
        const { fixture } = await create({ search: () => of([]) });
        fixture.detectChanges();
        expect(fixture.nativeElement.textContent).toContain('No events match the current filters.');
        await expectNoA11yViolations(fixture.nativeElement);
    });
});

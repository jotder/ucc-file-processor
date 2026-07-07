import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { afterEach, describe, expect, it } from 'vitest';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { INSPECTO_GRID_DARK, InspectoGridThemeService } from 'app/inspecto/grid';
import { StoreLineage } from 'app/inspecto/api';
import { environment } from '../../../../environments/environment';
import { StoreLineageComponent } from './store-lineage.component';

const base = environment.apiBaseUrl + '/v1'; // W7: apiUrl() builds /api/v1 paths

const LINEAGE: StoreLineage = {
    store: 'events_raw',
    upstream: [{ pipeline: 'events_etl', batchId: 'b1', inputFile: 'a.csv', partition: 'day=2020-04-03', rowCount: 1234 }],
    downstream: [{ flow: 'events_rollup', sinks: ['events_daily'] }],
};

describe('StoreLineageComponent', () => {
    let httpMock: HttpTestingController;

    async function create(store: string, payload: StoreLineage) {
        TestBed.configureTestingModule({
            imports: [StoreLineageComponent],
            providers: [
                provideHttpClient(),
                provideHttpClientTesting(),
                provideNoopAnimations(),
                // Mock the grid theme (mirrors audit-logs / data-table specs) so no gamma config is needed.
                { provide: InspectoGridThemeService, useValue: { theme: () => INSPECTO_GRID_DARK } },
            ],
        });
        await TestBed.compileComponents();
        httpMock = TestBed.inject(HttpTestingController);
        const fixture = TestBed.createComponent(StoreLineageComponent);
        fixture.componentRef.setInput('store', store);
        fixture.detectChanges();
        httpMock.expectOne(`${base}/lineage?store=${store}`).flush(payload);
        fixture.detectChanges();
        return fixture;
    }

    afterEach(() => {
        httpMock.verify();
        TestBed.resetTestingModule();
    });

    it('renders upstream count and downstream flows for a store', async () => {
        const fixture = await create('events_raw', LINEAGE);
        const text = fixture.nativeElement.textContent as string;
        expect(text).toContain('Files into this store (1)');
        expect(text).toContain('Consumed by (1)');
        expect(text).toContain('events_rollup');
    });

    it('shows an empty message when no flows consume the store', async () => {
        const fixture = await create('events_raw', { ...LINEAGE, downstream: [] });
        expect(fixture.nativeElement.textContent).toContain('No flows consume this store.');
    });

    it('has no a11y violations', async () => {
        const fixture = await create('events_raw', LINEAGE);
        await expectNoA11yViolations(fixture.nativeElement);
    });
});

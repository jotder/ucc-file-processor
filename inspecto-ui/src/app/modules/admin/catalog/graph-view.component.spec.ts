import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { GraphViewComponent } from './graph-view.component';

/** G6 can't instantiate in jsdom (per the angular-ui skill) — only the empty/no-data path is testable
 *  here; `rebuild()` returns before touching the canvas when there are no nodes. */
function create(data: GraphViewComponent['data'] = null) {
    TestBed.configureTestingModule({
        imports: [GraphViewComponent],
        providers: [{ provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } }],
    });
    const fixture = TestBed.createComponent(GraphViewComponent);
    fixture.componentRef.setInput('data', data);
    fixture.detectChanges();
    return fixture;
}

describe('GraphViewComponent', () => {
    it('renders the empty (no data) host with no a11y violations', async () => {
        const fixture = create(null);
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('renders an empty (zero-node) graph with no a11y violations', async () => {
        const fixture = create({ nodes: [], edges: [] });
        await expectNoA11yViolations(fixture.nativeElement);
    });
});

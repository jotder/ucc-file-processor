import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it } from 'vitest';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { GraphSourceId } from 'app/inspecto/graph';
import { LinkAnalysisQueryPanelComponent } from './link-analysis-query-panel.component';

function make(sourceId: GraphSourceId = 'entity-projection') {
    TestBed.configureTestingModule({
        imports: [LinkAnalysisQueryPanelComponent],
        providers: [provideNoopAnimations()],
    });
    const fixture = TestBed.createComponent(LinkAnalysisQueryPanelComponent);
    const c = fixture.componentInstance;
    fixture.componentRef.setInput('sourceId', sourceId);
    fixture.componentRef.setInput('sources', [{ id: 'entity-projection', label: 'Entity/Link', query: () => Promise.resolve({ nodes: [], edges: [] }) }]);
    fixture.detectChanges();
    return { fixture, c };
}

describe('LinkAnalysisQueryPanelComponent', () => {
    it('entity-projection: needs a dataset + source/target columns, then builds a projection', () => {
        const { c } = make('entity-projection');
        expect(c.buildQuery()).toEqual({ error: expect.stringMatching(/source and target/) });

        c.queryForm.patchValue({ datasetId: 'ds', sourceCol: 'a', targetCol: 'b', linkKindCol: 'rel' });
        expect(c.buildQuery()).toEqual({
            projection: { datasetId: 'ds', sourceCol: 'a', targetCol: 'b', linkKindCol: 'rel', attrCols: undefined, entityType: undefined },
        });
    });

    it('entity-projection: combining extra mappings requires an entity type on each', () => {
        const { c } = make('entity-projection');
        c.queryForm.patchValue({ datasetId: 'ds', sourceCol: 'a', targetCol: 'b' });
        c.addMapping();
        c.extraMappings.at(0).patchValue({ datasetId: 'ds2', sourceCol: 'x', targetCol: 'y' });
        expect(c.buildQuery()).toEqual({ error: expect.stringMatching(/entity type/) });

        c.queryForm.patchValue({ entityType: 'person' });
        c.extraMappings.at(0).patchValue({ entityType: 'account' });
        const q = c.buildQuery();
        expect('projections' in q && q.projections).toHaveLength(2);
    });

    it('provenance: needs a pipeline; folds extra pipelines into roots', () => {
        const { c } = make('provenance');
        expect(c.buildQuery()).toEqual({ error: expect.stringMatching(/pipeline/i) });

        c.queryForm.patchValue({ pipeline: 'p1', counts: true });
        expect(c.buildQuery()).toEqual({ from: 'p1', counts: true });

        c.queryForm.patchValue({ extraPipelines: ['p2'] });
        expect(c.buildQuery()).toEqual({ roots: ['p1', 'p2'], counts: true });
    });

    it('lineage: single root vs multi-root with depth/direction', () => {
        const { c } = make('lineage');
        c.queryForm.patchValue({ from: 'table:cdr', depth: 3, direction: 'out' });
        expect(c.buildQuery()).toEqual({ from: 'table:cdr', depth: 3, direction: 'out' });

        c.queryForm.patchValue({ extraRoots: 'table:orders, table:invoices' });
        expect(c.buildQuery()).toEqual({
            roots: ['table:cdr', 'table:orders', 'table:invoices'], depth: 3, direction: 'out',
        });
    });

    it('patchFormFromView repopulates the form from a saved view', () => {
        const { c } = make('entity-projection');
        c.patchFormFromView({
            id: 'v', name: 'V', sourceId: 'entity-projection',
            query: { projection: { datasetId: 'ds', sourceCol: 's', targetCol: 't' } },
        });
        expect(c.queryForm.getRawValue().datasetId).toBe('ds');
        expect(c.queryForm.getRawValue().sourceCol).toBe('s');
    });

    it('renders with no a11y violations', async () => {
        const { fixture } = make();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});

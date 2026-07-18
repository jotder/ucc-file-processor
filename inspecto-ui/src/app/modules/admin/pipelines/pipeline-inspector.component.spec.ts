import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it, vi } from 'vitest';
import { AuthoredNode } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { PipelineInspectorComponent } from './pipeline-inspector.component';

function create(inputs: Partial<PipelineInspectorComponent> = {}) {
    TestBed.configureTestingModule({
        imports: [PipelineInspectorComponent],
        providers: [provideNoopAnimations()],
    });
    const fixture = TestBed.createComponent(PipelineInspectorComponent);
    Object.assign(fixture.componentInstance, inputs);
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance };
}

const NODE: AuthoredNode = { id: 'parse', type: 'parser.dsv', name: 'Parse CSV', use: 'grammar/cdr_csv', config: { delimiter: ',' } };

describe('PipelineInspectorComponent', () => {
    it('shows the idle hint when nothing is selected', () => {
        const { fixture } = create();
        expect((fixture.nativeElement as HTMLElement).textContent).toContain('Drag a processor');
    });

    it('renders a selected node: category, status, name/use, and config rows', () => {
        const { fixture } = create({ node: NODE, status: 'configured', category: 'PARSE' });
        const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
        expect(text).toContain('Node · parse');
        expect(text).toContain('Parser'); // categoryLabel('PARSE')
        expect(text).toContain('Configured');
        expect(text).toContain('name:');
        expect(text).toContain('use:');
        expect(text).toContain('delimiter:');
    });

    it('shows the last-run overlay (T17) when provided, and nothing when absent', () => {
        // One TestBed/fixture, mutated between assertions — TestBed can only be configured once per test.
        const { fixture } = create({ node: NODE, status: 'configured', category: 'PARSE' });
        expect((fixture.nativeElement as HTMLElement).textContent).not.toContain('Last run:');

        fixture.componentRef.setInput('lastRun', { rowCount: 1234, runTs: '2026-07-18T10:00:00Z' });
        fixture.detectChanges();
        expect((fixture.nativeElement as HTMLElement).textContent).toContain('Last run: 1,234 row(s) · 2026-07-18T10:00:00Z');
    });

    it('emits configure/runToHere/connect/deleteSelected from the node actions', () => {
        const { fixture, c } = create({ node: NODE, status: 'configured', category: 'PARSE' });
        const configure = vi.fn();
        const runToHere = vi.fn();
        const connect = vi.fn();
        const del = vi.fn();
        c.configure.subscribe(configure);
        c.runToHere.subscribe(runToHere);
        c.connect.subscribe(connect);
        c.deleteSelected.subscribe(del);
        const buttons = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button'));
        buttons.find((b) => b.textContent?.includes('Configure'))?.click();
        buttons.find((b) => b.textContent?.includes('Run to here'))?.click();
        buttons.find((b) => b.textContent?.includes('Connect'))?.click();
        buttons.find((b) => b.getAttribute('aria-label') === 'Delete node')?.click();
        expect(configure).toHaveBeenCalledWith(NODE);
        expect(runToHere).toHaveBeenCalledWith(NODE);
        expect(connect).toHaveBeenCalled();
        expect(del).toHaveBeenCalled();
    });

    it('shows Preview data only for a sink.view node, and emits previewView on click', () => {
        const viewNode: AuthoredNode = { id: 'v', type: 'sink.view', name: 'orders_view' };
        const { fixture, c } = create({ node: viewNode, status: 'configured', category: 'SINK' });
        const preview = vi.fn();
        c.previewView.subscribe(preview);
        const buttons = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button'));
        const btn = buttons.find((b) => b.textContent?.includes('Preview data'));
        expect(btn).toBeTruthy();
        btn?.click();
        expect(preview).toHaveBeenCalledWith(viewNode);
    });

    it('hides Preview data for a non-view node', () => {
        const { fixture } = create({ node: NODE, status: 'configured', category: 'PARSE' });
        const buttons = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button'));
        expect(buttons.some((b) => b.textContent?.includes('Preview data'))).toBe(false);
    });

    it('renders the edge relationship picker when an edge is selected', () => {
        const { fixture } = create({
            selectedEdgeId: 'a->b:data:1',
            selectedEdgeRel: 'data',
            candidateRels: ['data', 'kept', 'dropped'],
        });
        expect((fixture.nativeElement as HTMLElement).textContent).toContain('Connection');
    });

    it('emits edgeRelChange and deleteSelected for the edge view', () => {
        const { fixture, c } = create({
            selectedEdgeId: 'a->b:data:1',
            selectedEdgeRel: 'data',
            candidateRels: ['data', 'kept'],
        });
        const change = vi.fn();
        const del = vi.fn();
        c.edgeRelChange.subscribe(change);
        c.deleteSelected.subscribe(del);
        Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button'))
            .find((b) => b.getAttribute('aria-label') === 'Delete connection')
            ?.click();
        expect(del).toHaveBeenCalled();
    });

    it('readOnly hides Configure/Connect/Delete but keeps Run to here', () => {
        // One TestBed/fixture, mutated between assertions — TestBed can only be configured once per test.
        const { fixture, c } = create({ node: NODE, status: 'configured', category: 'PARSE', readOnly: true });
        const el = fixture.nativeElement as HTMLElement;
        const buttons = Array.from(el.querySelectorAll('button'));
        expect(buttons.some((b) => b.textContent?.includes('Configure'))).toBe(false);
        expect(buttons.some((b) => b.textContent?.includes('Connect'))).toBe(false);
        expect(el.querySelector('[aria-label="Delete node"]')).toBeNull();
        expect(buttons.some((b) => b.textContent?.includes('Run to here'))).toBe(true);
    });

    it('readOnly hides Delete connection in the edge view', () => {
        const { fixture } = create({
            selectedEdgeId: 'a->b:data:1', selectedEdgeRel: 'data', candidateRels: ['data', 'kept'], readOnly: true,
        });
        expect(fixture.nativeElement.querySelector('[aria-label="Delete connection"]')).toBeNull();
    });

    it('has no a11y violations in any of the three states', async () => {
        // One TestBed/fixture, mutated between assertions — TestBed can only be configured once per test.
        const { fixture, c } = create();
        await expectNoA11yViolations(fixture.nativeElement); // idle

        c.node = NODE;
        c.status = 'tested';
        c.category = 'PARSE';
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement); // node selected

        c.node = null;
        c.selectedEdgeId = 'a->b:data:1';
        c.selectedEdgeRel = 'data';
        c.candidateRels = ['data'];
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement); // edge selected
    });
});

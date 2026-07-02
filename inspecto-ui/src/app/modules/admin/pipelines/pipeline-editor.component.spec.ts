import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { PipelineEditorComponent } from './pipeline-editor.component';
import { AuthoredPipeline, ComponentsService, LensService, PipelineDryRunResult, PipelinesService } from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { ToastrService } from 'ngx-toastr';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';

/** A canvas double — the real G6 host can't instantiate in jsdom, so we assert the mutation calls. */
function canvasMock() {
    return {
        addNode: vi.fn(),
        addNodeAtCenter: vi.fn(),
        addEdge: vi.fn(),
        removeElement: vi.fn(),
        updateNodeLabel: vi.fn(),
        setNodeStatus: vi.fn(),
    };
}

const FLOW: AuthoredPipeline = {
    name: 'demo',
    active: false,
    nodes: [
        { id: 'src', type: 'acquisition', config: { source_store: 'events' } },
        { id: 'flt', type: 'transform.filter', config: { where: 'amt >= 100' } },
    ],
    edges: [{ from: 'src', rel: 'data', to: 'flt' }],
};

describe('PipelineEditorComponent', () => {
    let api: {
        authoredList: ReturnType<typeof vi.fn>;
        nodeTypes: ReturnType<typeof vi.fn>;
        authoredRaw: ReturnType<typeof vi.fn>;
        createAuthored: ReturnType<typeof vi.fn>;
        replaceAuthored: ReturnType<typeof vi.fn>;
        deleteAuthored: ReturnType<typeof vi.fn>;
        dryRunAuthored: ReturnType<typeof vi.fn>;
    };

    beforeEach(() => {
        api = {
            authoredList: vi.fn().mockReturnValue(of([])),
            nodeTypes: vi.fn().mockReturnValue(
                of([{ type: 'transform.filter', category: 'TRANSFORM', label: 'Filter', description: '', accepts: ['data'], emits: ['data'], emitsNamedRoutes: false }]),
            ),
            authoredRaw: vi.fn().mockReturnValue(of(structuredClone(FLOW))),
            createAuthored: vi.fn().mockReturnValue(of({})),
            replaceAuthored: vi.fn().mockReturnValue(of({})),
            deleteAuthored: vi.fn().mockReturnValue(of({})),
            dryRunAuthored: vi.fn().mockReturnValue(of({ seedNode: 'src', nodes: [], sinks: [] } as PipelineDryRunResult)),
        };
        TestBed.configureTestingModule({
            imports: [PipelineEditorComponent],
            providers: [
                provideNoopAnimations(),
                { provide: PipelinesService, useValue: api },
                { provide: ComponentsService, useValue: { list: vi.fn().mockReturnValue(of([])) } },
                { provide: ToastrService, useValue: { success: vi.fn(), error: vi.fn() } },
                { provide: InspectoConfirmService, useValue: { confirmDestructive: vi.fn().mockResolvedValue(true) } },
            ],
        });
    });

    /** Build the component, run ngOnInit, and inject a canvas double (no live G6). */
    function make(): PipelineEditorComponent {
        const c = TestBed.createComponent(PipelineEditorComponent).componentInstance;
        c.ngOnInit();
        (c as unknown as { canvas: unknown }).canvas = canvasMock();
        return c;
    }
    function canvasOf(c: PipelineEditorComponent) {
        return (c as unknown as { canvas: ReturnType<typeof canvasMock> }).canvas;
    }

    it('dropping a palette node adds it to the model and the canvas', () => {
        const c = make();
        c.model.set(structuredClone(FLOW));
        c.onDropAdd({ type: 'transform.filter', x: 10, y: 20 });
        const m = c.model()!;
        expect(m.nodes).toHaveLength(3);
        expect(m.nodes[2].type).toBe('transform.filter');
        expect(c.dirty()).toBe(true);
        expect(canvasOf(c).addNode).toHaveBeenCalled();
    });

    it('clicking a palette node adds it at the canvas centre (the no-mouse path)', () => {
        const c = make();
        c.model.set(structuredClone(FLOW));
        c.addFromPalette('transform.filter');
        const m = c.model()!;
        expect(m.nodes).toHaveLength(3);
        expect(m.nodes[2].type).toBe('transform.filter');
        expect(canvasOf(c).addNodeAtCenter).toHaveBeenCalled();
        expect(c.dirty()).toBe(true);
    });

    it('two-click connect adds a new edge between the two nodes', () => {
        const c = make();
        c.model.set(structuredClone(FLOW));
        c.selectedNode.set(c.model()!.nodes[1]); // flt
        c.armConnect();
        c.onNodeSelected('src'); // flt -> src is not the existing src -> flt edge
        expect(c.model()!.edges.filter((e) => e.from === 'flt' && e.to === 'src')).toHaveLength(1);
        expect(c.connectFrom()).toBeNull();
        expect(canvasOf(c).addEdge).toHaveBeenCalled();
    });

    it('deleting a selected node drops it and its edges', () => {
        const c = make();
        c.model.set(structuredClone(FLOW));
        c.selectedNode.set(c.model()!.nodes[0]); // src (has edge src->flt)
        c.onDeleteKey();
        const m = c.model()!;
        expect(m.nodes.find((n) => n.id === 'src')).toBeUndefined();
        expect(m.edges).toHaveLength(0);
        expect(canvasOf(c).removeElement).toHaveBeenCalledWith('src');
    });

    it('dry-run parses the sample and maps the result', () => {
        const c = make();
        c.selectedId.set('demo');
        c.sampleText.setValue('[{"amt":"150"}]');
        c.runDryRun();
        expect(api.dryRunAuthored).toHaveBeenCalledWith('demo', [{ amt: '150' }]);
        expect(c.dryRunResult()?.seedNode).toBe('src');
    });

    it('dry-run rejects invalid JSON without calling the API', () => {
        const c = make();
        c.selectedId.set('demo');
        c.sampleText.setValue('not json');
        c.runDryRun();
        expect(api.dryRunAuthored).not.toHaveBeenCalled();
        expect(c.dryRunError()).toBeTruthy();
    });

    it('save PUTs the model and clears the dirty flag', () => {
        const c = make();
        c.selectedId.set('demo');
        c.model.set(structuredClone(FLOW));
        c.dirty.set(true);
        c.save();
        expect(api.replaceAuthored).toHaveBeenCalledWith('demo', FLOW);
        expect(c.dirty()).toBe(false);
    });

    it('a 503 on create marks the editor read-only', () => {
        api.createAuthored.mockReturnValue(throwError(() => ({ status: 503 })));
        const c = make();
        c.startNew();
        c.newName.setValue('x');
        c.createFlow();
        expect(c.unavailable()).toBe(true);
    });

    it('the empty path has no accessibility violations', async () => {
        const fixture = TestBed.createComponent(PipelineEditorComponent);
        fixture.detectChanges(); // no flows → empty-state, the G6 canvas is not mounted
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('hides New/Save/delete-selected in the Business (read-only) lens', () => {
        TestBed.inject(LensService).selectLens('business');
        const fixture = TestBed.createComponent(PipelineEditorComponent);
        fixture.detectChanges();
        const el = fixture.nativeElement as HTMLElement;
        expect(el.querySelector('[aria-label="New pipeline"]')).toBeNull();
        expect(el.querySelector('[aria-label="Save pipeline"]')).toBeNull();
        expect(el.querySelector('[aria-label="Delete the selected node or edge"]')).toBeNull();
    });
});

import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { MatDialog } from '@angular/material/dialog';
import { PipelineEditorComponent } from './pipeline-editor.component';
import { AuthoredPipeline, ComponentsService, LensService, PipelinesService } from 'app/inspecto/api';
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
        provenanceBatches: ReturnType<typeof vi.fn>;
        provenance: ReturnType<typeof vi.fn>;
    };
    let dialog: { open: ReturnType<typeof vi.fn> };
    let toast: { success: ReturnType<typeof vi.fn>; error: ReturnType<typeof vi.fn> };

    beforeEach(() => {
        // LensService persists to localStorage; clear it so a lens set by one test/file can't leak into another.
        localStorage.removeItem('inspecto.currentLens');
        api = {
            authoredList: vi.fn().mockReturnValue(of([])),
            nodeTypes: vi.fn().mockReturnValue(
                of([{ type: 'transform.filter', category: 'TRANSFORM', label: 'Filter', description: '', accepts: ['data'], emits: ['data'], emitsNamedRoutes: false }]),
            ),
            authoredRaw: vi.fn().mockReturnValue(of(structuredClone(FLOW))),
            createAuthored: vi.fn().mockReturnValue(of({})),
            replaceAuthored: vi.fn().mockReturnValue(of({})),
            deleteAuthored: vi.fn().mockReturnValue(of({})),
            provenanceBatches: vi.fn().mockReturnValue(of([])),
            provenance: vi.fn().mockReturnValue(of([])),
        };
        dialog = { open: vi.fn() };
        toast = { success: vi.fn(), error: vi.fn() };
        TestBed.configureTestingModule({
            imports: [PipelineEditorComponent],
            providers: [
                provideNoopAnimations(),
                { provide: PipelinesService, useValue: api },
                { provide: ComponentsService, useValue: { list: vi.fn().mockReturnValue(of([])) } },
                { provide: ToastrService, useValue: toast },
                { provide: InspectoConfirmService, useValue: { confirmDestructive: vi.fn().mockResolvedValue(true) } },
                { provide: MatDialog, useValue: dialog },
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

    it('selecting a flow loads its last-run overlay and paints edge counts (T17)', () => {
        api.provenanceBatches.mockReturnValue(of([{ batchId: 'b2', runTs: '2026-07-18T10:00:00Z', totalRows: 50 }]));
        api.provenance.mockReturnValue(of([{ nodeId: 'src', rel: 'data', rowCount: 50 }]));
        const c = make();
        c.select('demo');
        expect(api.provenanceBatches).toHaveBeenCalledWith('demo');
        expect(api.provenance).toHaveBeenCalledWith('demo', 'b2');
        expect(c.lastRunBatch()).toEqual({ batchId: 'b2', runTs: '2026-07-18T10:00:00Z', totalRows: 50 });
        const edge = c.g6Data()!.edges.find((e) => e.source === 'src' && e.target === 'flt');
        expect(edge!.data).toEqual({ kind: 'data · 50', weight: 50 });
        c.selectedNode.set(c.model()!.nodes[0]); // src
        expect(c.selectedNodeLastRun()).toEqual({ rowCount: 50, runTs: '2026-07-18T10:00:00Z' });
    });

    it('a node absent from the last run has no overlay (null, not zero)', () => {
        api.provenanceBatches.mockReturnValue(of([{ batchId: 'b2', runTs: '2026-07-18T10:00:00Z', totalRows: 50 }]));
        api.provenance.mockReturnValue(of([{ nodeId: 'src', rel: 'data', rowCount: 50 }]));
        const c = make();
        c.select('demo');
        c.selectedNode.set(c.model()!.nodes[1]); // flt — never emitted in this run
        expect(c.selectedNodeLastRun()).toBeNull();
    });

    it('no recorded run (empty batch list) leaves the overlay off, no error', () => {
        const c = make();
        c.select('demo');
        expect(c.lastRunBatch()).toBeNull();
        c.selectedNode.set(c.model()!.nodes[0]);
        expect(c.selectedNodeLastRun()).toBeNull();
    });

    it('a 404 (no provenance backend configured) degrades silently, no toast', () => {
        api.provenanceBatches.mockReturnValue(throwError(() => ({ status: 404 })));
        const c = make();
        c.select('demo');
        expect(c.lastRunBatch()).toBeNull();
        expect(toast.error).not.toHaveBeenCalled();
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

    it('the Business lens blocks model mutation even when called directly (defense-in-depth)', () => {
        TestBed.inject(LensService).selectLens('business');
        const c = make();
        c.model.set(structuredClone(FLOW));

        c.onDropAdd({ type: 'transform.filter', x: 10, y: 20 });
        expect(c.model()!.nodes).toHaveLength(2); // unchanged

        c.addFromPalette('transform.filter');
        expect(c.model()!.nodes).toHaveLength(2); // unchanged

        c.selectedNode.set(c.model()!.nodes[1]);
        c.onDeleteKey();
        expect(c.model()!.nodes).toHaveLength(2); // unchanged

        c.onEdgeCreated({ source: 'flt', target: 'src' });
        expect(c.model()!.edges.filter((e) => e.from === 'flt' && e.to === 'src')).toHaveLength(0);

        c.openNodeConfig(c.model()!.nodes[0]);
        expect(dialog.open).not.toHaveBeenCalled();
    });
});

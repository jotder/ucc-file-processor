import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { of } from 'rxjs';
import { ToastrService } from 'ngx-toastr';
import { describe, expect, it, vi } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { AssistService, CatalogService, NodeDetail } from 'app/inspecto/api';
import { InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { NodeDetailDialog } from './node-detail.dialog';

// A SOURCE-kind node so isStore() is false and <app-store-lineage> never renders.
const DETAIL: NodeDetail = {
    node: {
        id: 'source/cdr',
        kind: 'SOURCE',
        label: 'cdr',
        description: { text: 'CDR drop folder' },
        attrs: { path: '/data/in/cdr' },
    },
    neighbors: {
        nodes: [{ id: 'schema/cdr_csv', kind: 'SCHEMA', label: 'cdr_csv' }],
        edges: [{ from: 'source/cdr', to: 'schema/cdr_csv', kind: 'EMITS' }],
    },
};

function create() {
    const node = vi.fn(() => of(DETAIL));
    TestBed.configureTestingModule({
        imports: [NodeDetailDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MAT_DIALOG_DATA, useValue: { id: 'source/cdr' } },
            { provide: MatDialogRef, useValue: { close: vi.fn() } },
            { provide: CatalogService, useValue: { node } },
            { provide: AssistService, useValue: { run: vi.fn(() => of({})) } },
            { provide: ToastrService, useValue: { success: () => undefined, error: () => undefined } },
            InspectoGridThemeService,
            { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } },
        ],
    });
    const fixture = TestBed.createComponent(NodeDetailDialog);
    fixture.detectChanges();
    return { fixture, node };
}

describe('NodeDetailDialog', () => {
    it('loads the node detail and reloads in place when a neighbour is clicked', () => {
        const { fixture, node } = create();
        const c = fixture.componentInstance;
        expect(node).toHaveBeenCalledWith('source/cdr');
        expect(c.loading).toBe(false);
        expect(c.detail?.node.label).toBe('cdr');
        expect((fixture.nativeElement as HTMLElement).textContent).toContain('Neighbours (1 nodes, 1 edges)');

        c.onNeighbourClicked(DETAIL.neighbors.nodes[0]);
        expect(node).toHaveBeenLastCalledWith('schema/cdr_csv');
    });

    it('renders with no a11y violations', async () => {
        const { fixture } = create();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});

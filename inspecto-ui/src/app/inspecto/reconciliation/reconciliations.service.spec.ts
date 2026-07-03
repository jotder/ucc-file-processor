import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ComponentsService } from 'app/inspecto/api';
import { ReconciliationsService } from './reconciliations.service';
import { buildReconciliation } from './reconciliation-types';

function setup() {
    const create = vi.fn((_t: string, c: Record<string, unknown>) => of({ type: 'reconciliation', name: String(c['id']), ref: '', content: c }));
    const update = vi.fn((_t: string, id: string, c: Record<string, unknown>) => of({ type: 'reconciliation', name: id, ref: '', content: c }));
    const list = vi.fn(() =>
        of([
            {
                type: 'reconciliation', name: 'switch_vs_billing', ref: '',
                content: { name: 'switch vs billing', leftDataset: 'switch_cdr', rightDataset: 'billing_cdr', keyColumns: ['id'], compareColumns: [], breaks: [] },
            },
        ]),
    );
    TestBed.configureTestingModule({
        providers: [ReconciliationsService, { provide: ComponentsService, useValue: { create, update, list } }],
    });
    return { svc: TestBed.inject(ReconciliationsService), create, update, list };
}

describe('ReconciliationsService', () => {
    it('creates a reconciliation as a "reconciliation" registry component', () => {
        const { svc, create } = setup();
        svc.create(buildReconciliation('x', 'a', 'b', ['id'], [])).subscribe();
        expect(create).toHaveBeenCalledWith('reconciliation', expect.objectContaining({ leftDataset: 'a', rightDataset: 'b', keyColumns: ['id'] }));
    });

    it('saves (updates) an existing reconciliation via PUT', () => {
        const { svc, update } = setup();
        const r = { ...buildReconciliation('x', 'a', 'b', ['id'], []), lastRunAt: '2026-07-03T00:00:00Z' };
        svc.save(r).subscribe();
        expect(update).toHaveBeenCalledWith('reconciliation', r.id, expect.objectContaining({ lastRunAt: '2026-07-03T00:00:00Z' }));
    });

    it('lists reconciliations back from the registry', () => {
        const { svc } = setup();
        let out: { id: string; leftDataset: string }[] = [];
        svc.list().subscribe((r) => (out = r));
        expect(out[0].id).toBe('switch_vs_billing');
        expect(out[0].leftDataset).toBe('switch_cdr');
    });
});

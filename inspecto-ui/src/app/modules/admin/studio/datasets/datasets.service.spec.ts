import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ComponentsService } from 'app/inspecto/api';
import { DatasetsService } from './datasets.service';
import { buildDataset } from './dataset-types';

function setup() {
    const create = vi.fn((_t: string, c: Record<string, unknown>) =>
        of({ type: 'dataset', name: String(c['id']), ref: `dataset/${c['id']}`, content: c }),
    );
    const update = vi.fn((_t: string, id: string, c: Record<string, unknown>) =>
        of({ type: 'dataset', name: id, ref: `dataset/${id}`, content: c }),
    );
    const list = vi.fn(() =>
        of([
            {
                type: 'dataset',
                name: 'd1',
                ref: 'dataset/d1',
                content: { name: 'd1', kind: 'virtual', sourceName: 'cdr', columns: [], measures: [] },
            },
        ]),
    );
    TestBed.configureTestingModule({
        providers: [DatasetsService, { provide: ComponentsService, useValue: { create, update, list, remove: vi.fn(() => of(null)) } }],
    });
    return { svc: TestBed.inject(DatasetsService), create, update, list };
}

describe('DatasetsService', () => {
    it('saves a dataset as a "dataset" registry component', () => {
        const { svc, create } = setup();
        let saved: { id: string } | undefined;
        svc.save(buildDataset('d1', 'virtual', 'cdr')).subscribe((d) => (saved = d));
        expect(create).toHaveBeenCalledWith('dataset', expect.objectContaining({ id: 'd1', kind: 'virtual', sourceName: 'cdr' }));
        expect(saved?.id).toBe('d1');
    });

    it('edits go through PUT — save with {update: true} never re-creates (the backend 409s that)', () => {
        const { svc, create, update } = setup();
        svc.save(buildDataset('d1', 'virtual', 'cdr'), { update: true }).subscribe();
        expect(create).not.toHaveBeenCalled();
        expect(update).toHaveBeenCalledWith('dataset', 'd1', expect.objectContaining({ kind: 'virtual', sourceName: 'cdr' }));
    });

    it('lists datasets back from the registry', () => {
        const { svc } = setup();
        let datasets: { name: string; kind: string }[] = [];
        svc.list().subscribe((d) => (datasets = d));
        expect(datasets[0].name).toBe('d1');
        expect(datasets[0].kind).toBe('virtual');
    });
});

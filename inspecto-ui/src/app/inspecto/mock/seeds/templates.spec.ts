import { describe, expect, it } from 'vitest';
import { SAMPLE_SOURCES } from '../../../modules/admin/studio/datasets/dataset-sources';
import { componentCollection } from '../handlers/components.handler';
import { PIPELINES_COLL } from '../handlers/pipelines.handler';
import { MockStore } from '../mock-store';
import { SPACE_TEMPLATES } from './templates';

interface DatasetContent {
    name: string;
    content: { sourceName: string; columns: Array<{ name: string }> };
}
interface WidgetContent {
    name: string;
    content: { datasetId: string; vizType: string; controls: Record<string, Array<{ field: string }>> };
}
interface DashboardContent {
    content: { tiles: Array<{ widgetId: string }> };
}
interface ReconContent {
    content: { leftDataset: string; rightDataset: string; keyColumns: string[] };
}

/**
 * Referential coherence of every W5 Space-Template seed pack: the blueprint must hang together —
 * datasets resolve to real sample rows (with matching columns), widgets to seeded datasets (with
 * real fields), dashboard tiles to seeded widgets, reconciliations to seeded datasets — so a space
 * created from any template demos end-to-end with zero manual fixes.
 */
describe('space template seed packs', () => {
    for (const template of SPACE_TEMPLATES) {
        describe(template.id, () => {
            const store = new MockStore();
            const space = `t-${template.id}`;
            store.ensureSeeded(space, template.seed);

            const datasets = store.list<DatasetContent>(space, componentCollection('dataset'));
            const widgets = store.list<WidgetContent>(space, componentCollection('widget'));

            it('seeds a non-trivial blueprint (pipelines, datasets, widgets, a dashboard)', () => {
                expect(store.list(space, PIPELINES_COLL).length).toBeGreaterThan(0);
                expect(datasets.length).toBeGreaterThan(1);
                expect(widgets.length).toBeGreaterThanOrEqual(3);
                expect(store.list(space, componentCollection('dashboard')).length).toBe(1);
                expect(store.list(space, componentCollection('requirement')).length).toBe(1);
            });

            it('every dataset resolves to SAMPLE_SOURCES rows and declares real columns', () => {
                for (const d of datasets) {
                    const rows = SAMPLE_SOURCES[d.content.sourceName];
                    expect(rows, `source ${d.content.sourceName}`).toBeDefined();
                    expect(rows.length).toBeGreaterThan(0);
                    for (const col of d.content.columns) {
                        expect(Object.keys(rows[0]), `column ${col.name} of ${d.content.sourceName}`).toContain(col.name);
                    }
                }
            });

            it('every widget maps real fields of a seeded dataset', () => {
                for (const w of widgets) {
                    const ds = datasets.find((d) => d.name === w.content.datasetId);
                    expect(ds, `dataset ${w.content.datasetId}`).toBeDefined();
                    const cols = new Set(ds!.content.columns.map((c) => c.name));
                    for (const assignments of Object.values(w.content.controls)) {
                        for (const a of assignments) expect(cols, `field ${a.field}`).toContain(a.field);
                    }
                }
            });

            it('every dashboard tile references a seeded widget', () => {
                const widgetIds = new Set(widgets.map((w) => w.name));
                for (const dash of store.list<DashboardContent>(space, componentCollection('dashboard'))) {
                    for (const tile of dash.content.tiles) expect(widgetIds).toContain(tile.widgetId);
                }
            });

            it('every reconciliation joins two seeded datasets on columns both sides have', () => {
                const dsNames = new Set(datasets.map((d) => d.name));
                for (const r of store.list<ReconContent>(space, componentCollection('reconciliation'))) {
                    expect(dsNames).toContain(r.content.leftDataset);
                    expect(dsNames).toContain(r.content.rightDataset);
                    for (const side of [r.content.leftDataset, r.content.rightDataset]) {
                        const ds = datasets.find((d) => d.name === side)!;
                        const cols = new Set(ds.content.columns.map((c) => c.name));
                        for (const k of r.content.keyColumns) expect(cols).toContain(k);
                    }
                }
            });
        });
    }
});

import { describe, expect, it } from 'vitest';
import type { ComponentDef } from '../../api/components.service';
import { registerIntegrityRules } from '../integrity';
import { MockRequest } from '../mock-http';
import { MockStore } from '../mock-store';
import { seedDefaultSpace } from '../seeds/default-space.seed';
import { componentCollection, componentsHandler } from './components.handler';

const req = (method: string, url: string, body: unknown = null): MockRequest => ({
    method,
    url,
    body,
    params: {},
    space: 'default',
});

function seededStore(): MockStore {
    const store = new MockStore();
    registerIntegrityRules(store);
    store.ensureSeeded('default', seedDefaultSpace);
    return store;
}

describe('componentsHandler', () => {
    const handler = componentsHandler({ mockStudio: true, mockFlows: true });

    it('lists, creates, gets and deletes a component kind', () => {
        const store = seededStore();
        const listed = handler(req('GET', '/api/components/grammar'), store);
        expect((listed?.body as ComponentDef[]).map((d) => d.name)).toContain('cdr_csv');

        handler(req('POST', '/api/components/grammar', { id: 'tsv', delimiter: '\t' }), store);
        const got = handler(req('GET', '/api/components/grammar/tsv'), store);
        expect((got?.body as ComponentDef).content['delimiter']).toBe('\t');

        const del = handler(req('DELETE', '/api/components/grammar/tsv'), store);
        expect(del?.body).toEqual({ deleted: true });
        expect(handler(req('GET', '/api/components/grammar/tsv'), store)?.body).toBeNull();
    });

    it('409s deleting a widget a dashboard tiles, and a dataset a widget binds (R1 generic rules)', () => {
        const store = seededStore();
        // Seeded: dashboard investigation_overview tiles cost_by_tariff, which binds dataset cdr_sample.
        const widgetDel = handler(req('DELETE', '/api/components/widget/cost_by_tariff'), store);
        expect(widgetDel?.status).toBe(409);
        expect(String((widgetDel?.body as { error: string }).error)).toContain('investigation_overview');

        const datasetDel = handler(req('DELETE', '/api/components/dataset/cdr_sample'), store);
        expect(datasetDel?.status).toBe(409);
        expect(String((datasetDel?.body as { error: string }).error)).toContain('cost_by_tariff');
    });

    it('409s a delete while the component is still referenced', () => {
        const store = seededStore();
        // Seeded pipeline cdr_ingest does not bind grammar/cdr_csv via `use`; wire one that does.
        store.put('default', 'authored-pipeline', 'uses_grammar', {
            name: 'uses_grammar',
            active: true,
            nodes: [{ id: 'p', type: 'parser.dsv', name: 'Parse', use: 'grammar/cdr_csv' }],
            edges: [],
        });
        const res = handler(req('DELETE', '/api/components/grammar/cdr_csv'), store);
        expect(res?.status).toBe(409);
        expect(String((res?.body as { error: string }).error)).toContain('uses_grammar');
        expect(store.get('default', componentCollection('grammar'), 'cdr_csv')).toBeDefined();
    });

    it('respects the per-kind flag gating (studio kinds vs registry kinds)', () => {
        const store = seededStore();
        const studioOnly = componentsHandler({ mockStudio: true, mockFlows: false });
        expect(studioOnly(req('GET', '/api/components/dataset'), store)).toBeDefined();
        expect(studioOnly(req('GET', '/api/components/grammar'), store)).toBeUndefined(); // falls through
    });

    it('previews a pasted DSV sample with the configured delimiter', () => {
        const store = seededStore();
        const res = handler(
            req('POST', '/api/components/grammar/preview', {
                parserType: 'dsv',
                content: { column_delimiter: '|', header_position: 'top' },
                sampleText: 'a|b\n1|2\nbad_row\n3|4',
            }),
            store,
        );
        const preview = res?.body as { kind: string; columns: string[]; rowCount: number; rejectedRows: number };
        expect(preview.kind).toBe('table');
        expect(preview.columns).toEqual(['a', 'b']);
        expect(preview.rowCount).toBe(2);
        expect(preview.rejectedRows).toBe(1);
    });
});

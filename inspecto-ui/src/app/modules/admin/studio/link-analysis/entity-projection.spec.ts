import { describe, expect, it } from 'vitest';
import { of, throwError } from 'rxjs';
import { SAMPLE_SOURCES } from 'app/modules/admin/studio/datasets/dataset-sources';
import { Dataset } from 'app/modules/admin/studio/datasets/dataset-types';
import {
    EntityProjectionGraphSource,
    PROJECTION_NODE_CAP,
    ProjectedGraph,
    isProjectionError,
    mergeProjectedGraphs,
    projectEntities,
    projectTriples,
} from './entity-projection';

const rows = [
    { source: 'sub-01', target: 'dev-01', link_type: 'shared_device', weight: 5 },
    { source: 'sub-02', target: 'dev-01', link_type: 'shared_device', weight: 4 },
    { source: 'sub-01', target: 'sub-02', link_type: 'calls', weight: 17 },
    { source: 'sub-01', target: 'sub-02', link_type: 'calls', weight: 3 }, // duplicate link → folded count
    { source: '', target: 'dev-01', link_type: 'calls' },                   // blank endpoint → skipped
];

describe('projectEntities', () => {
    it('folds rows into entities and typed, deduplicated links', () => {
        const g = projectEntities(rows, { datasetId: 'd', sourceCol: 'source', targetCol: 'target', linkKindCol: 'link_type' });
        if (isProjectionError(g)) throw new Error(g.error);
        expect(g.nodes.map((n) => n.id).sort()).toEqual(['entity:dev-01', 'entity:sub-01', 'entity:sub-02']);
        expect(g.nodes[0].data.kind).toBe('entity');
        expect(g.edges).toHaveLength(3);
        const calls = g.edges.find((e) => e.id.includes('calls'))!;
        expect(calls.data).toEqual({ kind: 'calls · 2', count: 2 });
        expect(g.truncated).toBe(false);
    });

    it('defaults the link kind when no kind column is mapped', () => {
        const g = projectEntities(rows.slice(0, 1), { datasetId: 'd', sourceCol: 'source', targetCol: 'target' }) as ProjectedGraph;
        expect(g.edges[0].data.kind).toBe('link');
    });

    it('reports a bad mapping as a typed error, not a throw', () => {
        const missing = projectEntities(rows, { datasetId: 'd', sourceCol: 'nope', targetCol: 'target' });
        expect(isProjectionError(missing) && missing.error).toMatch(/'nope'/);
        const blank = projectEntities(rows, { datasetId: 'd', sourceCol: '', targetCol: 'target' });
        expect(isProjectionError(blank)).toBe(true);
    });

    it('truncates at the node cap and says so', () => {
        const many = Array.from({ length: PROJECTION_NODE_CAP + 50 }, (_, i) => ({ a: `s${i}`, b: `t${i}` }));
        const g = projectEntities(many, { datasetId: 'd', sourceCol: 'a', targetCol: 'b' }) as ProjectedGraph;
        expect(g.nodes).toHaveLength(PROJECTION_NODE_CAP);
        expect(g.truncated).toBe(true);
    });

    it('attrCols join the fold key and round-trip onto the edge (differing values split a folded pair)', () => {
        const mixed = [
            { source: 's', target: 't', channel: 'sms' },
            { source: 's', target: 't', channel: 'call' },
        ];
        const g = projectEntities(mixed, { datasetId: 'd', sourceCol: 'source', targetCol: 'target', attrCols: ['channel'] }) as ProjectedGraph;
        expect(g.edges).toHaveLength(2);
        expect(g.edges.every((e) => (e.data as unknown as { count: number }).count === 1)).toBe(true);
        const smsEdge = g.edges.find((e) => e.data.attrs?.channel === 'sms');
        expect(smsEdge).toBeDefined();
    });

    it('tags nodes from a caseId/incidentId column with an objectRef; other columns get none (R8)', () => {
        const g = projectEntities(
            [{ caseId: 'case-7', msisdn: '555-0101' }],
            { datasetId: 'd', sourceCol: 'caseId', targetCol: 'msisdn' },
        ) as ProjectedGraph;
        expect(g.nodes.find((n) => n.id === 'entity:case-7')!.data.objectRef).toEqual({ id: 'case-7', type: 'CASE' });
        expect(g.nodes.find((n) => n.id === 'entity:555-0101')!.data.objectRef).toBeUndefined();
    });
});

// The four example graphs seeded for user testing (default-space.seed.ts): pin each one's
// shape so a seed edit can't silently break a saved example view.
describe('example graph sample sources (C5 user testing)', () => {
    const CASES: Array<[source: string, nodes: number, links: number]> = [
        ['graph_simple', 6, 5],
        ['graph_moderate', 11, 13],
        ['graph_mindmap', 20, 19],
        ['graph_complex', 41, 57],
    ];

    it('each projects cleanly with the seeded mapping and stays under the node cap', () => {
        for (const [source, nodes, links] of CASES) {
            const g = projectEntities(SAMPLE_SOURCES[source], {
                datasetId: source, sourceCol: 'source', targetCol: 'target', linkKindCol: 'link_type',
            });
            if (isProjectionError(g)) throw new Error(`${source}: ${g.error}`);
            expect(g.nodes, source).toHaveLength(nodes);
            expect(g.edges, source).toHaveLength(links);
            expect(g.truncated, source).toBe(false);
        }
    });
});

describe('EntityProjectionGraphSource', () => {
    const ds: Dataset = {
        id: 'links-ds', name: 'Links', kind: 'physical', sourceName: 'links',
        query: null, physicalRef: null, columns: [], measures: [], calculated: [],
    };

    /** An InvService stub for the offline path: the backend call fails, forcing the client fold. */
    const offlineInv = { project: () => throwError(() => new Error('offline')) } as never;

    it('falls back to the client fold over sample rows when the backend is unavailable', async () => {
        const src = new EntityProjectionGraphSource({ get: () => of(ds) } as never, offlineInv);
        const g = (await src.query({
            projection: { datasetId: 'links-ds', sourceCol: 'source', targetCol: 'target', linkKindCol: 'link_type' },
        })) as ProjectedGraph;
        expect(g.nodes.length).toBeGreaterThan(0);
        expect(g.edges.some((e) => e.data.kind.startsWith('shared_device'))).toBe(true);
    });

    it('requires a mapping and surfaces projection errors as thrown messages', async () => {
        const src = new EntityProjectionGraphSource({ get: () => of(ds) } as never, offlineInv);
        await expect(src.query({})).rejects.toThrow(/mapping/);
        await expect(
            src.query({ projection: { datasetId: 'links-ds', sourceCol: 'bogus', targetCol: 'target' } }),
        ).rejects.toThrow(/bogus/);
    });

    it('multi-mapping: merges N mappings into one graph via q.projections, type-scoping node ids', async () => {
        const inv = {
            project: (req: { dataset: string }) => {
                if (req.dataset === 'phones-ds') {
                    return of({ rows: [{ source: '555-0101', target: '555-0102', kind: null, count: 1 }], truncated: false });
                }
                return of({ rows: [{ source: '555-0101', target: 'acct-9', kind: null, count: 1 }], truncated: false });
            },
        } as never;
        const src = new EntityProjectionGraphSource({ get: () => of(ds) } as never, inv);
        const g = await src.query({
            projections: [
                { datasetId: 'phones-ds', sourceCol: 'a', targetCol: 'b', entityType: 'phone' },
                { datasetId: 'accounts-ds', sourceCol: 'a', targetCol: 'b', entityType: 'account' },
            ],
        });
        // The same value '555-0101' surfaces under two entity types and stays two distinct nodes.
        expect(g.nodes.map((n) => n.id).sort()).toEqual([
            'entity:account:555-0101', 'entity:account:acct-9', 'entity:phone:555-0101', 'entity:phone:555-0102',
        ]);
    });

    it('is backend-first: aggregated triples become the graph, no dataset row fetch (INV-1)', async () => {
        const inv = {
            project: (req: unknown) => {
                expect(req).toEqual({
                    dataset: 'links-ds', sourceCol: 'source', targetCol: 'target', linkKindCol: undefined,
                });
                return of({
                    rows: [
                        { source: 'alice', target: 'bob', kind: null, count: 3 },
                        { source: 'bob', target: 'carol', kind: null, count: 1 },
                    ],
                    truncated: false,
                });
            },
        } as never;
        const datasets = { get: () => { throw new Error('must not fetch rows on the backend path'); } } as never;
        const src = new EntityProjectionGraphSource(datasets, inv);
        const g = (await src.query({
            projection: { datasetId: 'links-ds', sourceCol: 'source', targetCol: 'target' },
        })) as ProjectedGraph;
        expect(g.nodes.map((n) => n.id)).toEqual(['entity:alice', 'entity:bob', 'entity:carol']);
        expect(g.edges[0].data.kind).toBe('link · 3');
        expect(g.edges[1].data.kind).toBe('link');
        expect(g.truncated).toBe(false);
    });
});

describe('projectTriples', () => {
    it('folds triples with the same shapes as the client fold (ids, kind·count, cap)', () => {
        const g = projectTriples(
            [
                { source: 'a', target: 'b', kind: 'sms', count: 2 },
                { source: 'a', target: 'c', kind: 'call', count: 1 },
                { source: ' ', target: 'x', kind: null, count: 5 },   // blank endpoint skipped
            ],
            false,
        );
        expect(g.nodes.map((n) => n.id)).toEqual(['entity:a', 'entity:b', 'entity:c']);
        expect(g.nodes[0].data.kind).toBe('entity');
        expect(g.edges.map((e) => e.id)).toEqual(['entity:a->entity:b:sms', 'entity:a->entity:c:call']);
        expect(g.edges[0].data.kind).toBe('sms · 2');
        expect(g.edges[1].data.kind).toBe('call');
        expect(g.truncated).toBe(false);
    });

    it('caps nodes and carries server truncation through', () => {
        const many = Array.from({ length: PROJECTION_NODE_CAP + 50 }, (_, i) => ({
            source: `s${i}`, target: `t${i}`, kind: null, count: 1,
        }));
        const capped = projectTriples(many, false);
        expect(capped.nodes.length).toBeLessThanOrEqual(PROJECTION_NODE_CAP);
        expect(capped.truncated).toBe(true);

        expect(projectTriples([{ source: 'a', target: 'b', kind: null, count: 1 }], true).truncated).toBe(true);
    });

    it('tags nodes with an objectRef when the projection column names an operational object (R8)', () => {
        const g = projectTriples(
            [{ source: 'inc-1', target: 'tower-9', kind: null, count: 1 }],
            false,
            { datasetId: 'd', sourceCol: 'incidentId', targetCol: 'tower' },
        );
        expect(g.nodes.find((n) => n.id === 'entity:inc-1')!.data.objectRef).toEqual({ id: 'inc-1', type: 'INCIDENT' });
        expect(g.nodes.find((n) => n.id === 'entity:tower-9')!.data.objectRef).toBeUndefined();
    });

    it('carries the backend attrs through onto the edge data (Phase B, attrCols passthrough)', () => {
        const g = projectTriples(
            [{ source: 'a', target: 'b', kind: 'sms', count: 1, attrs: { channel: 'sms' } }],
            false,
        );
        expect(g.edges[0].data.attrs).toEqual({ channel: 'sms' });
    });
});

describe('mergeProjectedGraphs', () => {
    it('dedups nodes by id, concatenates edges, and ORs truncation across mappings', () => {
        const a: ProjectedGraph = {
            nodes: [{ id: 'entity:x', data: { label: 'x', kind: 'entity' } }],
            edges: [{ id: 'e1', source: 'entity:x', target: 'entity:y', data: { kind: 'link' } }],
            truncated: true,
        };
        const b: ProjectedGraph = {
            nodes: [{ id: 'entity:x', data: { label: 'stale', kind: 'entity' } }, { id: 'entity:z', data: { label: 'z', kind: 'entity' } }],
            edges: [{ id: 'e2', source: 'entity:x', target: 'entity:z', data: { kind: 'link' } }],
            truncated: false,
        };
        const merged = mergeProjectedGraphs([a, b]);
        expect(merged.nodes.map((n) => n.id)).toEqual(['entity:x', 'entity:z']);
        expect(merged.nodes.find((n) => n.id === 'entity:x')!.data.label).toBe('x'); // first mapping wins
        expect(merged.edges.map((e) => e.id)).toEqual(['e1', 'e2']);
        expect(merged.truncated).toBe(true);
    });
});

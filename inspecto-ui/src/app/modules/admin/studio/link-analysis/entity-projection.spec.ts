import { describe, expect, it } from 'vitest';
import { of } from 'rxjs';
import { SAMPLE_SOURCES } from 'app/modules/admin/studio/datasets/dataset-sources';
import { Dataset } from 'app/modules/admin/studio/datasets/dataset-types';
import {
    EntityProjectionGraphSource,
    PROJECTION_NODE_CAP,
    ProjectedGraph,
    isProjectionError,
    projectEntities,
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
        query: null, physicalRef: null, columns: [], measures: [],
    };

    it('projects the dataset rows (the seeded `links` sample source)', async () => {
        const src = new EntityProjectionGraphSource({ get: () => of(ds) } as never);
        const g = (await src.query({
            projection: { datasetId: 'links-ds', sourceCol: 'source', targetCol: 'target', linkKindCol: 'link_type' },
        })) as ProjectedGraph;
        expect(g.nodes.length).toBeGreaterThan(0);
        expect(g.edges.some((e) => e.data.kind.startsWith('shared_device'))).toBe(true);
    });

    it('requires a mapping and surfaces projection errors as thrown messages', async () => {
        const src = new EntityProjectionGraphSource({ get: () => of(ds) } as never);
        await expect(src.query({})).rejects.toThrow(/mapping/);
        await expect(
            src.query({ projection: { datasetId: 'links-ds', sourceCol: 'bogus', targetCol: 'target' } }),
        ).rejects.toThrow(/bogus/);
    });
});

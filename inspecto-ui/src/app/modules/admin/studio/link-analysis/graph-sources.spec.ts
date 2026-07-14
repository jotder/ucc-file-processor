import { describe, expect, it } from 'vitest';
import { of } from 'rxjs';
import { MetadataEdge, MetadataNode, PipelineGraph, ProvenanceBatch, ProvenanceCount } from 'app/inspecto/api';
import { Component } from 'app/inspecto/component-model';
import { deriveComponentGraph } from 'app/inspecto/component-model';
import { toG6Data } from 'app/modules/admin/catalog/catalog-graph';
import { provenanceCounts, toPipelineG6Data } from 'app/modules/admin/pipelines/pipeline-graph';
import { REGISTRY_KINDS } from 'app/modules/admin/catalog/registry.component';
import { ComponentRegistryGraphSource, LineageGraphSource, PipelineGraphSource } from './graph-sources';

/**
 * The behavior-preserving contract (design doc §6): each GraphSource.query() must return data
 * deep-equal to its plane's existing pure mapper for the same input — proving the seam adds no logic.
 */

const metaNodes: MetadataNode[] = [
    { id: 'src1', label: 'Files', kind: 'STREAM' } as MetadataNode,
    { id: 'tbl1', label: 'cdr', kind: 'TABLE' } as MetadataNode,
];
const metaEdges: MetadataEdge[] = [
    { from: 'src1', to: 'tbl1', kind: 'FEEDS' } as MetadataEdge,
    { from: 'src1', to: 'tbl1', kind: 'EMITS' } as MetadataEdge, // parallel edge — id must stay unique
];

describe('LineageGraphSource', () => {
    it('returns exactly toG6Data() of the API graph and forwards the query', async () => {
        let seen: unknown;
        const catalog = { graph: (q: unknown) => { seen = q; return of({ nodes: metaNodes, edges: metaEdges }); } };
        const src = new LineageGraphSource(catalog as never);
        const out = await src.query({ from: 'src1', depth: 2, direction: 'out', kinds: ['STREAM'], edgeKinds: ['FEEDS'], overlay: true });
        expect(out).toEqual(toG6Data(metaNodes, metaEdges));
        expect(seen).toEqual({ from: 'src1', depth: 2, direction: 'out', kinds: ['STREAM'], edgeKinds: ['FEEDS'], overlay: true });
    });
});

describe('ComponentRegistryGraphSource', () => {
    const comps: Component[] = [
        { kind: 'dashboard', id: 'd1', name: 'Ops', parts: [{ partId: 'w', ref: { kind: 'widget', id: 'w1' } }] } as Component,
        { kind: 'widget', id: 'w1', name: 'Trend' } as Component,
    ];

    it('returns exactly deriveComponentGraph() over all listed kinds', async () => {
        const provider = { list: (kind: string) => Promise.resolve(comps.filter((c) => c.kind === kind)) };
        const src = new ComponentRegistryGraphSource(provider as never);
        // expected components in REGISTRY_KINDS load order, exactly as the source assembles them
        const ordered = REGISTRY_KINDS.flatMap((k) => comps.filter((c) => c.kind === k));
        expect(await src.query({})).toEqual(deriveComponentGraph({ components: ordered }));
    });

    it('a failing kind degrades to the remaining kinds (no throw)', async () => {
        const provider = {
            list: (kind: string) => (kind === 'widget'
                ? Promise.reject(new Error('boom'))
                : Promise.resolve(comps.filter((c) => c.kind === kind))),
        };
        const src = new ComponentRegistryGraphSource(provider as never);
        const expected = deriveComponentGraph({ components: comps.filter((c) => c.kind !== 'widget') });
        expect(await src.query({})).toEqual(expected);
    });
});

describe('PipelineGraphSource', () => {
    const graph: PipelineGraph = {
        nodes: [
            { id: 'in', label: 'Read', category: 'SOURCE' },
            { id: 'out', label: 'Write', category: 'SINK' },
        ],
        edges: [{ from: 'in', to: 'out', rel: 'data', kind: 'data' }],
    } as PipelineGraph;

    it('requires a pipeline root', async () => {
        const src = new PipelineGraphSource({} as never);
        await expect(src.query({})).rejects.toThrow(/pipeline/);
    });

    it('without counts returns exactly toPipelineG6Data()', async () => {
        const pipelines = { graph: () => of(graph) };
        const src = new PipelineGraphSource(pipelines as never);
        expect(await src.query({ from: 'p1' })).toEqual(toPipelineG6Data(graph));
    });

    it('with counts weights edges from the latest batch, identical to the mapper', async () => {
        const batches: ProvenanceBatch[] = [{ batchId: 'b2', runTs: '2026-07-04T00:00:00Z', totalRows: 10 }];
        const rows: ProvenanceCount[] = [{ nodeId: 'in', rel: 'data', rowCount: 10 }];
        const pipelines = {
            graph: () => of(graph),
            provenanceBatches: () => of(batches),
            provenance: () => of(rows),
        };
        const src = new PipelineGraphSource(pipelines as never);
        expect(await src.query({ from: 'p1', counts: true })).toEqual(toPipelineG6Data(graph, provenanceCounts(rows)));
    });

    it('with counts but no recorded batches falls back to the unweighted mapper', async () => {
        const pipelines = { graph: () => of(graph), provenanceBatches: () => of([]) };
        const src = new PipelineGraphSource(pipelines as never);
        expect(await src.query({ from: 'p1', counts: true })).toEqual(toPipelineG6Data(graph));
    });
});

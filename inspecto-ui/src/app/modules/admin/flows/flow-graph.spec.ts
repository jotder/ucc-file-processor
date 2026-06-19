import { describe, expect, it } from 'vitest';
import { FlowCombined, FlowGraph, FlowNode, FlowNodeType } from 'app/inspecto/api';
import {
    categoryVisualKind,
    groupByCategory,
    nodeDisplayLabel,
    provenanceCounts,
    toCombinedG6Data,
    toFlowG6Data,
} from './flow-graph';

const node = (over: Partial<FlowNode>): FlowNode =>
    ({ id: 'n', type: 'transform.map', category: 'TRANSFORM', label: 'Map', ...over });

describe('categoryVisualKind', () => {
    it('maps flow categories onto catalog node kinds for shape/colour reuse', () => {
        expect(categoryVisualKind('SOURCE')).toBe('SOURCE');
        expect(categoryVisualKind('PARSE')).toBe('SCHEMA');
        expect(categoryVisualKind('TRANSFORM')).toBe('ENRICHMENT');
        expect(categoryVisualKind('SINK')).toBe('TABLE');
        expect(categoryVisualKind('CONTROL')).toBe('KPI');
        expect(categoryVisualKind('STORE')).toBe('TABLE');   // the combined-view shared-store node
    });

    it('passes unknown categories through (NodeKind includes string)', () => {
        expect(categoryVisualKind('WHATEVER')).toBe('WHATEVER');
    });
});

describe('nodeDisplayLabel', () => {
    it('prefers the user-given name, falling back to the type label', () => {
        expect(nodeDisplayLabel(node({ name: 'Active subscribers' }))).toBe('Active subscribers');
        expect(nodeDisplayLabel(node({ name: undefined }))).toBe('Map');
        expect(nodeDisplayLabel(node({ name: '  ' }))).toBe('Map');
    });
});

describe('toFlowG6Data', () => {
    const graph: FlowGraph = {
        name: 'F', active: true, produces: ['orders'], consumes: [],
        nodes: [
            node({ id: 'acq', type: 'acquisition', category: 'SOURCE', label: 'Acquisition' }),
            node({ id: 'sink', type: 'sink.persistent', category: 'SINK', label: 'Sink (persistent)', name: 'orders', store: 'orders' }),
        ],
        edges: [
            { from: 'acq', to: 'sink', rel: 'data', kind: 'data' },
            { from: 'acq', to: 'sink', rel: 'route:emea', kind: 'route', routeKey: 'emea' },
        ],
    };

    it('maps nodes with the display label + visual kind', () => {
        const { nodes } = toFlowG6Data(graph);
        expect(nodes[0]).toEqual({ id: 'acq', data: { label: 'Acquisition', kind: 'SOURCE' } });
        expect(nodes[1].data).toEqual({ label: 'orders', kind: 'TABLE' });   // user name + SINK→TABLE
    });

    it('keeps parallel edges unique and carries the relationship as the edge-kind label', () => {
        const { edges } = toFlowG6Data(graph);
        expect(new Set(edges.map((e) => e.id)).size).toBe(2);
        expect(edges[0].data.kind).toBe('data');
        expect(edges[1].data.kind).toBe('route:emea');
    });

    it('overlays provenance counts onto matching edges (label + weight) and leaves others plain', () => {
        const counts = provenanceCounts([{ nodeId: 'acq', rel: 'data', rowCount: 1234 }]);
        const { edges } = toFlowG6Data(graph, counts);
        expect(edges[0].data).toEqual({ kind: 'data · 1,234', weight: 1234 });   // matched
        expect(edges[1].data).toEqual({ kind: 'route:emea' });                   // no count for this rel
    });
});

describe('toCombinedG6Data', () => {
    const combined: FlowCombined = {
        flows: [{ name: 'orders_etl', active: true }, { name: 'orders_rollup', active: true }],
        nodes: [
            { id: 'orders_etl/acq', type: 'acquisition', category: 'SOURCE', label: 'Acquisition', flow: 'orders_etl' },
            { id: 'orders_etl/sink', type: 'sink.persistent', category: 'SINK', label: 'Sink', store: 'orders', flow: 'orders_etl' },
            { id: 'orders_rollup/src', type: 'transform.map', category: 'TRANSFORM', label: 'Read', sourceStore: 'orders', flow: 'orders_rollup' },
            { id: 'store:orders', type: 'store', category: 'STORE', label: 'orders', store: 'orders' },
        ],
        edges: [
            { from: 'orders_etl/acq', to: 'orders_etl/sink', rel: 'data', kind: 'data' },
            { from: 'orders_etl/sink', to: 'store:orders', rel: 'produces', kind: 'store', restsOnDisk: true },
            { from: 'store:orders', to: 'orders_rollup/src', rel: 'consumes', kind: 'store', restsOnDisk: true },
        ],
        links: [{ producer: 'orders_etl', store: 'orders', consumer: 'orders_rollup' }],
    };

    it('maps namespaced flow nodes plus the synthetic store node (as a TABLE)', () => {
        const { nodes } = toCombinedG6Data(combined);
        expect(nodes.find((n) => n.id === 'orders_etl/acq')?.data.kind).toBe('SOURCE');
        const store = nodes.find((n) => n.id === 'store:orders');
        expect(store?.data).toEqual({ label: 'orders', kind: 'TABLE' });
    });

    it('carries the store-join edges with unique ids', () => {
        const { edges } = toCombinedG6Data(combined);
        expect(new Set(edges.map((e) => e.id)).size).toBe(3);
        expect(edges.some((e) => e.source === 'orders_etl/sink' && e.target === 'store:orders')).toBe(true);
        expect(edges.some((e) => e.source === 'store:orders' && e.target === 'orders_rollup/src')).toBe(true);
    });
});

describe('groupByCategory', () => {
    it('orders groups by the canonical category order, unknown categories last', () => {
        const t = (type: string, category: string): FlowNodeType =>
            ({ type, category, label: type, description: '', accepts: [], emits: [], emitsNamedRoutes: false });
        const groups = groupByCategory([
            t('gap', 'CONTROL'), t('acquisition', 'SOURCE'), t('x', 'WEIRD'), t('sink.view', 'SINK'),
        ]);
        expect(groups.map((g) => g.category)).toEqual(['SOURCE', 'SINK', 'CONTROL', 'WEIRD']);
    });
});

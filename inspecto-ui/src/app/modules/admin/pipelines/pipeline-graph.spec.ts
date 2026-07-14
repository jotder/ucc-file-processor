import { describe, expect, it } from 'vitest';
import { AuthoredNode, AuthoredPipeline, PipelineCombined, PipelineGraph, PipelineNode, PipelineNodeType } from 'app/inspecto/api';
import { NODE_KIND_COLORS } from 'app/inspecto/theme/chart-tokens';
import {
    TestOutcome,
    addEdgeToModel,
    addNodeToModel,
    applyNodePatchInModel,
    bindKindFor,
    candidateRelsFor,
    categoryVisualKind,
    computeNodeStatus,
    decodeEdgeId,
    encodeEdgeId,
    groupByCategory,
    nodeConfigEntries,
    nodeDisplayLabel,
    provenanceCounts,
    removeEdgeFromModel,
    removeNodeFromModel,
    resolveNodeIcon,
    setEdgeRelInModel,
    toCombinedG6Data,
    toPipelineG6Data,
    uniqueNodeId,
    validatePipeline,
} from './pipeline-graph';

const node = (over: Partial<PipelineNode>): PipelineNode =>
    ({ id: 'n', type: 'transform.map', category: 'TRANSFORM', label: 'Map', ...over });

describe('categoryVisualKind', () => {
    it('maps flow categories onto catalog node kinds for shape/colour reuse', () => {
        expect(categoryVisualKind('SOURCE')).toBe('STREAM');
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

describe('toPipelineG6Data', () => {
    const graph: PipelineGraph = {
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
        const { nodes } = toPipelineG6Data(graph);
        expect(nodes[0]).toEqual({ id: 'acq', data: { label: 'Acquisition', kind: 'STREAM' } });
        expect(nodes[1].data).toEqual({ label: 'orders', kind: 'TABLE' });   // user name + SINK→TABLE
    });

    it('keeps parallel edges unique and carries the relationship as the edge-kind label', () => {
        const { edges } = toPipelineG6Data(graph);
        expect(new Set(edges.map((e) => e.id)).size).toBe(2);
        expect(edges[0].data.kind).toBe('data');
        expect(edges[1].data.kind).toBe('route:emea');
    });

    it('overlays provenance counts onto matching edges (label + weight) and leaves others plain', () => {
        const counts = provenanceCounts([{ nodeId: 'acq', rel: 'data', rowCount: 1234 }]);
        const { edges } = toPipelineG6Data(graph, counts);
        expect(edges[0].data).toEqual({ kind: 'data · 1,234', weight: 1234 });   // matched
        expect(edges[1].data).toEqual({ kind: 'route:emea' });                   // no count for this rel
    });
});

describe('toCombinedG6Data', () => {
    const combined: PipelineCombined = {
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
        expect(nodes.find((n) => n.id === 'orders_etl/acq')?.data.kind).toBe('STREAM');
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

describe('resolveNodeIcon', () => {
    const map = {
        PARSE: { glyph: 'lines', color: NODE_KIND_COLORS.SCHEMA },
        'parser.dsv': { glyph: 'filter', color: NODE_KIND_COLORS.ENRICHMENT },
    };

    it('prefers an exact type rule over the category rule', () => {
        const r = resolveNodeIcon('parser.dsv', 'PARSE', map);
        expect(r.color).toBe(NODE_KIND_COLORS.ENRICHMENT);
        expect(r.iconSrc.startsWith('data:image/svg+xml')).toBe(true);
    });

    it('falls back to the category rule, then to the built-in kind glyph', () => {
        expect(resolveNodeIcon('parser.other', 'PARSE', map).color).toBe(NODE_KIND_COLORS.SCHEMA);
        // no rule for SINK in this map → built-in fallback still yields an icon
        expect(resolveNodeIcon('sink.file', 'SINK', map).iconSrc.startsWith('data:image/svg+xml')).toBe(true);
    });

    it('embeds iconSrc+color into toPipelineG6Data only when a map is supplied', () => {
        const g = {
            name: 'F', active: true, produces: [], consumes: [],
            nodes: [node({ id: 'p', type: 'parser.dsv', category: 'PARSE' })],
            edges: [],
        };
        expect(toPipelineG6Data(g, undefined, map).nodes[0].data.iconSrc).toBeTruthy();
        expect(toPipelineG6Data(g).nodes[0].data.iconSrc).toBeUndefined();
    });
});

describe('bindKindFor', () => {
    it('maps a node category to the registry kind it binds', () => {
        expect(bindKindFor('PARSE')).toBe('grammar');
        expect(bindKindFor('TRANSFORM')).toBe('transform');
        expect(bindKindFor('SINK')).toBe('sink');
        expect(bindKindFor('SOURCE')).toBeNull();
        expect(bindKindFor('CONTROL')).toBeNull();
    });
});

describe('computeNodeStatus', () => {
    const refs = new Set(['grammar/cdr_csv']);
    const noTests = new Map<string, TestOutcome>();

    it('flags a parser with no grammar as unconfigured', () => {
        expect(computeNodeStatus({ id: 'p', type: 'parser.dsv' }, 'PARSE', refs, noTests)).toBe('unconfigured');
    });

    it('flags a bound-but-missing ref as dangling (only once the registry is loaded)', () => {
        const n = { id: 'p', type: 'parser.dsv', use: 'grammar/ghost' };
        expect(computeNodeStatus(n, 'PARSE', refs, noTests)).toBe('dangling');
        expect(computeNodeStatus(n, 'PARSE', refs, noTests, false)).toBe('configured'); // pre-load: no false flag
    });

    it('is configured when the ref resolves, and a recorded test outcome wins', () => {
        const n = { id: 'p', type: 'parser.dsv', use: 'grammar/cdr_csv' };
        expect(computeNodeStatus(n, 'PARSE', refs, noTests)).toBe('configured');
        expect(computeNodeStatus(n, 'PARSE', refs, new Map([['p', 'tested']]))).toBe('tested');
        expect(computeNodeStatus(n, 'PARSE', refs, new Map([['p', 'rejects']]))).toBe('rejects');
    });

    it('treats a source as unconfigured until a connection is bound', () => {
        expect(computeNodeStatus({ id: 's', type: 'collector.file' }, 'SOURCE', refs, noTests)).toBe('unconfigured');
        expect(computeNodeStatus({ id: 's', type: 'collector.file', use: 'connections/cdr' }, 'SOURCE', refs, noTests)).toBe('configured');
    });
});

describe('validatePipeline', () => {
    const typeCat = new Map([['collector.file', 'SOURCE'], ['parser.dsv', 'PARSE'], ['sink.file', 'SINK']]);
    const refs = new Set(['grammar/cdr_csv']);

    it('reports an error for an unconfigured node and blocks activation', () => {
        const flow: AuthoredPipeline = {
            name: 'f', active: false,
            nodes: [
                { id: 'src', type: 'collector.file', use: 'connections/cdr' },
                { id: 'parse', type: 'parser.dsv' },          // no grammar → error
                { id: 'write', type: 'sink.file', use: 'sink/out' },
            ],
            edges: [{ from: 'src', rel: 'success', to: 'parse' }, { from: 'parse', rel: 'success', to: 'write' }],
        };
        const findings = validatePipeline(flow, typeCat, refs, new Map());
        expect(findings.some((f) => f.severity === 'error' && f.nodeId === 'parse')).toBe(true);
    });

    it('warns when there is no source or no sink', () => {
        const flow: AuthoredPipeline = {
            name: 'f', active: false,
            nodes: [{ id: 'parse', type: 'parser.dsv', use: 'grammar/cdr_csv' }],
            edges: [],
        };
        const findings = validatePipeline(flow, typeCat, refs, new Map());
        expect(findings.some((f) => /no source/i.test(f.message))).toBe(true);
        expect(findings.some((f) => /no writer/i.test(f.message))).toBe(true);
    });
});

describe('groupByCategory', () => {
    it('orders groups by the canonical category order, unknown categories last', () => {
        const t = (type: string, category: string): PipelineNodeType =>
            ({ type, category, label: type, description: '', accepts: [], emits: [], emitsNamedRoutes: false });
        const groups = groupByCategory([
            t('gap', 'CONTROL'), t('acquisition', 'SOURCE'), t('x', 'WEIRD'), t('sink.view', 'SINK'),
        ]);
        expect(groups.map((g) => g.category)).toEqual(['SOURCE', 'SINK', 'CONTROL', 'WEIRD']);
    });
});

describe('nodeConfigEntries', () => {
    it('stringifies non-string config values for display', () => {
        const n: AuthoredNode = { id: 'n', type: 't', config: { a: 'x', b: 42, c: { nested: true } } };
        expect(nodeConfigEntries(n)).toEqual([
            { k: 'a', v: 'x' },
            { k: 'b', v: '42' },
            { k: 'c', v: '{"nested":true}' },
        ]);
    });

    it('is empty for a node with no config', () => {
        expect(nodeConfigEntries({ id: 'n', type: 't' })).toEqual([]);
    });
});

describe('canvas edge-id codec', () => {
    it('round-trips (from, to, rel) through encode/decode', () => {
        const id = encodeEdgeId('src', 'dst', 'data');
        expect(decodeEdgeId(id)).toEqual({ from: 'src', to: 'dst', rel: 'data' });
    });

    it('two encodes of the same triple stay distinct ids (nonce)', () => {
        expect(encodeEdgeId('a', 'b', 'data') === encodeEdgeId('a', 'b', 'data')).toBe(false);
    });

    it('decode returns null for a malformed id', () => {
        expect(decodeEdgeId('not-an-edge-id')).toBeNull();
    });
});

describe('uniqueNodeId', () => {
    it('sanitizes the type into a base id and starts at _1', () => {
        expect(uniqueNodeId(null, 'transform.filter')).toBe('transform_filter_1');
    });

    it('skips ids already present on the model', () => {
        const model: AuthoredPipeline = {
            name: 'f', active: false,
            nodes: [{ id: 'parser_dsv_1', type: 'parser.dsv' }, { id: 'parser_dsv_2', type: 'parser.dsv' }],
            edges: [],
        };
        expect(uniqueNodeId(model, 'parser.dsv')).toBe('parser_dsv_3');
    });
});

describe('authored-model reducers', () => {
    const base: AuthoredPipeline = {
        name: 'f', active: false,
        nodes: [{ id: 'a', type: 'collector.file' }, { id: 'b', type: 'transform.filter' }],
        edges: [{ from: 'a', rel: 'data', to: 'b' }],
    };

    it('addNodeToModel appends without mutating the input', () => {
        const next = addNodeToModel(base, { id: 'c', type: 'sink.file' });
        expect(next.nodes).toHaveLength(3);
        expect(base.nodes).toHaveLength(2); // original untouched
    });

    it('addEdgeToModel appends a new edge, and returns null for a duplicate', () => {
        const next = addEdgeToModel(base, 'b', 'c', 'data');
        expect(next?.edges).toHaveLength(2);
        expect(addEdgeToModel(base, 'a', 'b', 'data')).toBeNull();
    });

    it('removeNodeFromModel drops the node and every edge touching it', () => {
        const next = removeNodeFromModel(base, 'a');
        expect(next.nodes.map((n) => n.id)).toEqual(['b']);
        expect(next.edges).toHaveLength(0);
    });

    it('removeEdgeFromModel drops only the matching edge', () => {
        const withTwo = { ...base, edges: [...base.edges, { from: 'b', rel: 'kept', to: 'a' }] };
        const next = removeEdgeFromModel(withTwo, 'a', 'b', 'data');
        expect(next.edges).toEqual([{ from: 'b', rel: 'kept', to: 'a' }]);
    });

    it('setEdgeRelInModel relabels, and returns null when unchanged or colliding', () => {
        const next = setEdgeRelInModel(base, 'a', 'b', 'data', 'kept');
        expect(next?.edges[0].rel).toBe('kept');
        expect(setEdgeRelInModel(base, 'a', 'b', 'data', 'data')).toBeNull(); // unchanged
        const withTwo = { ...base, edges: [...base.edges, { from: 'a', rel: 'kept', to: 'b' }] };
        expect(setEdgeRelInModel(withTwo, 'a', 'b', 'data', 'kept')).toBeNull(); // would collide
    });

    it('applyNodePatchInModel replaces a node by id', () => {
        const patched: AuthoredNode = { id: 'a', type: 'collector.file', name: 'Renamed' };
        const next = applyNodePatchInModel(base, patched);
        expect(next.nodes.find((n) => n.id === 'a')?.name).toBe('Renamed');
    });
});

describe('candidateRelsFor', () => {
    it('offers the source node\'s emitted rels plus data and the edge\'s current rel', () => {
        const model: AuthoredPipeline = {
            name: 'f', active: false,
            nodes: [{ id: 'a', type: 'transform.filter' }, { id: 'b', type: 'sink.file' }],
            edges: [],
        };
        const emits = new Map([['transform.filter', ['kept', 'dropped']]]);
        const id = encodeEdgeId('a', 'b', 'kept');
        expect(candidateRelsFor(model, id, emits)).toEqual(['data', 'kept', 'dropped']);
    });

    it('returns an empty list for a malformed edge id', () => {
        expect(candidateRelsFor(null, 'bad', new Map())).toEqual([]);
    });
});

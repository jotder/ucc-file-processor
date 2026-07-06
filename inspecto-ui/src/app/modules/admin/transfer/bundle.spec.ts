import { describe, expect, it } from 'vitest';
import {
    BUNDLE_FORMAT,
    BUNDLE_VERSION,
    BundleItem,
    BundleKind,
    buildBundle,
    parseBundle,
    planImport,
    refsOf,
    withDependencies,
} from './bundle';

const item = (kind: BundleKind, id: string, content: Record<string, unknown> = {}): BundleItem => ({ kind, id, content });

const DATASET = item('dataset', 'cell_sites', { name: 'cell_sites', columns: [] });
const GEO_VIEW = item('geo-map-view', 'dhaka-network', {
    name: 'Example — Dhaka cell network',
    query: { projection: { datasetId: 'cell_sites', latCol: 'lat', lonCol: 'lon' } },
    display: 'heatmap',
    camera: { center: [88.89, 23.04], zoom: 11 },
});
const MAP_WIDGET = item('widget', 'dhaka_network_map', { vizType: 'geo-map', datasetId: '', controls: {}, viewId: 'dhaka-network' });
const BAR_WIDGET = item('widget', 'cost_by_tariff', { vizType: 'bar', datasetId: 'cdr_sample', controls: {} });
const CDR_DATASET = item('dataset', 'cdr_sample', { name: 'cdr_sample' });
const DASHBOARD = item('dashboard', 'overview', { tiles: [{ widgetId: 'dhaka_network_map', span: 2 }, { widgetId: 'cost_by_tariff', span: 1 }] });
const GRAMMAR = item('grammar', 'cdr_asn1_ber', { parser_type: 'asn1' });
const CONNECTION = item('connection', 'cdr_sftp_prod', { id: 'cdr_sftp_prod', connector: 'sftp' });
const PIPELINE = item('authored-pipeline', 'mediation_backbone', {
    name: 'mediation_backbone',
    nodes: [
        { id: 'c', type: 'collector.file', use: 'connections/cdr_sftp_prod' },
        { id: 'p', type: 'parser.asn1', use: 'grammar/cdr_asn1_ber' },
        { id: 's', type: 'sink.file' },
    ],
    edges: [],
});

const ALL = [DATASET, GEO_VIEW, MAP_WIDGET, BAR_WIDGET, CDR_DATASET, DASHBOARD, GRAMMAR, CONNECTION, PIPELINE];

describe('refsOf', () => {
    it('resolves a view-bound widget to its saved view (kind from vizType)', () => {
        expect(refsOf(MAP_WIDGET)).toEqual([{ kind: 'geo-map-view', id: 'dhaka-network' }]);
    });

    it('resolves a dataset-bound widget, a view, a dashboard and a pipeline', () => {
        expect(refsOf(BAR_WIDGET)).toEqual([{ kind: 'dataset', id: 'cdr_sample' }]);
        expect(refsOf(GEO_VIEW)).toEqual([{ kind: 'dataset', id: 'cell_sites' }]);
        expect(refsOf(DASHBOARD).map((r) => r.id)).toEqual(['dhaka_network_map', 'cost_by_tariff']);
        expect(refsOf(PIPELINE)).toEqual([
            { kind: 'connection', id: 'cdr_sftp_prod' },
            { kind: 'grammar', id: 'cdr_asn1_ber' },
        ]);
    });
});

describe('job transport (R2)', () => {
    const JOB = item('job', 'enrich_roaming', { name: 'enrich_roaming', type: 'enrich', cron: null, onPipeline: 'mediation_backbone' });

    it("a job's pipeline trigger maps onto the bundle's authored-pipeline kind", () => {
        expect(refsOf(JOB)).toEqual([{ kind: 'authored-pipeline', id: 'mediation_backbone' }]);
    });

    it('jobs sort after pipelines (their referenced kind) in a bundle', () => {
        const bundle = buildBundle([JOB, PIPELINE], null);
        expect(bundle.items.map((i) => i.kind)).toEqual(['authored-pipeline', 'job']);
    });

    it('with dependencies, exporting a job pulls the pipeline it triggers on', () => {
        const pipeline = item('authored-pipeline', 'mediation_backbone', { name: 'mediation_backbone', nodes: [], edges: [] });
        const { items, missing } = withDependencies([JOB], [JOB, pipeline]);
        expect(missing).toEqual([]);
        expect(items.map((i) => i.id).sort()).toEqual(['enrich_roaming', 'mediation_backbone']);
    });
});

describe('query transport (R3)', () => {
    const QUERY = item('query', 'recent_high_cost', { name: 'recent_high_cost', type: 'sql', datasetId: 'cdr_sample', text: 'SELECT 1', parameters: [] });
    const QW = item('widget', 'recent_cost_by_tariff', { vizType: 'bar', datasetId: 'cdr_sample', queryId: 'recent_high_cost', controls: {} });

    it('a query binds its dataset; a query-bound widget binds both query and dataset', () => {
        expect(refsOf(QUERY)).toEqual([{ kind: 'dataset', id: 'cdr_sample' }]);
        expect(refsOf(QW)).toEqual([{ kind: 'query', id: 'recent_high_cost' }, { kind: 'dataset', id: 'cdr_sample' }]);
    });

    it('queries sort after datasets and before widgets in a bundle', () => {
        expect(buildBundle([QW, QUERY, CDR_DATASET], null).items.map((i) => i.kind)).toEqual(['dataset', 'query', 'widget']);
    });

    it('with dependencies, exporting a query-bound widget pulls the query and its dataset', () => {
        const { items, missing } = withDependencies([QW], [QW, QUERY, CDR_DATASET]);
        expect(missing).toEqual([]);
        expect(items.map((i) => `${i.kind}/${i.id}`).sort()).toEqual([
            'dataset/cdr_sample', 'query/recent_high_cost', 'widget/recent_cost_by_tariff',
        ]);
    });
});

describe('withDependencies', () => {
    it('expands a dashboard to its full transitive closure (widgets → view → dataset)', () => {
        const { items, missing } = withDependencies([DASHBOARD], ALL);
        expect(missing).toEqual([]);
        expect(items.map((i) => `${i.kind}/${i.id}`).sort()).toEqual([
            'dashboard/overview',
            'dataset/cdr_sample',
            'dataset/cell_sites',
            'geo-map-view/dhaka-network',
            'widget/cost_by_tariff',
            'widget/dhaka_network_map',
        ]);
    });

    it('reports unresolvable references without failing the export', () => {
        const { items, missing } = withDependencies([MAP_WIDGET], [MAP_WIDGET]);
        expect(items).toEqual([MAP_WIDGET]);
        expect(missing).toEqual(['geo-map-view/dhaka-network']);
    });
});

describe('buildBundle / parseBundle round-trip', () => {
    it('round-trips losslessly, sorted referenced-kinds-first', () => {
        const bundle = buildBundle([DASHBOARD, MAP_WIDGET, GEO_VIEW, DATASET, PIPELINE, GRAMMAR, CONNECTION], 'default');
        expect(bundle.format).toBe(BUNDLE_FORMAT);
        expect(bundle.version).toBe(BUNDLE_VERSION);
        expect(bundle.items.map((i) => i.kind)).toEqual([
            'connection', 'grammar', 'dataset', 'geo-map-view', 'widget', 'dashboard', 'authored-pipeline',
        ]);
        const { bundle: parsed, errors } = parseBundle(JSON.stringify(bundle));
        expect(errors).toEqual([]);
        expect(parsed).toEqual(bundle);
        // Visual aspects survive verbatim — the "renders as-is" contract.
        const view = parsed!.items.find((i) => i.kind === 'geo-map-view')!;
        expect(view.content['display']).toBe('heatmap');
        expect(view.content['camera']).toEqual({ center: [88.89, 23.04], zoom: 11 });
    });

    it('rejects non-JSON, wrong format, future versions and malformed items', () => {
        expect(parseBundle('not json').errors).toEqual(['Not valid JSON.']);
        expect(parseBundle('{"format":"other","version":1,"items":[]}').errors[0]).toContain('format');
        expect(parseBundle(`{"format":"${BUNDLE_FORMAT}","version":99,"items":[]}`).errors[0]).toContain('version');
        const bad = `{"format":"${BUNDLE_FORMAT}","version":1,"items":[{"kind":"nope","id":"x","content":{}}]}`;
        expect(parseBundle(bad).errors[0]).toContain('unknown kind');
    });
});

describe('planImport', () => {
    it('defaults new items to import and existing ones to skip (user opts into overwrite)', () => {
        const bundle = buildBundle([DATASET, CDR_DATASET, MAP_WIDGET], null);
        const rows = planImport(bundle, new Map([['dataset', new Set(['cell_sites'])]]));
        expect(rows.map((r) => [r.item.id, r.exists, r.action])).toEqual([
            ['cdr_sample', false, 'import'],
            ['cell_sites', true, 'skip'],
            ['dhaka_network_map', false, 'import'],
        ]);
    });
});

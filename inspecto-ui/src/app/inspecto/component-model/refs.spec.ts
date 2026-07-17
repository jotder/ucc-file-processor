import { describe, expect, it } from 'vitest';
import {
    dashboardRefs,
    investigationViewRefs,
    jobRefs,
    parseUseRef,
    pipelineRefs,
    queryRefs,
    refsForComponent,
    requirementRefs,
    widgetRefs,
} from './refs';

/**
 * R1 invariants: the ONE ref derivation of the metadata network. These pins are the contract for
 * every consumer — reuse-graph, delete protection, bundle closure, import fit-check.
 */
describe('parseUseRef', () => {
    it('splits use bindings and maps the connections prefix to the connection kind', () => {
        expect(parseUseRef('grammar/cdr_csv')).toEqual({ kind: 'grammar', id: 'cdr_csv' });
        expect(parseUseRef('connections/cdr_sftp_prod')).toEqual({ kind: 'connection', id: 'cdr_sftp_prod' });
        expect(parseUseRef('grammar/nested/id')).toEqual({ kind: 'grammar', id: 'nested/id' });
        for (const bad of [undefined, '', '  ', 'nokind', '/noid', 'kind/']) expect(parseUseRef(bad)).toBeNull();
    });
});

describe('structural derivations', () => {
    it('widget: binds its dataset, or renders its saved view (kind from vizType)', () => {
        expect(widgetRefs({ datasetId: 'cdr_sample', vizType: 'bar' })).toEqual([
            { kind: 'dataset', id: 'cdr_sample', rel: 'binds', via: 'dataset' },
        ]);
        expect(widgetRefs({ datasetId: '', vizType: 'geo-map', viewId: 'dhaka-network' })).toEqual([
            { kind: 'geo-map-view', id: 'dhaka-network', rel: 'renders', via: 'view' },
        ]);
        expect(widgetRefs({ datasetId: '', vizType: 'link-analysis', viewId: 'graph-complex' })).toEqual([
            { kind: 'link-analysis-view', id: 'graph-complex', rel: 'renders', via: 'view' },
        ]);
    });

    it('widget: when query-bound, binds the query as well as the dataset (R3 lineage chain)', () => {
        expect(widgetRefs({ datasetId: 'cdr_sample', queryId: 'recent_high_cost', vizType: 'bar' })).toEqual([
            { kind: 'query', id: 'recent_high_cost', rel: 'binds', via: 'query' },
            { kind: 'dataset', id: 'cdr_sample', rel: 'binds', via: 'dataset' },
        ]);
    });

    it('query: binds its source dataset, or nothing when unset (R3)', () => {
        expect(queryRefs({ type: 'sql', datasetId: 'cdr_sample' })).toEqual([
            { kind: 'dataset', id: 'cdr_sample', rel: 'binds', via: 'dataset' },
        ]);
        expect(queryRefs({ type: 'sql', datasetId: null })).toEqual([]);
    });

    it('dashboard: tiles its widgets with stable tile anchors', () => {
        expect(dashboardRefs({ tiles: [{ widgetId: 'a', span: 1 }, { widgetId: '', span: 1 }, { widgetId: 'b', span: 2 }] })).toEqual([
            { kind: 'widget', id: 'a', rel: 'tiles', via: 'tile0' },
            { kind: 'widget', id: 'b', rel: 'tiles', via: 'tile2' },
        ]);
    });

    it('investigation view: projects every dataset inside its query config (projection AND routes shapes)', () => {
        expect(investigationViewRefs({ query: { projection: { datasetId: 'cell_sites', latCol: 'lat' } } })).toEqual([
            { kind: 'dataset', id: 'cell_sites', rel: 'projects', via: 'projection' },
        ]);
        expect(investigationViewRefs({ query: { routes: { datasetId: 'money_moves' } } })).toEqual([
            { kind: 'dataset', id: 'money_moves', rel: 'projects', via: 'routes' },
        ]);
    });

    it('job: triggers on its upstream pipeline; cron/manual jobs reference nothing (R2)', () => {
        expect(jobRefs({ name: 'enrich_roaming', type: 'enrich', cron: null, onPipeline: 'cdr_ingest' })).toEqual([
            { kind: 'pipeline', id: 'cdr_ingest', rel: 'triggers', via: 'onPipeline' },
        ]);
        expect(jobRefs({ name: 'daily', type: 'report', cron: '0 30 6 * * *', onPipeline: null })).toEqual([]);
    });

    it('pipeline: binds every node use ref, anchored on the node id', () => {
        const flow = {
            nodes: [
                { id: 'c', type: 'collector.file', use: 'connections/cdr_sftp_prod' },
                { id: 'p', type: 'parser.asn1', use: 'grammar/cdr_asn1_ber' },
                { id: 's', type: 'sink.file' },
            ],
        };
        expect(pipelineRefs(flow)).toEqual([
            { kind: 'connection', id: 'cdr_sftp_prod', rel: 'binds', via: 'c' },
            { kind: 'grammar', id: 'cdr_asn1_ber', rel: 'binds', via: 'p' },
        ]);
    });

    it('requirement: delivered-by edges only when the note is a pure <kind>/<id> ref list', () => {
        expect(requirementRefs({ deliveredNote: 'dashboard/churn_kpi' })).toEqual([
            { kind: 'dashboard', id: 'churn_kpi', rel: 'delivered-by', via: 'deliveredNote0' },
        ]);
        // A ref LIST (the seeds' shape) — one edge per token, `+`/`,`/space separated.
        expect(requirementRefs({ deliveredNote: 'reconciliation/gl_vs_payments + dashboard/audit_overview' })).toEqual([
            { kind: 'reconciliation', id: 'gl_vs_payments', rel: 'delivered-by', via: 'deliveredNote0' },
            { kind: 'dashboard', id: 'audit_overview', rel: 'delivered-by', via: 'deliveredNote1' },
        ]);
        // Prose never becomes an edge — even prose containing a slash token.
        for (const prose of [undefined, '', 'shipped in Q3', 'reused pipelines/orders rework', 'a/b then c', 'kind/', '/id', 'kind/a/b']) {
            expect(requirementRefs({ deliveredNote: prose })).toEqual([]);
        }
    });
});

describe('refsForComponent', () => {
    it('serves the structural derivation for unregistered kinds, and aliases authored-pipeline → pipeline', () => {
        const flow = { nodes: [{ id: 'n', use: 'transform/drop_test_rows' }] };
        expect(refsForComponent('authored-pipeline', flow)).toEqual(refsForComponent('pipeline', flow));
        expect(refsForComponent('pipeline', flow)).toEqual([
            { kind: 'transform', id: 'drop_test_rows', rel: 'binds', via: 'n' },
        ]);
    });

    it('atomic kinds reference nothing', () => {
        expect(refsForComponent('grammar', { parser_type: 'dsv' })).toEqual([]);
        expect(refsForComponent('unknown-kind', {})).toEqual([]);
    });
});

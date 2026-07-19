import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { describe, expect, it } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { AuthoredPipeline, PipelineSummary, PipelinesService } from 'app/inspecto/api';
import { RequirementsService } from 'app/inspecto/requirement';
import { Component as ModelComponent } from 'app/inspecto/component-model';
import { ComponentsDataProvider } from './components-data-provider';
import { RegistryComponent } from './registry.component';

function configure(byKind: Record<string, ModelComponent[]>, flows: { summaries?: PipelineSummary[]; raw?: Record<string, AuthoredPipeline> } = {}) {
    const summaries = flows.summaries ?? [];
    const raw = flows.raw ?? {};
    TestBed.configureTestingModule({
        imports: [RegistryComponent],
        providers: [
            provideNoopAnimations(),
            provideRouter([]),
            { provide: ComponentsDataProvider, useValue: { list: (k: string) => Promise.resolve(byKind[k] ?? []) } },
            { provide: PipelinesService, useValue: { authoredList: () => of(summaries), authoredRaw: (id: string) => of(raw[id]) } },
            { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } },
        ],
    });
}

describe('RegistryComponent', () => {
    it('derives reference edges from composites (dashboard → widget → dataset)', async () => {
        configure({
            dataset: [{ kind: 'dataset', id: 'cdr', name: 'cdr', config: {} }],
            widget: [{ kind: 'widget', id: 'ch1', name: 'ch1', config: { datasetId: 'cdr' } }],
            dashboard: [{ kind: 'dashboard', id: 'db1', name: 'db1', config: { tiles: [{ widgetId: 'ch1', span: 1 }] } }],
        });
        // Drive state directly (no detectChanges) — the G6 host can't instantiate in jsdom.
        const c = TestBed.createComponent(RegistryComponent).componentInstance;
        await c.load();
        const edges = c.graph().edges.map((e) => `${e.source}->${e.target}`);
        expect(edges).toContain('widget/ch1->dataset/cdr');
        expect(edges).toContain('dashboard/db1->widget/ch1');
        expect(c.refRows()).toHaveLength(2);
    });

    it('loads pipelines and derives node→component reference edges from use=<kind>/<id>', async () => {
        const flow: AuthoredPipeline = {
            name: 'cdr_pipeline',
            active: true,
            nodes: [
                { id: 'src', type: 'collector' },
                { id: 'parse', type: 'dsv', use: 'grammar/cdr_csv' },
                { id: 'write', type: 'parquet', use: 'sink/cdr_parquet' },
            ],
            edges: [{ from: 'src', to: 'parse', rel: 'data' }, { from: 'parse', to: 'write', rel: 'data' }],
        };
        configure(
            {
                grammar: [{ kind: 'grammar', id: 'cdr_csv', name: 'cdr_csv', config: {} }],
                sink: [{ kind: 'sink', id: 'cdr_parquet', name: 'cdr_parquet', config: {} }],
            },
            { summaries: [{ name: 'cdr_pipeline', active: true, nodeCount: 3, edgeCount: 2, produces: [], consumes: [] }], raw: { cdr_pipeline: flow } },
        );
        const c = TestBed.createComponent(RegistryComponent).componentInstance;
        await c.load();
        const edges = c.graph().edges.map((e) => `${e.source}->${e.target}`);
        expect(edges).toContain('pipeline/cdr_pipeline->grammar/cdr_csv');
        expect(edges).toContain('pipeline/cdr_pipeline->sink/cdr_parquet');
    });

    it('flags a dangling reference as a ghost node', async () => {
        configure({ widget: [{ kind: 'widget', id: 'ch1', name: 'ch1', config: { datasetId: 'missing' } }] });
        const c = TestBed.createComponent(RegistryComponent).componentInstance;
        await c.load();
        expect(c.graph().nodes.find((n) => n.id === 'dataset/missing')?.data.missing).toBe(true);
    });

    it('loads requirements and derives the delivered-by edge from a <kind>/<id> delivered-note', async () => {
        configure({ dashboard: [{ kind: 'dashboard', id: 'churn_kpi', name: 'churn_kpi', config: {} }] });
        TestBed.overrideProvider(RequirementsService, {
            useValue: {
                list: () =>
                    of([
                        { id: 'churn_req', title: 'Churn KPI', kind: 'kpi', description: '', status: 'delivered', submittedAt: '', deliveredNote: 'dashboard/churn_kpi' },
                        { id: 'prose_req', title: 'Prose', kind: 'report', description: '', status: 'delivered', submittedAt: '', deliveredNote: 'shipped in Q3' },
                    ]),
            },
        });
        const c = TestBed.createComponent(RegistryComponent).componentInstance;
        await c.load();
        const edges = c.graph().edges.map((e) => `${e.source}->${e.target}`);
        expect(edges).toContain('requirement/churn_req->dashboard/churn_kpi');
        // Prose stays a note — the prose requirement joins as a node but contributes no edge.
        expect(c.graph().nodes.some((n) => n.id === 'requirement/prose_req')).toBe(true);
        expect(edges.filter((e) => e.startsWith('requirement/prose_req'))).toEqual([]);
    });

    it('resolves editor links via the kind registry (S7): routed, pane-based, and none', () => {
        configure({});
        const c = TestBed.createComponent(RegistryComponent).componentInstance;
        c.ngOnInit(); // registers the platform kinds (+ editor routes); the *.kind side-effect imports cover the rest
        expect(c.editorLink({ kind: 'widget', id: 'w1', name: 'w1', config: {} })).toEqual(['/studio/widgets', 'w1']);
        expect(c.editorLink({ kind: 'dataset', id: 'd1', name: 'd1', config: {} })).toEqual(['/catalog/datasets', 'd1']);
        // Dialog-based panes keep their id-less pane link.
        expect(c.editorLink({ kind: 'pipeline', id: 'p1', name: 'p1', config: {} })).toEqual(['/pipelines']);
        expect(c.editorLink({ kind: 'job', id: 'j1', name: 'j1', config: {} })).toEqual(['/jobs']);
        expect(c.editorLink({ kind: 'requirement', id: 'r1', name: 'r1', config: {} })).toEqual(['/requirements']);
        // Atomic kinds without an editor fail closed.
        expect(c.editorLink({ kind: 'grammar', id: 'g1', name: 'g1', config: {} })).toBeNull();
        expect(c.editorLink({ kind: 'no-such-kind', id: 'x', name: 'x', config: {} })).toBeNull();
    });

    it('renders the empty (no-graph) state with no a11y violations', async () => {
        configure({});
        const fixture = TestBed.createComponent(RegistryComponent);
        fixture.detectChanges();
        await fixture.whenStable();
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});

import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { describe, expect, it } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { AuthoredPipeline, PipelineSummary, PipelinesService } from 'app/inspecto/api';
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
    it('derives reference edges from composites (dashboard → chart → dataset)', async () => {
        configure({
            dataset: [{ kind: 'dataset', id: 'cdr', name: 'cdr', config: {} }],
            chart: [{ kind: 'chart', id: 'ch1', name: 'ch1', config: { datasetId: 'cdr' } }],
            dashboard: [{ kind: 'dashboard', id: 'db1', name: 'db1', config: { tiles: [{ chartId: 'ch1', span: 1 }] } }],
        });
        // Drive state directly (no detectChanges) — the G6 host can't instantiate in jsdom.
        const c = TestBed.createComponent(RegistryComponent).componentInstance;
        await c.load();
        const edges = c.graph().edges.map((e) => `${e.source}->${e.target}`);
        expect(edges).toContain('chart/ch1->dataset/cdr');
        expect(edges).toContain('dashboard/db1->chart/ch1');
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
        configure({ chart: [{ kind: 'chart', id: 'ch1', name: 'ch1', config: { datasetId: 'missing' } }] });
        const c = TestBed.createComponent(RegistryComponent).componentInstance;
        await c.load();
        expect(c.graph().nodes.find((n) => n.id === 'dataset/missing')?.data.missing).toBe(true);
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

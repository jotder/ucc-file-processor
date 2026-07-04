import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { CatalogService, PipelinesService } from 'app/inspecto/api';
import { G6GraphData, GraphSource, GraphSourceQuery } from 'app/inspecto/graph';
import { deriveComponentGraph } from 'app/inspecto/component-model';
import { toG6Data } from 'app/modules/admin/catalog/catalog-graph';
import { ComponentsDataProvider } from 'app/modules/admin/catalog/components-data-provider';
import { REGISTRY_KINDS } from 'app/modules/admin/catalog/registry.component';
import { provenanceCounts, toPipelineG6Data } from 'app/modules/admin/pipelines/pipeline-graph';
import { DatasetsService } from 'app/modules/admin/studio/datasets/datasets.service';
import { EntityProjectionGraphSource } from './entity-projection';

/**
 * The concrete {@link GraphSource}s behind the Link Analysis Studio (GLOSSARY §11; design:
 * docs/superpower/link-analysis-and-graphsource.md §4/§6). Each wraps its plane's **existing** pure
 * mapper verbatim — no mapper logic moves — so `query()` output is contract-tested deep-equal to the
 * mapper's (see graph-sources.spec.ts). The `entity-projection` source (P3) is added separately.
 */

/** P2 — the lineage plane: `GET /catalog/graph` → {@link toG6Data}. */
export class LineageGraphSource implements GraphSource {
    readonly id = 'lineage' as const;
    readonly label = 'Lineage (data assets)';
    constructor(private catalog: CatalogService) {}

    async query(q: GraphSourceQuery): Promise<G6GraphData> {
        const g = await firstValueFrom(this.catalog.graph({
            from: q.from, depth: q.depth, direction: q.direction,
            kinds: q.kinds, edgeKinds: q.edgeKinds, overlay: q.overlay,
        }));
        return toG6Data(g.nodes, g.edges);
    }
}

/** P1 — the artifact plane: `/components` per registry kind → {@link deriveComponentGraph}. */
export class ComponentRegistryGraphSource implements GraphSource {
    readonly id = 'component-registry' as const;
    readonly label = 'Components (reuse graph)';
    constructor(private provider: ComponentsDataProvider) {}

    async query(_q: GraphSourceQuery): Promise<G6GraphData> {
        const settled = await Promise.allSettled(REGISTRY_KINDS.map((k) => this.provider.list(k)));
        const components = settled.flatMap((r) => (r.status === 'fulfilled' ? r.value : []));
        return deriveComponentGraph({ components });
    }
}

/** P2′ — the provenance plane: a pipeline's graph, optionally weighted by its latest run's counts. */
export class PipelineGraphSource implements GraphSource {
    readonly id = 'provenance' as const;
    readonly label = 'Pipeline (provenance)';
    constructor(private pipelines: PipelinesService) {}

    async query(q: GraphSourceQuery): Promise<G6GraphData> {
        if (!q.from) throw new Error('The provenance source needs a pipeline (query.from).');
        const g = await firstValueFrom(this.pipelines.graph(q.from));
        if (!q.counts) return toPipelineG6Data(g);
        const batches = await firstValueFrom(this.pipelines.provenanceBatches(q.from)).catch(() => []);
        if (!batches.length) return toPipelineG6Data(g);
        const rows = await firstValueFrom(this.pipelines.provenance(q.from, batches[0].batchId)).catch(() => []);
        return toPipelineG6Data(g, provenanceCounts(rows));
    }
}

/** Root factory holding one instance per source, in stable UI order. */
@Injectable({ providedIn: 'root' })
export class GraphSourcesService {
    private catalog = inject(CatalogService);
    private pipelines = inject(PipelinesService);
    private components = inject(ComponentsDataProvider);
    private datasets = inject(DatasetsService);

    readonly sources: GraphSource[] = [
        new EntityProjectionGraphSource(this.datasets),
        new LineageGraphSource(this.catalog),
        new ComponentRegistryGraphSource(this.components),
        new PipelineGraphSource(this.pipelines),
    ];

    byId(id: string): GraphSource | undefined {
        return this.sources.find((s) => s.id === id);
    }
}

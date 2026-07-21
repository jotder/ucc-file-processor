import { ChangeDetectionStrategy, Component, OnInit, inject, input, output, signal } from '@angular/core';
import { FormArray, FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { PipelineSummary } from 'app/inspecto/api';
import { EntityProjection, GraphSource, GraphSourceId, GraphSourceQuery } from 'app/inspecto/graph';
import { Dataset } from 'app/modules/admin/studio/datasets/dataset-types';
import { SAMPLE_SOURCES } from 'app/modules/admin/studio/datasets/dataset-sources';
import { LinkAnalysisView } from './link-analysis.service';

/** One line of the collapsed-query summary (also used by the host's canvas status bar). */
export interface QuerySummaryItem {
    icon: string;
    label: string;
    value: string;
}

/**
 * **Link Analysis — query panel** (the bottom panel's Query tab, extracted from the studio god
 * component per plan S2/B4). Owns the graph-source query form (entity-projection mappings, lineage
 * and provenance seeds) and turns it into a {@link GraphSourceQuery} via {@link buildQuery}. The host
 * keeps `sourceId` and the run/save/load lifecycle: this panel emits `run`/`edit`/`sourceIdChange` and
 * exposes `buildQuery()` + `patchFormFromView()` for the host to call (via a ViewChild).
 */
@Component({
    selector: 'inspecto-link-analysis-query-panel',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        ReactiveFormsModule, MatButtonModule, MatCheckboxModule, MatFormFieldModule, MatIconModule,
        MatInputModule, MatSelectModule,
    ],
    templateUrl: './link-analysis-query-panel.component.html',
})
export class LinkAnalysisQueryPanelComponent implements OnInit {
    private fb = inject(FormBuilder);

    readonly sources = input<GraphSource[]>([]);
    readonly datasets = input<Dataset[]>([]);
    readonly pipelines = input<PipelineSummary[]>([]);
    readonly sourceId = input<GraphSourceId>('entity-projection');
    /** Full form vs the collapsed selected-values summary (owned by the host). */
    readonly queryOpen = input(true);
    readonly loading = input(false);
    readonly querySummary = input<QuerySummaryItem[]>([]);
    readonly sourceLabel = input('');

    readonly run = output<void>();
    readonly edit = output<void>();
    readonly sourceIdChange = output<GraphSourceId>();

    readonly queryForm = this.fb.nonNullable.group({
        from: [''],
        depth: [2],
        direction: ['both' as 'out' | 'in' | 'both'],
        pipeline: [''],
        counts: [false],
        datasetId: [''],
        sourceCol: [''],
        targetCol: [''],
        linkKindCol: [''],
        attrCols: [[] as string[]],
        /** Only meaningful once a second mapping exists (Phase C) — see {@link EntityProjection.entityType}. */
        entityType: [''],
        /** Multi-root seeds (Phase D, lineage only): extra roots beyond `from`, comma-separated. */
        extraRoots: [''],
        /** Multi-root seeds (Phase D, provenance only): extra pipelines beyond `pipeline`. */
        extraPipelines: [[] as string[]],
    });

    /** Columns offered by the projection mapping selects — the picked Dataset's columns (or its sample rows'). */
    readonly datasetColumns = signal<string[]>([]);

    /**
     * Extra entity-projection mappings beyond the primary one above (Phase C, multi-entity/multi-dataset
     * mapping): each row is its own Dataset + column mapping, merged client-side into one graph — no new
     * backend endpoint, {@code /inv/projection} runs once per row.
     */
    readonly extraMappings = this.fb.array<FormGroup>([]);
    /** Column choices per extra-mapping row, indexed like `extraMappings.controls`. */
    readonly extraMappingColumns = signal<string[][]>([]);

    ngOnInit(): void {
        this.queryForm.controls.datasetId.valueChanges.subscribe((id) => this.onDatasetPicked(id));
    }

    private newMappingGroup() {
        return this.fb.nonNullable.group({
            datasetId: [''], sourceCol: [''], targetCol: [''], linkKindCol: [''],
            attrCols: [[] as string[]], entityType: ['', Validators.required],
        });
    }

    addMapping(): void {
        const group = this.newMappingGroup();
        const i = this.extraMappings.length;
        group.controls.datasetId.valueChanges.subscribe((id) => {
            const cols = this.columnsForDataset(id);
            this.extraMappingColumns.update((all) => all.map((c, idx) => (idx === i ? cols : c)));
        });
        this.extraMappings.push(group);
        this.extraMappingColumns.update((all) => [...all, []]);
    }

    removeMapping(i: number): void {
        this.extraMappings.removeAt(i);
        this.extraMappingColumns.update((all) => all.filter((_, idx) => idx !== i));
    }

    private onDatasetPicked(id: string): void {
        this.datasetColumns.set(this.columnsForDataset(id));
    }

    /** The columns a mapping row's Dataset select should offer — declared columns, or sampled ones. */
    private columnsForDataset(id: string): string[] {
        const ds = this.datasets().find((d) => d.id === id);
        if (!ds) return [];
        const declared = ds.columns.map((c) => c.name);
        const sampled = Object.keys(SAMPLE_SOURCES[ds.sourceName]?.[0] ?? {});
        return declared.length ? declared : sampled;
    }

    /** The query the current form + source amounts to (also what a saved view persists). */
    buildQuery(): GraphSourceQuery | { error: string } {
        const f = this.queryForm.getRawValue();
        switch (this.sourceId()) {
            case 'entity-projection': {
                if (!f.datasetId || !f.sourceCol || !f.targetCol) {
                    return { error: 'Pick a dataset plus its source and target columns.' };
                }
                const primary: EntityProjection = {
                    datasetId: f.datasetId, sourceCol: f.sourceCol, targetCol: f.targetCol,
                    linkKindCol: f.linkKindCol || undefined,
                    attrCols: f.attrCols.length ? f.attrCols : undefined,
                    entityType: f.entityType || undefined,
                };
                const extras = this.extraMappings.controls
                    .map((g) => g.getRawValue())
                    .filter((m) => m.datasetId && m.sourceCol && m.targetCol) as EntityProjection[];
                if (!extras.length) return { projection: primary };
                if (extras.some((m) => !m.entityType) || !primary.entityType) {
                    return { error: 'Every mapping needs an entity type when combining more than one.' };
                }
                return { projections: [primary, ...extras] };
            }
            case 'provenance': {
                if (!f.pipeline) return { error: 'Pick a pipeline.' };
                const pipelineRoots = [f.pipeline, ...f.extraPipelines.filter((p) => p && p !== f.pipeline)];
                return pipelineRoots.length > 1
                    ? { roots: pipelineRoots, counts: f.counts }
                    : { from: f.pipeline, counts: f.counts };
            }
            case 'lineage': {
                const extra = f.extraRoots.split(',').map((r) => r.trim()).filter(Boolean);
                const lineageRoots = [f.from, ...extra].filter((r): r is string => !!r);
                return lineageRoots.length > 1
                    ? { roots: lineageRoots, depth: f.depth, direction: f.direction }
                    : { from: f.from || undefined, depth: f.depth, direction: f.direction };
            }
            default:
                return {};
        }
    }

    /** Patch the form from a saved view's query (the host sets sourceId/display/layout separately). */
    patchFormFromView(view: LinkAnalysisView): void {
        const p = view.query.projection;
        this.queryForm.patchValue({
            from: view.query.from ?? '',
            depth: view.query.depth ?? 2,
            direction: view.query.direction ?? 'both',
            pipeline: view.sourceId === 'provenance' ? (view.query.from ?? '') : '',
            counts: view.query.counts ?? false,
            datasetId: p?.datasetId ?? '',
            sourceCol: p?.sourceCol ?? '',
            targetCol: p?.targetCol ?? '',
            linkKindCol: p?.linkKindCol ?? '',
        });
    }
}

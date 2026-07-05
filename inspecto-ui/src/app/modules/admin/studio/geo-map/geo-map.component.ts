import { ChangeDetectionStrategy, Component, OnInit, ViewChild, computed, inject, signal } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatMenuModule, MatMenuTrigger } from '@angular/material/menu';
import { MatSelectModule } from '@angular/material/select';
import { MatSliderModule } from '@angular/material/slider';
import { MatTooltipModule } from '@angular/material/tooltip';
import { firstValueFrom } from 'rxjs';
import { ToastrService } from 'ngx-toastr';

import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { InspectoSkeletonComponent } from 'app/inspecto/components/skeleton.component';
import { DataTableComponent } from 'app/inspecto/data-table';
import {
    GeoBBox,
    GeoCamera,
    GeoData,
    GeoDisplayMode,
    GeoEmphasis,
    GeoPoint,
    GeoQuery,
    GeoSourceId,
    MapViewComponent,
    filterByKinds,
    filterByTime,
    formatDistance,
    haversineMeters,
    searchPoints,
    timeExtent,
    withinBBox,
} from 'app/inspecto/geo';
import {
    ElementDetailDialog,
    ElementDetailResult,
    ElementDetailRow,
    uniqueNameValidator,
} from 'app/inspecto/investigation';
import { apiErrorMessage } from 'app/inspecto/api';
import { Dataset } from 'app/modules/admin/studio/datasets/dataset-types';
import { DatasetsService } from 'app/modules/admin/studio/datasets/datasets.service';
import { datasetRows } from 'app/modules/admin/studio/link-analysis/entity-projection';
import { GeoSourcesService, ProjectedGeo } from './geo-projection';
import { GeoMapService, GeoMapView } from './geo-map.service';

/** One row of the bottom Data panel (a point, flattened for the shared table). */
interface PointRow {
    id: string;
    label: string;
    kind: string;
    lat: number;
    lon: number;
    time?: string;
}

/**
 * **Geo Map Analysis Studio** (plan: docs/superpower/geo-map-analysis-plan.md §Phase 1) — pick a
 * GeoSource, map a Dataset's lat/lon columns, render on the offline MapLibre host
 * ({@link MapViewComponent}), investigate (search, kind filter, click-to-detail, nearby), and save
 * the investigation as a `geo-map-view` Component. The *where* sibling of the Link Analysis studio.
 */
@Component({
    selector: 'inspecto-geo-map',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        DecimalPipe, ReactiveFormsModule, MatButtonModule, MatCheckboxModule, MatDialogModule, MatFormFieldModule,
        MatIconModule, MatInputModule, MatMenuModule, MatSelectModule, MatSliderModule, MatTooltipModule,
        InspectoAlertComponent, InspectoEmptyStateComponent, InspectoSkeletonComponent, MapViewComponent,
        DataTableComponent,
    ],
    templateUrl: './geo-map.component.html',
})
export class GeoMapComponent implements OnInit {
    private fb = inject(FormBuilder);
    private toastr = inject(ToastrService);
    private dialog = inject(MatDialog);
    private geoSources = inject(GeoSourcesService);
    private datasetsService = inject(DatasetsService);
    private viewsService = inject(GeoMapService);

    @ViewChild(MapViewComponent) private mapView?: MapViewComponent;
    @ViewChild('saveTrigger') private saveTrigger?: MatMenuTrigger;

    readonly sources = this.geoSources.sources;

    // ── query builder ──
    readonly sourceId = signal<GeoSourceId>('dataset');
    readonly datasets = signal<Dataset[]>([]);
    readonly datasetColumns = signal<string[]>([]);
    /** Full query form vs its collapsed summary (auto-collapses after a run). */
    readonly queryOpen = signal(true);
    // Column presence is validated by the projection folds (typed errors → the banner), so only
    // the dataset itself is form-required — the fields differ per source.
    readonly queryForm = this.fb.nonNullable.group({
        datasetId: ['', Validators.required],
        latCol: [''],
        lonCol: [''],
        entityCol: [''],
        kindCol: [''],
        timeCol: [''],
        fromLatCol: [''],
        fromLonCol: [''],
        toLatCol: [''],
        toLonCol: [''],
        fromCol: [''],
        toCol: [''],
    });

    // ── result state ──
    readonly loading = signal(false);
    readonly loadError = signal('');
    readonly geo = signal<ProjectedGeo | null>(null);
    readonly lastRun = signal<GeoQuery | null>(null);

    // ── investigation state ──
    readonly search = signal('');
    readonly kindFilter = signal<string[]>([]);
    readonly selectedId = signal<string | null>(null);
    readonly dataOpen = signal(false);
    /** Markers vs density heatmap (persisted with a saved view). */
    readonly displayMode = signal<GeoDisplayMode>('markers');
    /** Time window [from, to] in epoch millis; `null` = no time filter. */
    readonly timeRange = signal<[number, number] | null>(null);
    /** Region filter (the "filter to view" viewport box); `null` = everywhere. */
    readonly viewBox = signal<GeoBBox | null>(null);

    /** The [min, max] time extent of the loaded data (the slider's rail); `null` = untimed data. */
    readonly extent = computed<[number, number] | null>(() => {
        const g = this.geo();
        return g ? timeExtent(g) : null;
    });

    /** All point kinds present in the result (the filter menu's options). */
    readonly pointKinds = computed<string[]>(() =>
        [...new Set((this.geo()?.points ?? []).map((p) => p.kind))].sort(),
    );

    /** The kind/time/region-filtered subset actually on the canvas. */
    readonly displayed = computed<GeoData | null>(() => {
        const g = this.geo();
        if (!g) return null;
        const kinds = this.kindFilter();
        let d: GeoData = filterByKinds(g, kinds.length ? kinds : null);
        const t = this.timeRange();
        if (t) d = filterByTime(d, t[0], t[1]);
        const box = this.viewBox();
        if (box) {
            const points = withinBBox(d.points, box);
            const keep = new Set(points.map((p) => p.id));
            d = { points, routes: d.routes.filter((r) => keep.has(r.from) && keep.has(r.to)) };
        }
        return d;
    });

    /** Search/selection highlight: matching points full-strength, the rest dimmed. */
    readonly emphasis = computed<GeoEmphasis | null>(() => {
        const d = this.displayed();
        if (!d) return null;
        const q = this.search();
        if (q) return { pointIds: searchPoints(d, q) };
        const sel = this.selectedId();
        return sel ? { pointIds: [sel] } : null;
    });

    /** The bottom Data panel rows (kind-filtered, search-narrowed to match the canvas emphasis). */
    readonly rows = computed<PointRow[]>(() => {
        const d = this.displayed();
        if (!d) return [];
        const em = this.search() ? new Set(this.emphasis()?.pointIds ?? []) : null;
        return d.points
            .filter((p) => !em || em.has(p.id))
            .map((p) => ({
                id: p.id,
                label: p.label ?? p.id,
                kind: p.kind,
                lat: p.lat,
                lon: p.lon,
                time: p.time !== undefined ? new Date(p.time).toISOString() : undefined,
            }));
    });

    // ── saved views ──
    readonly views = signal<GeoMapView[]>([]);
    readonly saving = signal(false);
    readonly saveForm = this.fb.nonNullable.group({
        name: ['', [Validators.required, uniqueNameValidator(() => this.views().map((v) => v.name))]],
        description: [''],
    });

    ngOnInit(): void {
        this.datasetsService.list().subscribe({ next: (d) => this.datasets.set(d), error: () => undefined });
        this.viewsService.list().subscribe({ next: (v) => this.views.set(v), error: () => undefined });
        this.queryForm.controls.datasetId.valueChanges.subscribe((id) => this.onDatasetPicked(id));
    }

    /** Offer the picked Dataset's columns and preselect obvious coordinate columns by name. */
    private onDatasetPicked(id: string): void {
        const ds = this.datasets().find((d) => d.id === id);
        const cols = ds ? Object.keys(datasetRows(ds)[0] ?? {}) : [];
        this.datasetColumns.set(cols);
        const guess = (re: RegExp): string => cols.find((c) => re.test(c)) ?? '';
        this.queryForm.patchValue({
            latCol: guess(/lat/i),
            lonCol: guess(/lon|lng/i),
            entityCol: '',
            kindCol: '',
            timeCol: guess(/time|date/i),
            fromLatCol: guess(/(from|orig|src).*lat/i),
            fromLonCol: guess(/(from|orig|src).*(lon|lng)/i),
            toLatCol: guess(/(to|dest|dst).*lat/i),
            toLonCol: guess(/(to|dest|dst).*(lon|lng)/i),
            fromCol: '',
            toCol: '',
        });
    }

    private currentQuery(): GeoQuery {
        const f = this.queryForm.getRawValue();
        if (this.sourceId() === 'od-routes') {
            return {
                routes: {
                    datasetId: f.datasetId,
                    fromLatCol: f.fromLatCol,
                    fromLonCol: f.fromLonCol,
                    toLatCol: f.toLatCol,
                    toLonCol: f.toLonCol,
                    fromCol: f.fromCol || undefined,
                    toCol: f.toCol || undefined,
                    kindCol: f.kindCol || undefined,
                    timeCol: f.timeCol || undefined,
                },
            };
        }
        return {
            projection: {
                datasetId: f.datasetId,
                latCol: f.latCol,
                lonCol: f.lonCol,
                entityCol: f.entityCol || undefined,
                kindCol: f.kindCol || undefined,
                timeCol: f.timeCol || undefined,
            },
        };
    }

    async run(): Promise<void> {
        if (this.queryForm.invalid) {
            this.queryForm.markAllAsTouched();
            return;
        }
        const query = this.currentQuery();
        this.loading.set(true);
        this.loadError.set('');
        this.clearInvestigation();
        try {
            const source = this.sources.find((s) => s.id === this.sourceId()) ?? this.sources[0];
            const out = await source.query(query);
            this.geo.set(out as ProjectedGeo);
            this.lastRun.set(query);
            this.queryOpen.set(false);
        } catch (err) {
            this.geo.set(null);
            this.loadError.set(err instanceof Error ? err.message : apiErrorMessage(err, 'The query failed.'));
        } finally {
            this.loading.set(false);
        }
    }

    /** The collapsed-query summary line (dataset + mapping). */
    readonly querySummary = computed<string>(() => {
        const run = this.lastRun();
        const dsName = (id: string): string => this.datasets().find((d) => d.id === id)?.name ?? id;
        if (run?.routes) {
            const r = run.routes;
            return `${dsName(r.datasetId)}: ${r.fromLatCol}/${r.fromLonCol} → ${r.toLatCol}/${r.toLonCol}`;
        }
        const q = run?.projection;
        if (!q) return '';
        const extras = [q.entityCol && `entity ${q.entityCol}`, q.kindCol && `kind ${q.kindCol}`, q.timeCol && `time ${q.timeCol}`]
            .filter(Boolean)
            .join(' · ');
        return `${dsName(q.datasetId)}: ${q.latCol}/${q.lonCol}${extras ? ' · ' + extras : ''}`;
    });

    // ── investigation ──
    onSearch(text: string): void {
        this.search.set(text);
    }

    kindOn(kind: string): boolean {
        return this.kindFilter().includes(kind);
    }

    toggleKind(kind: string, on: boolean): void {
        const cur = new Set(this.kindFilter());
        if (on) cur.add(kind);
        else cur.delete(kind);
        this.kindFilter.set([...cur]);
    }

    clearInvestigation(): void {
        this.search.set('');
        this.kindFilter.set([]);
        this.selectedId.set(null);
        this.timeRange.set(null);
        this.viewBox.set(null);
    }

    fit(): void {
        this.mapView?.fitToData();
    }

    toggleHeatmap(): void {
        this.displayMode.set(this.displayMode() === 'heatmap' ? 'markers' : 'heatmap');
    }

    /** Region filter: keep only what the current viewport shows. */
    filterToView(): void {
        const b = this.mapView?.getViewBounds();
        if (b) this.viewBox.set(b);
    }

    /** Time-slider thumbs (epoch millis). */
    setTimeFrom(v: number): void {
        const ext = this.extent();
        if (ext) this.timeRange.set([v, this.timeRange()?.[1] ?? ext[1]]);
    }

    setTimeTo(v: number): void {
        const ext = this.extent();
        if (ext) this.timeRange.set([this.timeRange()?.[0] ?? ext[0], v]);
    }

    /** Short date-time label for the slider readout. */
    timeLabel(t: number): string {
        return new Date(t).toISOString().slice(0, 16).replace('T', ' ');
    }

    /** Canvas or data-row click → full details (attributes + the 3 nearest points). */
    onPointClick(id: string): void {
        const d = this.displayed();
        const p = d?.points.find((x) => x.id === id);
        if (!d || !p) return;
        this.selectedId.set(id);
        const rows: ElementDetailRow[] = [
            { label: 'Coordinates', value: `${p.lat.toFixed(5)}, ${p.lon.toFixed(5)}` },
        ];
        if (p.time !== undefined) rows.push({ label: 'Time', value: new Date(p.time).toISOString() });
        for (const [k, v] of Object.entries(p.attrs ?? {})) {
            if (rows.length >= 14) break;
            rows.push({ label: k, value: String(v ?? '') });
        }
        for (const near of this.nearest(d.points, p, 3)) {
            rows.push({ label: 'Nearby', value: `${near.point.label ?? near.point.id} · ${formatDistance(near.distanceM)}` });
        }
        this.dialog
            .open(ElementDetailDialog, {
                data: { title: p.label ?? p.id, subtitle: p.kind, rows },
                width: '26rem',
            })
            .afterClosed()
            .subscribe((result: ElementDetailResult) => {
                if (result === 'focus') this.mapView?.flyTo(id);
            });
    }

    /** Route click → details with great-circle distance and folded movement count. */
    onRouteClick(id: string): void {
        const d = this.displayed();
        const r = d?.routes.find((x) => x.id === id);
        if (!d || !r) return;
        const a = d.points.find((p) => p.id === r.from);
        const b = d.points.find((p) => p.id === r.to);
        if (!a || !b) return;
        const rows: ElementDetailRow[] = [
            { label: 'From', value: a.label ?? a.id },
            { label: 'To', value: b.label ?? b.id },
            { label: 'Distance', value: formatDistance(haversineMeters(a.lat, a.lon, b.lat, b.lon)) },
            { label: 'Movements', value: String(r.weight ?? 1) },
        ];
        if (r.time !== undefined) rows.push({ label: 'Time', value: new Date(r.time).toISOString() });
        this.dialog
            .open(ElementDetailDialog, { data: { title: r.label ?? r.kind, subtitle: r.kind, rows }, width: '26rem' })
            .afterClosed()
            .subscribe((result: ElementDetailResult) => {
                if (result === 'focus') this.mapView?.flyTo(r.from);
            });
    }

    onRowClick(row: Record<string, unknown>): void {
        const id = String(row['id'] ?? '');
        if (!id) return;
        this.mapView?.flyTo(id);
        this.onPointClick(id);
    }

    private nearest(points: readonly GeoPoint[], from: GeoPoint, n: number): { point: GeoPoint; distanceM: number }[] {
        return points
            .filter((p) => p.id !== from.id)
            .map((point) => ({ point, distanceM: haversineMeters(from.lat, from.lon, point.lat, point.lon) }))
            .sort((a, b) => a.distanceM - b.distanceM)
            .slice(0, n);
    }

    // ── saved views ──
    async saveView(): Promise<void> {
        if (this.saveForm.invalid) {
            this.saveForm.markAllAsTouched();
            return;
        }
        const { name, description } = this.saveForm.getRawValue();
        const query = this.lastRun();
        if (!query) return;
        const view: GeoMapView = {
            id: name.trim().toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, ''),
            name: name.trim(),
            description: description.trim() || undefined,
            sourceId: this.sourceId(),
            query,
            display: this.displayMode(),
            camera: this.mapView?.getCamera() ?? undefined,
        };
        this.saving.set(true);
        try {
            await firstValueFrom(this.viewsService.save(view));
            this.views.set([...this.views().filter((v) => v.id !== view.id), view]);
            this.saveForm.reset({ name: '', description: '' });
            this.saveTrigger?.closeMenu();
            this.toastr.success(`Saved view '${view.name}'.`);
        } catch (err) {
            this.toastr.error(apiErrorMessage(err, 'Saving the view failed.'));
        } finally {
            this.saving.set(false);
        }
    }

    async loadView(view: GeoMapView): Promise<void> {
        this.sourceId.set(view.sourceId);
        this.displayMode.set(view.display ?? 'markers');
        this.loadCamera = view.camera ?? null;
        const p = view.query.projection;
        const r = view.query.routes;
        if (p) {
            this.queryForm.patchValue({ datasetId: p.datasetId });
            this.queryForm.patchValue({
                latCol: p.latCol,
                lonCol: p.lonCol,
                entityCol: p.entityCol ?? '',
                kindCol: p.kindCol ?? '',
                timeCol: p.timeCol ?? '',
            });
        } else if (r) {
            this.queryForm.patchValue({ datasetId: r.datasetId });
            this.queryForm.patchValue({
                fromLatCol: r.fromLatCol,
                fromLonCol: r.fromLonCol,
                toLatCol: r.toLatCol,
                toLonCol: r.toLonCol,
                fromCol: r.fromCol ?? '',
                toCol: r.toCol ?? '',
                kindCol: r.kindCol ?? '',
                timeCol: r.timeCol ?? '',
            });
        } else {
            return;
        }
        await this.run();
        if (view.camera) this.mapView?.setCamera(view.camera);
    }

    /** A loaded view's saved camera — consumed by the map host on its next mount. */
    loadCamera: GeoCamera | null = null;

    // ── export ──
    exportPng(): void {
        const dataUri = this.mapView?.exportPng();
        if (!dataUri) return;
        this.download(dataUri, 'geo-map.png');
    }

    exportGeoJson(): void {
        const d = this.displayed();
        if (!d) return;
        const fc = {
            type: 'FeatureCollection',
            features: d.points.map((p) => ({
                type: 'Feature',
                geometry: { type: 'Point', coordinates: [p.lon, p.lat] },
                properties: { id: p.id, kind: p.kind, label: p.label, time: p.time, ...p.attrs },
            })),
        };
        const url = URL.createObjectURL(new Blob([JSON.stringify(fc)], { type: 'application/geo+json' }));
        this.download(url, 'geo-map.geojson');
        URL.revokeObjectURL(url);
    }

    private download(href: string, filename: string): void {
        const a = document.createElement('a');
        a.href = href;
        a.download = filename;
        a.click();
    }
}

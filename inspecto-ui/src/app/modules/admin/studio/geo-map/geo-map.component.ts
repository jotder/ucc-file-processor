import { ChangeDetectionStrategy, Component, OnDestroy, OnInit, ViewChild, computed, inject, signal } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
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
    GeoLayerToggles,
    GeoNote,
    GeoSourceId,
    MapViewComponent,
    CoLocation,
    FrequentLocation,
    StayPoint,
    circleRing,
    coLocationGraph,
    coLocations,
    filterByKinds,
    filterByTime,
    formatDistance,
    frequentLocations,
    haversineMeters,
    nearby,
    pointInPolygon,
    searchPoints,
    stayPoints,
    timeExtent,
    withinBBox,
} from 'app/inspecto/geo';
import type { Feature, FeatureCollection } from 'geojson';
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
import { ICON_COLOR_SWATCHES } from 'app/inspecto/theme/chart-tokens';
import { GeoSourcesService, ProjectedGeo } from './geo-projection';

/** Annotation accent — the amber chart-token swatch (visually distinct from data kinds). */
const NOTE_ACCENT = ICON_COLOR_SWATCHES[3];
import { GeoMapService, GeoMapView } from './geo-map.service';
import { ColocationGraphDialog } from './colocation-graph.dialog';

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
        DecimalPipe, ReactiveFormsModule, MatButtonModule, MatButtonToggleModule, MatCheckboxModule, MatDialogModule,
        MatFormFieldModule, MatIconModule, MatInputModule, MatMenuModule, MatSelectModule, MatSliderModule, MatTooltipModule,
        InspectoAlertComponent, InspectoEmptyStateComponent, InspectoSkeletonComponent, MapViewComponent,
        DataTableComponent,
    ],
    templateUrl: './geo-map.component.html',
})
export class GeoMapComponent implements OnInit, OnDestroy {
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

    // ── geo intelligence (Phase 3) ──
    readonly analysisOpen = signal(false);
    readonly analysisTool = signal<'stay' | 'frequent' | 'coloc'>('coloc');
    /** Tool parameters (meters / minutes — converted to ms at run time). */
    readonly radiusM = signal(250);
    readonly windowMin = signal(60);
    readonly dwellMin = signal(30);
    readonly stays = signal<StayPoint[]>([]);
    readonly freqs = signal<FrequentLocation[]>([]);
    readonly colocs = signal<CoLocation[]>([]);
    readonly analysisRan = signal(false);
    /** Analysis-result highlight (kept separate from search/selection emphasis). */
    readonly resultEmphasis = signal<string[] | null>(null);

    /** The intelligence tools need entity + time mappings — hint instead of empty results. */
    readonly analysisReady = computed<boolean>(() => {
        const pts = this.geo()?.points ?? [];
        return pts.some((p) => p.label && p.time !== undefined);
    });

    // ── playback (animates the time-window end across the extent) ──
    readonly playing = signal(false);
    private playTimer: ReturnType<typeof setInterval> | null = null;

    // ── investigation tools (Phase 3b): measure / radius search / polygon filter / notes ──
    readonly activeTool = signal<'measure' | 'radius' | 'polygon' | 'note' | null>(null);
    readonly measureVertices = signal<{ lat: number; lon: number }[]>([]);
    readonly radiusCenter = signal<{ lat: number; lon: number } | null>(null);
    readonly searchRadiusM = signal(1000);
    readonly polygonVertices = signal<{ lat: number; lon: number }[]>([]);
    /** A closed [lon, lat] ring filtering the displayed subset; `null` = no polygon filter. */
    readonly polygonFilter = signal<[number, number][] | null>(null);
    readonly notes = signal<GeoNote[]>([]);
    readonly noteText = signal('');
    /** Layer-manager state + an uploaded custom GeoJSON overlay. */
    readonly layerToggles = signal<GeoLayerToggles>({});
    readonly customOverlay = signal<FeatureCollection | null>(null);

    /** Template alias for the pure formatter. */
    readonly fmtDistance = formatDistance;

    readonly measureTotalM = computed<number>(() => {
        const v = this.measureVertices();
        let total = 0;
        for (let i = 1; i < v.length; i++) total += haversineMeters(v[i - 1].lat, v[i - 1].lon, v[i].lat, v[i].lon);
        return total;
    });

    /** Points within the radius-search circle, nearest first. */
    readonly radiusHits = computed(() => {
        const c = this.radiusCenter();
        const d = this.displayed();
        return c && d ? nearby(d.points, c.lat, c.lon, this.searchRadiusM()) : [];
    });

    /** Everything the map draws on top of the data plane (tools + notes + uploaded GeoJSON). */
    readonly overlay = computed<FeatureCollection>(() => {
        const features: Feature[] = [...(this.customOverlay()?.features ?? [])];
        const line = (pts: { lat: number; lon: number }[], label?: string): void => {
            if (pts.length > 1) {
                features.push({
                    type: 'Feature',
                    geometry: { type: 'LineString', coordinates: pts.map((p) => [p.lon, p.lat]) },
                    properties: {},
                });
            }
            for (const [i, p] of pts.entries()) {
                features.push({
                    type: 'Feature',
                    geometry: { type: 'Point', coordinates: [p.lon, p.lat] },
                    properties: label && i === pts.length - 1 ? { label } : {},
                });
            }
        };
        const m = this.measureVertices();
        if (m.length) line(m, formatDistance(this.measureTotalM()));
        const rc = this.radiusCenter();
        if (rc) {
            features.push({
                type: 'Feature',
                geometry: { type: 'Polygon', coordinates: [circleRing(rc.lat, rc.lon, this.searchRadiusM())] },
                properties: {},
            });
            features.push({
                type: 'Feature',
                geometry: { type: 'Point', coordinates: [rc.lon, rc.lat] },
                properties: { label: `${this.radiusHits().length} within ${formatDistance(this.searchRadiusM())}` },
            });
        }
        const pv = this.polygonVertices();
        if (pv.length) line(pv);
        const ring = this.polygonFilter();
        if (ring) {
            features.push({ type: 'Feature', geometry: { type: 'Polygon', coordinates: [ring] }, properties: {} });
        }
        for (const n of this.notes()) {
            features.push({
                type: 'Feature',
                geometry: { type: 'Point', coordinates: [n.lon, n.lat] },
                properties: { label: n.text, color: NOTE_ACCENT },
            });
        }
        return { type: 'FeatureCollection', features };
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
        const ring = this.polygonFilter();
        if (ring) {
            const points = d.points.filter((p) => pointInPolygon(p.lat, p.lon, ring));
            const keep = new Set(points.map((p) => p.id));
            d = { points, routes: d.routes.filter((r) => keep.has(r.from) && keep.has(r.to)) };
        }
        return d;
    });

    /** Search / analysis-result / selection highlight: matches full-strength, the rest dimmed. */
    readonly emphasis = computed<GeoEmphasis | null>(() => {
        const d = this.displayed();
        if (!d) return null;
        const q = this.search();
        if (q) return { pointIds: searchPoints(d, q) };
        const result = this.resultEmphasis();
        if (result) return { pointIds: result };
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
        this.clearAnalysis();
        this.clearTools(); // notes survive — they're annotations, cleared/saved with the view
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

    /** Event playback: sweep the time-window end across the extent (~30 steps). */
    togglePlay(): void {
        if (this.playing()) {
            this.stopPlayback();
            return;
        }
        const ext = this.extent();
        if (!ext) return;
        const [start, end] = ext;
        const step = Math.max(1, (end - start) / 30);
        let t = start;
        this.playing.set(true);
        this.timeRange.set([start, start]);
        this.playTimer = setInterval(() => {
            t = Math.min(end, t + step);
            this.timeRange.set([start, t]);
            if (t >= end) this.stopPlayback();
        }, 400);
    }

    private stopPlayback(): void {
        if (this.playTimer) clearInterval(this.playTimer);
        this.playTimer = null;
        this.playing.set(false);
    }

    ngOnDestroy(): void {
        this.stopPlayback();
    }

    // ── geo intelligence ──
    runAnalysis(): void {
        const pts = this.displayed()?.points ?? [];
        const radius = this.radiusM();
        switch (this.analysisTool()) {
            case 'stay':
                this.stays.set(stayPoints(pts, radius, this.dwellMin() * 60_000));
                break;
            case 'frequent':
                this.freqs.set(frequentLocations(pts, radius));
                break;
            case 'coloc':
                this.colocs.set(coLocations(pts, radius, this.windowMin() * 60_000));
                break;
        }
        this.analysisRan.set(true);
    }

    /** Result click: highlight the folded points and fly to the spot. */
    focusResult(pointIds: string[], lat: number, lon: number): void {
        this.search.set('');
        this.selectedId.set(null);
        this.resultEmphasis.set(pointIds);
        this.mapView?.setCamera({ center: [lon, lat], zoom: 12 });
    }

    /** Open the co-location pairs as an Entity/Link graph (the Link Analysis bridge). */
    viewCoLocationGraph(): void {
        this.dialog.open(ColocationGraphDialog, { data: { graph: coLocationGraph(this.colocs()) } });
    }

    clearAnalysis(): void {
        this.stays.set([]);
        this.freqs.set([]);
        this.colocs.set([]);
        this.analysisRan.set(false);
        this.resultEmphasis.set(null);
    }

    // ── investigation tools ──
    /** Tool clicks land here (the map host emits every click; points also emit pointClick). */
    onMapClick(at: { lat: number; lon: number }): void {
        switch (this.activeTool()) {
            case 'measure':
                this.measureVertices.set([...this.measureVertices(), at]);
                break;
            case 'radius':
                this.radiusCenter.set(at);
                break;
            case 'polygon':
                this.polygonFilter.set(null);
                this.polygonVertices.set([...this.polygonVertices(), at]);
                break;
            case 'note': {
                const text = this.noteText().trim();
                if (!text) return;
                this.notes.set([...this.notes(), { id: `note-${Date.now()}`, lat: at.lat, lon: at.lon, text }]);
                break;
            }
        }
    }

    setTool(tool: 'measure' | 'radius' | 'polygon' | 'note' | null): void {
        this.activeTool.set(this.activeTool() === tool ? null : tool);
    }

    /** Close the in-progress polygon into a filter ring. */
    closePolygon(): void {
        const v = this.polygonVertices();
        if (v.length < 3) return;
        this.polygonFilter.set([...v, v[0]].map((p) => [p.lon, p.lat]));
        this.polygonVertices.set([]);
        this.activeTool.set(null);
    }

    clearTools(): void {
        this.activeTool.set(null);
        this.measureVertices.set([]);
        this.radiusCenter.set(null);
        this.polygonVertices.set([]);
        this.polygonFilter.set(null);
    }

    /** Any tool artifact on the canvas (drives the clear-tools affordance). */
    readonly toolsActive = computed<boolean>(
        () => !!(this.activeTool() || this.measureVertices().length || this.radiusCenter() || this.polygonVertices().length || this.polygonFilter()),
    );

    /** Custom GeoJSON overlay upload (layer manager). */
    onOverlayFile(input: HTMLInputElement): void {
        const file = input.files?.[0];
        input.value = '';
        if (!file) return;
        file.text().then(
            (text) => {
                try {
                    const fc = JSON.parse(text) as FeatureCollection;
                    if (fc?.type !== 'FeatureCollection' || !Array.isArray(fc.features)) throw new Error('not a FeatureCollection');
                    this.customOverlay.set(fc);
                    this.toastr.success(`Overlay loaded (${fc.features.length} features).`);
                } catch {
                    this.toastr.error('Not a valid GeoJSON FeatureCollection.');
                }
            },
            () => this.toastr.error('Reading the file failed.'),
        );
    }

    toggleLayer(key: keyof GeoLayerToggles, on: boolean): void {
        this.layerToggles.set({ ...this.layerToggles(), [key]: on });
    }

    /** Canvas or data-row click → full details (attributes + the 3 nearest points). */
    onPointClick(id: string): void {
        if (this.activeTool()) return; // tool clicks own the canvas
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
            notes: this.notes().length ? this.notes() : undefined,
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
        this.notes.set(view.notes ?? []);
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

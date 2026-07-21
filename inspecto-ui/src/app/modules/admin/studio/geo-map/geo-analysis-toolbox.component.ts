import { DecimalPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, input, output, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';

import {
    CoLocation,
    FrequentLocation,
    GeoPoint,
    StayPoint,
    coLocationGraph,
    coLocations,
    frequentLocations,
    stayPoints,
} from 'app/inspecto/geo';
import { ColocationGraphDialog } from './colocation-graph.dialog';

/** A result-click focus request — the host owns the MapLibre camera + emphasis, so it does the flying. */
export interface GeoAnalysisFocus {
    pointIds: string[];
    lat: number;
    lon: number;
}

/**
 * **Geo intelligence toolbox** (geo-map Phase 3), extracted from {@link GeoMapComponent} per plan S2/B4.
 * A self-contained panel that runs the pure `geo-analysis` library (co-location / frequent locations /
 * stay points) over the currently-displayed points and, on a result click, emits a {@link GeoAnalysisFocus}
 * for the host to fly to and highlight. Owns its own result state; the host mounts it with `[hidden]`
 * (never `@if`), so this instance survives the toolbox toggle and the host's `reset()` reaches it.
 */
@Component({
    selector: 'inspecto-geo-analysis-toolbox',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [DecimalPipe, MatButtonModule, MatButtonToggleModule, MatFormFieldModule, MatIconModule, MatInputModule],
    templateUrl: './geo-analysis-toolbox.component.html',
})
export class GeoAnalysisToolboxComponent {
    private dialog = inject(MatDialog);

    /** The kind/time/region-filtered points on the canvas — what the tools run over. */
    readonly points = input<readonly GeoPoint[]>([]);

    /** Result click → the host flies the map and highlights the folded points. */
    readonly focus = output<GeoAnalysisFocus>();

    readonly analysisTool = signal<'stay' | 'frequent' | 'coloc'>('coloc');
    /** Tool parameters (meters / minutes — converted to ms at run time). */
    readonly radiusM = signal(250);
    readonly windowMin = signal(60);
    readonly dwellMin = signal(30);
    readonly stays = signal<StayPoint[]>([]);
    readonly freqs = signal<FrequentLocation[]>([]);
    readonly colocs = signal<CoLocation[]>([]);
    readonly analysisRan = signal(false);

    /** The intelligence tools need entity + time mappings — hint instead of empty results. */
    readonly analysisReady = computed<boolean>(() => this.points().some((p) => p.label && p.time !== undefined));

    runAnalysis(): void {
        const pts = this.points();
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

    /** Result click: ask the host to highlight the folded points and fly to the spot. */
    pick(pointIds: string[], lat: number, lon: number): void {
        this.focus.emit({ pointIds, lat, lon });
    }

    /** Open the co-location pairs as an Entity/Link graph (the Link Analysis bridge). */
    viewCoLocationGraph(): void {
        this.dialog.open(ColocationGraphDialog, { data: { graph: coLocationGraph(this.colocs()) } });
    }

    /** Drop all results (the host calls this from clearAnalysis / on a new run). */
    reset(): void {
        this.stays.set([]);
        this.freqs.set([]);
        this.colocs.set([]);
        this.analysisRan.set(false);
    }

    /** Short date-time label for the result readouts. */
    timeLabel(t: number): string {
        return new Date(t).toISOString().slice(0, 16).replace('T', ' ');
    }
}

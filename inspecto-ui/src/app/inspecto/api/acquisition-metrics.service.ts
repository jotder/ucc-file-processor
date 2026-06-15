import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { apiUrl } from './api-base';

/** One label-set sample of a metric series. `labels` is a Prometheus-style string, e.g. `connector="sftp"`. */
export interface MetricSample {
    labels: string;
    value?: number;
    sum?: number;
    count?: number;
}

/** One metric: its Prometheus type, help text and per-label samples. */
export interface MetricSeries {
    type: 'counter' | 'gauge' | 'histogram';
    help: string;
    series: MetricSample[];
}

/** Snapshot of the acquisition metrics family (GET /metrics/acquisition), keyed by metric name. */
export type AcquisitionMetrics = Record<string, MetricSeries>;

/** Acquisition (Data Collection) metrics snapshot (CONTROL scope). */
@Injectable({ providedIn: 'root' })
export class AcquisitionMetricsService {
    private http = inject(HttpClient);

    /** The acquisition metric family, keyed by metric name. */
    get(): Observable<AcquisitionMetrics> {
        return this.http.get<AcquisitionMetrics>(apiUrl('/metrics/acquisition'));
    }
}

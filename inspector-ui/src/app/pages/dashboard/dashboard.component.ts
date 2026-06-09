
import { Component, DestroyRef, OnInit, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DxDataGridModule } from 'devextreme-angular/ui/data-grid';
import { DxChartModule } from 'devextreme-angular/ui/chart';
import { DxPieChartModule } from 'devextreme-angular/ui/pie-chart';
import { DxLoadIndicatorModule } from 'devextreme-angular/ui/load-indicator';
import { DxButtonModule } from 'devextreme-angular/ui/button';
import { forkJoin } from 'rxjs';
import {
  HealthService,
  ReportsService,
  ReadyStatus,
  StatusReport,
  ServiceReport,
  visibleInterval,
  DEFAULT_REFRESH_MS,
} from '../../shared/api';

/**
 * Inspector dashboard — service health, throughput and error-rate overview.
 * Consumes GET /ready, /status, /report and /metrics (raw Prometheus text shown as a fallback).
 */
@Component({
  standalone: true,
  imports: [DxDataGridModule, DxChartModule, DxPieChartModule, DxLoadIndicatorModule, DxButtonModule],
  templateUrl: './dashboard.component.html',
  styleUrls: ['./dashboard.component.scss'],
})
export class DashboardComponent implements OnInit {
  private health = inject(HealthService);
  private reports = inject(ReportsService);
  private destroyRef = inject(DestroyRef);
  autoRefresh = true;

  loading = true;
  ready: ReadyStatus | null = null;
  status: StatusReport | null = null;
  report: ServiceReport | null = null;
  metricsText = '';
  showMetrics = false;

  // chart-friendly projections
  latency: { metric: string; ms: number }[] = [];
  outcome: { label: string; value: number }[] = [];

  ngOnInit(): void {
    this.refresh();
    visibleInterval(DEFAULT_REFRESH_MS)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => { if (this.autoRefresh) this.refresh(); });
  }

  refresh(): void {
    this.loading = true;
    forkJoin({
      ready: this.health.ready(),
      status: this.reports.status(),
      report: this.reports.serviceReport(),
      metrics: this.health.metrics(),
    }).subscribe({
      next: ({ ready, status, report, metrics }) => {
        this.ready = ready;
        this.status = status;
        this.report = report;
        this.metricsText = metrics;
        this.latency = [
          { metric: 'p50', ms: report.p50DurationMs },
          { metric: 'p95', ms: report.p95DurationMs },
          { metric: 'p99', ms: report.p99DurationMs },
        ];
        this.outcome = [
          { label: 'Success', value: report.success },
          { label: 'Failed', value: report.failed },
        ];
        this.loading = false;
      },
      error: () => { this.loading = false; },
    });
  }

  get errorRatePct(): string {
    return this.report ? (this.report.errorRate * 100).toFixed(1) + '%' : '—';
  }
}

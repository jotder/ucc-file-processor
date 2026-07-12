import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { apiUrl } from './api-base';
import { ReadyStatus } from './models';

/** One subsystem's health on /health/details (System Maintenance MNT-15). */
export interface SubsystemHealth {
  status: 'UP' | 'DOWN' | 'NOT_CONFIGURED';
  detail: string;
}

/** Per-subsystem health (MNT-15): DOWN iff any subsystem is genuinely DOWN. */
export interface HealthDetails {
  status: 'UP' | 'DOWN';
  subsystems: Record<string, SubsystemHealth>;
}

/** Public probes: /health, /ready, /metrics (no token required). */
@Injectable({ providedIn: 'root' })
export class HealthService {
  private http = inject(HttpClient);

  health(): Observable<{ status: string }> {
    return this.http.get<{ status: string }>(apiUrl('/health'));
  }
  /** Per-subsystem health (MNT-15) — deeper than the liveness probe; auth-gated on Standard. */
  details(): Observable<HealthDetails> {
    return this.http.get<HealthDetails>(apiUrl('/health/details'));
  }
  ready(): Observable<ReadyStatus> {
    return this.http.get<ReadyStatus>(apiUrl('/ready'));
  }
  /** Prometheus text exposition (parsed client-side for the dashboard). */
  metrics(): Observable<string> {
    return this.http.get(apiUrl('/metrics'), { responseType: 'text' });
  }
}

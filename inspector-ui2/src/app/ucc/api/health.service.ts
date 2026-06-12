import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { apiUrl } from './api-base';
import { ReadyStatus } from './models';

/** Public probes: /health, /ready, /metrics (no token required). */
@Injectable({ providedIn: 'root' })
export class HealthService {
  private http = inject(HttpClient);

  health(): Observable<{ status: string }> {
    return this.http.get<{ status: string }>(apiUrl('/health'));
  }
  ready(): Observable<ReadyStatus> {
    return this.http.get<ReadyStatus>(apiUrl('/ready'));
  }
  /** Prometheus text exposition (parsed client-side for the dashboard). */
  metrics(): Observable<string> {
    return this.http.get(apiUrl('/metrics'), { responseType: 'text' });
  }
}

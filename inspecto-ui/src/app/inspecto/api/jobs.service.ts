import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { apiUrl, toParams } from './api-base';
import { JobRun, JobView } from './models';

/** Aggregate job-execution metrics (GET /jobs/metrics) — the DuckDB reporting projection (T27). */
export interface JobMetrics {
  total: number;
  success: number;
  failed: number;
  successRate: number; // 0..1
  p50Ms: number;
  p95Ms: number;
  meanMs: number;
}

/** One durable job-run row (GET /jobs/runs) — survives restarts (unlike the in-memory /jobs/{n}/runs). */
export interface JobRunRow {
  runId: string;
  job: string;
  type: string;
  trigger: string;
  startTime: string;
  endTime: string;
  status: string;
  durationMs: number;
  message: string;
}

/** A day in the failure trend (GET /jobs/failures): total runs and how many failed that day. */
export interface JobFailureDay {
  day: string; // yyyy-MM-dd
  total: number;
  failed: number;
}

/** Config-driven jobs: cron / event / manual (CONTROL scope). 404 when no jobs are registered. */
@Injectable({ providedIn: 'root' })
export class JobsService {
  private http = inject(HttpClient);

  list(): Observable<JobView[]> {
    return this.http.get<JobView[]>(apiUrl('/jobs'));
  }
  runs(name: string): Observable<JobRun[]> {
    return this.http.get<JobRun[]>(apiUrl(`/jobs/${encodeURIComponent(name)}/runs`));
  }
  trigger(name: string): Observable<{ job: string; status: string }> {
    return this.http.post<{ job: string; status: string }>(apiUrl(`/jobs/${encodeURIComponent(name)}/trigger`), {});
  }

  // ── T27 reporting (404 unless the DuckDB backend is on: -Djobs.backend=duckdb) ──
  metrics(job?: string): Observable<JobMetrics> {
    return this.http.get<JobMetrics>(apiUrl('/jobs/metrics'), { params: toParams({ job }) });
  }
  recentRuns(limit = 100, job?: string): Observable<JobRunRow[]> {
    return this.http.get<JobRunRow[]>(apiUrl('/jobs/runs'), { params: toParams({ limit, job }) });
  }
  failures(days = 30): Observable<JobFailureDay[]> {
    return this.http.get<JobFailureDay[]>(apiUrl('/jobs/failures'), { params: toParams({ days }) });
  }
}

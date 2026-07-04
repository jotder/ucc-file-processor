import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { apiUrl, toParams } from './api-base';
import { JobRun, JobType, JobView } from './models';

/** A single scheduled job with its full config (GET /jobs/{name}) — the list `JobView` plus the type-specific
 *  `params` and the catch-up flag. (List endpoint omits these; they're shown on the detail page.) */
export interface JobDetail extends JobView {
  params?: Record<string, unknown>;
  catchUp?: boolean;
}

/** The editable shape for create (POST /jobs) and edit (PUT /jobs/{name}). A job is cron-scheduled, event-driven
 *  (`onPipeline`), or manual (neither). */
export interface JobUpsert {
  name: string;
  type: JobType;
  cron?: string | null;
  onPipeline?: string | null;
  enabled: boolean;
  catchUp?: boolean;
  params?: Record<string, unknown>;
}

/** One log line for a job run (GET /jobs/{name}/runs/{runId}/logs). */
export interface JobLogLine {
  ts: string;
  level: 'INFO' | 'WARN' | 'ERROR' | 'DEBUG' | string;
  message: string;
}

/** One domain event emitted during a job run. */
export interface JobEvent {
  ts: string;
  type: string;
  message: string;
}

/** A run's logs + events (GET /jobs/{name}/runs/{runId}/logs). */
export interface JobRunLogs {
  logs: JobLogLine[];
  events: JobEvent[];
}

/** A generated export (C6) behind a `type:'report'` job's completed run (GET .../runs/{runId}/artifact). */
export interface ReportArtifact {
  runId: string;
  filename: string;
  mime: string;
  content: string;
}

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

  // ── single job + management (mock-served until the real Java endpoints land — see the plan) ──
  get(name: string): Observable<JobDetail> {
    return this.http.get<JobDetail>(apiUrl(`/jobs/${encodeURIComponent(name)}`));
  }
  create(body: JobUpsert): Observable<JobDetail> {
    return this.http.post<JobDetail>(apiUrl('/jobs'), body);
  }
  update(name: string, body: JobUpsert): Observable<JobDetail> {
    return this.http.put<JobDetail>(apiUrl(`/jobs/${encodeURIComponent(name)}`), body);
  }
  remove(name: string): Observable<unknown> {
    return this.http.delete(apiUrl(`/jobs/${encodeURIComponent(name)}`));
  }
  setEnabled(name: string, enabled: boolean): Observable<JobDetail> {
    return this.http.post<JobDetail>(apiUrl(`/jobs/${encodeURIComponent(name)}/${enabled ? 'enable' : 'disable'}`), {});
  }
  reschedule(name: string, cron: string): Observable<JobDetail> {
    return this.http.post<JobDetail>(apiUrl(`/jobs/${encodeURIComponent(name)}/reschedule`), { cron });
  }
  runLogs(name: string, runId: string): Observable<JobRunLogs> {
    return this.http.get<JobRunLogs>(apiUrl(`/jobs/${encodeURIComponent(name)}/runs/${encodeURIComponent(runId)}/logs`));
  }
  /** C6: the generated artifact behind a `type:'report'` job's completed run. */
  runArtifact(name: string, runId: string): Observable<ReportArtifact> {
    return this.http.get<ReportArtifact>(apiUrl(`/jobs/${encodeURIComponent(name)}/runs/${encodeURIComponent(runId)}/artifact`));
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

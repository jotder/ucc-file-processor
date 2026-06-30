import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { apiUrl, toParams } from './api-base';
import { AuditRow, RunResult, RunView, BatchAuditReport, ReportWindow, InboxStatus } from './models';

/** Ingest run lifecycle + audit queries (CONTROL scope). */
@Injectable({ providedIn: 'root' })
export class RunsService {
  private http = inject(HttpClient);

  list(): Observable<RunView[]> {
    return this.http.get<RunView[]>(apiUrl('/pipelines'));
  }
  trigger(name: string): Observable<RunResult> {
    return this.http.post<RunResult>(apiUrl(`/pipelines/${encodeURIComponent(name)}/trigger`), {});
  }
  runAll(): Observable<Record<string, RunResult>> {
    return this.http.post<Record<string, RunResult>>(apiUrl('/trigger'), {});
  }
  pause(name: string): Observable<{ pipeline: string; paused: boolean }> {
    return this.http.post<{ pipeline: string; paused: boolean }>(apiUrl(`/pipelines/${encodeURIComponent(name)}/pause`), {});
  }
  resume(name: string): Observable<{ pipeline: string; paused: boolean }> {
    return this.http.post<{ pipeline: string; paused: boolean }>(apiUrl(`/pipelines/${encodeURIComponent(name)}/resume`), {});
  }
  reprocess(name: string, batchId: string): Observable<Record<string, string>> {
    return this.http.post<Record<string, string>>(apiUrl(`/pipelines/${encodeURIComponent(name)}/reprocess`), { batchId });
  }
  commits(name: string): Observable<string[]> {
    return this.http.get<string[]>(apiUrl(`/pipelines/${encodeURIComponent(name)}/commits`));
  }
  batches(name: string): Observable<AuditRow[]> {
    return this.http.get<AuditRow[]>(apiUrl(`/pipelines/${encodeURIComponent(name)}/batches`));
  }
  files(name: string): Observable<AuditRow[]> {
    return this.http.get<AuditRow[]>(apiUrl(`/pipelines/${encodeURIComponent(name)}/files`));
  }
  lineage(name: string, batchId?: string): Observable<AuditRow[]> {
    return this.http.get<AuditRow[]>(apiUrl(`/pipelines/${encodeURIComponent(name)}/lineage`), { params: toParams({ batchId }) });
  }
  quarantine(name: string): Observable<AuditRow[]> {
    return this.http.get<AuditRow[]>(apiUrl(`/pipelines/${encodeURIComponent(name)}/quarantine`));
  }
  /** Inbox/processing status: files pending (matched, not yet processed) + whether mid-ingest. */
  pending(name: string): Observable<InboxStatus> {
    return this.http.get<InboxStatus>(apiUrl(`/pipelines/${encodeURIComponent(name)}/pending`));
  }
  report(name: string, window?: ReportWindow): Observable<BatchAuditReport> {
    return this.http.get<BatchAuditReport>(apiUrl(`/pipelines/${encodeURIComponent(name)}/report`), { params: toParams({ ...window }) });
  }
}

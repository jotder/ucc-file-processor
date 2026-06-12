import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { apiUrl, toParams } from './api-base';
import { AuditRow, EnrichmentJobView, EnrichmentRunReport, ReportWindow } from './models';

/** Stage-2 enrichment run audit + lineage + rollup (CONTROL scope). 404 when none registered. */
@Injectable({ providedIn: 'root' })
export class EnrichmentService {
  private http = inject(HttpClient);

  list(): Observable<EnrichmentJobView[]> {
    return this.http.get<EnrichmentJobView[]>(apiUrl('/enrichment'));
  }
  runs(job: string): Observable<AuditRow[]> {
    return this.http.get<AuditRow[]>(apiUrl(`/enrichment/${encodeURIComponent(job)}/runs`));
  }
  lineage(job: string, runId?: string): Observable<AuditRow[]> {
    return this.http.get<AuditRow[]>(apiUrl(`/enrichment/${encodeURIComponent(job)}/lineage`), { params: toParams({ runId }) });
  }
  report(job: string, window?: ReportWindow): Observable<EnrichmentRunReport> {
    return this.http.get<EnrichmentRunReport>(apiUrl(`/enrichment/${encodeURIComponent(job)}/report`), { params: toParams({ ...window }) });
  }
}

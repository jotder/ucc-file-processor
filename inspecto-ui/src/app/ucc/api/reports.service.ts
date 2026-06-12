import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { apiUrl, toParams } from './api-base';
import { ServiceReport, StatusReport, ReportWindow } from './models';

/** Service-wide status snapshot + batch-audit rollup (CONTROL scope). */
@Injectable({ providedIn: 'root' })
export class ReportsService {
  private http = inject(HttpClient);

  status(): Observable<StatusReport> {
    return this.http.get<StatusReport>(apiUrl('/status'));
  }
  serviceReport(window?: ReportWindow): Observable<ServiceReport> {
    return this.http.get<ServiceReport>(apiUrl('/report'), { params: toParams({ ...window }) });
  }
}

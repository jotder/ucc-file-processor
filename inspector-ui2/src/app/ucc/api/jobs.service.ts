import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { apiUrl } from './api-base';
import { JobRun, JobView } from './models';

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
}

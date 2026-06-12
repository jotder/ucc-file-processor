import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { apiUrl, toParams } from './api-base';
import { AssistIntent, AssistRequest, AssistResult, Diagnosis } from './models';

/**
 * Embedded assist agent (ASSIST_READ scope). The agent lives in the optional file-processor-agent
 * module; when absent, POST /assist/{intent} returns 503 and /assist/diagnoses returns []. Callers
 * should degrade gracefully (see error.interceptor + per-screen handling).
 */
@Injectable({ providedIn: 'root' })
export class AssistService {
  private http = inject(HttpClient);

  diagnoses(limit = 50): Observable<Diagnosis[]> {
    return this.http.get<Diagnosis[]>(apiUrl('/assist/diagnoses'), { params: toParams({ limit }) });
  }

  run(intent: AssistIntent | string, req: AssistRequest): Observable<AssistResult> {
    return this.http.post<AssistResult>(apiUrl(`/assist/${encodeURIComponent(intent)}`), req);
  }
}

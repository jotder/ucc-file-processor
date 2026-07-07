import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { apiUrl, toParams } from './api-base';

/** One fired alert from the core alert engine (GET /alerts). */
export interface FiredAlert {
  rule: string;
  severity: 'INFO' | 'WARNING' | 'CRITICAL' | string;
  pipeline: string;
  metric: string;
  value: number;
  comparator: string;
  threshold: number;
  window: string;
  epochMillis: number;
  message: string;
}

/** One armed alert rule (GET /alerts/rules). */
export interface AlertRule {
  name: string;
  metric: string;
  comparator: string;
  threshold: number;
  window: string;
  severity: string;
  onPipeline?: string;
}

/** Create/update body — the whole rule is authorable; `name` is the identity (immutable on edit). */
export type AlertRuleUpsert = AlertRule;

/**
 * The core alert execution engine (v4.1, B5; CONTROL scope). Rules are operator-saved
 * *_alert.toon files (drafted by the diagnose-and-alert assist skill); evaluation is
 * event-driven off the batch bus, with a manual sweep via evaluate().
 */
@Injectable({ providedIn: 'root' })
export class AlertsService {
  private http = inject(HttpClient);

  recent(limit = 50): Observable<FiredAlert[]> {
    return this.http.get<FiredAlert[]>(apiUrl('/alerts'), { params: toParams({ limit }) });
  }

  rules(): Observable<AlertRule[]> {
    return this.http.get<AlertRule[]>(apiUrl('/alerts/rules'));
  }

  // Rule authoring (audit C3; mirrors /decision-rules). Mock-served today — a live server
  // without the write endpoints answers 503, which the form surfaces as writes-disabled.
  createRule(body: AlertRuleUpsert): Observable<AlertRule> {
    return this.http.post<AlertRule>(apiUrl('/alerts/rules'), body);
  }

  updateRule(name: string, body: AlertRuleUpsert): Observable<AlertRule> {
    return this.http.put<AlertRule>(apiUrl(`/alerts/rules/${encodeURIComponent(name)}`), body);
  }

  removeRule(name: string): Observable<void> {
    return this.http.delete<void>(apiUrl(`/alerts/rules/${encodeURIComponent(name)}`));
  }

  /** Manual evaluation sweep; returns the alerts fired by this pass. */
  evaluate(): Observable<FiredAlert[]> {
    return this.http.post<FiredAlert[]>(apiUrl('/alerts/evaluate'), {});
  }
}

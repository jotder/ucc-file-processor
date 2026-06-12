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
/** Per-provider defaults the settings screen seeds its form from (GET /assist/settings). */
export interface AssistProviderDefaults {
  baseUrl: string;
  apiKeyRef: string;
  models: Record<string, string>;
  local: boolean;
}

/** Masked model-provider settings view (v4.1). Never contains an API key — only its presence. */
export interface AssistSettings {
  supported: boolean;
  provider?: string;
  baseUrl?: string | null;
  apiKeyRef?: string | null;
  apiKeySet?: boolean;
  models?: Record<string, string>;
  timeoutSeconds?: number;
  availableProviders?: string[];
  knownProviders?: string[];
  modelAvailable?: boolean;
  defaults?: Record<string, AssistProviderDefaults>;
}

/** Settings write payload. `apiKey` rides the request once and is never echoed back. */
export interface AssistSettingsUpdate {
  provider: string;
  baseUrl?: string;
  apiKeyRef?: string;
  apiKey?: string;
  models?: Record<string, string>;
  timeoutSeconds?: number;
}

export interface AssistTierTest {
  provider: string;
  ok: boolean;
  latencyMs?: number;
  error?: string;
}

/** Per-tier round-trip results (POST /assist/settings/test). */
export interface AssistSettingsTest {
  supported: boolean;
  small?: AssistTierTest;
  medium?: AssistTierTest;
  large?: AssistTierTest;
}

@Injectable({ providedIn: 'root' })
export class AssistService {
  private http = inject(HttpClient);

  diagnoses(limit = 50): Observable<Diagnosis[]> {
    return this.http.get<Diagnosis[]>(apiUrl('/assist/diagnoses'), { params: toParams({ limit }) });
  }

  run(intent: AssistIntent | string, req: AssistRequest): Observable<AssistResult> {
    return this.http.post<AssistResult>(apiUrl(`/assist/${encodeURIComponent(intent)}`), req);
  }

  /** Masked provider settings (assist.read). */
  settings(): Observable<AssistSettings> {
    return this.http.get<AssistSettings>(apiUrl('/assist/settings'));
  }

  /** Apply + persist provider settings, hot-swapping the live router (assist.write). */
  saveSettings(update: AssistSettingsUpdate): Observable<AssistSettings> {
    return this.http.post<AssistSettings>(apiUrl('/assist/settings'), update);
  }

  /** Round-trip each tier's configured model (assist.write). */
  testSettings(): Observable<AssistSettingsTest> {
    return this.http.post<AssistSettingsTest>(apiUrl('/assist/settings/test'), {});
  }
}

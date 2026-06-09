import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { apiUrl } from './api-base';
import { ConfigSpec, ConfigType, ValidateResult } from './models';

/** Declarative config specs (drive UI form rendering) + validation (ASSIST_READ / CONTROL). */
@Injectable({ providedIn: 'root' })
export class ConfigService {
  private http = inject(HttpClient);

  /** Field/rule spec for a config type — used to render the authoring form. */
  spec(type: ConfigType): Observable<ConfigSpec> {
    return this.http.get<ConfigSpec>(apiUrl(`/config/spec/${encodeURIComponent(type)}`));
  }
  /** Validate a saved .toon file on disk. */
  validateFile(configPath: string): Observable<ValidateResult> {
    return this.http.post<ValidateResult>(apiUrl('/validate'), { configPath });
  }
  /** Validate an unsaved draft against its type's spec; opt-in hard-fail safety gate. */
  validateDraft(type: ConfigType, config: Record<string, unknown>, safety = false): Observable<ValidateResult> {
    return this.http.post<ValidateResult>(apiUrl('/validate'), { type, config, safety });
  }
}

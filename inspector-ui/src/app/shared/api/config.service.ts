import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { apiUrl } from './api-base';
import { ConfigSpec, ConfigType, RegisterPipelineResult, ValidateResult, WriteConfigResult } from './models';

/**
 * Declarative config specs (drive UI form rendering) + validation (ASSIST_READ / CONTROL),
 * plus the v4.1 author→save→register loop: {@link write} persists a validated draft under the
 * server's -Dassist.write.root (ASSIST_WRITE / CONTROL) and {@link registerPipeline} makes the
 * saved config a live pipeline (CONTROL). Both are fail-closed server-side (503 when the write
 * root is unset; 422 on ERROR findings; 409 on an existing file without overwrite).
 */
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
  /** Persist a draft as a .toon under the server's write root (ASSIST_WRITE scope). */
  write(type: ConfigType, config: Record<string, unknown>, overwrite = false): Observable<WriteConfigResult> {
    return this.http.post<WriteConfigResult>(apiUrl('/config/write'), { type, config, overwrite });
  }
  /** Register a saved config (write-root-relative or absolute path) as a live pipeline (CONTROL). */
  registerPipeline(configPath: string): Observable<RegisterPipelineResult> {
    return this.http.post<RegisterPipelineResult>(apiUrl('/pipelines'), { configPath });
  }
}

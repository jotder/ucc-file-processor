import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { apiUrl, toParams } from './api-base';
import {
  ConfigDeleteResult,
  ConfigReadResult,
  ConfigSpec,
  ConfigType,
  ConfigWriteResult,
  ParsingPreview,
  PipelineRegisterResult,
  SchemaPreview,
  ValidateResult,
} from './models';

/**
 * Declarative config specs (drive UI form rendering) + validation (ASSIST_READ / CONTROL), plus the
 * server-side draft lifecycle the guided stream onboarding authors against (v5.1.0): write →
 * register → read back on resume → overwrite per stage → delete on discard. A draft is just a
 * pipeline config with `active: false` — parsed, indexed and catalog-visible, never executed.
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
  /** Persist a validated draft under the write root; `overwrite: true` replaces (stage save). */
  write(
    type: ConfigType,
    config: Record<string, unknown>,
    opts?: { subdir?: string; overwrite?: boolean },
  ): Observable<ConfigWriteResult> {
    return this.http.post<ConfigWriteResult>(apiUrl('/config/write'), { type, config, ...opts });
  }
  /** Read a config back as its decoded map — the onboarding resume path. */
  read(type: ConfigType, name: string, subdir?: string): Observable<ConfigReadResult> {
    return this.http.get<ConfigReadResult>(
      apiUrl(`/config/${encodeURIComponent(type)}/${encodeURIComponent(name)}`),
      { params: toParams({ subdir }) },
    );
  }
  /** Discard a config file. The server refuses an `active: true` pipeline (409) — deactivate first. */
  remove(type: ConfigType, name: string, subdir?: string): Observable<ConfigDeleteResult> {
    return this.http.delete<ConfigDeleteResult>(
      apiUrl(`/config/${encodeURIComponent(type)}/${encodeURIComponent(name)}`),
      { params: toParams({ subdir }) },
    );
  }
  /**
   * Register a freshly written pipeline file with the running service (`POST /runs` — pairs with
   * {@link write}: writing alone does not index a NEW file; later overwrites hot-reload by mtime).
   */
  registerPipeline(configPath: string): Observable<PipelineRegisterResult> {
    return this.http.post<PipelineRegisterResult>(apiUrl('/runs'), { configPath });
  }
  /** Parse a raw sample with a draft's parsing settings — stateless, scratch-only (raw→parsed hop). */
  previewParsing(config: Record<string, unknown>, sampleText: string): Observable<ParsingPreview> {
    return this.http.post<ParsingPreview>(apiUrl('/config/preview/parsing'), {
      config,
      sample_text: sampleText,
    });
  }
  /** TRY_CAST already-parsed sample rows against a draft schema's typed fields — stateless, scratch-only. */
  previewSchema(config: Record<string, unknown>, sampleRows: Record<string, unknown>[]): Observable<SchemaPreview> {
    return this.http.post<SchemaPreview>(apiUrl('/config/preview/schema'), { config, sampleRows });
  }
}

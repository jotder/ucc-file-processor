import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { apiUrl } from './api-base';

/** One component a template writes, as the gallery listing names it (component kind + resolved-later id). */
export interface TemplateComponentRef {
  kind: string;
  id: string;
}

/** A curated starter template as `GET /bi/templates` lists it (metadata only; content ships with apply). */
export interface BiTemplate {
  id: string;
  title: string;
  description: string;
  params: string[];
  components: TemplateComponentRef[];
}

/** The `POST /bi/templates/{id}/apply` result — the components written into the registry. */
export interface TemplateApplyResult {
  template: string;
  dataset: string;
  created: TemplateComponentRef[];
}

/**
 * The curated widget/dashboard template gallery (BI-8): browse starter component sets and apply one to a
 * Dataset. Apply writes real, immediately-editable components server-side (all-or-nothing; 409 on id
 * collision — pass a `prefix`). Offline/mock lists the curated set but answers 501 on apply (it writes
 * through the real ComponentStore); the gallery surfaces that as a clear "applies on the backend" notice.
 */
@Injectable({ providedIn: 'root' })
export class BiTemplatesService {
  private http = inject(HttpClient);

  list(): Observable<BiTemplate[]> {
    return this.http.get<BiTemplate[]>(apiUrl('/bi/templates'));
  }

  apply(id: string, dataset: string, prefix?: string): Observable<TemplateApplyResult> {
    const body: Record<string, string> = { dataset };
    if (prefix && prefix.trim()) body['prefix'] = prefix.trim();
    return this.http.post<TemplateApplyResult>(
      apiUrl('/bi/templates/' + encodeURIComponent(id) + '/apply'), body);
  }
}

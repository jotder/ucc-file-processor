import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { apiUrl } from 'app/inspecto/api';
import { Requirement, RequirementKind } from './requirement-types';

/**
 * Requirement store (UI-6 + SEC-7(c)) — the Business→Builder lifecycle over the dedicated
 * `/requirements*` control-plane routes. Submission is open; the accept/reject (`/decision`) and
 * `/deliver` transitions are enforced server-side on `canTriageRequirements` (a no-op on Personal),
 * so the UI's lens gate (`LensService.canTriageRequirements()`) is convenience, not the boundary.
 */
@Injectable({ providedIn: 'root' })
export class RequirementsService {
    private http = inject(HttpClient);

    list(): Observable<Requirement[]> {
        return this.http.get<Requirement[]>(apiUrl('/requirements'));
    }

    create(r: { id: string; title: string; kind: RequirementKind; description: string }): Observable<Requirement> {
        return this.http.post<Requirement>(apiUrl('/requirements'),
            { id: r.id, title: r.title, kind: r.kind, description: r.description });
    }

    decide(id: string, accept: boolean, note?: string): Observable<Requirement> {
        return this.http.post<Requirement>(apiUrl(`/requirements/${encodeURIComponent(id)}/decision`), { accept, note });
    }

    deliver(id: string, note?: string): Observable<Requirement> {
        return this.http.post<Requirement>(apiUrl(`/requirements/${encodeURIComponent(id)}/deliver`), { note });
    }
}

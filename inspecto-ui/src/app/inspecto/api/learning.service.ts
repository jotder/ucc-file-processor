import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { map, Observable } from 'rxjs';
import { apiUrl, toParams } from './api-base';

/** The stored verdict on an investigation Case (GET /agent/feedback) — the learning corpus. */
export interface CaseFeedback {
    id: string;
    caseId: string;
    rating: 'HELPFUL' | 'NOT_HELPFUL';
    note: string | null;
    submittedBy: string | null;
    at: string;
}

/** What an operator submits (POST /agent/cases/{id}/feedback). */
export type FeedbackRating = 'helpful' | 'not_helpful';

/**
 * The AGT-5 P5 learning surface: read the Case-feedback corpus that drives tuning, submit a rating on a
 * Case, and recall prior Cases similar to one. Reads degrade to empty when the intelligence module is
 * absent (the dashboard handles it); the corpus accrues durably server-side.
 */
@Injectable({ providedIn: 'root' })
export class LearningService {
    private http = inject(HttpClient);

    /** Recent Case feedback, newest-first, capped at `limit`. */
    feedback(limit = 200): Observable<CaseFeedback[]> {
        return this.http
            .get<{ feedback: CaseFeedback[] }>(apiUrl('/agent/feedback'), { params: toParams({ limit }) })
            .pipe(map((r) => r.feedback ?? []));
    }

    /** Rate a Case helpful / not-helpful (+ optional note). 404 when the case id is unknown. */
    rateCase(caseId: string, rating: FeedbackRating, note?: string): Observable<CaseFeedback> {
        return this.http.post<CaseFeedback>(
            apiUrl(`/agent/cases/${encodeURIComponent(caseId)}/feedback`),
            { rating, note },
        );
    }

    /** Prior Cases similar to `caseId` (recall), each with a `similarity` score. */
    similarCases(caseId: string, k = 5): Observable<Record<string, unknown>[]> {
        return this.http
            .get<{ similar: Record<string, unknown>[] }>(
                apiUrl(`/agent/cases/${encodeURIComponent(caseId)}/similar`),
                { params: toParams({ k }) },
            )
            .pipe(map((r) => r.similar ?? []));
    }
}

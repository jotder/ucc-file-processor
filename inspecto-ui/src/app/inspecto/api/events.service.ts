import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { apiUrl, toParams } from './api-base';

/**
 * One immutable operational fact from the Operational Intelligence event engine (GET /events*). Mirrors
 * `com.gamma.event.Event#toMap()`: `ts` is epoch millis, `timestamp` the ISO-8601 UTC string, `level` an
 * {@link EVENT_LEVELS} member, `type` a {@link EVENT_TYPES} constant or any custom string, and `attributes`
 * the structured detail bag (never row content). `pipeline`/`correlationId` may be null (service-wide).
 */
export interface EventRow {
    eventId: string;
    ts: number;
    timestamp: string;
    level: string;
    type: string;
    source: string;
    pipeline: string | null;
    correlationId: string | null;
    message: string;
    attributes: Record<string, string>;
}

/** Filter + page over the event store (the `?…` query of GET /events/search); every field is optional. */
export interface EventFilter {
    level?: string;
    type?: string;
    pipeline?: string;
    correlationId?: string;
    q?: string;
    from?: string;
    to?: string;
    limit?: number;
    offset?: number;
}

/** An operator-saved filter set (GET/POST /events/views) — name + the set search params. */
export interface SavedEventView {
    name: string;
    filters: Record<string, string>;
    createdAt: number;
}

/** Severity ladder (`EventLevel`), least → most severe; the level filter is a *minimum* (`level >= minLevel`). */
export const EVENT_LEVELS = ['TRACE', 'DEBUG', 'INFO', 'WARN', 'ERROR'] as const;

/** Well-known `Event.type` constants for the type filter — the model is extensible, so any string is valid too. */
export const EVENT_TYPES: string[] = [
    'LOG',
    'SERVICE_STARTED', 'PIPELINE_REGISTERED', 'PIPELINE_PAUSED', 'PIPELINE_RESUMED',
    'BATCH_COMMITTED', 'BATCH_FAILED', 'FILE_RECEIVED', 'FILE_QUARANTINED',
    'FILE_DISCOVERED', 'FILE_FETCHED', 'FILE_VALIDATED', 'FILE_FETCH_FAILED', 'FILE_ARCHIVED',
    'SOURCE_CIRCUIT_OPEN', 'FILE_STABLE', 'FILE_CHANGED', 'SEQUENCE_GAP',
    'JOB_STARTED', 'JOB_SUCCEEDED', 'JOB_FAILED', 'ENRICHMENT_RUN',
    'ALERT_FIRED', 'CONFIG_VALIDATED',
    'OBJECT_OPENED', 'OBJECT_ACTIVITY', 'OBJECT_SLA_BREACH', 'OBJECT_LINKED', 'OBJECT_NOTE',
];

/** Operational event stream + saved views (CONTROL scope). */
@Injectable({ providedIn: 'root' })
export class EventsService {
    private http = inject(HttpClient);

    /** Filtered events, newest-first (GET /events/search). An empty filter returns the newest `limit` events. */
    search(filter: EventFilter = {}): Observable<EventRow[]> {
        return this.http.get<EventRow[]>(apiUrl('/events/search'), {
            params: toParams(filter as Record<string, unknown>),
        });
    }

    /** One event by id (GET /events/{id}); 404 when not found in the newest window. */
    get(id: string): Observable<EventRow> {
        return this.http.get<EventRow>(apiUrl(`/events/${encodeURIComponent(id)}`));
    }

    /** Matching events as CSV text (GET /events/export?format=csv) — fetched via HttpClient so the bearer token is sent. */
    exportCsv(filter: EventFilter = {}): Observable<string> {
        return this.http.get(apiUrl('/events/export'), {
            params: toParams({ ...filter, format: 'csv' } as Record<string, unknown>),
            responseType: 'text',
        });
    }

    /** Operator-saved views (GET /events/views). */
    views(): Observable<SavedEventView[]> {
        return this.http.get<SavedEventView[]>(apiUrl('/events/views'));
    }

    /** Upsert a saved view (POST /events/views) — the body is {name, …set filter keys}. */
    saveView(name: string, filters: Record<string, string>): Observable<SavedEventView> {
        return this.http.post<SavedEventView>(apiUrl('/events/views'), { name, ...filters });
    }

    /** Delete a saved view (POST /events/views/{name}/delete). */
    deleteView(name: string): Observable<unknown> {
        return this.http.post(apiUrl(`/events/views/${encodeURIComponent(name)}/delete`), {});
    }
}

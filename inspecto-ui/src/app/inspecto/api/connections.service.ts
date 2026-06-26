import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { apiUrl, toParams } from './api-base';

/** An SSH/bastion hop in front of a target system (part of a connection profile). */
export interface ConnectionTunnel {
    host: string;
    port?: number;
    username?: string;
    password?: string; // a ${…} reference or '***' — never a value
}

/**
 * A reusable remote-system connection profile (GET /connections) — host/port/credentials/database/base
 * path for a source, authored as a `*_connection.toon` and referenced by pipelines via `source.connection`.
 * Secret-masked by the API: a `password` is either a `${…}` reference or '***', never a real value.
 */
export interface ConnectionProfile {
    id: string;
    connector: string;
    host?: string;
    port?: number;
    database?: string;
    basePath?: string;
    username?: string;
    password?: string;
    options?: Record<string, string>;
    tunnel?: ConnectionTunnel;
}

/** The outcome of POST /connections/{id}/test — a TCP reachability + secret-resolution probe. */
export interface ConnectionTestResult {
    id: string;
    connector: string;
    endpoint: string;
    reachable: boolean;
    latencyMs?: number;
    secretsResolved: boolean;
    detail: string;
}

@Injectable({ providedIn: 'root' })
export class ConnectionsService {
    private http = inject(HttpClient);

    /** All connection profiles (secret-masked). */
    list(): Observable<ConnectionProfile[]> {
        return this.http.get<ConnectionProfile[]>(apiUrl('/connections'));
    }

    /** One connection profile by id (secret-masked). */
    get(id: string): Observable<ConnectionProfile> {
        return this.http.get<ConnectionProfile>(apiUrl(`/connections/${encodeURIComponent(id)}`));
    }

    /** Test reachability of the profile's endpoint (the tunnel hop when one is configured). */
    test(id: string): Observable<ConnectionTestResult> {
        return this.http.post<ConnectionTestResult>(apiUrl(`/connections/${encodeURIComponent(id)}/test`), {});
    }

    /**
     * Test an <em>unsaved</em> profile straight from a form (no persistence). {@code target} selects which
     * endpoint to probe — the connection itself or its SSH tunnel hop.
     */
    testProfile(profile: ConnectionProfile, target: 'connection' | 'tunnel' = 'connection'): Observable<ConnectionTestResult> {
        return this.http.post<ConnectionTestResult>(apiUrl('/connections/test'), profile, { params: toParams({ target }) });
    }

    /**
     * Create a connection profile (write-root gated). Secrets must be `${ENV:…}` references; the response
     * is the secret-masked profile map. 503 = writes disabled, 409 = duplicate id, 400/422 = bad input.
     */
    create(p: ConnectionProfile): Observable<ConnectionProfile> {
        return this.http.post<ConnectionProfile>(apiUrl('/connections'), p);
    }

    /**
     * Update a connection profile (write-root gated). Submitting `'***'` for a secret preserves the stored
     * value. 503 = writes disabled, 404 = unknown, 400/422 = bad input.
     */
    update(id: string, p: ConnectionProfile): Observable<ConnectionProfile> {
        return this.http.put<ConnectionProfile>(apiUrl(`/connections/${encodeURIComponent(id)}`), p);
    }

    /** Delete a connection profile (write-root gated). 503 = writes disabled, 404 = unknown, 409 = in use. */
    remove(id: string): Observable<unknown> {
        return this.http.delete(apiUrl(`/connections/${encodeURIComponent(id)}`));
    }
}

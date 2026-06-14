import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { apiUrl } from './api-base';

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
}

import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { apiUrl } from './api-base';

/** One aggregated projection triple: a distinct (source, target[, kind]) pair with its folded row count. */
export interface ProjectionTriple {
  source: string;
  target: string;
  /** The link kind from `linkKindCol`, or null when the projection is untyped. */
  kind: string | null;
  count: number;
  /** Values of `attrCols`, keyed by column name — present only when the request sent `attrCols`. */
  attrs?: Record<string, string | null>;
}

export interface ProjectionResult {
  /** Heaviest first — the server orders by count so a node cap keeps the densest subgraph. */
  rows: ProjectionTriple[];
  /** True when the server row limit cut the projection short. */
  truncated: boolean;
}

/** The wire shape of an entity-projection request (mirrors the studio's `EntityProjection` mapping). */
export interface ProjectionRequest {
  dataset: string;
  sourceCol: string;
  targetCol: string;
  linkKindCol?: string;
  /** Extra columns to carry as per-edge attributes; differing values split a folded pair into separate rows. */
  attrCols?: string[];
  limit?: number;
}

/** {@code POST /inv/projection/neighbors} request — a mapping plus the entity value to expand. */
export interface NeighborsRequest extends ProjectionRequest {
  /** The entity's raw projected value — rows where it's either endpoint. */
  value: string;
}

/**
 * Investigation-studio backend (INV-1): the real DuckDB-side Entity Projection over a Dataset —
 * the server half of the Link Analysis studio's `entity-projection` GraphSource. Offline/mock mode
 * answers 501 (see `mock/handlers/inv.handler.ts`) and the studio falls back to its client-side
 * sample fold, so the demo path is unchanged.
 */
@Injectable({ providedIn: 'root' })
export class InvService {
  private http = inject(HttpClient);

  project(req: ProjectionRequest): Observable<ProjectionResult> {
    return this.http.post<ProjectionResult>(apiUrl('/inv/projection'), req);
  }

  /** Phase E incremental expand: the one-hop neighborhood of `req.value` within the mapping. */
  neighbors(req: NeighborsRequest): Observable<ProjectionResult> {
    return this.http.post<ProjectionResult>(apiUrl('/inv/projection/neighbors'), req);
  }
}

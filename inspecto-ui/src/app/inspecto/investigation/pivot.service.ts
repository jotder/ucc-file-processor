import { Injectable, inject } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import type { ElementObjectRef } from './element-detail.dialog';

/** The alternate investigation views a selection can be pivoted into (ui-design-review R8). 'table'
 *  (object/case detail) is already reached via `ElementDetailDialog`'s "Open record" action — this
 *  contract covers the two visualization hosts. */
export type PivotView = 'graph' | 'map';

const ROUTE_BY_VIEW: Record<PivotView, string> = {
    graph: '/studio/link-analysis',
    map: '/studio/geo-map',
};

/**
 * **Investigation pivot** (ui-design-review R8) — the shared contract link-analysis and geo-map use to
 * switch the *view* on a selection without losing it, the natural growth of the drill-drawer pattern
 * (table/graph/map/timeline over the same selection; only the two hosts that exist today are wired).
 * The selection travels as its `ElementObjectRef` (Incident/Case id) via query params so the target
 * host can look the record up in its own already-loaded data — no new backend query is introduced, so
 * a record not present in the target view's current load is a graceful no-op (the host toasts and
 * stays put) rather than an error.
 */
@Injectable({ providedIn: 'root' })
export class PivotService {
    private router = inject(Router);

    /** Navigate to `view`, carrying `ref` so the target host can re-select the same record. */
    pivotTo(view: PivotView, ref: ElementObjectRef): void {
        void this.router.navigate([ROUTE_BY_VIEW[view]], { queryParams: { pivotId: ref.id, pivotType: ref.type } });
    }

    /** Read an incoming pivot selection off the route's query params, if present. */
    readIncoming(route: ActivatedRoute): ElementObjectRef | undefined {
        const params = route.snapshot.queryParamMap;
        const id = params.get('pivotId');
        const type = params.get('pivotType');
        if (!id || (type !== 'CASE' && type !== 'INCIDENT')) return undefined;
        return { id, type };
    }
}

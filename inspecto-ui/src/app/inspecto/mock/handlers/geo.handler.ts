import { MockFlags } from '../mock-flags';
import { error, match, MockHandler, MockRequest } from '../mock-http';

const PROJECTION = /\/geo\/projection$/;
const ROUTES = /\/geo\/routes$/;

/**
 * The Geo Map backend mock domain (Phase 4). Server-side projection/aggregation is a real
 * DuckDB-side fold — the mock layer has no dataset rows to fold (offline rows live in the Dataset
 * editor's sample seam, a feature concern this shared layer must not import). Answering an explicit
 * 501 here keeps the offline path honest: the `dataset`/`od-routes` GeoSources catch it and fall
 * back to their client-side sample fold, mirroring `inv.handler.ts`'s `/inv/projection` precedent.
 * Gated on `mockStudio` (the dataset/sample seam's flag) so a real backend serves `/geo/projection`
 * and `/geo/routes` when mocks are off.
 */
export function geoHandler(flags: MockFlags): MockHandler {
  return (req: MockRequest) => {
    if (!flags.mockStudio) return undefined;
    if (req.method === 'POST' && match(req.url, PROJECTION)) {
      return error(501, 'Geo projection runs on the real backend; offline mode folds sample rows client-side.');
    }
    if (req.method === 'POST' && match(req.url, ROUTES)) {
      return error(501, 'Geo route aggregation runs on the real backend; offline mode folds sample rows client-side.');
    }
    return undefined;
  };
}

import { MockFlags } from '../mock-flags';
import { error, json, match, MockHandler, MockRequest } from '../mock-http';

const LIST = /\/bi\/templates$/;
const APPLY = /\/bi\/templates\/[^/]+\/apply$/;

/**
 * The BI template gallery mock (BI-8). Listing mirrors the backend's curated set so the gallery renders
 * offline; APPLY writes real components through the server-side ComponentStore, which the mock layer has
 * no equivalent of — so it answers an honest 501 (the `invHandler` precedent), and the gallery surfaces a
 * clear "applies on the backend" toast rather than faking a partial board.
 * Gated on `mockStudio` so the backend's curated list + real apply serve when mocks are off.
 */
export function biTemplatesHandler(flags: MockFlags): MockHandler {
  return (req: MockRequest) => {
    if (!flags.mockStudio) return undefined;
    if (req.method === 'POST' && match(req.url, APPLY)) {
      return error(501, 'Templates apply on the real backend (they write components server-side); offline mode is browse-only.');
    }
    if (req.method === 'GET' && match(req.url, LIST)) {
      return json([
        {
          id: 'kpi-overview', title: 'KPI overview',
          description: 'A count KPI, a sum-by-dimension bar, and a per-dimension table over one Dataset — the minimal executive board to start from.',
          params: ['dataset', 'prefix?'],
          components: [
            { kind: 'widget', id: 'kpi_total' }, { kind: 'widget', id: 'sum_by_dim' },
            { kind: 'widget', id: 'raw_table' }, { kind: 'dashboard', id: 'kpi_board' },
          ],
        },
        {
          id: 'quality-monitor', title: 'Data quality monitor',
          description: 'Row volume by dimension plus a distinct-key count — a starting point for watching a feed’s health.',
          params: ['dataset', 'prefix?'],
          components: [
            { kind: 'widget', id: 'volume' }, { kind: 'widget', id: 'distincts' },
            { kind: 'dashboard', id: 'quality_board' },
          ],
        },
      ]);
    }
    return undefined;
  };
}

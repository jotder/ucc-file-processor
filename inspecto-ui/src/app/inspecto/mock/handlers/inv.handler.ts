import { MockFlags } from '../mock-flags';
import { error, match, MockHandler, MockRequest } from '../mock-http';

const PROJECTION = /\/inv\/projection$/;

/**
 * The investigation-backend mock domain (INV-1). Entity projection is a real DuckDB-side aggregation —
 * the mock layer has no dataset rows to fold (offline rows live in the Dataset editor's sample seam,
 * a feature concern this shared layer must not import). Answering an explicit 501 here keeps the
 * offline path honest: `EntityProjectionGraphSource` catches it and falls back to its client-side
 * sample fold, and no unhandled request ever escapes to the network (which would false-trigger the
 * connectivity banner).
 */
export function invHandler(_flags: MockFlags): MockHandler {
  return (req: MockRequest) => {
    if (req.method === 'POST' && match(req.url, PROJECTION)) {
      return error(501, 'Entity projection runs on the real backend; offline mode folds sample rows client-side.');
    }
    return undefined;
  };
}

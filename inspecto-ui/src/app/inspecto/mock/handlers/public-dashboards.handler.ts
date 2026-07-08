import { MockFlags } from '../mock-flags';
import { error, match, MockHandler, MockRequest } from '../mock-http';

const PUBLIC_DASHBOARD = /\/public\/dashboards\/[^/]+(\/query)?$/;

/**
 * The public share surface mock (BI-6). Share tokens are HMAC-signed and verified server-side
 * (`-Dbi.share.secret`) — the mock layer has no secret and MUST NOT pretend a token is valid, so it
 * answers an explicit 501 (the `invHandler` precedent): the embed viewer shows its clean "link
 * unavailable" state offline, and no unhandled request escapes to the network (which would
 * false-trigger the connectivity banner).
 */
export function publicDashboardsHandler(_flags: MockFlags): MockHandler {
  return (req: MockRequest) => {
    if (match(req.url, PUBLIC_DASHBOARD)) {
      return error(501, 'Share links resolve on the real backend (HMAC-verified); offline mode cannot validate tokens.');
    }
    return undefined;
  };
}

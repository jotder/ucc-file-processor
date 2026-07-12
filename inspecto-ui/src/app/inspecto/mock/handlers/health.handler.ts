import { MockFlags } from '../mock-flags';
import { json, match, MockHandler, MockRequest } from '../mock-http';

const DETAILS = /\/health\/details$/;

/**
 * Mock for `GET /health/details` (System Maintenance MNT-15) so the Maintenance Overview pane
 * renders offline. Gated on `mockOps` (the operational domain) so a mocks-off real-backend drive
 * hits the real route. Deliberately answers ONLY the details route — the bare `/health` liveness
 * probe stays unmocked on purpose: the connectivity banner's Retry uses it to detect a real
 * backend, and mocking it would make the banner lie. Static healthy payload mirroring the
 * backend's subsystem shape (jobRunsProjection NOT_CONFIGURED, like a backend without -Djobs.backend).
 */
export function healthHandler(flags: MockFlags): MockHandler {
  return (req: MockRequest) => {
    if (!flags.mockOps) return undefined;
    if (req.method === 'GET' && match(req.url, DETAILS)) {
      return json({
        status: 'UP',
        subsystems: {
          configStore: { status: 'UP', detail: 'mock write root' },
          dataStore: { status: 'UP', detail: 'mock data root' },
          pipelines: { status: 'UP', detail: '2 registered' },
          scheduler: { status: 'UP', detail: '5 job(s), 2 cron-scheduled' },
          jobRunsProjection: { status: 'NOT_CONFIGURED', detail: '-Djobs.backend not set' },
        },
      });
    }
    return undefined;
  };
}

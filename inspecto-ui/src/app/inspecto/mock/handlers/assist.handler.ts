import type { AssistResult } from '../../api/models';
import type { Consequence } from '../../decision/consequence';
import { MockFlags } from '../mock-flags';
import { json, MockHandler, MockRequest } from '../mock-http';

/**
 * R5 — a minimal mock for the Assist **decision-engine** intent (`POST /assist/propose-decision`). The
 * real Assist is backend-only (503 in mock mode); this handler serves a deterministic proposal so the
 * "AI proposes the same {@link Consequence} objects, a human approves" flow works offline. Every other
 * `/assist/*` intent still falls through (the real agent, or the panel's graceful 503). Gated on
 * `mockOps` so a real backend takes over when connected.
 */

const PROPOSE = /\/assist\/propose-decision$/;

export function assistHandler(flags: MockFlags): MockHandler {
    return (req: MockRequest) => {
        if (!flags.mockOps) return undefined;
        if (req.method !== 'POST' || !PROPOSE.test(req.url)) return undefined;

        // The proposed decision: quarantine + emit a review signal + raise an alert — the same typed
        // Consequences a rule holds, so the human reviews them in the Decision Rule form and saves.
        const consequences: Consequence[] = [
            { action: 'quarantine', destination: 'suspected fraud' },
            { action: 'emit-signal', params: { type: 'FRAUD_REVIEW', severity: 'warn', message: 'AI-proposed: high-cost short-call pattern' } },
            { action: 'create-alert', params: { rule: 'ai_high_cost', metric: 'cost_usd', severity: 'warn' } },
        ];
        const result: AssistResult = {
            intent: 'propose-decision',
            status: 'OK',
            answer: 'Proposed a decision rule: quarantine suspected high-cost fraud, emit a review signal, and raise an alert. Review and save to activate.',
            citations: [],
            links: [],
            rationale: 'High cost_usd with short duration_s is a common SIM-box / IRSF signature; quarantining + alerting lets an analyst confirm before the records propagate.',
            confidence: 0.72,
            validated: false,
            applyVia: 'decision-rule',
            data: { consequences },
        };
        return json(result);
    };
}

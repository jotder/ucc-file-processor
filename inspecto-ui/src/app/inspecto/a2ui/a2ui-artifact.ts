/**
 * A2UI artifact descriptor (event-signal-backbone plan S4, spike §4.1) — the render-only wire shape
 * the intelligence agent emits on `event: artifact` SSE frames and in `AgentAskResult.artifact`.
 *
 * The descriptor is **data, never code**: `kind` is matched against the closed client-side allowlist
 * in `A2uiRenderComponent` (`text | kpi | chart | data-table`) and anything else degrades to a
 * placeholder — the backend enforces the same allowlist, but the client fails closed on its own.
 * `actions` are declarative intents (spike §4.4); `navigate` renders with its target validated
 * against the real route config; `invoke` (S6 — gated agentic write) renders a confirm-then-apply
 * flow whose target must name an existing, human-authored Decision Rule (`DecisionRulesService`
 * `simulate`/`apply`) — never an arbitrary route or a rule defined ad hoc by the agent.
 */
export interface A2uiArtifact {
    /** Render kind — must be in the client allowlist; unknown kinds degrade to a placeholder. */
    kind: string;
    /** Optional heading rendered above the artifact body. */
    title?: string;
    /** Kind-specific config (e.g. `{type, data, options}` for `chart`) — mapped defensively. */
    config?: Record<string, unknown>;
    /** Nested render-only artifacts, rendered recursively (depth-capped). */
    parts?: A2uiArtifact[];
    /** Declarative action intents — this slice renders `navigate` only. */
    actions?: A2uiAction[];
}

/** A declarative artifact action. `navigate` targets an in-app route; `invoke` (S6) names an existing
 *  Decision Rule to run through the gated dry-run/confirm/apply flow. Any other intent is parsed but
 *  hidden (fail closed). */
export interface A2uiAction {
    label: string;
    intent: 'navigate' | 'invoke' | string;
    /** `navigate`: an in-app route, validated against the router config. `invoke`: a Decision Rule
     *  name, validated only by existing (the simulate/apply calls 404 on an unknown one). */
    target?: string;
}

/** Narrowing guard for loosely-typed agent-emitted JSON values. */
export function isRecord(v: unknown): v is Record<string, unknown> {
    return typeof v === 'object' && v !== null && !Array.isArray(v);
}

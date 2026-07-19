/**
 * A2UI artifact descriptor (event-signal-backbone plan S4, spike §4.1) — the render-only wire shape
 * the intelligence agent emits on `event: artifact` SSE frames and in `AgentAskResult.artifact`.
 *
 * The descriptor is **data, never code**: `kind` is matched against the closed client-side allowlist
 * in `A2uiRenderComponent` (`text | kpi | chart | data-table`) and anything else degrades to a
 * placeholder — the backend enforces the same allowlist, but the client fails closed on its own.
 * `actions` are declarative intents (spike §4.4); only `navigate` renders in this slice (`invoke` is
 * S6) and its target is validated against the real route config before it becomes clickable.
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

/** A declarative artifact action. Only `intent: 'navigate'` is rendered this slice; other intents
 *  (e.g. the S6 `invoke`) are parsed but hidden. */
export interface A2uiAction {
    label: string;
    intent: 'navigate' | string;
    /** In-app route target for `navigate` — validated against the router config before rendering. */
    target?: string;
}

/** Narrowing guard for loosely-typed agent-emitted JSON values. */
export function isRecord(v: unknown): v is Record<string, unknown> {
    return typeof v === 'object' && v !== null && !Array.isArray(v);
}

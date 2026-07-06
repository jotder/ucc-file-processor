package com.gamma.agent.kernel.model;

/**
 * Model capability tier. A capability declares the tier it needs; a {@link ModelRouter} resolves the
 * tier to a concrete provider. Keeping capabilities tier-bound (not model-bound) lets a deployment
 * re-map hardware without touching capability code.
 *
 * <ul>
 *   <li>{@link #SMALL} — fast, small (~2-3B): extraction, classification, short narrative.</li>
 *   <li>{@link #MEDIUM} — 7-8B: judgement, explanation, SQL.</li>
 *   <li>{@link #LARGE} — 14B+: the hardest reasoning; the top escalation rung.</li>
 * </ul>
 */
public enum ModelTier {
    SMALL,
    MEDIUM,
    LARGE
}

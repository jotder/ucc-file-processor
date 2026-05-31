package com.gamma.agent.model;

/**
 * Model capability tier (v3.3.0). A skill declares the tier it needs; the {@link ModelRouter}
 * resolves the tier to a concrete model+endpoint per the active {@link AssistProfile}. Keeping
 * skills tier-bound (not model-bound) is what lets a deployment re-map hardware without touching
 * skill code (V-8).
 *
 * <ul>
 *   <li>{@link #SMALL} — fast, small (~2-3B): extraction, classification, short narrative,
 *       column descriptions. Snappy even on CPU.</li>
 *   <li>{@link #MEDIUM} — 7-8B: judgement, explanation, SQL. The default for {@code explain-entity}.</li>
 *   <li>{@link #LARGE} — 14B+: the hardest reasoning (the future {@code kpi-to-sql} hero);
 *       a production-GPU upgrade, falls back to {@link #MEDIUM} on smaller profiles.</li>
 * </ul>
 */
public enum ModelTier {
    SMALL,
    MEDIUM,
    LARGE
}

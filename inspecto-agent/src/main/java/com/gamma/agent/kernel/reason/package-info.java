/**
 * Reasoning machinery: {@code ConfidenceEstimator}, {@code EscalationPolicy} with sealed
 * {@code EscalationRung} (BumpModelTier / HumanHandoff / Abstain), {@code Deadline},
 * {@code RepairLoop}. The K1 ingredients of the orchestrator; the assembled pipeline ships at R1.
 *
 * <p>Implemented in K1.
 */
package com.gamma.agent.kernel.reason;

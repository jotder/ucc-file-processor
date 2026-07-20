package com.gamma.intelligence.pack;

import com.eoiagent.app.PackConfig;
import com.eoiagent.core.DeploymentProfile;
import com.eoiagent.core.Feature;
import com.gamma.intelligence.action.AgentApprovals;

import java.util.Map;

/**
 * P0/P3 configuration: always {@link DeploymentProfile#OFFLINE} (Personal/air-gap default — the
 * embedded-intelligence pack has no hosted-provider companion module yet, mirrors
 * {@code inspecto-agent-hosted} as a later phase). {@code MCP_TOOLS} stays off.
 *
 * <p>{@code MUTATING_ACTIONS} (AGT-5 P3, autonomy ladder L2) tracks the opt-in
 * {@link AgentApprovals#enabled()} kill-switch: off by default (L0/L1 — read + draft only), and even
 * when on it only <em>unhides</em> the mutating belt — every mutating call still fails closed through
 * the approvals gate ({@code docs/superpower/embedded-intelligence-plan.md} §6). The handler that
 * makes the gate non-headless is wired in lockstep by {@code InspectoIntelligenceAgent.start()}.
 */
final class InspectoPackConfig implements PackConfig {

    @Override
    public DeploymentProfile profile() {
        return DeploymentProfile.OFFLINE;
    }

    @Override
    public Map<Feature, Boolean> featureOverrides() {
        return Map.of(Feature.MUTATING_ACTIONS, AgentApprovals.enabled(), Feature.MCP_TOOLS, false);
    }

    @Override
    public Map<String, String> configDefaults() {
        return Map.of("eoiagent.profile", "OFFLINE");
    }
}

package com.gamma.intelligence.pack;

import com.eoiagent.app.PackConfig;
import com.eoiagent.core.DeploymentProfile;
import com.eoiagent.core.Feature;

import java.util.Map;

/**
 * P0 configuration: always {@link DeploymentProfile#OFFLINE} (Personal/air-gap default — the
 * embedded-intelligence pack has no hosted-provider companion module yet, mirrors
 * {@code inspecto-agent-hosted} as a later phase). {@code MUTATING_ACTIONS}/{@code MCP_TOOLS} stay
 * off: P0 is autonomy-ladder level L0 (read tools only) per
 * {@code docs/superpower/embedded-intelligence-plan.md} §6.
 */
final class InspectoPackConfig implements PackConfig {

    @Override
    public DeploymentProfile profile() {
        return DeploymentProfile.OFFLINE;
    }

    @Override
    public Map<Feature, Boolean> featureOverrides() {
        return Map.of(Feature.MUTATING_ACTIONS, false, Feature.MCP_TOOLS, false);
    }

    @Override
    public Map<String, String> configDefaults() {
        return Map.of("eoiagent.profile", "OFFLINE");
    }
}

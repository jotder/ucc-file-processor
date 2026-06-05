package com.gamma.agent.skill;

import com.gamma.agentkernel.agent.AgentContext;
import com.gamma.agentkernel.error.AgentError;
import com.gamma.agentkernel.tool.Tool;
import com.gamma.agentkernel.tool.ToolResult;
import com.gamma.agentkernel.tool.ToolSpec;
import com.gamma.config.spec.Finding;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The kernel-SPI {@link Tool} wrapper around the deterministic {@link AlertRuleValidator} oracle. It
 * is registered in {@code UccAgentContext.tools()} and declared in the {@code allowedTools} of
 * {@code diagnose-and-alert}.
 *
 * <p>{@code diagnose-and-alert}'s generate→validate→repair loop keeps calling
 * {@link AlertRuleValidator} directly so the loop retains the verbatim findings; this wrapper exists
 * so the capability's tool grant is representable in the kernel vocabulary.
 */
public final class AlertRuleTool implements Tool {

    public static final String ID = "alert-rule";

    private static final ToolSpec SPEC = new ToolSpec(ID, 1,
            "Validate the shape of a drafted alert rule (required fields, enum membership, numeric "
                    + "bounds, catalog-grounded onPipeline); returns the ERROR findings (empty when valid).",
            Duration.ofSeconds(5));

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolResult invoke(Map<String, Object> args, AgentContext ctx) throws AgentError {
        if (args == null || !(args.get("rule") instanceof Map<?, ?> rule)) {
            return ToolResult.noData();
        }
        Set<String> known = (args.get("knownPipelines") instanceof Set<?> s)
                ? (Set<String>) s : Set.of();
        List<Finding> findings = AlertRuleValidator.check((Map<String, Object>) rule, known);
        return ToolResult.of(findings, List.of());
    }
}

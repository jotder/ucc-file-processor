package com.gamma.agent.skill;

import com.gamma.agent.kernel.agent.AgentContext;
import com.gamma.agent.kernel.error.AgentError;
import com.gamma.agent.kernel.tool.CredibilityTier;
import com.gamma.agent.kernel.tool.Evidence;
import com.gamma.agent.kernel.tool.Tool;
import com.gamma.agent.kernel.tool.ToolResult;
import com.gamma.agent.kernel.tool.ToolSpec;
import com.gamma.sql.SqlOracle;
import com.gamma.sql.SqlSandboxPolicy;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * The kernel-SPI {@link Tool} wrapper around the deterministic {@link SqlOracle} (the lean-core SQL
 * sandbox). It is registered in {@code UccAgentContext.tools()} and declared in the
 * {@code allowedTools} of {@code kpi-to-sql} / {@code report-sql}.
 *
 * <p>The skills' own generate→validate→repair loops keep calling {@link SqlOracle} directly so the
 * loop retains full-fidelity error feedback; this wrapper exists so the capability's tool grant is
 * representable in the kernel vocabulary and a future orchestrator can route through {@link #invoke}.
 */
public final class SqlOracleTool implements Tool {

    public static final String ID = "sql-oracle";

    private static final ToolSpec SPEC = new ToolSpec(ID, 1,
            "Validate a single read-only DuckDB SELECT/WITH in a sealed, file-access-denied sandbox "
                    + "(EXPLAIN + LIMIT 0); returns the authoritative output columns.",
            Duration.ofSeconds(30));

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolResult invoke(Map<String, Object> args, AgentContext ctx) throws AgentError {
        if (args == null) return ToolResult.noData();
        Object sql = args.get("sql");
        if (!(sql instanceof String s) || s.isBlank()) return ToolResult.noData();

        boolean sampleRows = Boolean.TRUE.equals(args.get("sampleRows"));
        SqlOracle oracle = new SqlOracle(SqlSandboxPolicy.defaultPolicy());
        SqlOracle.Result result;
        Object views = args.get("views");
        Object tables = args.get("tables");
        if (views instanceof List<?> v) {
            result = oracle.validate(new SqlOracle.Request(s, (List<SqlOracle.ViewSpec>) v, sampleRows));
        } else if (tables instanceof List<?> t) {
            result = oracle.validate(SqlOracle.Request.ofTables(s, (List<SqlOracle.TableData>) t, sampleRows));
        } else {
            result = oracle.validate(new SqlOracle.Request(s, List.of(), sampleRows));
        }

        if (!result.ok()) return ToolResult.noData();
        return ToolResult.of(result.columnsProduced(), List.of(new Evidence(result.columnsProduced(),
                CredibilityTier.DERIVED, "oracle", "sql-sandbox:explain+limit0", 1.0, null)));
    }
}

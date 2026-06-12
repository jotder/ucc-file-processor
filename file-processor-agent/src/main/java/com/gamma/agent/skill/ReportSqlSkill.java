package com.gamma.agent.skill;

import static com.gamma.agent.skill.SkillInputs.asBool;
import static com.gamma.agent.skill.SkillInputs.firstNonBlank;
import static com.gamma.agent.skill.SkillInputs.orDefault;
import static com.gamma.agent.skill.SkillInputs.str;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.agentkernel.agent.AgentContext;
import com.gamma.agentkernel.agent.AgentRequest;
import com.gamma.agentkernel.agent.AgentResult;
import com.gamma.agentkernel.agent.Capability;
import com.gamma.agentkernel.agent.CapabilitySpec;
import com.gamma.agentkernel.error.AgentError;
import com.gamma.agentkernel.model.ModelProvider;
import com.gamma.agentkernel.model.ModelRequest;
import com.gamma.agentkernel.model.ModelTier;
import com.gamma.agentkernel.reason.RepairLoop;
import com.gamma.agentkernel.tool.CredibilityTier;
import com.gamma.agentkernel.tool.Evidence;
import com.gamma.enrich.EnrichmentConfig;
import com.gamma.etl.PipelineConfig;
import com.gamma.sql.SqlOracle;
import com.gamma.sql.SqlSandboxPolicy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code report-sql} (M8 / v3.8.0, B2): describe a report over the platform's <b>operational</b>
 * audit/status data in plain language and the agent writes the read-only DuckDB query that answers it
 * — validated to <em>run</em> before a human sees it. The "remember the audit-CSV column names and
 * hand-write the SQL" replacement, and a programmable substitute for a report-builder UI.
 *
 * <h3>Operational tables, not catalog partitions</h3>
 * The sibling {@code kpi-to-sql} validates SQL over the catalog's data partitions; this skill validates
 * over the platform's own ledgers — {@code batches} / {@code files} / {@code lineage} /
 * {@code quarantine} for a pipeline, {@code enrich_runs} / {@code enrich_lineage} for an enrichment job.
 * Those reach the agent as header→value row maps through the backend-agnostic {@link com.gamma.service.StatusStore}
 * / {@link com.gamma.enrich.EnrichmentAuditReader} seams, so {@link OperationalTables} materialises them
 * as in-memory {@link SqlOracle.TableData}s (all-{@code VARCHAR}; the SQL {@code CAST}s as needed) and
 * the M6 {@link SqlOracle} registers them in a sealed sandbox.
 *
 * <h3>Generate → validate → repair, behind the SQL sandbox</h3>
 * The model proposes a single read-only {@code SELECT}/{@code WITH} over the resolved table names; the
 * {@link RepairLoop} runs it through the {@link SqlOracle} (lexical {@code SqlGuard} <em>plus</em> a
 * sealed, file-access-denied {@code EXPLAIN} + {@code LIMIT 0}). A query that reads a file, writes
 * output, loads an extension, or won't plan is rejected and the verbatim reason fed back — never
 * surfaced. The authoritative column list comes from the oracle, not the model's claim.
 *
 * <h3>Safety &amp; scope</h3>
 * Read-only / draft-only (V-9): {@code applyVia} is always {@code null}; the skill holds no write token
 * and the sandbox executes nothing beyond a sealed {@code EXPLAIN}/{@code LIMIT}. Sample rows are
 * opt-in (the default response carries no data-plane values), mirroring M6.
 *
 * @since 3.8.0
 */
public final class ReportSqlSkill implements Capability {

    public static final String ID = "report-sql";

    private static final CapabilitySpec SPEC = new CapabilitySpec(ID, 1,
            "Write a validated read-only SQL query over the platform's operational audit/status tables.",
            ModelTier.MEDIUM, com.gamma.agent.model.AssistTunables.confidenceThreshold(0.5), java.time.Duration.ofSeconds(60),
            java.util.Set.of(), java.util.Set.of("sql-oracle"));

    private static final int MAX_REPAIR_ROUNDS = com.gamma.agent.model.AssistTunables.repairRounds(3);

    private static final String SYSTEM = """
            You write a single, read-only DuckDB SQL query that answers a question about a data
            pipeline's OPERATIONAL audit/status history. Reply with ONLY a JSON object (no prose, no
            markdown):
              {"sql":"<a single SELECT or WITH ... SELECT>",
               "logicExplanation":"<how the query answers the question, in plain English>"}
            Hard rules for "sql": reference ONLY the table names listed under AVAILABLE TABLES, by name
            only. Every column is typed VARCHAR — CAST to integer/double/timestamp when you aggregate,
            compare, or sort numerically/chronologically (e.g. CAST(total_output_rows AS BIGINT),
            CAST(start_time AS TIMESTAMP)). NEVER call read_csv/read_parquet/copy/install/load/attach/
            getenv or any file/extension/system function. No semicolons, no DDL/DML — exactly one
            read-only SELECT (a leading WITH is fine).""";

    private final ObjectMapper json = new ObjectMapper();
    private final SqlSandboxPolicy sandboxPolicy;

    public ReportSqlSkill() {
        this(SqlSandboxPolicy.defaultPolicy());
    }

    ReportSqlSkill(SqlSandboxPolicy sandboxPolicy) {
        this.sandboxPolicy = sandboxPolicy;
    }

    @Override public CapabilitySpec spec() { return SPEC; }

    @Override
    public AgentResult run(AgentRequest request, AgentContext context) throws AgentError {
        UccAgentContext ctx = (UccAgentContext) context;
        ModelTier tier = ctx.effectiveTier(SPEC.defaultTier());
        String question = firstNonBlank(
                request.context("userText"),
                str(request.partialInput().get("question")),
                str(request.partialInput().get("userText")),
                request.userText());
        if (question == null) {
            return AgentResult.unavailable(ID,
                    "report-sql needs a question (the report in plain language) in userText, "
                            + "screenContext, or partialInput");
        }
        boolean sampleRows = asBool(firstNonBlank(request.context("sampleRows"),
                str(request.partialInput().get("sampleRows"))));

        String pipelineName = firstNonBlank(request.context("pipeline"),
                str(request.partialInput().get("pipeline")));
        String jobName = firstNonBlank(request.context("job"),
                str(request.partialInput().get("job")));

        // Resolve the named pipeline/job into operational tables (grounding — a name that does not
        // resolve cannot be queried, so the model can never invent one).
        List<SqlOracle.TableData> tables = new ArrayList<>();
        List<Evidence> evidence = new ArrayList<>();
        List<String> links = new ArrayList<>();

        if (pipelineName != null) {
            Optional<PipelineConfig> cfg = OperationalTables.pipeline(ctx.configs(), pipelineName);
            if (cfg.isEmpty()) {
                return AgentResult.unavailable(ID, "no pipeline named '" + pipelineName + "'. "
                        + availableHint(ctx));
            }
            tables.addAll(OperationalTables.stage1(ctx.statusStore(), cfg.get()));
            String id = cfg.get().identity().name();
            evidence.add(new Evidence(id, CredibilityTier.AUTHORITATIVE, "pipeline", id, 1.0, null));
            links.add("/catalog/tables/" + id);
        }
        if (jobName != null) {
            Optional<EnrichmentConfig> ec = OperationalTables.enrichment(ctx.configs(), jobName);
            if (ec.isEmpty()) {
                return AgentResult.unavailable(ID, "no enrichment job named '" + jobName + "'. "
                        + availableHint(ctx));
            }
            tables.addAll(OperationalTables.stage2(ec.get()));
            String jid = ec.get().name();
            evidence.add(new Evidence(jid, CredibilityTier.AUTHORITATIVE, "job", jid, 1.0, null));
        }

        if (tables.isEmpty()) {
            return AgentResult.unavailable(ID, "report-sql needs a 'pipeline' (and/or 'job') to "
                    + "report on. " + availableHint(ctx));
        }

        ModelProvider model = ctx.models().providerFor(tier);
        if (!model.available()) {
            return AgentResult.unavailable(ID,
                    "the assist model (tier " + tier + ") is not available — enable the assist layer "
                            + "and a 7B-capable endpoint (or a hosted provider) to use report-sql");
        }

        List<String> tableDescriptions = new ArrayList<>();
        List<String> tableNames = new ArrayList<>();
        for (SqlOracle.TableData t : tables) {
            tableDescriptions.add(OperationalTables.describe(t));
            tableNames.add(t.name());
        }
        String basePrompt = "QUESTION: " + question
                + "\n\nAVAILABLE TABLES (reference by name only; all columns are VARCHAR):\n- "
                + String.join("\n- ", tableDescriptions);

        final boolean wantSample = sampleRows;
        final List<SqlOracle.TableData> oracleTables = List.copyOf(tables);
        SqlOracle oracle = new SqlOracle(sandboxPolicy);

        RepairLoop.Result<Draft> result = RepairLoop.run(MAX_REPAIR_ROUNDS,
                feedback -> {
                    String prompt = (feedback == null) ? basePrompt : basePrompt + "\n\n" + feedback;
                    return model.generate(ModelRequest.json(tier, SYSTEM, prompt)).text();
                },
                raw -> parseAndValidate(raw, oracle, oracleTables, wantSample));

        if (!result.ok()) {
            String last = result.errors().isEmpty() ? "unknown error"
                    : result.errors().get(result.errors().size() - 1);
            return AgentResult.unavailable(ID,
                    "could not produce a valid, safe SQL draft after " + result.rounds()
                            + " attempt(s): " + last);
        }

        Draft d = result.value();
        evidence.add(new Evidence("sql-sandbox:explain+limit0", CredibilityTier.DERIVED, "oracle",
                "sql-sandbox:explain+limit0", 1.0, null));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sql", d.sql);
        data.put("logicExplanation", d.logicExplanation);
        data.put("columnsProduced", d.columnsProduced);   // authoritative, from the oracle
        data.put("tablesUsed", tableNames);
        data.put("validated", true);
        data.put("repaired", result.repaired());
        if (wantSample) data.put("sampleRows", d.sampleRows);

        String answer = "Generated a validated read-only SQL draft over the operational tables; it "
                + "planned clean (" + d.columnsProduced.size() + " output column(s)). The oracle proves "
                + "the query runs, not that it answers exactly what you meant — review the logic before "
                + "using it.";

        return AgentResult.draft(ID, SPEC.version(), answer, evidence, links, null, 1.0, tier, data);
    }

    // ── oracle: parse JSON → validate the SQL in the sandbox ────────────────────────────

    private record Draft(String sql, String logicExplanation, List<String> columnsProduced,
                         List<Map<String, Object>> sampleRows) {}

    private Draft parseAndValidate(String raw, SqlOracle oracle, List<SqlOracle.TableData> tables,
                                   boolean sampleRows) throws Exception {
        JsonNode root = json.readTree(raw);
        String sql = text(root, "sql");
        if (sql == null) {
            throw new IllegalArgumentException("missing required 'sql' (a single read-only SELECT/WITH)");
        }

        SqlOracle.Result r = oracle.validate(SqlOracle.Request.ofTables(sql, tables, sampleRows));
        if (!r.ok()) {
            throw new IllegalArgumentException("the SQL was rejected by the sandbox oracle: " + r.error());
        }

        return new Draft(sql, orDefault(text(root, "logicExplanation"), ""),
                r.columnsProduced(), r.sampleRows());
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────

    private static String availableHint(UccAgentContext ctx) {
        List<String> pipelines = new ArrayList<>();
        List<String> jobs = new ArrayList<>();
        if (ctx.configs() != null) {
            ctx.configs().pipelines().forEach(p -> pipelines.add(p.identity().name()));
            ctx.configs().enrichments().forEach(e -> jobs.add(e.name()));
        }
        return "Available pipelines: " + (pipelines.isEmpty() ? "(none)" : String.join(", ", pipelines))
                + (jobs.isEmpty() ? "" : "; jobs: " + String.join(", ", jobs));
    }

    private static String text(JsonNode root, String field) {
        JsonNode v = root.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText();
        return (s == null || s.isBlank()) ? null : s.trim();
    }

}

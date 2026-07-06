package com.gamma.agent.skill;

import static com.gamma.agent.skill.SkillInputs.asBool;
import static com.gamma.agent.skill.SkillInputs.firstNonBlank;
import static com.gamma.agent.skill.SkillInputs.orDefault;
import static com.gamma.agent.skill.SkillInputs.str;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.agent.kernel.agent.AgentContext;
import com.gamma.agent.kernel.agent.AgentRequest;
import com.gamma.agent.kernel.agent.AgentResult;
import com.gamma.agent.kernel.agent.Capability;
import com.gamma.agent.kernel.agent.CapabilitySpec;
import com.gamma.agent.kernel.error.AgentError;
import com.gamma.agent.kernel.model.ModelProvider;
import com.gamma.agent.kernel.model.ModelRequest;
import com.gamma.agent.kernel.model.ModelTier;
import com.gamma.agent.kernel.reason.RepairLoop;
import com.gamma.agent.kernel.tool.CredibilityTier;
import com.gamma.agent.kernel.tool.Evidence;
import com.gamma.catalog.MetadataGraphService;
import com.gamma.catalog.MetadataNode;
import com.gamma.catalog.NodeKind;
import com.gamma.sql.SqlOracle;
import com.gamma.sql.SqlSandboxPolicy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The hero skill (v3.6.0, B1): {@code kpi-to-sql} — describe a business KPI in domain terms and the
 * agent writes the Stage-2 enrichment <b>transformation SQL</b>, grounded in the catalog and validated
 * to <em>run</em> before a human ever sees it. The "write the transform SQL by hand against half-known
 * schemas" replacement.
 *
 * <h3>Generate → validate → repair, behind the SQL sandbox</h3>
 * The model proposes a single read-only {@code SELECT}/{@code WITH} over named catalog tables; the
 * skill runs it through a {@link RepairLoop} whose oracle is the {@link SqlOracle} — a lexical
 * allow-list ({@code SqlGuard}) <em>plus</em> a sealed, file-access-denied DuckDB connection that
 * {@code EXPLAIN}s + {@code LIMIT 0}s the query against the <em>real</em> partitions (security guardrail
 * G4). A query that reads a file, writes output, loads an extension, or simply won't plan is rejected
 * and the verbatim reason fed back — it can never be surfaced. The authoritative column list comes from
 * the oracle, not the model's claim.
 *
 * <h3>Confirm-first (the oracle proves it runs, not that it's right)</h3>
 * {@code EXPLAIN} guards structure, not semantics: a wrong join key, a double-count, or
 * COUNT-vs-COUNT-DISTINCT all plan clean. So the draft surfaces {@code kpiInterpretation} +
 * {@code chosenJoinKeys} (and, on request, preview {@code sampleRows}) for a human to confirm the
 * meaning. Local 14B is best-effort; hosted is recommended in connected mode.
 *
 * <h3>Safety & scope</h3>
 * Draft-only (V-9): {@code applyVia} is always {@code null}; the user saves the returned SQL /
 * {@code enrichmentConfigSnippet}. The skill holds no write token and the sandbox executes nothing
 * beyond a read-only, file-denied {@code EXPLAIN}/{@code LIMIT}.
 *
 * @since 3.6.0
 */
public final class KpiToSqlSkill implements Capability {

    public static final String ID = "kpi-to-sql";

    private static final CapabilitySpec SPEC = new CapabilitySpec(ID, 1,
            "Write a validated, catalog-grounded Stage-2 transformation SQL for a business KPI.",
            ModelTier.LARGE, com.gamma.agent.model.AssistTunables.confidenceThreshold(0.5), java.time.Duration.ofSeconds(60),
            java.util.Set.of(), java.util.Set.of("sql-oracle"));

    private static final int MAX_REPAIR_ROUNDS = com.gamma.agent.model.AssistTunables.repairRounds(3);

    private static final String SYSTEM = """
            You write a single, read-only DuckDB SQL query that computes a business KPI as a Stage-2
            transformation. Reply with ONLY a JSON object (no prose, no markdown):
              {"sql":"<a single SELECT or WITH ... SELECT>",
               "logicExplanation":"<how the query computes the KPI, in plain English>",
               "columnsProduced":["<output column>", ...],
               "chosenJoinKeys":["<the join key(s) you used>", ...],
               "kpiInterpretation":"<your reading of what the KPI means, for the human to confirm>",
               "enrichmentConfigSnippet":"<the transform SQL to paste into an enrichment .toon>"}
            Hard rules for "sql": reference ONLY the table names listed under AVAILABLE TABLES; refer to
            them by name only. NEVER call read_csv/read_parquet/copy/install/load/attach/getenv or any
            file/extension/system function. No semicolons, no DDL/DML — exactly one read-only SELECT (a
            leading WITH is fine). Prefer the join keys and grain named in the KPI CATALOG.""";

    private final ObjectMapper json = new ObjectMapper();
    private final SqlSandboxPolicy sandboxPolicy;

    public KpiToSqlSkill() {
        this(SqlSandboxPolicy.defaultPolicy());
    }

    KpiToSqlSkill(SqlSandboxPolicy sandboxPolicy) {
        this.sandboxPolicy = sandboxPolicy;
    }

    @Override public CapabilitySpec spec() { return SPEC; }

    @Override
    public AgentResult run(AgentRequest request, AgentContext context) throws AgentError {
        UccAgentContext ctx = (UccAgentContext) context;
        ModelTier tier = ctx.effectiveTier(SPEC.defaultTier());
        String kpiDescription = firstNonBlank(
                request.context("kpiDescription"),
                str(request.partialInput().get("kpiDescription")),
                request.userText());
        if (kpiDescription == null) {
            return AgentResult.unavailable(ID,
                    "kpi-to-sql needs a 'kpiDescription' (the KPI in domain terms) in screenContext, "
                            + "partialInput, or userText");
        }
        String targetGrain = firstNonBlank(request.context("targetGrain"),
                str(request.partialInput().get("targetGrain")));
        String domainNotes = firstNonBlank(request.context("domainNotes"),
                str(request.partialInput().get("domainNotes")));
        boolean sampleRows = asBool(firstNonBlank(request.context("sampleRows"),
                str(request.partialInput().get("sampleRows"))));

        List<String> refs = catalogRefs(request);
        if (refs.isEmpty()) {
            return AgentResult.unavailable(ID,
                    "kpi-to-sql needs 'catalogRefs' (catalog node ids for the event/reference/"
                            + "transformed tables and KPIs to ground on)");
        }

        // Resolve refs into data views (for the oracle) and KPI grounding (for the prompt).
        List<SqlOracle.ViewSpec> views = new ArrayList<>();
        List<String> resolvedIds = new ArrayList<>();
        List<String> tableNames = new ArrayList<>();
        StringBuilder kpiCatalog = new StringBuilder();
        MetadataGraphService catalog = ctx.catalog();
        for (String ref : refs) {
            MetadataNode n = catalog.node(ref);
            if (n == null) continue;
            resolvedIds.add(n.id());
            SqlOracle.ViewSpec v = viewSpecFor(n);
            if (v != null) {
                views.add(v);
                tableNames.add(v.name());
            } else if (n.kind() == NodeKind.KPI) {
                kpiCatalog.append("- ").append(n.label()).append(": ")
                        .append(strAttr(n, "definition"));
                Object grain = n.attrs().get("grain");
                if (grain != null) kpiCatalog.append(" [grain: ").append(grain).append("]");
                Object jk = n.attrs().get("joinKeys");
                if (jk != null) kpiCatalog.append(" [join keys: ").append(jk).append("]");
                kpiCatalog.append('\n');
            }
        }
        if (views.isEmpty()) {
            return AgentResult.unavailable(ID,
                    "none of the catalogRefs resolve to a data table (event/reference/transformed); "
                            + "kpi-to-sql needs at least one to validate against");
        }

        ModelProvider model = ctx.models().providerFor(tier);
        if (!model.available()) {
            return AgentResult.unavailable(ID,
                    "the assist model (tier " + tier + ") is not available — enable the assist layer "
                            + "and a 14B-capable endpoint (or a hosted provider) to use kpi-to-sql");
        }

        String basePrompt = "KPI DESCRIPTION: " + kpiDescription
                + (targetGrain == null ? "" : "\nTARGET GRAIN: " + targetGrain)
                + "\n\nAVAILABLE TABLES (reference by name only): " + String.join(", ", tableNames)
                + (kpiCatalog.length() == 0 ? "" : "\n\nKPI CATALOG:\n" + kpiCatalog)
                + (domainNotes == null ? "" : "\n\nDOMAIN NOTES: " + domainNotes);

        final boolean wantSample = sampleRows;
        final List<SqlOracle.ViewSpec> viewSpecs = List.copyOf(views);
        SqlOracle oracle = new SqlOracle(sandboxPolicy);

        RepairLoop.Result<Draft> result = RepairLoop.run(MAX_REPAIR_ROUNDS,
                feedback -> {
                    String prompt = (feedback == null) ? basePrompt : basePrompt + "\n\n" + feedback;
                    return model.generate(ModelRequest.json(tier, SYSTEM, prompt)).text();
                },
                raw -> parseAndValidate(raw, oracle, viewSpecs, wantSample));

        if (!result.ok()) {
            String last = result.errors().isEmpty() ? "unknown error"
                    : result.errors().get(result.errors().size() - 1);
            return AgentResult.unavailable(ID,
                    "could not produce a valid, safe SQL draft after " + result.rounds()
                            + " attempt(s): " + last);
        }

        Draft d = result.value();

        List<Evidence> evidence = new ArrayList<>();
        List<String> links = new ArrayList<>();
        for (String id : resolvedIds) {
            evidence.add(new Evidence(id, CredibilityTier.AUTHORITATIVE, "catalog", id, 1.0, null));
            links.add("/catalog/nodes/" + id);
        }
        evidence.add(new Evidence("sql-sandbox:explain+limit0", CredibilityTier.DERIVED, "oracle",
                "sql-sandbox:explain+limit0", 1.0, null));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sql", d.sql);
        data.put("logicExplanation", d.logicExplanation);
        data.put("columnsProduced", d.columnsProduced);   // authoritative, from the oracle
        data.put("chosenJoinKeys", d.chosenJoinKeys);
        data.put("kpiInterpretation", d.kpiInterpretation);
        data.put("validated", true);
        data.put("enrichmentConfigSnippet", d.enrichmentConfigSnippet);
        data.put("repaired", result.repaired());
        data.put("tablesUsed", tableNames);
        if (wantSample) data.put("sampleRows", d.sampleRows);

        String answer = "Generated a validated Stage-2 SQL draft for the KPI; it planned clean against "
                + "the catalog partitions (" + d.columnsProduced.size() + " output column(s)). "
                + "Confirm the interpretation and chosen join keys before using it — the oracle proves "
                + "the query runs, not that it computes the KPI correctly.";

        return AgentResult.draft(ID, SPEC.version(), answer, evidence, links, null, 1.0, tier, data);
    }

    // ── oracle: parse JSON → validate the SQL in the sandbox ────────────────────────────

    private record Draft(String sql, String logicExplanation, List<String> columnsProduced,
                         List<String> chosenJoinKeys, String kpiInterpretation,
                         String enrichmentConfigSnippet, List<Map<String, Object>> sampleRows) {}

    private Draft parseAndValidate(String raw, SqlOracle oracle, List<SqlOracle.ViewSpec> views,
                                   boolean sampleRows) throws Exception {
        JsonNode root = json.readTree(raw);
        String sql = text(root, "sql");
        if (sql == null) {
            throw new IllegalArgumentException("missing required 'sql' (a single read-only SELECT/WITH)");
        }

        SqlOracle.Result r = oracle.validate(new SqlOracle.Request(sql, views, sampleRows));
        if (!r.ok()) {
            throw new IllegalArgumentException("the SQL was rejected by the sandbox oracle: " + r.error());
        }

        return new Draft(sql,
                orDefault(text(root, "logicExplanation"), ""),
                r.columnsProduced(),                                  // authoritative
                stringList(root.get("chosenJoinKeys")),
                orDefault(text(root, "kpiInterpretation"), ""),
                orDefault(text(root, "enrichmentConfigSnippet"), sql),
                r.sampleRows());
    }

    // ── catalog → view specs ───────────────────────────────────────────────────────────

    /** A data view for the oracle, or {@code null} if the node is not a queryable table. */
    private static SqlOracle.ViewSpec viewSpecFor(MetadataNode n) {
        return switch (n.kind()) {
            case REFERENCE_DATASET -> {
                String path = strAttr(n, "path");
                if (path.isBlank()) yield null;
                yield new SqlOracle.ViewSpec(n.label(), formatOr(n, "CSV"), path, false);
            }
            case TABLE -> {
                String root = strAttr(n, "outputGlob");
                if (root.isBlank()) yield null;
                String fmt = formatOr(n, "PARQUET");
                yield new SqlOracle.ViewSpec(n.label(), fmt, glob(root, fmt), true);
            }
            case DERIVED_TABLE -> {
                String root = strAttr(n, "outputDb");
                if (root.isBlank()) yield null;
                String fmt = formatOr(n, "PARQUET");
                yield new SqlOracle.ViewSpec(n.label(), fmt, glob(root, fmt), true);
            }
            default -> null; // SOURCE / RAW_SCHEMA / COLUMN / KPI / REPORT are not data views
        };
    }

    private static String glob(String root, String format) {
        String ext = "PARQUET".equals(format) ? "parquet" : "csv";
        String r = root.replace('\\', '/');
        if (r.endsWith("/")) r = r.substring(0, r.length() - 1);
        return r + "/**/*." + ext;
    }

    private static String formatOr(MetadataNode n, String fallback) {
        String f = strAttr(n, "format");
        return f.isBlank() ? fallback : f.trim().toUpperCase();
    }

    private static String strAttr(MetadataNode n, String key) {
        Object v = n.attrs().get(key);
        return v == null ? "" : v.toString();
    }

    // ── input helpers ──────────────────────────────────────────────────────────────────

    private static List<String> catalogRefs(AgentRequest request) {
        Object v = request.partialInput().get("catalogRefs");
        if (v == null) v = request.screenContext().get("catalogRefs");
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> l) {
            for (Object o : l) if (o != null && !o.toString().isBlank()) out.add(o.toString().trim());
        } else if (v instanceof String s && !s.isBlank()) {
            for (String part : s.split(",")) if (!part.isBlank()) out.add(part.trim());
        }
        return out;
    }

    private List<String> stringList(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode e : node) {
                if (e != null && !e.isNull() && !e.asText().isBlank()) out.add(e.asText().trim());
            }
        }
        return out;
    }

    private static String text(JsonNode root, String field) {
        JsonNode v = root.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText();
        return (s == null || s.isBlank()) ? null : s.trim();
    }

}

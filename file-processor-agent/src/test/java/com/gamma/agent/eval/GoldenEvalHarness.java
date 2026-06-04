package com.gamma.agent.eval;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.agent.AgentTestConfigs;
import com.gamma.agent.UccAssistAgent;
import com.gamma.agent.model.FakeModelProvider;
import com.gamma.agent.model.ModelProvider;
import com.gamma.agent.model.ModelRouter;
import com.gamma.assist.AssistRequest;
import com.gamma.assist.AssistResult;
import com.gamma.service.SourceService;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Runs declarative {@link EvalCase} fixtures against the real {@link UccAssistAgent} in-process,
 * CPU-only (a {@link FakeModelProvider}, no Ollama). The regression net U0 lays down <em>before</em> the
 * U1 kernel reshape — the same fixtures port to the kernel's {@code agent-eval} runner at U1.
 *
 * <p>Each case gets a fresh {@link SourceService} over the {@code mini} pipeline (with a seeded CSV
 * partition so the {@code kpi-to-sql} SQL sandbox can plan), the agent wired via {@code init()}, and one
 * dispatch. {@link #run(EvalCase)} returns {@link Optional#empty()} on pass, else the failure reason.
 */
public final class GoldenEvalHarness {

    // Tolerate annotation fields in fixtures (e.g. "comment").
    private static final ObjectMapper JSON = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private GoldenEvalHarness() {}

    /** Load a JSON array of cases from a classpath resource (e.g. {@code /eval/explain-entity/cases.json}). */
    public static List<EvalCase> load(String resourcePath) {
        try (InputStream in = GoldenEvalHarness.class.getResourceAsStream(resourcePath)) {
            if (in == null) throw new IllegalArgumentException("eval fixture not found: " + resourcePath);
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return JSON.readValue(json, JSON.getTypeFactory()
                    .constructCollectionType(List.class, EvalCase.class));
        } catch (Exception e) {
            throw new RuntimeException("failed to load eval fixture " + resourcePath, e);
        }
    }

    /** Run one case; empty result = pass, else the first failed expectation. */
    public static Optional<String> run(EvalCase c) throws Exception {
        Path dir = Files.createTempDirectory("ucc-eval");
        try {
            Path pipe = AgentTestConfigs.writePipeline(dir);
            seedEventPartition(dir);
            try (SourceService svc = new SourceService(List.of(pipe), 60, 1)) {
                ModelProvider provider = (c.model() == null || c.model().isAvailable())
                        ? FakeModelProvider.canned(c.model() == null ? "" : c.model().response())
                        : FakeModelProvider.down();
                UccAssistAgent agent = new UccAssistAgent(ModelRouter.of(provider), null);
                agent.init(svc);

                EvalCase.Input in = c.input();
                AssistRequest req = new AssistRequest(
                        c.capabilityId(),
                        in == null ? Map.of() : in.screenContext(),
                        in == null ? Map.of() : in.partialInput(),
                        in == null ? null : in.userText());
                return check(c, agent.assist(req));
            }
        } finally {
            deleteRecursively(dir);
        }
    }

    /** Returns the first failed expectation, or empty if the case passes. */
    private static Optional<String> check(EvalCase c, AssistResult res) {
        EvalCase.Expect ex = c.expect();
        if (ex == null) return Optional.empty();

        if (Boolean.TRUE.equals(ex.mustAbstainWhenNoData())
                && res.status() != AssistResult.Status.UNAVAILABLE) {
            return Optional.of("expected abstain (UNAVAILABLE) but was " + res.status() + " — " + res.message());
        }
        if (ex.status() != null && !ex.status().equals(res.status().name())) {
            return Optional.of("status expected " + ex.status() + " but was " + res.status()
                    + " (" + res.message() + ")");
        }
        if (Boolean.TRUE.equals(ex.mustValidate()) && !res.validated()) {
            return Optional.of("expected validated=true");
        }
        if (ex.answerContains() != null
                && (res.answer() == null || !res.answer().contains(ex.answerContains()))) {
            return Optional.of("answer expected to contain '" + ex.answerContains() + "' but was: " + res.answer());
        }
        if (ex.requiredDataKeys() != null && !res.data().keySet().containsAll(ex.requiredDataKeys())) {
            return Optional.of("missing data keys; expected " + ex.requiredDataKeys()
                    + " present " + res.data().keySet());
        }
        if (ex.requiredCitationRefs() != null) {
            Set<String> refs = res.citations().stream()
                    .map(AssistResult.Citation::ref).collect(Collectors.toSet());
            if (!refs.containsAll(ex.requiredCitationRefs())) {
                return Optional.of("missing citation refs; expected " + ex.requiredCitationRefs()
                        + " present " + refs);
            }
        }
        return Optional.empty();
    }

    /** The mini pipeline's Stage-1 output (CSV) lives under {@code <dir>/db}; seed one hive partition. */
    private static void seedEventPartition(Path dir) throws Exception {
        Path part = dir.resolve("db").resolve("EVENT_DATE=2026-01-01");
        Files.createDirectories(part);
        Files.writeString(part.resolve("part-0.csv"), "id,amt\n1,10\n2,20\n3,30\n");
    }

    private static void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) { } });
        } catch (Exception ignored) { /* best-effort temp cleanup */ }
    }
}

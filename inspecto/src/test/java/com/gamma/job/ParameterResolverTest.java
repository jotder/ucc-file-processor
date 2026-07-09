package com.gamma.job;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** P3a parameter resolution (§7): layer order (config → deduce → default), the $-context, and fail-closed
 *  REJECTED on a missing required parameter. Deterministic — a fixed fire time + UTC zone. */
class ParameterResolverTest {

    private static final Instant FIRE = Instant.parse("2026-07-08T06:00:00Z");

    private static ParameterResolver.Context ctx(Optional<LocalDateTime> lastSuccess) {
        return ctx(lastSuccess, (job, name) -> Optional.empty());
    }

    private static ParameterResolver.Context ctx(Optional<LocalDateTime> lastSuccess,
            java.util.function.BiFunction<String, String, Optional<RunArtifact>> upstream) {
        return ctx(lastSuccess, upstream, Map.of());
    }

    private static ParameterResolver.Context ctx(Optional<LocalDateTime> lastSuccess,
            java.util.function.BiFunction<String, String, Optional<RunArtifact>> upstream,
            Map<String, Object> signalPayload) {
        return new ParameterResolver.Context("run-1", FIRE, "cron", ZoneOffset.UTC, () -> lastSuccess,
                upstream, signalPayload);
    }

    private static ParameterResolver.Resolution resolve(List<ParameterDecl> decls,
            Map<String, String> config, ParameterResolver.Context ctx) {
        return ParameterResolver.resolve(decls, Map.of(), Map.of(), config, ctx);
    }

    private static ParameterDecl decl(String name, boolean required, String deduce, String def) {
        return new ParameterDecl(name, ParamType.STRING, required, deduce, def, name);
    }

    @Test
    void deducesTheBuiltInDollarContext() {
        var c = ctx(Optional.of(LocalDateTime.parse("2026-07-07T06:00:04")));
        assertEquals("2026-07-08", ParameterResolver.deduce("$today", c));
        assertEquals("2026-07-07", ParameterResolver.deduce("$day(-1)", c));
        assertEquals("2026-07-09", ParameterResolver.deduce("$day(1)", c));
        assertEquals("2026-06-08", ParameterResolver.deduce("$month(-1)", c));
        assertEquals("2026-07-08T06:00:00Z", ParameterResolver.deduce("$now", c));
        assertEquals("run-1", ParameterResolver.deduce("$run.id", c));
        assertEquals("2026-07-08T06:00:00Z", ParameterResolver.deduce("$run.fire_time", c));
        assertEquals("cron", ParameterResolver.deduce("$run.actor", c));
        assertEquals("2026-07-07T06:00:04Z", ParameterResolver.deduce("$job.last_success_time", c));
        assertNull(ParameterResolver.deduce("$unknown.token", c), "unknown token is unresolved");
    }

    @Test
    void lastSuccessTimeIsNullWhenTheJobNeverSucceeded() {
        assertNull(ParameterResolver.deduce("$job.last_success_time", ctx(Optional.empty())));
    }

    @Test
    void resolvesUpstreamArtifactAttributes() {
        RunArtifact art = new RunArtifact("up-run-9", "loader", 1, "output", "dataset",
                "txn_rollup", null, 4200L, 0L, "2026-07-07T06:00:04Z", "2026-07-01..2026-07-07",
                "2026-07-08T06:00:00Z");
        var c = ctx(Optional.empty(),
                (job, name) -> "loader".equals(job) && "output".equals(name) ? Optional.of(art) : Optional.empty());

        assertEquals("txn_rollup", ParameterResolver.deduce("$upstream(loader).artifact(output).ref", c));
        assertEquals("4200", ParameterResolver.deduce("$upstream(loader).artifact(output).rows", c));
        assertEquals("2026-07-07T06:00:04Z",
                ParameterResolver.deduce("$upstream(loader).artifact(output).watermark", c));
        assertEquals("2026-07-01..2026-07-07",
                ParameterResolver.deduce("$upstream(loader).artifact(output).time_range", c));
        assertNull(ParameterResolver.deduce("$upstream(loader).artifact(missing).ref", c),
                "an absent artifact resolves to null (⇒ REJECTED if the param is required)");
        assertNull(ParameterResolver.deduce("$upstream(loader).artifact(output).bogus_attr", c),
                "an unknown attribute is unresolved");
    }

    @Test
    void layerOrderConfigThenDeduceThenDefault() {
        List<ParameterDecl> decls = List.of(
                decl("event_date", true, "$day(-1)", null),   // deduced (no config value)
                decl("scope", false, null, "status"),          // default
                decl("region", false, null, null));            // unresolved optional ⇒ absent
        var r = resolve(decls, Map.of(), ctx(Optional.empty()));

        assertEquals("2026-07-07", r.resolved().get("event_date"), "deduce fills when config is absent");
        assertEquals("status", r.resolved().get("scope"), "default fills when config + deduce are absent");
        assertFalse(r.resolved().containsKey("region"), "an unresolved optional is simply omitted");
        assertTrue(r.missingRequired().isEmpty());
    }

    @Test
    void authoredConfigWinsOverDeduceAndDefault() {
        List<ParameterDecl> decls = List.of(
                decl("event_date", true, "$day(-1)", null),
                decl("scope", false, null, "status"));
        var r = resolve(decls,
                Map.of("event_date", "2026-01-01", "scope", "batch"), ctx(Optional.empty()));

        assertEquals("2026-01-01", r.resolved().get("event_date"), "authored config beats the deduce");
        assertEquals("batch", r.resolved().get("scope"), "authored config beats the default");
    }

    @Test
    void signalBindResolvesAgainstTheFiringPayload() {
        // P3a-2 (§7.2 layer 2): bind: maps a parameter to a $signal.<field> expression.
        List<ParameterDecl> decls = List.of(
                decl("event_date", true, "$day(-1)", null),   // would deduce yesterday, but bind wins
                decl("findings", true, null, null));
        var c = ctx(Optional.empty(), (j, n) -> Optional.empty(),
                Map.of("event_date", "2026-07-02", "findings", 17));
        var r = ParameterResolver.resolve(decls,
                Map.of(),
                Map.of("event_date", "$signal.event_date", "findings", "$signal.findings"),
                Map.of(), c);

        assertEquals("2026-07-02", r.resolved().get("event_date"), "bind beats the declared deduce");
        assertEquals("17", r.resolved().get("findings"), "a non-string payload value is stringified");
        assertTrue(r.missingRequired().isEmpty());
    }

    @Test
    void triggerArgsWinOverBindConfigAndDeduce() {
        // P3a-2 (§7.2 layer 1): explicit trigger args are the highest-precedence source.
        List<ParameterDecl> decls = List.of(decl("event_date", true, "$day(-1)", null));
        var c = ctx(Optional.empty(), (j, n) -> Optional.empty(), Map.of("event_date", "2026-07-02"));
        var r = ParameterResolver.resolve(decls,
                Map.of("event_date", "2026-01-01"),                 // trigger args
                Map.of("event_date", "$signal.event_date"),          // bind
                Map.of("event_date", "2026-05-05"),                  // config
                c);

        assertEquals("2026-01-01", r.resolved().get("event_date"), "trigger args beat bind, config and deduce");
    }

    @Test
    void bindToAnAbsentSignalFieldFallsThroughToConfig() {
        List<ParameterDecl> decls = List.of(decl("scope", true, null, null));
        var c = ctx(Optional.empty(), (j, n) -> Optional.empty(), Map.of());   // empty payload
        var r = ParameterResolver.resolve(decls,
                Map.of(), Map.of("scope", "$signal.missing"), Map.of("scope", "fallback"), c);

        assertEquals("fallback", r.resolved().get("scope"),
                "a bind whose $signal field is absent falls through to the next layer");
    }

    @Test
    void missingRequiredParameterIsReported() {
        List<ParameterDecl> decls = List.of(
                decl("must_have", true, null, null),           // no config, no deduce, no default
                decl("ok", false, null, "d"));
        var r = resolve(decls, Map.of(), ctx(Optional.empty()));

        assertEquals(List.of("must_have"), r.missingRequired(), "a required param with no source is REJECTED-worthy");
        assertFalse(r.resolved().containsKey("must_have"));
        assertEquals("d", r.resolved().get("ok"));
    }
}

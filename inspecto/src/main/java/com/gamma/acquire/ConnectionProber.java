package com.gamma.acquire;

import com.gamma.acquire.ConnectionWorkbench.CheckOutcome;
import com.gamma.acquire.ConnectionWorkbench.ProbeCheck;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * Orchestrates a graded connection probe (design §3: REACHABILITY → AUTHENTICATE → READ → WRITE → LIST) over
 * a {@link ConnectionProfile}, and resolves the {@link ConnectionWorkbench} that serves a profile's explore/
 * sample verbs. Reachability + secret resolution are answered generically (via {@link ConnectionTester});
 * everything else is delegated to the workbench, and reported honestly as <em>skipped</em> when the target is
 * unreachable or the connector has no workbench yet — a probe never lies about what it did not attempt.
 */
public final class ConnectionProber {

    private ConnectionProber() {}

    /**
     * Resolve the workbench for a profile: the built-in {@link LocalConnectionWorkbench} for non-remote
     * profiles, else whatever the matching {@link CollectorConnectorFactory} contributes ({@code null} when
     * no factory matches or the factory doesn't support the workbench yet). Callers own closing it.
     */
    public static ConnectionWorkbench workbenchFor(ConnectionProfile profile) {
        if (!profile.isRemote()) return new LocalConnectionWorkbench(profile);
        for (CollectorConnectorFactory f : ServiceLoader.load(CollectorConnectorFactory.class))
            if (f.scheme().equalsIgnoreCase(profile.connector())) return f.workbench(profile);
        return null;
    }

    /**
     * Run the requested checks (all five when {@code requested} is empty) in canonical order and assemble
     * the probe result. {@code sampleLimit} bounds the LIST check. Never throws — failures land per-check.
     */
    public static Map<String, Object> probe(ConnectionProfile profile, EnumSet<ProbeCheck> requested, int sampleLimit) {
        EnumSet<ProbeCheck> checks = (requested == null || requested.isEmpty())
                ? EnumSet.allOf(ProbeCheck.class) : requested;

        ConnectionTester.Result reach = ConnectionTester.test(profile);
        List<CheckOutcome> outcomes = new ArrayList<>();
        if (checks.contains(ProbeCheck.REACHABILITY))
            outcomes.add(new CheckOutcome(ProbeCheck.REACHABILITY, reach.reachable(), false,
                    reach.detail(), reach.latencyMs()));

        EnumSet<ProbeCheck> graded = EnumSet.copyOf(checks);
        graded.remove(ProbeCheck.REACHABILITY);
        if (!graded.isEmpty()) {
            if (!reach.reachable()) {
                for (ProbeCheck c : graded) outcomes.add(CheckOutcome.skipped(c, "not attempted (unreachable)"));
            } else {
                ConnectionWorkbench wb = workbenchFor(profile);
                if (wb == null) {
                    for (ProbeCheck c : graded)
                        outcomes.add(CheckOutcome.skipped(c,
                                "not supported by the '" + profile.connector() + "' connector yet"));
                } else {
                    try (ConnectionWorkbench w = wb) {
                        for (ProbeCheck c : graded) outcomes.add(timed(w, c, sampleLimit));
                    } catch (AcquisitionException e) {   // close() failure — the outcomes themselves are kept
                        outcomes.add(CheckOutcome.fail(ProbeCheck.AUTHENTICATE,
                                "session close failed: " + e.getMessage()));
                    }
                }
            }
        }

        boolean ok = outcomes.stream().allMatch(o -> o.skipped() || o.ok());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", profile.id());
        m.put("connector", profile.connector());
        m.put("endpoint", reach.endpoint());
        m.put("ok", ok);
        m.put("secretsResolved", reach.secretsResolved());
        m.put("checks", outcomes.stream().map(CheckOutcome::toMap).toList());
        return m;
    }

    /** Run one check, fill in its wall-clock latency when the implementation didn't, absorb its exception. */
    private static CheckOutcome timed(ConnectionWorkbench w, ProbeCheck c, int sampleLimit) {
        long start = System.nanoTime();
        CheckOutcome o;
        try {
            o = w.check(c, sampleLimit);
        } catch (AcquisitionException | RuntimeException e) {
            o = CheckOutcome.fail(c, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        if (o.latencyMs() == null && !o.skipped())
            o = new CheckOutcome(o.check(), o.ok(), o.skipped(), o.detail(), (System.nanoTime() - start) / 1_000_000L);
        return o;
    }
}

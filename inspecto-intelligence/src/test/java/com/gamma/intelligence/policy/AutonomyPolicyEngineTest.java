package com.gamma.intelligence.policy;

import com.gamma.intelligence.policy.AutonomyPolicy.ClassPolicy;
import com.gamma.intelligence.policy.AutonomyPolicy.Mode;
import com.gamma.intelligence.policy.AutonomyPolicyEngine.Outcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AGT-5 P4 (L3) policy substrate: the kill switch / mode / budget precedence in
 * {@link AutonomyPolicyEngine}, the rolling-window {@link ActionBudget}, and durable
 * {@link AutonomyPolicyStore}.
 */
class AutonomyPolicyEngineTest {

    private static AutonomyPolicyEngine engine(ActionBudget budget) {
        return new AutonomyPolicyEngine(new AutonomyPolicyStore(), budget);
    }

    @Test
    void anUnconfiguredClassIsDeniedByDefault() {
        AutonomyPolicyEngine e = engine(new ActionBudget());
        var v = e.authorize("batch_rerun");
        assertEquals(Outcome.DENY, v.outcome());
        assertTrue(v.reason().contains("off"));
    }

    @Test
    void shadowModeReturnsShadowAndNeverConsumesBudget() {
        AtomicLong now = new AtomicLong(0);
        ActionBudget budget = new ActionBudget(now::get);
        AutonomyPolicyEngine e = engine(budget);
        e.setClass("batch_rerun", new ClassPolicy(Mode.SHADOW, 1, 1), "op");
        // Shadow ten times — still SHADOW every time, budget untouched (an AUTO would have 1/hr).
        for (int i = 0; i < 10; i++) assertEquals(Outcome.SHADOW, e.authorize("batch_rerun").outcome());
        assertTrue(budget.allows("batch_rerun", 1, 1)); // budget never spent
    }

    @Test
    void autoModeConsumesBudgetAndDeniesOnceExhausted() {
        AtomicLong now = new AtomicLong(1_000);
        ActionBudget budget = new ActionBudget(now::get);
        AutonomyPolicyEngine e = engine(budget);
        e.setClass("batch_rerun", new ClassPolicy(Mode.AUTO, 2, 5), "op");

        assertEquals(Outcome.ALLOW, e.authorize("batch_rerun").outcome());
        assertEquals(Outcome.ALLOW, e.authorize("batch_rerun").outcome());
        var third = e.authorize("batch_rerun");                 // hourly cap = 2
        assertEquals(Outcome.DENY, third.outcome());
        assertTrue(third.reason().contains("maxPerHour=2"));

        now.addAndGet(java.time.Duration.ofHours(1).toMillis() + 1); // hour rolls over
        assertEquals(Outcome.ALLOW, e.authorize("batch_rerun").outcome());
    }

    @Test
    void dailyCapHoldsEvenAsHourlyWindowsRollOver() {
        AtomicLong now = new AtomicLong(0);
        ActionBudget budget = new ActionBudget(now::get);
        AutonomyPolicyEngine e = engine(budget);
        e.setClass("batch_rerun", new ClassPolicy(Mode.AUTO, 10, 3), "op"); // day cap = 3

        for (int i = 0; i < 3; i++) {
            assertEquals(Outcome.ALLOW, e.authorize("batch_rerun").outcome());
            now.addAndGet(java.time.Duration.ofHours(2).toMillis()); // new hour each time
        }
        var overDay = e.authorize("batch_rerun");
        assertEquals(Outcome.DENY, overDay.outcome());
        assertTrue(overDay.reason().contains("maxPerDay=3"));
    }

    @Test
    void killSwitchDeniesEverythingRegardlessOfMode() {
        AutonomyPolicyEngine e = engine(new ActionBudget());
        e.setClass("batch_rerun", new ClassPolicy(Mode.AUTO, 100, 100), "op");
        e.setKillSwitch(true, "incident-commander");
        var v = e.authorize("batch_rerun");
        assertEquals(Outcome.DENY, v.outcome());
        assertEquals("kill switch engaged", v.reason());
        // Disengage → the class is admissible again.
        e.setKillSwitch(false, "incident-commander");
        assertEquals(Outcome.ALLOW, e.authorize("batch_rerun").outcome());
    }

    @Test
    void updateFromABodyReplacesTheWholePolicy() {
        AutonomyPolicyEngine e = engine(new ActionBudget());
        e.update(Map.of(
                "killSwitch", false,
                "classes", Map.of("alert_triage", Map.of("mode", "auto", "maxPerHour", 4, "maxPerDay", 20))
        ), "op");
        assertEquals(Mode.AUTO, e.current().classPolicy("alert_triage").mode());
        assertEquals(4, e.current().classPolicy("alert_triage").maxPerHour());
        assertEquals("op", e.current().updatedBy());
    }

    @Test
    void policyIsDurableAcrossAStoreReload(@TempDir Path dir) {
        Path file = dir.resolve("agent").resolve("policy.json");
        AutonomyPolicyStore first = new AutonomyPolicyStore(file);
        AutonomyPolicyEngine e1 = new AutonomyPolicyEngine(first);
        e1.setClass("batch_rerun", new ClassPolicy(Mode.AUTO, 5, 25), "op");
        e1.setKillSwitch(true, "op");

        AutonomyPolicyStore reloaded = new AutonomyPolicyStore(file);
        AutonomyPolicy p = reloaded.current();
        assertTrue(p.killSwitch());
        assertEquals(Mode.AUTO, p.classPolicy("batch_rerun").mode());
        assertEquals(25, p.classPolicy("batch_rerun").maxPerDay());
    }

    @Test
    void malformedModeInABodyFallsBackToOffRatherThanThrowing() {
        AutonomyPolicyEngine e = engine(new ActionBudget());
        e.update(Map.of("classes", Map.of("x", Map.of("mode", "banana"))), "op");
        assertEquals(Mode.OFF, e.current().classPolicy("x").mode());
        assertEquals(Outcome.DENY, e.authorize("x").outcome());
    }

    @Test
    void remainingTodayReflectsConsumption() {
        AtomicLong now = new AtomicLong(0);
        ActionBudget budget = new ActionBudget(now::get);
        AutonomyPolicyEngine e = engine(budget);
        e.setClass("batch_rerun", new ClassPolicy(Mode.AUTO, 100, 5), "op");
        e.authorize("batch_rerun");
        e.authorize("batch_rerun");
        assertEquals(3, e.remainingToday().get("batch_rerun"));
    }

    @Test
    void concurrentAutoAuthorizeNeverExceedsTheCap() throws Exception {
        ActionBudget budget = new ActionBudget(); // real clock; all calls land in the same hour
        AutonomyPolicyEngine e = engine(budget);
        e.setClass("batch_rerun", new ClassPolicy(Mode.AUTO, 50, 0), "op");

        int threads = 16, each = 20;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.atomic.AtomicLong allowed = new java.util.concurrent.atomic.AtomicLong();
        var latch = new java.util.concurrent.CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                for (int i = 0; i < each; i++) {
                    if (e.authorize("batch_rerun").allowed()) allowed.incrementAndGet();
                }
                latch.countDown();
            });
        }
        latch.await();
        pool.shutdownNow();
        assertEquals(50, allowed.get(), "budget must be consumed exactly to the cap under contention");
        assertFalse(e.authorize("batch_rerun").allowed());
    }
}

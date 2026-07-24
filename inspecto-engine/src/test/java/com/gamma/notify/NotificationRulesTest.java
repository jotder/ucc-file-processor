package com.gamma.notify;

import com.gamma.event.Event;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** {@link NotificationRules#forEvent}: built-in defaults, and operator-authored rules checked first. */
class NotificationRulesTest {

    private static Event event(String type) {
        return Event.builder(type).message("m").build();
    }

    @Test
    void defaultsMatchBuiltinRulesOnly() {
        NotificationRules rules = NotificationRules.defaults();
        assertTrue(rules.forEvent(event("BATCH_FAILED")).isPresent());
        assertTrue(rules.forEvent(event("no.such.type")).isEmpty());
    }

    @Test
    void customRuleIsCheckedAheadOfBuiltins() {
        NotificationRule override = new NotificationRule("custom-batch-failed", "BATCH_FAILED", null,
                "custom", "Custom title", "Custom body", "custom-key", true);
        NotificationRules rules = new NotificationRules(NotificationRules.defaults().rules(),
                () -> List.of(override));

        NotificationRule matched = rules.forEvent(event("BATCH_FAILED")).orElseThrow();
        assertEquals("custom-batch-failed", matched.id(), "the authored rule wins over the built-in");
    }

    @Test
    void disabledCustomRuleFallsThroughToBuiltin() {
        NotificationRule disabled = new NotificationRule("custom-batch-failed", "BATCH_FAILED", null,
                "custom", "t", "b", "k", false);
        NotificationRules rules = new NotificationRules(NotificationRules.defaults().rules(),
                () -> List.of(disabled));

        NotificationRule matched = rules.forEvent(event("BATCH_FAILED")).orElseThrow();
        assertEquals("builtin-batch-failed", matched.id(), "a disabled authored rule doesn't shadow the built-in");
    }

    @Test
    void customRuleCanCoverANewEventType() {
        NotificationRule fresh = new NotificationRule("custom-new", "custom.event", null,
                "custom", "t", "b", "k", true);
        NotificationRules rules = new NotificationRules(NotificationRules.defaults().rules(),
                () -> List.of(fresh));

        assertTrue(rules.forEvent(event("custom.event")).isPresent());
    }
}

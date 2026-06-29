package com.gamma.notify;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@code {{var}}} interpolation. */
class NotificationTemplateTest {

    @Test
    void interpolatesFlatAndNestedVariables() {
        Map<String, Object> ctx = Map.of(
                "pipeline", "orders",
                "recipient", Map.of("first_name", "appUser"),
                "attributes", Map.of("rows", "1200"));
        assertEquals("Hi appUser — orders has 1200 rows",
                NotificationTemplate.render("Hi {{recipient.first_name}} — {{pipeline}} has {{attributes.rows}} rows", ctx));
    }

    @Test
    void missingVariablesRenderEmptyNeverLeakTokens() {
        String out = NotificationTemplate.render("a={{nope}} b={{deep.missing.path}} c", Map.of());
        assertEquals("a= b= c", out);
        assertFalse(out.contains("{{"), "no raw token leaks to the user");
    }

    @Test
    void plainTextWithoutBracesIsUnchanged() {
        String s = "no variables here";
        assertEquals(s, NotificationTemplate.render(s, Map.of()));
    }
}

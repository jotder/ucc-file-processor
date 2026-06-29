package com.gamma.notify;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for the preference grid: defaults, edits, and the critical-category opt-out bypass. */
class NotificationPreferencesTest {

    @Test
    void defaultsInAppOnEmailOffForActiveCategories() {
        NotificationPreferences p = new NotificationPreferences();
        assertTrue(p.enabled("pipeline", NotificationPreferences.IN_APP));
        assertFalse(p.enabled("pipeline", NotificationPreferences.EMAIL));
    }

    @Test
    void editsApply() {
        NotificationPreferences p = new NotificationPreferences();
        p.set("pipeline", Map.of(NotificationPreferences.IN_APP, false, NotificationPreferences.EMAIL, true));
        assertFalse(p.enabled("pipeline", NotificationPreferences.IN_APP));
        assertTrue(p.enabled("pipeline", NotificationPreferences.EMAIL));
    }

    @Test
    void criticalCategoryBypassesOptOutAndIsLocked() {
        NotificationPreferences p = new NotificationPreferences();
        // security is critical → always delivered on every channel, and edits are ignored
        assertTrue(p.enabled("security", NotificationPreferences.IN_APP));
        assertTrue(p.enabled("security", NotificationPreferences.EMAIL));
        p.set("security", Map.of(NotificationPreferences.IN_APP, false, NotificationPreferences.EMAIL, false));
        assertTrue(p.enabled("security", NotificationPreferences.IN_APP), "critical can't be silenced");
        assertTrue(p.enabled("security", NotificationPreferences.EMAIL));
    }

    @Test
    void gridExposesAllCategoriesWithMetadata() {
        List<Map<String, Object>> grid = new NotificationPreferences().grid();
        assertEquals(NotificationCategory.values().length, grid.size());
        Map<String, Object> security = grid.stream()
                .filter(r -> "security".equals(r.get("category"))).findFirst().orElseThrow();
        assertEquals(Boolean.TRUE, security.get("critical"));
        assertEquals(Boolean.FALSE, security.get("available"), "shown but inert until triggers exist");
    }
}

package com.gamma.ops.tag;

import com.gamma.config.io.ConfigCodec;
import com.gamma.ops.ObjectType;
import com.gamma.ops.OperationalObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tag + Tag Rule domain semantics (GLOSSARY §9): filter matching (every set criterion must hold;
 * incident statuses fold legacy lifecycle names; category is a path prefix; q a substring),
 * validation, and the {@code *_tag.toon}/{@code *_tagrule.toon} round-trip {@code ServiceBootstrap}
 * relies on to survive restarts.
 */
class TagRuleTest {

    private static OperationalObject incident(String title, String status, String priority, Map<String, String> attrs) {
        return OperationalObject.builder(ObjectType.INCIDENT)
                .title(title).description("d").status(status).priority(priority)
                .attributes(attrs).createdAt(1).updatedAt(1).build();
    }

    @Test
    void everySetCriterionMustMatch() {
        TagRule rule = new TagRule("r", "urgent",
                new TagRule.Filter("INCIDENT", "rejected", "IDENTIFIED", "CRITICAL", null,
                        "Pipeline / Ingest"), 1);
        Map<String, String> attrs = Map.of("category", "Pipeline / Ingest / Parse failure");
        assertTrue(rule.matches(incident("rejected files spike", "IDENTIFIED", "CRITICAL", attrs)));
        assertFalse(rule.matches(incident("rejected files spike", "IDENTIFIED", "LOW", attrs)), "priority mismatch");
        assertFalse(rule.matches(incident("all good", "IDENTIFIED", "CRITICAL", attrs)), "q not contained");
        assertFalse(rule.matches(incident("rejected files spike", "RESOLVED", "CRITICAL", attrs)), "status mismatch");
        assertFalse(rule.matches(incident("rejected files spike", "IDENTIFIED", "CRITICAL",
                Map.of("category", "Application / API / Timeout"))), "category prefix mismatch");
    }

    @Test
    void incidentStatusCriteriaFoldLegacyLifecycleNames() {
        TagRule identified = new TagRule("r1", "t", new TagRule.Filter(null, null, "IDENTIFIED", null, null, null), 1);
        assertTrue(identified.matches(incident("x", "OPEN", null, Map.of())), "legacy OPEN folds to IDENTIFIED");
        TagRule diagnosing = new TagRule("r2", "t", new TagRule.Filter(null, null, "DIAGNOSING", null, null, null), 1);
        assertTrue(diagnosing.matches(incident("x", "IN_PROGRESS", null, Map.of())), "legacy IN_PROGRESS folds to DIAGNOSING");
        assertTrue(diagnosing.matches(incident("x", "ASSIGNED", null, Map.of())), "legacy ASSIGNED folds to DIAGNOSING");
        TagRule archived = new TagRule("r3", "t", new TagRule.Filter(null, null, "ARCHIVED", null, null, null), 1);
        assertTrue(archived.matches(incident("x", "CLOSED", null, Map.of())), "legacy CLOSED folds to ARCHIVED");
    }

    @Test
    void validationRejectsBadRulesAndTags() {
        assertThrows(IllegalArgumentException.class,
                () -> new TagRule("r", "t", new TagRule.Filter(null, null, null, null, null, null), 1),
                "a rule needs at least one criterion");
        assertThrows(IllegalArgumentException.class,
                () -> new TagRule("r", "a,b", new TagRule.Filter(null, "q", null, null, null, null), 1),
                "comma tags would break the CSV attribute");
        assertThrows(IllegalArgumentException.class,
                () -> new TagRule.Filter("BOGUS", null, null, null, null, null),
                "an unknown object type rejects at authoring time");
        assertThrows(IllegalArgumentException.class, () -> new Tag("a,b", 1));
        assertThrows(IllegalArgumentException.class, () -> new Tag("  ", 1));
    }

    @Test
    void toonRoundTripSurvivesRestartShape(@TempDir Path dir) throws Exception {
        Tag tag = new Tag("urgent", 42);
        Path tagFile = dir.resolve("urgent_tag.toon");
        Files.writeString(tagFile, ConfigCodec.toToon(Map.of("tag", tag.toMap())));
        assertEquals(tag, Tag.load(tagFile));

        TagRule rule = new TagRule("critical-is-urgent", "urgent",
                new TagRule.Filter("INCIDENT", null, null, "CRITICAL", null, null), 42);
        Path ruleFile = dir.resolve("critical-is-urgent_tagrule.toon");
        Files.writeString(ruleFile, ConfigCodec.toToon(Map.of("tag_rule", rule.toMap())));
        assertEquals(rule, TagRule.load(ruleFile));
    }
}

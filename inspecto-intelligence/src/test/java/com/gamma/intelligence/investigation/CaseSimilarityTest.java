package com.gamma.intelligence.investigation;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Unit coverage for the deterministic Jaccard Case-recall scorer (AGT-5 P5). */
class CaseSimilarityTest {

    @Test
    void tokensLowercasesSplitsAndDropsShortTokens() {
        // "id"/"42" are < 3 chars and dropped; "gap" survives; casing normalised; duplicates collapsed.
        Set<String> t = CaseSimilarity.tokens("ID 42 Sequence-GAP gap");
        assertEquals(Set.of("sequence", "gap"), t);
    }

    @Test
    void tokensOfNullOrEmptyIsEmpty() {
        assertTrue(CaseSimilarity.tokens(null).isEmpty());
        assertTrue(CaseSimilarity.tokens("  !! 12 ").isEmpty(), "only noise/short tokens ⇒ empty");
    }

    @Test
    void jaccardOfIdenticalSetsIsOne() {
        Set<String> a = CaseSimilarity.tokens("orders pipeline failed schema");
        assertEquals(1.0, CaseSimilarity.jaccard(a, a), 1e-9);
    }

    @Test
    void jaccardOfDisjointSetsIsZero() {
        assertEquals(0.0,
                CaseSimilarity.jaccard(CaseSimilarity.tokens("alpha bravo"), CaseSimilarity.tokens("charlie delta")),
                1e-9);
    }

    @Test
    void jaccardComputesIntersectionOverUnion() {
        // {aaa,bbb,ccc} vs {bbb,ccc,ddd} → ∩ = 2, ∪ = 4 → 0.5
        double s = CaseSimilarity.jaccard(
                CaseSimilarity.tokens("aaa bbb ccc"),
                CaseSimilarity.tokens("bbb ccc ddd"));
        assertEquals(0.5, s, 1e-9);
    }

    @Test
    void emptyFingerprintScoresZeroNotOne() {
        assertEquals(0.0, CaseSimilarity.jaccard(Set.of(), Set.of()), 1e-9,
                "two content-free Cases must not look identical");
    }

    @Test
    void scoreWiresTokensThroughJaccard() {
        double s = CaseSimilarity.score("database sequence gap detected", "sequence gap in database");
        assertTrue(s > 0.5, "high symptom-vocabulary overlap ⇒ high score, got " + s);
        assertEquals(0.0, CaseSimilarity.score("totally unrelated words here", "nothing common between"), 1e-9);
    }
}

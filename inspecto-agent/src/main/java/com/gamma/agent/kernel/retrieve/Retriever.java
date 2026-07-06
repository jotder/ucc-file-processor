package com.gamma.agent.kernel.retrieve;

import com.gamma.agent.kernel.tool.Evidence;

import java.util.List;

/**
 * Supplies qualitative grounding context for a query — <b>never figures</b>. Returned {@link Evidence}
 * carries a source locator so narration can cite it. Embedding/vector retrievers (pgvector) are ring-2
 * companions; ring-1 ships only the dependency-free lexical {@link DocRetriever}.
 */
@FunctionalInterface
public interface Retriever {

    /** Up to a budget-bounded set of grounding snippets for {@code query}. */
    List<Evidence> retrieve(String query, ContextBudget budget);

    /** A retriever that returns nothing. */
    Retriever NONE = (query, budget) -> List.of();
}

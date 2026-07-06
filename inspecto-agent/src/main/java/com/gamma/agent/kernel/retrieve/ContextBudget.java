package com.gamma.agent.kernel.retrieve;

/**
 * A rough token budget split across the request, retrieved grounding, and instructions. A retriever
 * uses {@link #retrievedTokens} to bound how much grounding it returns.
 */
public record ContextBudget(int requestTokens, int retrievedTokens, int instructionTokens) {

    /** A standard ~10 / 70 / 20 split of a total token budget. */
    public static ContextBudget standard(int total) {
        return new ContextBudget(total / 10, total * 7 / 10, total / 5);
    }
}

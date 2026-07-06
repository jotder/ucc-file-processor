package com.gamma.agent.kernel.reason;

import java.util.ArrayList;
import java.util.List;

/**
 * The generate → validate → repair primitive (ported from UCC). A capability generates a candidate,
 * runs it through a deterministic oracle, and on rejection feeds the <em>verbatim</em> error back into
 * the next generation so the model can self-correct — capped at a small number of rounds (past 2–3 a
 * local model rarely recovers). Guards <em>structure</em>, not <em>semantics</em>.
 */
public final class RepairLoop {

    private RepairLoop() {}

    /** Produces raw model output; {@code repairFeedback} is {@code null} on the first round. */
    @FunctionalInterface
    public interface Generator {
        String generate(String repairFeedback);
    }

    /** Parses + validates raw output, returning the typed value or throwing a human-usable message. */
    @FunctionalInterface
    public interface Validator<T> {
        T validate(String raw) throws Exception;
    }

    /**
     * The outcome of a repair loop.
     *
     * @param value    the validated value, or {@code null} if every round failed
     * @param rounds   how many generation rounds ran
     * @param repaired whether it took more than one round to succeed
     * @param errors   the oracle error from each failed round (in order)
     * @param lastRaw  the raw model output of the final round (for diagnostics)
     */
    public record Result<T>(T value, int rounds, boolean repaired, List<String> errors, String lastRaw) {
        public boolean ok() { return value != null; }
    }

    /**
     * Run up to {@code maxRounds} of generate → validate, re-prompting with the oracle's verbatim error
     * after each rejection. Returns as soon as the validator accepts, else a failed result carrying the
     * accumulated errors.
     */
    public static <T> Result<T> run(int maxRounds, Generator gen, Validator<T> validator) {
        if (maxRounds < 1) throw new IllegalArgumentException("maxRounds must be >= 1");
        List<String> errors = new ArrayList<>();
        String feedback = null;
        String raw = null;
        for (int round = 1; round <= maxRounds; round++) {
            raw = gen.generate(feedback);
            try {
                T value = validator.validate(raw);
                return new Result<>(value, round, round > 1, List.copyOf(errors), raw);
            } catch (Exception e) {
                String msg = (e.getMessage() == null || e.getMessage().isBlank())
                        ? e.getClass().getSimpleName() : e.getMessage();
                errors.add(msg);
                feedback = "Your previous answer was rejected by the validator:\n  " + msg
                        + "\nThe rejected answer was:\n" + raw
                        + "\nReturn a corrected answer that fixes exactly that problem.";
            }
        }
        return new Result<>(null, maxRounds, maxRounds > 1, List.copyOf(errors), raw);
    }
}

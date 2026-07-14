package com.gamma.acquire;

import java.nio.file.Path;

/**
 * How the engine will retrieve one file — the output of {@link RetrievalPlanner}.
 *
 * <p>{@code destination} is {@code null} for {@link Mode#STREAM} (no bytes land locally) and otherwise the
 * single place the bytes are written ({@link CollectorConnector#fetchTo}). The whole point of the plan is to
 * write bytes <b>at most once</b>: stream when there's nothing to keep, else fetch straight to the final
 * home — never temp-then-move.
 */
public record RetrievalPlan(Mode mode, Path destination) {

    public enum Mode {
        /** Read straight from the source, no local copy ({@link CollectorConnector#open}). */
        STREAM,
        /** Write once, directly into the backup/archive dir, and read from there (no later move). */
        FETCH_TO_BACKUP,
        /** No backup wanted but a local copy is required (no streaming / needs random access) → temp. */
        STAGE_TEMP
    }

    /** Whether the bytes are written to local disk (i.e. not a pure stream). */
    public boolean materializes() {
        return mode != Mode.STREAM;
    }

    public static RetrievalPlan stream() {
        return new RetrievalPlan(Mode.STREAM, null);
    }

    public static RetrievalPlan fetchToBackup(Path dest) {
        return new RetrievalPlan(Mode.FETCH_TO_BACKUP, dest);
    }

    public static RetrievalPlan stageTemp(Path tempDir) {
        return new RetrievalPlan(Mode.STAGE_TEMP, tempDir);
    }
}

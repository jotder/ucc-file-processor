package com.gamma.etl;

import java.util.List;

/**
 * Output file paths and their sizes in bytes produced by one transformation pass.
 * One entry per partition file written by {@code COPY … TO}.
 *
 * @param outputPaths absolute paths of the written output files
 * @param outputSizes corresponding file sizes in bytes
 */
public record TransformResult(List<String> outputPaths, List<Long> outputSizes) {

    /**
     * Sentinel returned when no output is produced (e.g. on early failure before
     * the transform stage is reached).
     */
    public static TransformResult empty() {
        return new TransformResult(List.of(), List.of());
    }
}

package com.gamma.acquire;

import java.util.List;

/**
 * The engine-computed parameters for one {@link SourceConnector#discover} call.
 *
 * <p>{@code includes}/{@code excludes} are pattern strings. A pattern may carry an explicit
 * {@code glob:}/{@code regex:} syntax prefix (passed straight to
 * {@link java.nio.file.FileSystem#getPathMatcher}); a bare pattern with no {@code /} is treated as a
 * <em>filename</em> glob (so {@code *.tmp} matches at any depth), and one containing {@code /} as a
 * path glob. Defaults preserve legacy behaviour: when a pipeline has no {@code source:} block the engine
 * passes {@code includes = [processing.file_pattern]}, {@code excludes = []}, {@code maxDepth = UNBOUNDED}
 * — exactly the old single-glob, full-tree walk.
 *
 * <p>{@code maxDepth} maps to {@link java.nio.file.Files#walk(java.nio.file.Path, int, java.nio.file.FileVisitOption...)}
 * with the source root at depth 0 ({@code inbox/*} = 1, {@code inbox/*}{@code /*} = 2);
 * {@link #UNBOUNDED} (-1) walks the whole tree.
 */
public record DiscoveryContext(List<String> includes, List<String> excludes, int maxDepth) {

    /** Walk the entire tree (the legacy default). */
    public static final int UNBOUNDED = -1;

    public DiscoveryContext {
        includes = List.copyOf(includes);
        excludes = List.copyOf(excludes);
    }

    /** Whether a finite recursion depth was configured. */
    public boolean bounded() {
        return maxDepth >= 0;
    }
}

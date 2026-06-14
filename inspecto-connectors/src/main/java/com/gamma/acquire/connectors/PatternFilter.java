package com.gamma.acquire.connectors;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Applies a {@link com.gamma.acquire.DiscoveryContext}'s include/exclude patterns to a connector's listing —
 * the remote-connector counterpart of the matcher {@code LocalFileSystemConnector} runs over local paths. It
 * tests the protocol-agnostic forward-slash {@code relativePath} so the same pipeline {@code includes:}/
 * {@code excludes:} behave identically across local, SFTP and FTP sources.
 *
 * <p>Pattern semantics mirror {@code DiscoveryContext}: an explicit {@code glob:}/{@code regex:} prefix is
 * honoured; a bare pattern with no {@code /} is a <em>filename</em> glob (matches at any depth); one containing
 * {@code /} is a path glob. An empty include list matches everything.
 *
 * <p>Globs are translated to regex here rather than handed to {@link java.nio.file.FileSystem#getPathMatcher}
 * because the engine matches a <em>relative</em> path: {@code glob:**}{@code /*.csv} must match a top-level
 * {@code a.csv} (zero directories), which {@code PathMatcher}'s {@code **}{@code /} does not do against a
 * single-element path. The translation gives {@code **}{@code /} zero-or-more-directory semantics and is
 * separator-deterministic across platforms.
 */
public final class PatternFilter {

    private final List<Pattern> includes;
    private final List<Pattern> excludes;

    public PatternFilter(List<String> includePatterns, List<String> excludePatterns) {
        this.includes = compile(includePatterns);
        this.excludes = compile(excludePatterns);
    }

    /** Keep {@code relativePath} (forward-slash, source-root-relative) iff it matches an include and no exclude. */
    public boolean accepts(String relativePath) {
        boolean included = includes.isEmpty() || anyMatch(includes, relativePath);
        if (!included) return false;
        return excludes.isEmpty() || !anyMatch(excludes, relativePath);
    }

    private static boolean anyMatch(List<Pattern> patterns, String relativePath) {
        for (Pattern p : patterns) if (p.matcher(relativePath).matches()) return true;
        return false;
    }

    private static List<Pattern> compile(List<String> patterns) {
        List<Pattern> out = new ArrayList<>();
        if (patterns == null) return out;
        for (String raw : patterns) {
            String p = raw == null ? "" : raw.trim();
            if (p.isEmpty()) continue;
            if (p.startsWith("regex:")) {
                out.add(Pattern.compile(p.substring("regex:".length())));
            } else {
                String glob = p.startsWith("glob:") ? p.substring("glob:".length()) : p;
                boolean filenameOnly = glob.indexOf('/') < 0;   // bare filename glob ⇒ match at any depth
                String regex = "^" + (filenameOnly ? "(?:.*/)?" : "") + globToRegex(glob) + "$";
                out.add(Pattern.compile(regex));
            }
        }
        return out;
    }

    /** Translate a glob to an (unanchored) regex body over a forward-slash path. Supports {@code *}, {@code **},
     *  {@code ?} and brace alternation {@code {a,b}}; {@code **}{@code /} matches zero-or-more directories.
     *  The caller anchors with {@code ^}…{@code $}. */
    static String globToRegex(String glob) {
        StringBuilder sb = new StringBuilder();
        int braceDepth = 0;
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> {
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {   // **
                        if (i + 2 < glob.length() && glob.charAt(i + 2) == '/') { sb.append("(?:.*/)?"); i += 2; }
                        else { sb.append(".*"); i += 1; }
                    } else {
                        sb.append("[^/]*");
                    }
                }
                case '?' -> sb.append("[^/]");
                case '{' -> { sb.append("(?:"); braceDepth++; }
                case '}' -> { if (braceDepth > 0) { sb.append(')'); braceDepth--; } else sb.append("\\}"); }
                case ',' -> sb.append(braceDepth > 0 ? "|" : "\\,");
                case '.', '(', ')', '+', '|', '^', '$', '\\', '[', ']' -> sb.append('\\').append(c);
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}

package com.gamma.agent.skill;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A tiny, dependency-free retriever over the project's Markdown docs (v3.3.0). It indexes
 * {@code docs/*.md} once and returns the paragraphs that best overlap a query — enough grounding
 * for {@code explain-entity} to cite "what does this mean" from the docs without pulling in a
 * vector store. Absence of a docs directory is fine: the corpus is simply empty and the skill
 * grounds on the catalog alone.
 *
 * <p>Scoring is naive term-overlap — deliberately deterministic, so golden tests are stable.
 */
public final class DocRetriever {

    /** A scored paragraph from one doc file. */
    public record Snippet(String file, String text, int score) {}

    private final Map<String, String> docs; // filename -> content

    public DocRetriever(Map<String, String> docs) {
        this.docs = (docs == null) ? Map.of() : Map.copyOf(docs);
    }

    public boolean isEmpty() { return docs.isEmpty(); }
    public int size() { return docs.size(); }

    /** Index every {@code *.md} directly under {@code dir} (non-recursive). Missing dir → empty. */
    public static DocRetriever fromDir(Path dir) {
        Map<String, String> m = new LinkedHashMap<>();
        if (dir != null && Files.isDirectory(dir)) {
            try (Stream<Path> s = Files.list(dir)) {
                s.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".md"))
                        .sorted()
                        .forEach(p -> {
                            try { m.put(p.getFileName().toString(), Files.readString(p)); }
                            catch (IOException ignored) { /* skip unreadable file */ }
                        });
            } catch (IOException ignored) { /* empty corpus */ }
        }
        return new DocRetriever(m);
    }

    /** From {@code -Dassist.docs.dir} (default {@code docs/} under the working dir). */
    public static DocRetriever fromEnvironment() {
        String d = System.getProperty("assist.docs.dir");
        if (d == null || d.isBlank()) d = "docs";
        return fromDir(Path.of(d));
    }

    /** Top-{@code k} paragraphs by term overlap with {@code query} (highest score first). */
    public List<Snippet> retrieve(String query, int k) {
        if (docs.isEmpty() || query == null || query.isBlank()) return List.of();
        Set<String> terms = terms(query);
        if (terms.isEmpty()) return List.of();
        List<Snippet> hits = new ArrayList<>();
        for (Map.Entry<String, String> e : docs.entrySet()) {
            for (String para : e.getValue().split("\\n\\s*\\n")) {
                int score = score(terms, para);
                if (score > 0) hits.add(new Snippet(e.getKey(), trim(para), score));
            }
        }
        hits.sort(Comparator.comparingInt(Snippet::score).reversed());
        return hits.stream().limit(Math.max(0, k)).toList();
    }

    private static Set<String> terms(String s) {
        return Arrays.stream(s.toLowerCase().split("[^a-z0-9]+"))
                .filter(t -> t.length() > 2)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static int score(Set<String> terms, String paragraph) {
        String low = paragraph.toLowerCase();
        int n = 0;
        for (String t : terms) if (low.contains(t)) n++;
        return n;
    }

    private static String trim(String p) {
        String s = p.strip().replaceAll("\\s+", " ");
        return s.length() > 400 ? s.substring(0, 400) + "…" : s;
    }
}

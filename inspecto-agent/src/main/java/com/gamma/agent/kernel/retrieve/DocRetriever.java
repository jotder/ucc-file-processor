package com.gamma.agent.kernel.retrieve;

import com.gamma.agent.kernel.tool.CredibilityTier;
import com.gamma.agent.kernel.tool.Evidence;

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
 * A tiny, dependency-free lexical {@link Retriever} over a corpus of Markdown docs (ported from UCC).
 * It returns the paragraphs that best overlap a query as {@link Evidence} (tier {@link CredibilityTier#INDICATIVE},
 * {@code sourceRef} = filename) — enough qualitative grounding without a vector store. Scoring is naive
 * term-overlap, deliberately deterministic so golden tests are stable.
 */
public final class DocRetriever implements Retriever {

    private final Map<String, String> docs; // filename -> content

    public DocRetriever(Map<String, String> docs) {
        this.docs = (docs == null) ? Map.of() : Map.copyOf(docs);
    }

    public boolean isEmpty() { return docs.isEmpty(); }
    public int size() { return docs.size(); }

    /** Index every {@code *.md} directly under {@code dir} (non-recursive). Missing dir → empty corpus. */
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

    @Override
    public List<Evidence> retrieve(String query, ContextBudget budget) {
        int k = (budget == null) ? 3 : Math.max(1, budget.retrievedTokens() / 200);
        if (docs.isEmpty() || query == null || query.isBlank()) return List.of();
        Set<String> terms = terms(query);
        if (terms.isEmpty()) return List.of();

        record Hit(String file, String text, int score) {}
        List<Hit> hits = new ArrayList<>();
        for (Map.Entry<String, String> e : docs.entrySet()) {
            for (String para : e.getValue().split("\\n\\s*\\n")) {
                int score = score(terms, para);
                if (score > 0) hits.add(new Hit(e.getKey(), trim(para), score));
            }
        }
        hits.sort(Comparator.comparingInt(Hit::score).reversed());
        return hits.stream()
                .limit(Math.max(0, k))
                .map(h -> new Evidence(h.text(), CredibilityTier.INDICATIVE, "doc", h.file(),
                        Math.min(1.0, h.score() / (double) terms.size()), null))
                .toList();
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

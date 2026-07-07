package com.gamma.intelligence.pack;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the canonical-term definitions out of {@code docs/GLOSSARY.md} (binding vocabulary, see
 * repo {@code CLAUDE.md}) into a term→definition map — feeds both {@link InspectoPromptProfile}
 * and the {@code glossary_lookup} tool ({@link InspectoTools}). Entries look like
 * {@code **Term** — One-sentence definition.}; this loader takes the first line following each
 * bold term, which covers the vast majority of entries. Missing/unreadable file → empty map
 * (never throws — the pack must still assemble offline).
 */
final class GlossaryLoader {

    private static final Logger log = LoggerFactory.getLogger(GlossaryLoader.class);
    private static final Path GLOSSARY_PATH = Path.of("docs", "GLOSSARY.md");
    private static final Pattern ENTRY = Pattern.compile("^\\*\\*([^*]+)\\*\\*\\s*[—-]+\\s*(.+)$");

    private GlossaryLoader() {
    }

    static Map<String, String> load() {
        Map<String, String> terms = new LinkedHashMap<>();
        List<String> lines;
        try {
            lines = Files.readAllLines(GLOSSARY_PATH);
        } catch (IOException e) {
            log.warn("Could not read {} for the domain glossary: {}", GLOSSARY_PATH, e.getMessage());
            return Map.of();
        }
        for (String line : lines) {
            Matcher m = ENTRY.matcher(line.trim());
            if (m.matches()) {
                terms.putIfAbsent(m.group(1).trim(), stripMarkdown(m.group(2).trim()));
            }
        }
        return Map.copyOf(terms);
    }

    /** Drops bold/italic markers and inline links' bracket text stays, target dropped. */
    private static String stripMarkdown(String text) {
        return text.replaceAll("\\*\\*", "")
                .replaceAll("\\[([^]]+)]\\([^)]*\\)", "$1");
    }
}

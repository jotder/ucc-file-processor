package com.gamma.service;

import com.gamma.alert.AlertRule;
import com.gamma.config.io.ConfigCodec;
import com.gamma.enrich.EnrichmentConfig;
import com.gamma.job.JobConfig;
import com.gamma.ops.EscalationPolicy;
import com.gamma.ops.queue.Queue;
import com.gamma.ops.rca.RcaTemplate;
import com.gamma.ops.tag.CaseRule;
import com.gamma.ops.tag.Tag;
import com.gamma.ops.tag.TagRule;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Guards the repo's committed sample spaces ({@code ../spaces/<id>/config} + {@code ../spaces/_templates}):
 * every authored TOON must parse with the same loaders the boot scan uses — a silently-mangled config
 * (a stray {@code #} comment, a bad list count, a wrong block key) fails here instead of being dropped
 * at serve time. Pipelines/registry components are validated at the syntax layer (decode + key presence);
 * the suffix-scanned ops/alert/enrich/job kinds run through their real {@code load()}s.
 */
class RepoSpacesConfigValidationTest {

    private static Path spacesRoot() {
        // surefire's CWD is the inspecto/ module dir; the spaces tree is a repo-root sibling.
        Path p = Path.of("..", "spaces").toAbsolutePath().normalize();
        return Files.isDirectory(p) ? p : null;
    }

    @Test
    void everyAuthoredSpaceConfigParses() throws IOException {
        Path root = spacesRoot();
        assumeTrue(root != null, "no repo spaces/ tree next to the module — skipping");
        List<String> failures = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path f : walk.filter(Files::isRegularFile)
                              .filter(p -> p.getFileName().toString().endsWith(".toon"))
                              // uat is generated, _shared is runtime state — only authored trees are guarded
                              .filter(p -> { String s = root.relativize(p).toString().replace('\\', '/');
                                             return !s.startsWith("uat/") && !s.startsWith("_shared/"); })
                              .toList()) {
                String name = f.getFileName().toString();
                try {
                    validate(f, name);
                } catch (Exception e) {
                    failures.add(root.relativize(f) + " -> " + e.getMessage());
                }
            }
        }
        assertTrue(failures.isEmpty(), "unparseable space configs:\n  " + String.join("\n  ", failures));
    }

    private void validate(Path f, String name) throws IOException {
        if (name.endsWith("_enrich.toon"))          { EnrichmentConfig.load(f.toString()); return; }
        if (name.endsWith("_alert.toon"))           { AlertRule.load(f); return; }
        if (name.endsWith("_tag.toon"))             { Tag.load(f); return; }
        if (name.endsWith("_tagrule.toon"))         { TagRule.load(f); return; }
        if (name.endsWith("_caserule.toon"))        { CaseRule.load(f); return; }
        if (name.endsWith("_queue.toon"))           { Queue.load(f); return; }
        if (name.endsWith("_escalation.toon"))      { EscalationPolicy.load(f); return; }
        if (name.endsWith("_rca.toon"))             { RcaTemplate.load(f); return; }
        if (name.endsWith("_job.toon")) {
            Map<String, Object> raw = ConfigCodec.toMap(Files.readString(f));
            Object job = raw.get("job");
            // template-instantiated jobs are expanded by the boot scan — standalone load can't resolve them
            if (job instanceof Map<?, ?> j && j.get("template") == null) JobConfig.load(f.toString());
            return;
        }
        // pipelines, schemas, grammars, meta, registry components, manifests, templates:
        // decode must succeed (the parser mangles '#' comments and bad list counts into junk/failures)
        Map<String, Object> raw = ConfigCodec.toMap(Files.readString(f));
        assertFalse(raw.isEmpty(), "decoded to an empty map");
        if (name.endsWith("_pipeline.toon")) {
            assertTrue(raw.containsKey("name") && raw.containsKey("dirs") && raw.containsKey("processing"),
                    "pipeline missing name/dirs/processing");
        }
    }
}

package com.gamma.service;

import com.gamma.catalog.SemanticModel;
import com.gamma.enrich.EnrichmentConfig;
import com.gamma.inspector.MultiCollectorProcessor;
import com.gamma.job.JobConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Builds a fully-wired {@link CollectorService} from CLI-style args by scanning each path
 * (file or dir) for the various {@code *.toon} config types. This is the service's
 * <em>bootstrap</em> concern — discovering and parsing configuration off disk — kept
 * separate from {@link CollectorService}'s runtime responsibilities (poll loop, scheduling,
 * event wiring, lifecycle).
 *
 * <p>Each loader warns-and-skips a bad file so one malformed config never blocks the
 * others. Logs under {@link CollectorService}'s category so existing log output is unchanged.
 */
final class ServiceBootstrap {

    private static final Logger log = LoggerFactory.getLogger(CollectorService.class);

    private ServiceBootstrap() {}

    /**
     * Build a fully-wired single-tenant service from CLI-style args (the legacy flat layout): each path (file or
     * dir) is scanned for the {@code *.toon} config types. Reads {@code -Dservice.poll.seconds} (default 60) and
     * {@code -Dservice.max.runs} (default = source count). Shared by the service and Control API entry points.
     * Exits the JVM with a message if no sources are found.
     */
    static CollectorService build(String[] args) throws IOException {
        return buildFrom(SpaceRoot.legacy(), args, true);
    }

    /**
     * Build a service rooted at {@code root}, discovering its {@code *.toon} configs by scanning {@code paths}.
     * The single-tenant entry point passes {@link SpaceRoot#legacy()} + the CLI args; {@link SpaceBootstrap}
     * passes a per-space {@link SpaceRoot} + its {@code config/} directory. When {@code exitIfEmpty} is set (the
     * CLI), a config-less invocation exits the JVM; a space tolerates an empty {@code config/} (a freshly created
     * space has no sources yet), so {@code SpaceBootstrap} passes {@code false}.
     */
    static CollectorService buildFrom(SpaceRoot root, String[] paths, boolean exitIfEmpty) throws IOException {
        List<Path> registry = MultiCollectorProcessor.resolveConfigs(paths);
        List<EnrichmentConfig> enrichJobs = loadEnrichJobs(resolveBySuffix(paths, "_enrich.toon"));
        // Job templates (PIP-6) resolve at load: jobs referencing `template:` are expanded here, so the
        // scheduler only ever sees plain JobConfigs. (The *_job_template.toon suffix does not match the
        // *_job.toon scan — a template file is never loaded as a job itself.)
        Map<String, com.gamma.job.JobTemplate> templates =
                loadJobTemplates(resolveBySuffix(paths, "_job_template.toon"));
        List<JobConfig> jobConfigs = loadJobs(resolveBySuffix(paths, "_job.toon"), templates);
        List<SemanticModel> semantics = loadSemantics(resolveBySuffix(paths, "_meta.toon"));
        List<com.gamma.alert.AlertRule> alertRules = loadAlerts(root);
        if (registry.isEmpty() && enrichJobs.isEmpty() && jobConfigs.isEmpty() && exitIfEmpty) {
            System.err.println("No *_pipeline.toon / *_enrich.toon / *_job.toon files found in: "
                    + String.join(", ", paths));
            System.exit(1);
        }
        long pollSeconds = Long.getLong("service.poll.seconds", 60L);
        int  maxRuns     = Integer.getInteger("service.max.runs", Math.max(1, registry.size()));
        CollectorService svc = new CollectorService(registry, enrichJobs, jobConfigs, semantics, alertRules,
                pollSeconds, maxRuns, ServiceStores.openStatusStore(root), root);
        for (com.gamma.ops.rca.RcaTemplate t : loadRcaTemplates(resolveBySuffix(paths, "_rca.toon")))
            svc.registerRcaTemplate(t);
        for (com.gamma.acquire.ConnectionProfile c : loadConnections(resolveBySuffix(paths, "_connection.toon")))
            svc.registerConnection(c);
        // INC-4: work queues + the SLA escalation policy. Queues route incident assignment; the escalation
        // policy (at most one — the incident SLA response) is applied by the sweep. Both optional.
        for (com.gamma.ops.queue.Queue q : loadQueues(resolveBySuffix(paths, "_queue.toon")))
            svc.objects().registerQueue(q);
        loadEscalation(resolveBySuffix(paths, "_escalation.toon")).ifPresent(svc.objects()::escalationPolicy);
        // Tags + Tag Rules (GLOSSARY §9): authored as *_tag.toon / *_tagrule.toon, or written by the
        // /tags routes at runtime — this rescan is what makes runtime-created tags survive a restart.
        for (com.gamma.ops.tag.Tag t : loadTags(resolveBySuffix(paths, "_tag.toon")))
            svc.objects().registerTag(t);
        for (com.gamma.ops.tag.TagRule r : loadTagRules(resolveBySuffix(paths, "_tagrule.toon")))
            svc.objects().registerTagRule(r);
        for (com.gamma.ops.tag.CaseRule r : loadCaseRules(resolveBySuffix(paths, "_caserule.toon")))
            svc.objects().registerCaseRule(r);
        return svc;
    }

    /** Walk CLI paths for files ending in {@code suffix} (file args matched directly). */
    private static List<Path> resolveBySuffix(String[] args, String suffix) throws IOException {
        List<Path> out = new ArrayList<>();
        for (String a : args) {
            Path p = Path.of(a);
            if (Files.isDirectory(p)) {
                try (Stream<Path> w = Files.walk(p)) {
                    w.filter(Files::isRegularFile)
                     .filter(f -> f.getFileName().toString().endsWith(suffix))
                     .sorted().forEach(out::add);
                }
            } else if (Files.isRegularFile(p) && p.getFileName().toString().endsWith(suffix)) {
                out.add(p);
            }
        }
        return out;
    }

    /** Load each enrichment config; a bad one is warned and skipped (others still host). */
    private static List<EnrichmentConfig> loadEnrichJobs(List<Path> paths) {
        List<EnrichmentConfig> jobs = new ArrayList<>();
        for (Path p : paths) {
            try {
                jobs.add(EnrichmentConfig.load(p.toString()));
                log.info("Registered enrichment job from {}", p);
            } catch (Exception e) {
                log.warn("Could not load enrichment config {}: {}", p, e.getMessage());
            }
        }
        return jobs;
    }

    /** Load each {@code *_meta.toon} semantic model; a bad one is warned and skipped. */
    private static List<SemanticModel> loadSemantics(List<Path> paths) {
        List<SemanticModel> models = new ArrayList<>();
        for (Path p : paths) {
            try {
                models.add(SemanticModel.load(p.toString()));
                log.info("Registered semantic model from {}", p);
            } catch (Exception e) {
                log.warn("Could not load semantic model {}: {}", p, e.getMessage());
            }
        }
        return models;
    }

    /**
     * Load {@code alert-rule} components from the space's registry ({@code root.config()/registry},
     * falling back to the legacy {@code -Dassist.write.root/registry} the same way
     * {@code DecisionRules.forPipeline} does — the boot-time read must agree with where
     * {@code AlertRoutes}' CRUD writes, via {@code ApiContext.writeRoot()}, for a restart to
     * re-arm what was authored). A bad component is warned and skipped (others still arm).
     */
    private static List<com.gamma.alert.AlertRule> loadAlerts(SpaceRoot root) {
        Path registryRoot = root.config() != null ? root.config().resolve("registry") : legacyRegistryRoot();
        if (registryRoot == null || !Files.isDirectory(registryRoot)) return List.of();
        List<com.gamma.alert.AlertRule> rules = new ArrayList<>();
        for (com.gamma.pipeline.ComponentRegistry.Component c :
                new com.gamma.pipeline.ComponentStore(registryRoot).list("alert-rule")) {
            try {
                com.gamma.alert.AlertRule r = com.gamma.alert.AlertRule.fromMap(c.content());
                rules.add(r);
                log.info("Armed alert rule '{}' ({} {} {} over {}) from the registry",
                        r.name(), r.metric(), r.comparator(), r.threshold(), r.window());
            } catch (Exception e) {
                log.warn("Could not load alert rule '{}': {}", c.name(), e.getMessage());
            }
        }
        return rules;
    }

    /** The legacy/default space's registry root — {@code -Dassist.write.root/registry}, or {@code null}. */
    private static Path legacyRegistryRoot() {
        String wr = System.getProperty("assist.write.root");
        return (wr == null || wr.isBlank()) ? null : Path.of(wr.trim()).resolve("registry");
    }

    /** Load each {@code *_rca.toon} (Phase 4); a bad one is warned and skipped (others still register). */
    static List<com.gamma.ops.rca.RcaTemplate> loadRcaTemplates(List<Path> paths) {
        List<com.gamma.ops.rca.RcaTemplate> out = new ArrayList<>();
        for (Path p : paths) {
            try {
                com.gamma.ops.rca.RcaTemplate t = com.gamma.ops.rca.RcaTemplate.load(p);
                out.add(t);
                log.info("Loaded RCA template '{}' ({} section(s)) from {}", t.name(), t.sections().size(), p);
            } catch (Exception e) {
                log.warn("Could not load RCA template {}: {}", p, e.getMessage());
            }
        }
        return out;
    }

    /** Load each {@code *_queue.toon} (INC-4); a bad one is warned and skipped (others still register). */
    static List<com.gamma.ops.queue.Queue> loadQueues(List<Path> paths) {
        List<com.gamma.ops.queue.Queue> out = new ArrayList<>();
        for (Path p : paths) {
            try {
                com.gamma.ops.queue.Queue q = com.gamma.ops.queue.Queue.load(p);
                out.add(q);
                log.info("Loaded queue '{}' ({} member(s), {} routing) from {}",
                        q.id(), q.members().size(), q.routing(), p);
            } catch (Exception e) {
                log.warn("Could not load queue {}: {}", p, e.getMessage());
            }
        }
        return out;
    }

    /** Load each {@code *_tag.toon} (GLOSSARY §9); a bad one is warned and skipped (others still register). */
    static List<com.gamma.ops.tag.Tag> loadTags(List<Path> paths) {
        List<com.gamma.ops.tag.Tag> out = new ArrayList<>();
        for (Path p : paths) {
            try {
                com.gamma.ops.tag.Tag t = com.gamma.ops.tag.Tag.load(p);
                out.add(t);
                log.info("Loaded tag '{}' from {}", t.name(), p);
            } catch (Exception e) {
                log.warn("Could not load tag {}: {}", p, e.getMessage());
            }
        }
        return out;
    }

    /** Load each {@code *_tagrule.toon} (GLOSSARY §9); a bad one is warned and skipped (others still register). */
    static List<com.gamma.ops.tag.TagRule> loadTagRules(List<Path> paths) {
        List<com.gamma.ops.tag.TagRule> out = new ArrayList<>();
        for (Path p : paths) {
            try {
                com.gamma.ops.tag.TagRule r = com.gamma.ops.tag.TagRule.load(p);
                out.add(r);
                log.info("Loaded tag rule '{}' (tags \"{}\") from {}", r.name(), r.tag(), p);
            } catch (Exception e) {
                log.warn("Could not load tag rule {}: {}", p, e.getMessage());
            }
        }
        return out;
    }

    /** Load each {@code *_caserule.toon} (GLOSSARY §9, C5); a bad one is warned and skipped (others still register). */
    static List<com.gamma.ops.tag.CaseRule> loadCaseRules(List<Path> paths) {
        List<com.gamma.ops.tag.CaseRule> out = new ArrayList<>();
        for (Path p : paths) {
            try {
                com.gamma.ops.tag.CaseRule r = com.gamma.ops.tag.CaseRule.load(p);
                out.add(r);
                log.info("Loaded case rule '{}' (raises \"{}\") from {}", r.name(), r.title(), p);
            } catch (Exception e) {
                log.warn("Could not load case rule {}: {}", p, e.getMessage());
            }
        }
        return out;
    }

    /** Load the SLA {@code *_escalation.toon} policy (INC-4) — the first valid one wins; a bad one is skipped. */
    static java.util.Optional<com.gamma.ops.EscalationPolicy> loadEscalation(List<Path> paths) {
        for (Path p : paths) {
            try {
                com.gamma.ops.EscalationPolicy pol = com.gamma.ops.EscalationPolicy.load(p);
                log.info("Loaded SLA escalation policy from {} (severity={}, reassignQueue={}, renotify={})",
                        p, pol.severity(), pol.reassignQueue(), pol.renotify());
                return java.util.Optional.of(pol);
            } catch (Exception e) {
                log.warn("Could not load escalation policy {}: {}", p, e.getMessage());
            }
        }
        return java.util.Optional.empty();
    }

    /** Load each {@code *_connection.toon} (Data Acquisition); a bad one is warned and skipped. */
    static List<com.gamma.acquire.ConnectionProfile> loadConnections(List<Path> paths) {
        List<com.gamma.acquire.ConnectionProfile> out = new ArrayList<>();
        for (Path p : paths) {
            try {
                com.gamma.acquire.ConnectionProfile c = com.gamma.acquire.ConnectionProfile.load(p);
                out.add(c);
                log.info("Loaded connection profile '{}' ({} -> {}) from {}",
                        c.id(), c.connector(), c.isRemote() ? c.testEndpoint() : "local", p);
            } catch (Exception e) {
                log.warn("Could not load connection profile {}: {}", p, e.getMessage());
            }
        }
        return out;
    }

    /** Load each {@code *_job_template.toon} (PIP-6) by name; a bad one is warned and skipped. */
    private static Map<String, com.gamma.job.JobTemplate> loadJobTemplates(List<Path> paths) {
        Map<String, com.gamma.job.JobTemplate> out = new java.util.LinkedHashMap<>();
        for (Path p : paths) {
            try {
                com.gamma.job.JobTemplate t = com.gamma.job.JobTemplate.load(p.toString());
                if (out.putIfAbsent(t.name(), t) != null)
                    log.warn("Duplicate job template '{}' at {} — keeping the first", t.name(), p);
                else log.info("Loaded job template '{}' ({} param(s)) from {}", t.name(), t.paramDefaults().size(), p);
            } catch (Exception e) {
                log.warn("Could not load job template {}: {}", p, e.getMessage());
            }
        }
        return out;
    }

    /** Load each {@code *_job.toon}, expanding {@code template:} references (PIP-6); a bad one is
     *  warned and skipped (others still host). */
    private static List<JobConfig> loadJobs(List<Path> paths, Map<String, com.gamma.job.JobTemplate> templates) {
        List<JobConfig> jobs = new ArrayList<>();
        for (Path p : paths) {
            try {
                Map<String, Object> raw = com.gamma.util.ToonHelper.load(p.toString());
                Map<String, Object> job = com.gamma.util.ToonHelper.requireSection(raw, "job");
                Object templateRef = job.get("template");
                if (templateRef != null) {
                    com.gamma.job.JobTemplate t = templates.get(String.valueOf(templateRef).trim());
                    if (t == null) throw new IllegalArgumentException(
                            "references unknown job template '" + templateRef + "'");
                    job = t.instantiate(job);
                }
                JobConfig c = JobConfig.fromMap(Map.of("job", job));
                jobs.add(c);
                log.info("Registered {} job '{}'{} from {}", c.type(), c.name(),
                        templateRef != null ? " (template " + templateRef + ")" : "", p);
            } catch (Exception e) {
                log.warn("Could not load job config {}: {}", p, e.getMessage());
            }
        }
        return jobs;
    }
}

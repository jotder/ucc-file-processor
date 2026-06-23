package com.gamma.service;

import com.gamma.catalog.SemanticModel;
import com.gamma.enrich.EnrichmentConfig;
import com.gamma.inspector.MultiSourceProcessor;
import com.gamma.job.JobConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Builds a fully-wired {@link SourceService} from CLI-style args by scanning each path
 * (file or dir) for the various {@code *.toon} config types. This is the service's
 * <em>bootstrap</em> concern — discovering and parsing configuration off disk — kept
 * separate from {@link SourceService}'s runtime responsibilities (poll loop, scheduling,
 * event wiring, lifecycle).
 *
 * <p>Each loader warns-and-skips a bad file so one malformed config never blocks the
 * others. Logs under {@link SourceService}'s category so existing log output is unchanged.
 */
final class ServiceBootstrap {

    private static final Logger log = LoggerFactory.getLogger(SourceService.class);

    private ServiceBootstrap() {}

    /**
     * Build a fully-wired single-tenant service from CLI-style args (the legacy flat layout): each path (file or
     * dir) is scanned for the {@code *.toon} config types. Reads {@code -Dservice.poll.seconds} (default 60) and
     * {@code -Dservice.max.runs} (default = source count). Shared by the service and Control API entry points.
     * Exits the JVM with a message if no sources are found.
     */
    static SourceService build(String[] args) throws IOException {
        return buildFrom(SpaceRoot.legacy(), args, true);
    }

    /**
     * Build a service rooted at {@code root}, discovering its {@code *.toon} configs by scanning {@code paths}.
     * The single-tenant entry point passes {@link SpaceRoot#legacy()} + the CLI args; {@link SpaceBootstrap}
     * passes a per-space {@link SpaceRoot} + its {@code config/} directory. When {@code exitIfEmpty} is set (the
     * CLI), a config-less invocation exits the JVM; a space tolerates an empty {@code config/} (a freshly created
     * space has no sources yet), so {@code SpaceBootstrap} passes {@code false}.
     */
    static SourceService buildFrom(SpaceRoot root, String[] paths, boolean exitIfEmpty) throws IOException {
        List<Path> registry = MultiSourceProcessor.resolveConfigs(paths);
        List<EnrichmentConfig> enrichJobs = loadEnrichJobs(resolveBySuffix(paths, "_enrich.toon"));
        List<JobConfig> jobConfigs = loadJobs(resolveBySuffix(paths, "_job.toon"));
        List<SemanticModel> semantics = loadSemantics(resolveBySuffix(paths, "_meta.toon"));
        List<com.gamma.alert.AlertRule> alertRules = loadAlerts(resolveBySuffix(paths, "_alert.toon"));
        if (registry.isEmpty() && enrichJobs.isEmpty() && jobConfigs.isEmpty() && exitIfEmpty) {
            System.err.println("No *_pipeline.toon / *_enrich.toon / *_job.toon files found in: "
                    + String.join(", ", paths));
            System.exit(1);
        }
        long pollSeconds = Long.getLong("service.poll.seconds", 60L);
        int  maxRuns     = Integer.getInteger("service.max.runs", Math.max(1, registry.size()));
        SourceService svc = new SourceService(registry, enrichJobs, jobConfigs, semantics, alertRules,
                pollSeconds, maxRuns, ServiceStores.openStatusStore(root), root);
        for (com.gamma.ops.rca.RcaTemplate t : loadRcaTemplates(resolveBySuffix(paths, "_rca.toon")))
            svc.registerRcaTemplate(t);
        for (com.gamma.acquire.ConnectionProfile c : loadConnections(resolveBySuffix(paths, "_connection.toon")))
            svc.registerConnection(c);
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

    /** Load each {@code *_alert.toon}; a bad one is warned and skipped (others still arm). */
    private static List<com.gamma.alert.AlertRule> loadAlerts(List<Path> paths) {
        List<com.gamma.alert.AlertRule> rules = new ArrayList<>();
        for (Path p : paths) {
            try {
                com.gamma.alert.AlertRule r = com.gamma.alert.AlertRule.load(p);
                rules.add(r);
                log.info("Armed alert rule '{}' ({} {} {} over {}) from {}",
                        r.name(), r.metric(), r.comparator(), r.threshold(), r.window(), p);
            } catch (Exception e) {
                log.warn("Could not load alert rule {}: {}", p, e.getMessage());
            }
        }
        return rules;
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

    /** Load each {@code *_job.toon}; a bad one is warned and skipped (others still host). */
    private static List<JobConfig> loadJobs(List<Path> paths) {
        List<JobConfig> jobs = new ArrayList<>();
        for (Path p : paths) {
            try {
                JobConfig c = JobConfig.load(p.toString());
                jobs.add(c);
                log.info("Registered {} job '{}' from {}", c.type(), c.name(), p);
            } catch (Exception e) {
                log.warn("Could not load job config {}: {}", p, e.getMessage());
            }
        }
        return jobs;
    }
}

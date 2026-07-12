package com.gamma.job;

import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.signal.Severity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code metadata_validate} maintenance task (System Maintenance MNT-7): a read-only
 * cross-component integrity audit over the component registry ({@code <write-root>/registry/}).
 *
 * <p>Finding classes — deliberately grounded in the reference shapes the demo space ships, never
 * guessed: <b>broken references</b> (a widget's {@code datasetId}/{@code queryId} naming a missing
 * Dataset/Query; a dashboard tile's {@code widgetId} naming a missing Widget), <b>duplicate
 * definitions</b> (two components of one type whose content is identical apart from {@code name}),
 * and <b>missing physical data</b> (a Dataset whose {@code physicalRef} resolves to nothing under the
 * data root — checked only when a data root is configured; never guessed otherwise). Reference keys
 * for other kinds (Expectations, Decision Rules, views) are not statically declared anywhere yet, so
 * this task does not invent checks for them — extend here as their shapes firm up.
 *
 * <p>Findings go to the Run Log and, when any exist, one {@code maintenance.metadata.findings}
 * WARNING signal an Alert Rule can subscribe to. Dry run and real run are identical (read-only).
 */
final class MetadataValidateTask {

    private MetadataValidateTask() {}

    static JobResult run(JobContext ctx, String dataDir) {
        long t0 = System.nanoTime();
        String writeRoot = System.getProperty("assist.write.root");
        if (writeRoot == null || writeRoot.isBlank()) {
            return JobResult.ok("metadata_validate: no component registry configured (-Dassist.write.root) — nothing to validate", 0L);
        }
        ComponentStore store = new ComponentStore(Path.of(writeRoot).resolve("registry"));
        Map<String, List<ComponentRegistry.Component>> byType = new LinkedHashMap<>();
        int total = 0;
        // Sweep whatever this build's store manages — never a hard-coded list, so a newly widened
        // component type is audited automatically.
        for (String type : ComponentStore.WRITABLE_TYPES.stream().sorted().toList()) {
            List<ComponentRegistry.Component> list = store.list(type);
            byType.put(type, list);
            total += list.size();
        }
        List<String> findings = new ArrayList<>();
        brokenRefs(byType, findings);
        duplicates(byType, findings);
        missingPhysical(byType.get("dataset"), dataDir, findings);
        if (ctx != null) {
            for (String f : findings) ctx.log().warn(f);
            if (!findings.isEmpty()) {
                ctx.signals().emit("maintenance.metadata.findings", Severity.WARNING,
                        Map.of("count", findings.size(), "findings", findings));
            }
        }
        return JobResult.ok("metadata_validate: " + findings.size() + " finding(s) across " + total
                + " component(s)" + (findings.isEmpty() ? " — healthy" : ""),
                (System.nanoTime() - t0) / 1_000_000L);
    }

    /** Widget → Dataset/Query and dashboard tile → Widget reference checks. */
    private static void brokenRefs(Map<String, List<ComponentRegistry.Component>> byType, List<String> findings) {
        var datasets = names(byType.get("dataset"));
        var queries = names(byType.get("query"));
        var widgets = names(byType.get("widget"));
        for (ComponentRegistry.Component w : byType.get("widget")) {
            String datasetId = str(w.content().get("datasetId"));
            if (datasetId != null && !datasets.contains(datasetId))
                findings.add("broken reference: widget '" + w.name() + "' → missing dataset '" + datasetId + "'");
            String queryId = str(w.content().get("queryId"));
            if (queryId != null && !queries.contains(queryId))
                findings.add("broken reference: widget '" + w.name() + "' → missing query '" + queryId + "'");
        }
        for (ComponentRegistry.Component d : byType.get("dashboard")) {
            if (!(d.content().get("tiles") instanceof List<?> tiles)) continue;
            for (Object t : tiles) {
                if (!(t instanceof Map<?, ?> tile)) continue;
                String widgetId = str(tile.get("widgetId"));
                if (widgetId != null && !widgets.contains(widgetId))
                    findings.add("broken reference: dashboard '" + d.name() + "' tile → missing widget '" + widgetId + "'");
            }
        }
    }

    /** Two components of one type with identical content apart from {@code name}. */
    private static void duplicates(Map<String, List<ComponentRegistry.Component>> byType, List<String> findings) {
        for (Map.Entry<String, List<ComponentRegistry.Component>> e : byType.entrySet()) {
            Map<Map<String, Object>, String> seen = new HashMap<>();
            for (ComponentRegistry.Component c : e.getValue()) {
                Map<String, Object> body = new HashMap<>(c.content());
                body.remove("name");
                String first = seen.putIfAbsent(body, c.name());
                if (first != null && !first.equals(c.name()))
                    findings.add("duplicate definition: " + e.getKey() + "s '" + first + "' and '"
                            + c.name() + "' are identical apart from the name");
            }
        }
    }

    /** A Dataset whose {@code physicalRef} has no store dir/file under the data root. */
    private static void missingPhysical(List<ComponentRegistry.Component> datasets, String dataDir,
                                        List<String> findings) {
        if (dataDir == null || dataDir.isBlank() || !Files.isDirectory(Path.of(dataDir))) return;
        for (ComponentRegistry.Component d : datasets) {
            String ref = str(d.content().get("physicalRef"));
            if (ref == null || ref.contains("/") || ref.contains("\\")) continue;   // only plain store names are checkable
            if (!Files.exists(Path.of(dataDir).resolve(ref)))
                findings.add("missing physical data: dataset '" + d.name() + "' → no store '" + ref
                        + "' under the data root");
        }
    }

    private static java.util.Set<String> names(List<ComponentRegistry.Component> list) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (ComponentRegistry.Component c : list) out.add(c.name());
        return out;
    }

    private static String str(Object o) {
        return o == null || String.valueOf(o).isBlank() ? null : String.valueOf(o);
    }
}

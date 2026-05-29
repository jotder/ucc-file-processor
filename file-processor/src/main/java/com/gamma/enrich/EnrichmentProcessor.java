package com.gamma.enrich;

import com.gamma.etl.PartitionOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CLI entry point for the Stage-2 enrichment engine.
 *
 * <pre>
 *   java -cp file-processor.jar com.gamma.enrich.EnrichmentProcessor &lt;enrich.toon&gt; [--partitions SPEC]
 * </pre>
 *
 * <ul>
 *   <li>No {@code --partitions} → <b>full</b> recompute over all input partitions.</li>
 *   <li>{@code --partitions "event_type=CALL/year=2020/month=04/day=03;event_type=SMS/..."}
 *       → <b>incremental</b> recompute of just those partitions (semicolon-separated;
 *       each is {@code col=val/col=val/...}). In the service this list comes from a
 *       committed batch's lineage; on the CLI it's supplied by hand.</li>
 * </ul>
 */
public final class EnrichmentProcessor {

    private static final Logger log = LoggerFactory.getLogger(EnrichmentProcessor.class);

    private EnrichmentProcessor() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: EnrichmentProcessor <enrich.toon> [--partitions col=val/...;...]");
            System.exit(1);
        }
        EnrichmentConfig cfg = EnrichmentConfig.load(args[0]);

        List<Map<String, String>> filter = null;
        for (int i = 1; i < args.length - 1; i++) {
            if ("--partitions".equals(args[i])) filter = parsePartitions(args[i + 1]);
        }

        List<PartitionOutput> outputs = EnrichmentEngine.run(cfg, filter);
        log.info("[ENRICH] {} complete: {} partition file(s) under {}",
                cfg.name(), outputs.size(), cfg.output().database());
        for (PartitionOutput o : outputs) log.info("  {} ({} bytes)", o.partition(), o.bytes());
    }

    /** Parse {@code "col=val/col=val;col=val/..."} into a list of partition-value maps. */
    static List<Map<String, String>> parsePartitions(String spec) {
        List<Map<String, String>> out = new ArrayList<>();
        for (String group : spec.split(";")) {
            if (group.isBlank()) continue;
            Map<String, String> m = new LinkedHashMap<>();
            for (String kv : group.split("/")) {
                int eq = kv.indexOf('=');
                if (eq > 0) m.put(kv.substring(0, eq).trim(), kv.substring(eq + 1).trim());
            }
            if (!m.isEmpty()) out.add(m);
        }
        return out;
    }
}

package com.gamma.acquire.connectors;

import com.gamma.acquire.AcquisitionException;
import com.gamma.acquire.AcquisitionLedgers;
import com.gamma.acquire.ConnectionProfile;
import com.gamma.acquire.DiscoveryContext;
import com.gamma.acquire.PostAction;
import com.gamma.acquire.RemoteFile;
import com.gamma.acquire.SecretResolver;
import com.gamma.acquire.SourceConnector;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.gamma.acquire.SourceConnector.Capability.STREAM;

/**
 * A <b>Kafka topic</b> {@link SourceConnector} (ACQ-5 — streaming source consumer). Each scan cycle drains the
 * unconsumed backlog of a topic partition into a file, which then flows through the normal Inspecto batch path
 * exactly like any other acquired file — the {@link DbExportConnector} virtual-file pattern applied to a stream.
 *
 * <h3>Offsets live in the acquisition ledger, not a consumer group</h3>
 * The connector uses {@code assign()} + {@code seek()} — no group coordinator, no broker-side commit, minimal
 * ACLs. The consumed frontier (next offset per partition) rides the ledger's per-key watermark exactly like the
 * DB-export row watermark: {@link #fetchTo} stashes the reached offset via
 * {@link AcquisitionLedgers#stashDbWatermark}, and {@code BatchProcessor.commit} persists it only <em>after the
 * batch is durable</em> — so a crash mid-ingest re-drains the slice rather than skipping it (at-least-once).
 *
 * <h3>Configuration (in the bound {@link ConnectionProfile})</h3>
 * <ul>
 *   <li>{@code host}/{@code port} — a bootstrap broker; or {@code options.bootstrap_servers} for a full
 *       {@code host1:9092,host2:9092} list.</li>
 *   <li>{@code topic} <b>(required, in options)</b> — the topic to consume.</li>
 *   <li>{@code username}/{@code password} — optional SASL credentials ({@link SecretResolver} references);
 *       mechanism from {@code options.sasl_mechanism} (default {@code PLAIN}), wire security from
 *       {@code options.security_protocol} (default {@code SASL_PLAINTEXT} when credentials are set, else
 *       {@code PLAINTEXT}).</li>
 *   <li>{@code options.start} — first-run position when no watermark is stored: {@code earliest} (default) |
 *       {@code latest}.</li>
 *   <li>{@code options.max_records} — per-partition drain cap per cycle (default 100000); a longer backlog
 *       carries over to the next cycle.</li>
 *   <li>{@code options.payload} — {@code envelope} (default): one JSON object per record
 *       ({@code {"topic","partition","offset","timestamp","key","value"}}, key/value decoded as UTF-8 text);
 *       {@code raw}: the record value verbatim, one per line (CSV-over-Kafka feeds; tombstones skipped).</li>
 *   <li>{@code options.export_ext} — extension of the drained file (default {@code ndjson}; use e.g.
 *       {@code csv} with {@code payload: raw} so the name matches the pipeline's file pattern).</li>
 *   <li>{@code options.kafka.*} — passthrough: {@code kafka.fetch.max.bytes: 1048576} sets the client
 *       property {@code fetch.max.bytes} verbatim (values may be {@code ${…}} secret references).</li>
 * </ul>
 *
 * <p>The drained file is named {@code <topic>-p<partition>-<from>-<to>.<ext>} — the offset range is the stable
 * per-slice identity, so marker/ledger dedup naturally ingests each slice once. Like the DB export, a drain has
 * no source-side file, so the connector is {@link Capability#STREAM}-only and any post-action except RETAIN is
 * rejected. Payloads are treated as text (UTF-8); binary values need a decode step downstream.
 */
public final class KafkaConnector implements SourceConnector {

    private static final Logger log = LoggerFactory.getLogger(KafkaConnector.class);

    /** Parses partition/from/to back out of the slice name minted by {@link #discover}. */
    private static final Pattern SLICE_NAME = Pattern.compile("-p(\\d+)-(\\d+)-(\\d+)\\.[^.]+$");

    private static final long DEFAULT_MAX_RECORDS = 100_000;
    private static final long POLL_MILLIS = 1_000;
    /** Consecutive empty polls before concluding the remaining range is gone (retention) or unreachable. */
    private static final int MAX_EMPTY_POLLS = 5;

    private final ConnectionProfile profile;
    private final Function<Properties, Consumer<byte[], byte[]>> consumerFactory;
    private final String topic;
    private final long maxRecords;
    private final boolean rawPayload;
    private final boolean startLatest;
    private final String ext;

    private Consumer<byte[], byte[]> consumer;   // one per connector lifetime (= one scan cycle)

    public KafkaConnector(ConnectionProfile profile, Function<Properties, Consumer<byte[], byte[]>> consumerFactory) {
        this.profile = profile;
        this.consumerFactory = consumerFactory;
        this.topic = profile.options().get("topic");
        if (topic == null || topic.isBlank())
            throw new IllegalArgumentException("kafka connection '" + profile.id() + "' needs options.topic");
        this.maxRecords = longOption("max_records", DEFAULT_MAX_RECORDS);
        String payload = profile.options().getOrDefault("payload", "envelope");
        this.rawPayload = switch (payload) {
            case "raw" -> true;
            case "envelope" -> false;
            default -> throw new IllegalArgumentException("kafka connection '" + profile.id()
                    + "': options.payload must be 'envelope' or 'raw', not '" + payload + "'");
        };
        String start = profile.options().getOrDefault("start", "earliest");
        this.startLatest = switch (start) {
            case "latest" -> true;
            case "earliest" -> false;
            default -> throw new IllegalArgumentException("kafka connection '" + profile.id()
                    + "': options.start must be 'earliest' or 'latest', not '" + start + "'");
        };
        this.ext = profile.options().getOrDefault("export_ext", "ndjson");
    }

    @Override
    public String scheme() {
        return "kafka";
    }

    @Override
    public EnumSet<Capability> capabilities() {
        return EnumSet.of(STREAM);   // a drained slice has no source-side file to delete/move/rename
    }

    @Override
    public List<RemoteFile> discover(DiscoveryContext ctx) throws AcquisitionException {
        try {
            Consumer<byte[], byte[]> c = ensureConsumer();
            List<PartitionInfo> parts = c.partitionsFor(topic);
            if (parts == null || parts.isEmpty()) return List.of();   // topic not created yet: nothing, quietly
            List<TopicPartition> tps = parts.stream().map(p -> new TopicPartition(topic, p.partition())).toList();
            Map<TopicPartition, Long> begin = c.beginningOffsets(tps);
            Map<TopicPartition, Long> end = c.endOffsets(tps);
            PatternFilter filter = new PatternFilter(ctx.includes(), ctx.excludes());

            List<RemoteFile> out = new ArrayList<>();
            for (TopicPartition tp : tps) {
                long from = AcquisitionLedgers.shared().dbWatermark(watermarkKey(tp.partition()))
                        .map(Long::parseLong)
                        .orElse(startLatest ? end.get(tp) : begin.get(tp));
                from = Math.max(from, begin.get(tp));   // retention may have pruned past the stored frontier
                long to = Math.min(end.get(tp), from + maxRecords);
                if (to <= from) continue;               // no backlog on this partition
                String name = topic + "-p" + tp.partition() + "-" + from + "-" + to + "." + ext;
                if (!filter.accepts(name)) continue;
                out.add(new RemoteFile(name, name, RemoteFile.SIZE_UNKNOWN, Instant.now(), null, null, null));
            }
            return out;
        } catch (RuntimeException e) {
            throw new AcquisitionException("Kafka discovery failed for '" + profile.id() + "' topic '" + topic
                    + "': " + e.getMessage(), e);
        }
    }

    @Override
    public Readiness readiness(RemoteFile file) {
        return Readiness.READY;   // listed records already exist on the broker
    }

    @Override
    public InputStream open(RemoteFile file) throws AcquisitionException {
        // STREAM via a self-deleting temp file (the DbExportConnector idiom): the drain runs once either way.
        try {
            Path tmp = Files.createTempFile("kafka-", "." + ext);
            fetchTo(file, tmp);
            InputStream in = Files.newInputStream(tmp);
            return new java.io.FilterInputStream(in) {
                @Override public void close() throws IOException {
                    try { super.close(); } finally { Files.deleteIfExists(tmp); }
                }
            };
        } catch (IOException e) {
            throw new AcquisitionException("Kafka stream failed for " + file.relativePath(), e);
        }
    }

    @Override
    public Path fetchTo(RemoteFile file, Path dest) throws AcquisitionException {
        Matcher m = SLICE_NAME.matcher(file.relativePath());
        if (!m.find())
            throw new AcquisitionException("not a kafka slice name: " + file.relativePath());
        int partition = Integer.parseInt(m.group(1));
        long from = Long.parseLong(m.group(2));
        long to = Long.parseLong(m.group(3));
        TopicPartition tp = new TopicPartition(topic, partition);
        try {
            if (dest.getParent() != null) Files.createDirectories(dest.getParent());
            Consumer<byte[], byte[]> c = ensureConsumer();
            c.assign(List.of(tp));
            c.seek(tp, from);
            long pos = from;
            long written = 0;
            try (BufferedWriter w = Files.newBufferedWriter(dest, StandardCharsets.UTF_8)) {
                int emptyPolls = 0;
                while (pos < to) {
                    ConsumerRecords<byte[], byte[]> recs = c.poll(Duration.ofMillis(POLL_MILLIS));
                    if (recs.isEmpty()) {
                        // The range can shrink between discover and drain (retention); give up after a few
                        // quiet polls — what was written stays, the frontier advances only to what arrived.
                        if (++emptyPolls >= MAX_EMPTY_POLLS) break;
                        continue;
                    }
                    emptyPolls = 0;
                    for (ConsumerRecord<byte[], byte[]> r : recs.records(tp)) {
                        if (r.offset() >= to) { pos = to; break; }
                        String line = rawPayload ? rawLine(r) : envelopeLine(r);
                        if (line != null) { w.write(line); w.newLine(); written++; }
                        pos = r.offset() + 1;
                    }
                }
            }
            if (pos == from)
                throw new AcquisitionException("Kafka drain got no records for " + file.relativePath()
                        + " (broker unreachable or slice pruned) — will retry next cycle");
            // Advance the frontier only after the batch commits — stash it for BatchProcessor to persist
            // (the DB-export watermark machinery; key is per topic-partition).
            AcquisitionLedgers.stashDbWatermark(dest, watermarkKey(partition), Long.toString(pos));
            log.info("Kafka drain {} p{} [{},{}) → {} record(s) → {}", topic, partition, from, pos, written,
                    dest.getFileName());
            return dest;
        } catch (IOException e) {
            throw new AcquisitionException("Kafka drain failed for '" + profile.id() + "' → " + dest + ": "
                    + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new AcquisitionException("Kafka drain failed for '" + profile.id() + "' topic '" + topic
                    + "': " + e.getMessage(), e);
        }
    }

    @Override
    public void post(RemoteFile file, PostAction action) throws AcquisitionException {
        if (action.kind() != PostAction.Kind.RETAIN)
            throw new AcquisitionException("kafka source has no source-side file to " + action.kind());
    }

    @Override
    public void close() {
        if (consumer != null) {
            try { consumer.close(); } catch (RuntimeException ignore) { /* best-effort */ }
            consumer = null;
        }
    }

    // ── record → line ─────────────────────────────────────────────────────────

    /** One JSON object per record; key/value decoded as UTF-8 text, a null (tombstone) value stays null. */
    private String envelopeLine(ConsumerRecord<byte[], byte[]> r) {
        StringBuilder b = new StringBuilder(64 + (r.value() == null ? 0 : r.value().length));
        b.append("{\"topic\":\"").append(jsonEscape(topic))
                .append("\",\"partition\":").append(r.partition())
                .append(",\"offset\":").append(r.offset())
                .append(",\"timestamp\":").append(r.timestamp())
                .append(",\"key\":").append(jsonString(r.key()))
                .append(",\"value\":").append(jsonString(r.value()))
                .append('}');
        return b.toString();
    }

    /** The record value verbatim (CSV-over-Kafka); a tombstone (null value) produces no line. */
    private static String rawLine(ConsumerRecord<byte[], byte[]> r) {
        return r.value() == null ? null : new String(r.value(), StandardCharsets.UTF_8);
    }

    private static String jsonString(byte[] bytes) {
        return bytes == null ? "null" : "\"" + jsonEscape(new String(bytes, StandardCharsets.UTF_8)) + "\"";
    }

    private static String jsonEscape(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                case '\b' -> b.append("\\b");
                case '\f' -> b.append("\\f");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.toString();
    }

    // ── client construction ───────────────────────────────────────────────────

    private Consumer<byte[], byte[]> ensureConsumer() {
        if (consumer == null) consumer = consumerFactory.apply(clientProperties());
        return consumer;
    }

    /** The Kafka client config from the profile: bootstrap, no group/commit, optional SASL, {@code kafka.*} passthrough. */
    private Properties clientProperties() {
        Properties p = new Properties();
        String bootstrap = profile.options().get("bootstrap_servers");
        if (bootstrap == null || bootstrap.isBlank()) {
            if (profile.host() == null || profile.host().isBlank())
                throw new IllegalArgumentException("kafka connection '" + profile.id()
                        + "' needs host/port or options.bootstrap_servers");
            bootstrap = profile.host() + ":" + (profile.port() > 0 ? profile.port() : 9092);
        }
        p.put("bootstrap.servers", bootstrap);
        p.put("key.deserializer", "org.apache.kafka.common.serialization.ByteArrayDeserializer");
        p.put("value.deserializer", "org.apache.kafka.common.serialization.ByteArrayDeserializer");
        p.put("enable.auto.commit", "false");     // the ledger watermark is the offset store, not a group
        p.put("auto.offset.reset", "earliest");   // a retention-pruned seek resumes at the new beginning
        p.put("client.id", "inspecto-" + profile.id());
        String user = profile.username();
        if (user != null && !user.isBlank()) {
            String pass = SecretResolver.resolve(profile.password());
            p.put("security.protocol", profile.options().getOrDefault("security_protocol", "SASL_PLAINTEXT"));
            p.put("sasl.mechanism", profile.options().getOrDefault("sasl_mechanism", "PLAIN"));
            p.put("sasl.jaas.config", "org.apache.kafka.common.security.plain.PlainLoginModule required"
                    + " username=\"" + jaasEscape(user) + "\" password=\"" + jaasEscape(pass) + "\";");
        } else if (profile.options().containsKey("security_protocol")) {
            p.put("security.protocol", profile.options().get("security_protocol"));
        }
        profile.options().forEach((k, v) -> {
            if (k.startsWith("kafka.")) p.put(k.substring("kafka.".length()), SecretResolver.resolve(v));
        });
        return p;
    }

    private static String jaasEscape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** The ledger watermark key holding this partition's consumed frontier (next offset to read). */
    private String watermarkKey(int partition) {
        return "kafka:" + profile.id() + ":" + topic + ":p" + partition;
    }

    private long longOption(String key, long dflt) {
        String v = profile.options().get(key);
        if (v == null || v.isBlank()) return dflt;
        try {
            long n = Long.parseLong(v.trim());
            if (n <= 0) throw new NumberFormatException();
            return n;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("kafka connection '" + profile.id() + "': options." + key
                    + " must be a positive integer, not '" + v + "'");
        }
    }
}

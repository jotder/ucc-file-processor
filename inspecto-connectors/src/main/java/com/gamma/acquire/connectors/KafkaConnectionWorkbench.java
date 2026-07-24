package com.gamma.acquire.connectors;

import com.gamma.acquire.AcquisitionException;
import com.gamma.acquire.ConnectionProfile;
import com.gamma.acquire.ConnectionWorkbench;
import com.gamma.acquire.SecretResolver;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Function;

/**
 * {@link ConnectionWorkbench} for {@code kafka} connection profiles — probes, explores and samples a broker
 * independently of any one {@link KafkaConnector}'s bound topic (a workbench browses the whole broker: explore
 * walks a {@code topic → partition} tree; {@link KafkaConnector} itself always targets one configured topic).
 * The consumer is a throwaway {@code assign()}/{@code seek()} reader like the connector's own drain — no
 * consumer group, and critically <b>no interaction with the acquisition ledger watermark</b>: a sample never
 * advances or is seen by the real ingest frontier.
 *
 * <p><b>Read-only.</b> The {@code write} probe check is always reported <em>skipped</em> — a workbench must
 * never produce a probe record onto someone's topic (the same discipline as {@link DbConnectionWorkbench}).
 */
final class KafkaConnectionWorkbench implements ConnectionWorkbench {

    private static final Duration LIST_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(2);
    private static final int MAX_EMPTY_POLLS = 5;

    private final ConnectionProfile profile;
    private final Function<Properties, Consumer<byte[], byte[]>> consumerFactory;
    private Consumer<byte[], byte[]> consumer;   // opened lazily on first check/explore/sample

    KafkaConnectionWorkbench(ConnectionProfile profile) {
        this(profile, KafkaConsumer::new);
    }

    /** Test seam — a {@link org.apache.kafka.clients.consumer.MockConsumer} instead of a real broker client. */
    KafkaConnectionWorkbench(ConnectionProfile profile, Function<Properties, Consumer<byte[], byte[]>> consumerFactory) {
        this.profile = profile;
        this.consumerFactory = consumerFactory;
    }

    private synchronized Consumer<byte[], byte[]> consumer() {
        if (consumer == null) consumer = consumerFactory.apply(clientProperties());
        return consumer;
    }

    @Override
    public CheckOutcome check(ProbeCheck check, int sampleLimit) {
        return switch (check) {
            case AUTHENTICATE -> {
                try {
                    int n = consumer().listTopics(LIST_TIMEOUT).size();
                    yield CheckOutcome.ok(check, "connected — " + n + " topic(s) visible");
                } catch (RuntimeException e) {
                    yield CheckOutcome.fail(check, "connect failed: " + e.getMessage());
                }
            }
            case READ -> {
                try {
                    int n = consumer().listTopics(LIST_TIMEOUT).size();
                    yield CheckOutcome.ok(check, n + " topic(s) readable");
                } catch (RuntimeException e) {
                    yield CheckOutcome.fail(check, "topic catalog unreadable: " + e.getMessage());
                }
            }
            case WRITE -> CheckOutcome.skipped(check, "kafka workbench is read-only — writes are never probed");
            case LIST -> {
                int cap = Math.max(1, sampleLimit);
                try {
                    int n = Math.min(consumer().listTopics(LIST_TIMEOUT).size(), cap);
                    yield CheckOutcome.ok(check, "listed " + n + " topic(s)" + (n == cap ? " (capped at " + cap + ")" : ""));
                } catch (RuntimeException e) {
                    yield CheckOutcome.fail(check, "cannot list topics: " + e.getMessage());
                }
            }
            default -> throw new IllegalStateException("check " + check + " is answered by the prober");
        };
    }

    @Override
    public List<ResourceNode> explore(String path) throws AcquisitionException {
        String p = norm(path);
        String[] parts = p.isEmpty() ? new String[0] : p.split("/");
        try {
            return switch (parts.length) {
                case 0 -> topics();
                case 1 -> partitions(parts[0]);
                default -> throw new NoSuchPath("kafka explore path is '<topic>' or '<topic>/<partition>': " + p);
            };
        } catch (RuntimeException e) {
            if (e instanceof NoSuchPath) throw e;
            throw new AcquisitionException("explore failed for '" + p + "': " + e.getMessage(), e);
        }
    }

    @Override
    public SampleResult sample(String path, int limit) throws AcquisitionException {
        String p = norm(path);
        String[] parts = p.isEmpty() ? new String[0] : p.split("/");
        if (parts.length != 2) throw new NoSuchPath("kafka sample path is '<topic>/<partition>': " + p);
        String topic = parts[0];
        int partition;
        try {
            partition = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            throw new NoSuchPath("kafka sample path is '<topic>/<partition>': " + p);
        }
        TopicPartition tp = new TopicPartition(topic, partition);
        List<PartitionInfo> infos = consumer().partitionsFor(topic);
        if (infos == null || infos.stream().noneMatch(i -> i.partition() == partition))
            throw new NoSuchPath("no such topic/partition under the connection: " + p);

        try {
            long begin = consumer().beginningOffsets(List.of(tp)).get(tp);
            long end = consumer().endOffsets(List.of(tp)).get(tp);
            int cap = Math.max(1, limit);
            List<Map<String, Object>> rows = new ArrayList<>();
            if (end > begin) {
                consumer().assign(List.of(tp));
                consumer().seek(tp, begin);
                long pos = begin;
                int emptyPolls = 0;
                while (pos < end && rows.size() < cap) {
                    ConsumerRecords<byte[], byte[]> recs = consumer().poll(POLL_TIMEOUT);
                    if (recs.isEmpty()) {
                        if (++emptyPolls >= MAX_EMPTY_POLLS) break;
                        continue;
                    }
                    emptyPolls = 0;
                    for (ConsumerRecord<byte[], byte[]> r : recs.records(tp)) {
                        if (rows.size() >= cap) break;
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("partition", r.partition());
                        row.put("offset", r.offset());
                        row.put("timestamp", r.timestamp());
                        row.put("key", text(r.key()));
                        row.put("value", text(r.value()));
                        rows.add(row);
                        pos = r.offset() + 1;
                    }
                }
            }
            boolean truncated = (end - begin) > rows.size();
            return new SampleResult(p, List.of("partition", "offset", "timestamp", "key", "value"), rows, truncated,
                    rows.isEmpty() ? "no records available on this partition" : null);
        } catch (RuntimeException e) {
            throw new AcquisitionException("sample failed for '" + p + "': " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        if (consumer != null) {
            try { consumer.close(); } catch (RuntimeException ignore) { /* best effort */ }
            consumer = null;
        }
    }

    // ── explore walkers ─────────────────────────────────────────────────────────

    private List<ResourceNode> topics() {
        List<ResourceNode> out = new ArrayList<>();
        for (String t : consumer().listTopics(LIST_TIMEOUT).keySet())
            out.add(new ResourceNode(t, t, ResourceNode.Kind.DIR, true, null, null, null, null));
        out.sort(Comparator.comparing(ResourceNode::name));
        return out;
    }

    private List<ResourceNode> partitions(String topic) {
        List<PartitionInfo> infos = consumer().partitionsFor(topic);
        if (infos == null || infos.isEmpty()) throw new NoSuchPath("no such topic under the connection: " + topic);
        List<ResourceNode> out = new ArrayList<>();
        for (PartitionInfo i : infos) {
            String name = String.valueOf(i.partition());
            out.add(new ResourceNode(name, topic + "/" + name, ResourceNode.Kind.FILE, false, null, null, null, null));
        }
        out.sort(Comparator.comparingInt(n -> Integer.parseInt(n.name())));
        return out;
    }

    // ── client construction (topic-independent — mirrors KafkaConnector.clientProperties) ──────────────────

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
        p.put("enable.auto.commit", "false");
        p.put("auto.offset.reset", "earliest");
        p.put("client.id", "inspecto-workbench-" + profile.id());
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

    private static String text(byte[] bytes) {
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    private static String norm(String path) {
        String p = path == null ? "" : path.trim().replace('\\', '/');
        while (p.startsWith("/")) p = p.substring(1);
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p;
    }
}

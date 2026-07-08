package com.gamma.acquire.connectors;

import com.gamma.acquire.AcquisitionException;
import com.gamma.acquire.AcquisitionLedgers;
import com.gamma.acquire.ConnectionProfile;
import com.gamma.acquire.DiscoveryContext;
import com.gamma.acquire.InMemoryAcquisitionLedger;
import com.gamma.acquire.PostAction;
import com.gamma.acquire.RemoteFile;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives {@link KafkaConnector} against kafka-clients' in-jar {@link MockConsumer} — offline, no broker:
 * backlog discovery (ledger-watermark resume, retention clamp, max_records cap, latest start), the envelope
 * and raw drain formats, the commit-time offset stash (the DB-export watermark machinery), and the
 * no-source-side-file post-action rule.
 */
class KafkaConnectorTest {

    private static final String TOPIC = "cdr";
    private static final TopicPartition P0 = new TopicPartition(TOPIC, 0);
    private static final DiscoveryContext ALL = new DiscoveryContext(List.of("*"), List.of(), DiscoveryContext.UNBOUNDED);

    private InMemoryAcquisitionLedger ledger;
    private MockConsumer<byte[], byte[]> mock;
    private KafkaConnector connector;

    @BeforeEach
    void setUp() {
        ledger = new InMemoryAcquisitionLedger();
        AcquisitionLedgers.use(ledger);
        mock = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connector != null) connector.close();
        AcquisitionLedgers.use(null);
    }

    /** A connector over the shared {@link MockConsumer}, with {@code topic} preset and {@code extra} options on top. */
    private KafkaConnector connector(Map<String, String> extra) {
        Map<String, String> options = new HashMap<>(Map.of("topic", TOPIC));
        options.putAll(extra);
        ConnectionProfile profile = new ConnectionProfile("kafka-test", "kafka", "127.0.0.1", 9092,
                null, null, null, null, options, null);
        connector = new KafkaConnector(profile, props -> mock);
        return connector;
    }

    /** Publish partition metadata + offset bounds to the mock (what a real broker's metadata answers). */
    private void broker(long begin, long end) {
        mock.updatePartitions(TOPIC, List.of(new PartitionInfo(TOPIC, 0, null, null, null)));
        mock.updateBeginningOffsets(Map.of(P0, begin));
        mock.updateEndOffsets(Map.of(P0, end));
    }

    private static ConsumerRecord<byte[], byte[]> rec(long offset, String key, String value) {
        return new ConsumerRecord<>(TOPIC, 0, offset,
                key == null ? null : key.getBytes(StandardCharsets.UTF_8),
                value == null ? null : value.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void discoversBacklogAsOffsetRangeSlice() throws Exception {
        broker(0, 3);
        List<RemoteFile> found = connector(Map.of()).discover(ALL);
        assertEquals(1, found.size());
        RemoteFile slice = found.getFirst();
        assertEquals("cdr-p0-0-3.ndjson", slice.relativePath());
        assertNull(slice.etag(), "offset identity lives in the name, not an etag (no checksum to verify)");
        assertFalse(slice.isLocal());
    }

    @Test
    void resumesFromLedgerWatermarkClampedToRetentionAndCappedAtMaxRecords() throws Exception {
        // Stored frontier 1, but retention pruned to 3 ⇒ resume at 3; backlog 3..10 capped at 2 records.
        ledger.recordDbWatermark("kafka:kafka-test:cdr:p0", "1");
        broker(3, 10);
        List<RemoteFile> found = connector(Map.of("max_records", "2")).discover(ALL);
        assertEquals("cdr-p0-3-5.ndjson", found.getFirst().relativePath());
    }

    @Test
    void startLatestSkipsHistoryOnFirstRun() throws Exception {
        broker(0, 5);
        assertTrue(connector(Map.of("start", "latest")).discover(ALL).isEmpty(),
                "latest first run: the frontier is the end offset — nothing to drain");
        // …but a stored watermark always wins over the start policy.
        ledger.recordDbWatermark("kafka:kafka-test:cdr:p0", "2");
        assertEquals("cdr-p0-2-5.ndjson", connector.discover(ALL).getFirst().relativePath());
    }

    @Test
    void drainsEnvelopeNdjsonAndStashesFrontierForCommit(@TempDir Path dir) throws Exception {
        broker(0, 3);
        KafkaConnector c = connector(Map.of());
        RemoteFile slice = c.discover(ALL).getFirst();
        mock.schedulePollTask(() -> {
            mock.addRecord(rec(0, "k0", "v0"));
            mock.addRecord(rec(1, null, "{\"a\":1}"));
            mock.addRecord(rec(2, "k2", null));           // tombstone: value stays null in the envelope
        });
        Path dest = dir.resolve(slice.relativePath());
        assertEquals(dest, c.fetchTo(slice, dest));

        List<String> lines = Files.readAllLines(dest, StandardCharsets.UTF_8);
        assertEquals(3, lines.size());
        assertTrue(lines.get(0).contains("\"topic\":\"cdr\"") && lines.get(0).contains("\"offset\":0")
                && lines.get(0).contains("\"key\":\"k0\"") && lines.get(0).contains("\"value\":\"v0\""), lines.get(0));
        assertTrue(lines.get(1).contains("\"key\":null")
                && lines.get(1).contains("\"value\":\"{\\\"a\\\":1}\""), "JSON-in-JSON stays escaped: " + lines.get(1));
        assertTrue(lines.get(2).contains("\"value\":null"), lines.get(2));

        // The frontier is stashed against the staged file — BatchProcessor persists it only after commit.
        var stashed = AcquisitionLedgers.takeDbWatermark(dest).orElseThrow();
        assertEquals("kafka:kafka-test:cdr:p0", stashed.key());
        assertEquals("3", stashed.value());
        assertTrue(ledger.dbWatermark("kafka:kafka-test:cdr:p0").isEmpty(),
                "nothing persists to the ledger before the batch commits");
    }

    @Test
    void rawPayloadWritesValuesVerbatimAndSkipsTombstones(@TempDir Path dir) throws Exception {
        broker(0, 3);
        KafkaConnector c = connector(Map.of("payload", "raw", "export_ext", "csv"));
        RemoteFile slice = c.discover(ALL).getFirst();
        assertEquals("cdr-p0-0-3.csv", slice.relativePath(), "export_ext lets the name match the pipeline pattern");
        mock.schedulePollTask(() -> {
            mock.addRecord(rec(0, null, "r1,10"));
            mock.addRecord(rec(1, null, null));           // tombstone: no line, but the offset still advances
            mock.addRecord(rec(2, null, "r2,20"));
        });
        Path dest = dir.resolve(slice.relativePath());
        c.fetchTo(slice, dest);
        assertEquals(List.of("r1,10", "r2,20"), Files.readAllLines(dest, StandardCharsets.UTF_8));
        assertEquals("3", AcquisitionLedgers.takeDbWatermark(dest).orElseThrow().value());
    }

    @Test
    void emptyDrainThrowsAndAdvancesNothing(@TempDir Path dir) throws Exception {
        broker(0, 2);
        KafkaConnector c = connector(Map.of());
        RemoteFile slice = c.discover(ALL).getFirst();
        Path dest = dir.resolve(slice.relativePath());
        assertThrows(AcquisitionException.class, () -> c.fetchTo(slice, dest),
                "no records arriving must not ingest an empty slice");
        assertTrue(AcquisitionLedgers.takeDbWatermark(dest).isEmpty(), "the frontier must not move");
    }

    @Test
    void quietWhenTopicAbsentAndNoBacklog() throws Exception {
        // Topic not created yet: discover must return empty and create nothing (SPI contract).
        assertTrue(connector(Map.of()).discover(ALL).isEmpty());
        // Topic exists but fully consumed: same.
        broker(5, 5);
        ledger.recordDbWatermark("kafka:kafka-test:cdr:p0", "5");
        assertTrue(connector.discover(ALL).isEmpty());
    }

    @Test
    void rejectsNonRetainPostActionsAndBadOptions() {
        broker(0, 1);
        KafkaConnector c = connector(Map.of());
        RemoteFile slice = new RemoteFile("cdr-p0-0-1.ndjson", "cdr-p0-0-1.ndjson",
                RemoteFile.SIZE_UNKNOWN, null, null, null, null);
        assertDoesNotThrow(() -> c.post(slice, new PostAction(PostAction.Kind.RETAIN, null, Map.of())));
        assertThrows(AcquisitionException.class,
                () -> c.post(slice, new PostAction(PostAction.Kind.DELETE, null, Map.of())));

        ConnectionProfile noTopic = new ConnectionProfile("k", "kafka", "h", 9092, null, null, null, null, Map.of(), null);
        assertThrows(IllegalArgumentException.class, () -> new KafkaConnector(noTopic, props -> mock));
        assertThrows(IllegalArgumentException.class,
                () -> connector(Map.of("payload", "avro")), "unknown payload mode must fail at load");
        assertThrows(IllegalArgumentException.class, () -> connector(Map.of("max_records", "0")));
    }
}

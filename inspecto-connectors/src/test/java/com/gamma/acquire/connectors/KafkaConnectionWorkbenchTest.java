package com.gamma.acquire.connectors;

import com.gamma.acquire.ConnectionProfile;
import com.gamma.acquire.ConnectionWorkbench;
import com.gamma.acquire.ConnectionWorkbench.CheckOutcome;
import com.gamma.acquire.ConnectionWorkbench.ProbeCheck;
import com.gamma.acquire.ConnectionWorkbench.ResourceNode;
import com.gamma.acquire.ConnectionWorkbench.SampleResult;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Kafka {@link ConnectionWorkbench} against kafka-clients' in-jar {@link MockConsumer} — offline, no
 * broker: topic→partition explore (broker-wide, independent of any one bound topic), bounded record sampling,
 * and the graded probe (WRITE always skipped).
 */
class KafkaConnectionWorkbenchTest {

    private static final String TOPIC = "cdr";
    private static final TopicPartition P0 = new TopicPartition(TOPIC, 0);

    private MockConsumer<byte[], byte[]> mock;
    private KafkaConnectionWorkbench wb;

    @BeforeEach
    void setUp() {
        mock = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
        ConnectionProfile profile = new ConnectionProfile("kafka-wb", "kafka", "127.0.0.1", 9092,
                null, null, null, null, Map.of(), null);
        wb = new KafkaConnectionWorkbench(profile, props -> mock);
    }

    @AfterEach
    void tearDown() {
        wb.close();
    }

    private void broker(long begin, long end) {
        mock.updatePartitions(TOPIC, List.of(new PartitionInfo(TOPIC, 0, null, null, null)));
        mock.updateBeginningOffsets(Map.of(P0, begin));
        mock.updateEndOffsets(Map.of(P0, end));
    }

    @Test
    void exploreListsTopicsThenPartitions() throws Exception {
        broker(0, 3);
        List<ResourceNode> topics = wb.explore("");
        assertEquals(1, topics.size());
        assertEquals("cdr", topics.get(0).name());
        assertEquals(ResourceNode.Kind.DIR, topics.get(0).kind());
        assertTrue(topics.get(0).hasChildren());

        List<ResourceNode> partitions = wb.explore("cdr");
        assertEquals(1, partitions.size());
        assertEquals("0", partitions.get(0).name());
        assertEquals("cdr/0", partitions.get(0).path());
        assertEquals(ResourceNode.Kind.FILE, partitions.get(0).kind());

        assertThrows(ConnectionWorkbench.NoSuchPath.class, () -> wb.explore("nope"), "unknown topic");
        assertThrows(ConnectionWorkbench.NoSuchPath.class, () -> wb.explore("cdr/0/deep"), "path too deep");
    }

    @Test
    void sampleDrainsRecordsWithoutTouchingTheLedgerWatermark() throws Exception {
        broker(0, 3);
        mock.schedulePollTask(() -> {
            mock.addRecord(new ConsumerRecord<>(TOPIC, 0, 0, "k0".getBytes(StandardCharsets.UTF_8), "v0".getBytes(StandardCharsets.UTF_8)));
            mock.addRecord(new ConsumerRecord<>(TOPIC, 0, 1, null, "v1".getBytes(StandardCharsets.UTF_8)));
            mock.addRecord(new ConsumerRecord<>(TOPIC, 0, 2, null, "v2".getBytes(StandardCharsets.UTF_8)));
        });
        SampleResult s = wb.sample("cdr/0", 2);
        assertEquals(List.of("partition", "offset", "timestamp", "key", "value"), s.columns());
        assertEquals(2, s.rows().size());
        assertEquals("v0", s.rows().get(0).get("value"));
        assertEquals("k0", s.rows().get(0).get("key"));
        assertTrue(s.truncated(), "3 records on the partition, limit 2");
    }

    @Test
    void sampleOfUnknownTopicOrPartitionRefusesHonestly() throws Exception {
        broker(0, 3);
        assertThrows(ConnectionWorkbench.NoSuchPath.class, () -> wb.sample("nope/0", 10));
        assertThrows(ConnectionWorkbench.NoSuchPath.class, () -> wb.sample("cdr/9", 10));
        assertThrows(ConnectionWorkbench.NoSuchPath.class, () -> wb.sample("cdr", 10), "sample needs topic/partition");
    }

    @Test
    void probeGradesChecksAndSkipsWrite() {
        broker(0, 3);
        assertTrue(wb.check(ProbeCheck.AUTHENTICATE, 25).ok());
        assertTrue(wb.check(ProbeCheck.READ, 25).ok());
        CheckOutcome write = wb.check(ProbeCheck.WRITE, 25);
        assertTrue(write.skipped(), "write must be skipped — the workbench never produces onto a topic");
        assertFalse(write.ok());
        assertTrue(wb.check(ProbeCheck.LIST, 25).ok());
    }
}

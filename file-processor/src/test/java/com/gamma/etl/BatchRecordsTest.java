package com.gamma.etl;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BatchRecordsTest {
    @Test
    void recordsHoldValues() {
        PartitionOutput po = new PartitionOutput("year=2020/month=04/day=03", "/db/b_out.csv", 123L);
        assertEquals("year=2020/month=04/day=03", po.partition());
        assertEquals(123L, po.bytes());

        LineageRow lr = new LineageRow("B1", 0, "a.csv", "/db/b_out.csv", "year=2020/month=04/day=03", 5L);
        assertEquals(5L, lr.rowCount());

        SchemaSelector.Selection sel =
                new SchemaSelector.Selection(Map.of("raw", Map.of("name", "mini")), "mini");
        Batch.Member m = new Batch.Member(new File("a.csv"), 0, 10L, sel);
        Batch b = new Batch("B1", "mini", "mini", List.of(m));
        assertEquals("B1", b.batchId());
        assertEquals(1, b.members().size());
        assertEquals(0, b.members().get(0).srcId());
    }
}

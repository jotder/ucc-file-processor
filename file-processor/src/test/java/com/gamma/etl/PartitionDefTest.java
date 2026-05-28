package com.gamma.etl;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PartitionDefTest {

    @Test
    void parsesExplicitPartitionsList() {
        Map<String, Object> schema = Map.of("partitions", List.of(
                Map.of("column", "event_type", "source", "EVENT_TYPE", "type", "VARCHAR"),
                Map.of("column", "year",       "source", "TX_DATE",    "type", "DATE_YEAR"),
                Map.of("column", "month",      "source", "TX_DATE",    "type", "DATE_MONTH"),
                Map.of("column", "day",        "source", "TX_DATE",    "type", "DATE_DAY")));

        List<PartitionDef> defs = PartitionDef.fromSchema(schema);

        assertEquals(4, defs.size());
        assertEquals("event_type",       defs.get(0).column());
        assertEquals("EVENT_TYPE",       defs.get(0).source());
        assertEquals(PartitionDef.Type.VARCHAR,    defs.get(0).type());
        assertEquals(PartitionDef.Type.DATE_YEAR,  defs.get(1).type());
        assertEquals(PartitionDef.Type.DATE_MONTH, defs.get(2).type());
        assertEquals(PartitionDef.Type.DATE_DAY,   defs.get(3).type());
    }

    @Test
    void fallsBackToLegacyPartitionKey() {
        Map<String, Object> schema = Map.of("partitionKey", "TRADE_DATE");

        List<PartitionDef> defs = PartitionDef.fromSchema(schema);

        assertEquals(3, defs.size());
        assertEquals("year",  defs.get(0).column());
        assertEquals("TRADE_DATE", defs.get(0).source());
        assertEquals(PartitionDef.Type.DATE_YEAR, defs.get(0).type());
        assertEquals("month", defs.get(1).column());
        assertEquals("day",   defs.get(2).column());
    }

    @Test
    void returnsEmptyWhenNoPartitionKey() {
        Map<String, Object> schema = Map.of("raw", Map.of());
        assertTrue(PartitionDef.fromSchema(schema).isEmpty());
    }

    @Test
    void columnNamesExtractsInOrder() {
        List<PartitionDef> defs = List.of(
                new PartitionDef("event_type", "ET", PartitionDef.Type.VARCHAR),
                new PartitionDef("year", "D", PartitionDef.Type.DATE_YEAR));
        assertEquals(List.of("event_type", "year"), PartitionDef.columnNames(defs));
    }
}

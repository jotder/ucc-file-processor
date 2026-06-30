package com.gamma.control;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for the pure request→action classifier behind the audit trail. */
class AuditTrailTest {

    @Test
    void classifiesDestructiveAndMutatingActions() {
        assertEquals(new AuditTrail.Action("run.deleted", "destructive"),
                AuditTrail.classify("DELETE", "/runs/orders"));
        assertEquals(new AuditTrail.Action("run.triggered", "data_mutation"),
                AuditTrail.classify("POST", "/runs/orders/trigger"));
        assertEquals(new AuditTrail.Action("run.created", "data_mutation"),
                AuditTrail.classify("POST", "/runs"));
        assertEquals(new AuditTrail.Action("connection.updated", "data_mutation"),
                AuditTrail.classify("PUT", "/connections/sftp1"));
        assertEquals(new AuditTrail.Action("space.deleted", "destructive"),
                AuditTrail.classify("DELETE", "/spaces/team-b"));
    }

    @Test
    void classifiesConfigAndExport() {
        assertEquals("configuration", AuditTrail.classify("POST", "/config/write").category());
        assertEquals(new AuditTrail.Action("event.exported", "export"),
                AuditTrail.classify("GET", "/events/export"));
    }

    @Test
    void skipsDiagnosticAndReadOnly() {
        assertNull(AuditTrail.classify("POST", "/connections/sftp1/test"));
        assertNull(AuditTrail.classify("POST", "/components/schema/x/test"));
        assertNull(AuditTrail.classify("POST", "/pipelines/authored/f1/dry-run"));
        assertNull(AuditTrail.classify("POST", "/validate"));
        assertNull(AuditTrail.classify("POST", "/assist/chat"));
        assertNull(AuditTrail.classify("GET", "/runs"), "ordinary reads are not audited");
    }
}

package com.gamma.acquire;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import com.gamma.event.EventLog;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Phase-E: the process-wide bridge the static poll path uses to resolve a source.connection binding. */
class ConnectionRegistryTest {

    private static ConnectionProfile profile(String id) {
        return new ConnectionProfile(id, "sftp", "host", 22, null, "/data", "u", "${ENV:PW}", Map.of(), null);
    }

    @Test
    void registerThenFindByIdAndRemove() {
        ConnectionRegistry.register(profile("conn-a"));
        assertTrue(ConnectionRegistry.find("conn-a").isPresent());
        assertEquals("host", ConnectionRegistry.find(" conn-a ").orElseThrow().host(), "id is trimmed on lookup");
        assertTrue(ConnectionRegistry.find("missing").isEmpty());

        ConnectionRegistry.remove("conn-a");
        assertTrue(ConnectionRegistry.find("conn-a").isEmpty());
    }

    @Test
    void registerNullIsIgnoredAndLaterRegistrationReplaces() {
        ConnectionRegistry.register(null);   // no-op, no throw
        ConnectionRegistry.register(profile("conn-b"));
        ConnectionRegistry.register(new ConnectionProfile("conn-b", "ftp", "host2", 21, null, null, null, null, Map.of(), null));
        assertEquals("ftp", ConnectionRegistry.find("conn-b").orElseThrow().connector(), "same id replaces");
        ConnectionRegistry.remove("conn-b");
    }

    @Test
    void profilesAreIsolatedPerSpaceMdc() {
        try {
            // Same id registered in two spaces resolves to each space's own profile.
            MDC.put(EventLog.SPACE_MDC_KEY, "space-a");
            ConnectionRegistry.register(profile("shared-id"));
            MDC.put(EventLog.SPACE_MDC_KEY, "space-b");
            ConnectionRegistry.register(new ConnectionProfile("shared-id", "ftp", "hostB", 21, null, null, null, null, Map.of(), null));

            MDC.put(EventLog.SPACE_MDC_KEY, "space-a");
            assertEquals("sftp", ConnectionRegistry.find("shared-id").orElseThrow().connector(), "space-a sees its own profile");
            MDC.put(EventLog.SPACE_MDC_KEY, "space-b");
            assertEquals("ftp", ConnectionRegistry.find("shared-id").orElseThrow().connector(), "space-b sees its own profile");

            // A space with no registration (and the default no-MDC namespace) cannot see another space's profile.
            MDC.put(EventLog.SPACE_MDC_KEY, "space-c");
            assertTrue(ConnectionRegistry.find("shared-id").isEmpty(), "unrelated space sees nothing");
            MDC.remove(EventLog.SPACE_MDC_KEY);
            assertTrue(ConnectionRegistry.find("shared-id").isEmpty(), "default namespace is isolated from named spaces");

            // remove() is scoped to the current space.
            MDC.put(EventLog.SPACE_MDC_KEY, "space-a");
            ConnectionRegistry.remove("shared-id");
            assertTrue(ConnectionRegistry.find("shared-id").isEmpty(), "removed from space-a");
            MDC.put(EventLog.SPACE_MDC_KEY, "space-b");
            assertTrue(ConnectionRegistry.find("shared-id").isPresent(), "space-b retains its profile after space-a remove");
        } finally {
            MDC.remove(EventLog.SPACE_MDC_KEY);
            ConnectionRegistry.clear();
        }
    }
}

package com.gamma.acquire;

import org.junit.jupiter.api.Test;

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
}

package com.gamma.acquire;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Connection-profile secrets are references, never values: resolution by scope + literal passthrough. */
class SecretResolverTest {

    @Test
    void recognisesReferences() {
        assertTrue(SecretResolver.isReference("${ENV:X}"));
        assertTrue(SecretResolver.isReference("${plain}"));
        assertFalse(SecretResolver.isReference("plain"));
        assertFalse(SecretResolver.isReference("${}"));
        assertFalse(SecretResolver.isReference(null));
    }

    @Test
    void resolvesSystemPropertyScope() {
        String key = "test.secret." + System.nanoTime();
        System.setProperty(key, "swordfish");
        try {
            assertEquals("swordfish", SecretResolver.resolve("${SYS:" + key + "}"));
            assertEquals("swordfish", SecretResolver.resolve("${" + key + "}"));   // bare ⇒ env then sys prop
            assertTrue(SecretResolver.isResolvable("${SYS:" + key + "}"));
        } finally {
            System.clearProperty(key);
        }
        assertNull(SecretResolver.resolve("${SYS:" + key + "}"), "cleared ⇒ unresolved");
        assertFalse(SecretResolver.isResolvable("${SYS:" + key + "}"));
    }

    @Test
    void missingEnvReferenceResolvesToNull() {
        assertNull(SecretResolver.resolve("${ENV:DEFINITELY_NOT_SET_" + System.nanoTime() + "}"));
    }

    @Test
    void literalIsPassedThrough() {
        assertEquals("literal-value", SecretResolver.resolve("literal-value"));
        assertTrue(SecretResolver.isResolvable("literal-value"), "a literal is trivially resolvable");
        assertNull(SecretResolver.resolve(null));
    }
}

package com.gamma.control;

import java.util.Optional;
import java.util.ServiceLoader;

/**
 * Resolves the edition's {@link Authenticator}, mirroring {@code com.gamma.acquire.SourceConnectors}'
 * "absent module ⇒ no-op wins" pattern: Personal ships no {@code META-INF/services} registration, so
 * {@link #active()} is empty and {@link ControlApi#dispatch} skips authentication entirely. Cached
 * after the first lookup (the classpath does not change at runtime). At most one implementation is
 * expected; the first found wins.
 */
final class Authenticators {
    private Authenticators() {}

    private static volatile Optional<Authenticator> cached;

    static Optional<Authenticator> active() {
        Optional<Authenticator> c = cached;
        if (c != null) return c;
        for (Authenticator a : ServiceLoader.load(Authenticator.class)) return cached = Optional.of(a);
        return cached = Optional.empty();
    }

    /** Test seam: force {@link #active()} to a specific value for the rest of this JVM's tests, bypassing
     *  the classpath scan (the core's own test classpath carries no {@code Authenticator} registration,
     *  so a real Standard-edition gate can only be exercised this way). Production code never calls this;
     *  a test must restore {@code null} in its teardown so later test classes see Personal behaviour again. */
    static void forTest(Authenticator a) { cached = Optional.ofNullable(a); }
}

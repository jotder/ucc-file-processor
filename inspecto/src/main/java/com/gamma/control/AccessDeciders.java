package com.gamma.control;

import java.util.Optional;
import java.util.ServiceLoader;

/**
 * Resolves the edition's {@link AccessDecider}, mirroring {@link Authenticators}' "absent module ⇒
 * no-op wins" pattern: Personal/Standard ship no {@code META-INF/services} registration, so
 * {@link #active()} is empty and both PEPs (the authorize stage, {@link RowScope}) skip policy
 * evaluation entirely. Cached after the first lookup; at most one implementation is expected.
 */
final class AccessDeciders {
    private AccessDeciders() {}

    private static volatile Optional<AccessDecider> cached;

    static Optional<AccessDecider> active() {
        Optional<AccessDecider> c = cached;
        if (c != null) return c;
        for (AccessDecider d : ServiceLoader.load(AccessDecider.class)) return cached = Optional.of(d);
        return cached = Optional.empty();
    }

    /** Test seam (mirrors {@link Authenticators#forTest}): force {@link #active()} for the rest of this
     *  JVM's tests. A test must restore {@code null} in its teardown so later classes see the
     *  classpath-scanned behaviour again. */
    static void forTest(AccessDecider d) { cached = Optional.ofNullable(d); }
}

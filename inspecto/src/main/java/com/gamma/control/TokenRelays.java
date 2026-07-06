package com.gamma.control;

import java.util.Optional;
import java.util.ServiceLoader;

/**
 * Resolves the edition's {@link TokenRelay} — same "absent module ⇒ nothing found ⇒ capability
 * unavailable" {@code ServiceLoader} pattern as {@link Authenticators}. Cached after the first
 * lookup; at most one implementation is expected, first found wins.
 */
final class TokenRelays {
    private TokenRelays() {}

    private static volatile Optional<TokenRelay> cached;

    static Optional<TokenRelay> active() {
        Optional<TokenRelay> c = cached;
        if (c != null) return c;
        for (TokenRelay r : ServiceLoader.load(TokenRelay.class)) return cached = Optional.of(r);
        return cached = Optional.empty();
    }

    /** Test seam (mirrors {@link Authenticators#forTest}): force {@link #active()}, bypassing the
     *  classpath scan. Tests must restore {@code null} in teardown. */
    static void forTest(TokenRelay r) { cached = Optional.ofNullable(r); }
}

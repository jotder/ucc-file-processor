package com.gamma.control;

import java.util.Set;

/**
 * The authenticated caller (W6, edition concern — {@code docs/EDITIONS.md} "Security direction").
 * Carries only the resolved capability grants — never raw JWT claims or role names, which stay
 * internal to the {@link Authenticator} that derived them (guideline 13: the contract speaks in
 * capability verbs, never roles). Set on the exchange as {@link ApiContext#ATTR_SUBJECT} once per
 * request by {@link ControlApi#dispatch}; absent entirely on Personal edition (no {@link Authenticator}
 * present, so nothing ever attaches one).
 */
public record Subject(String id, Set<String> capabilities) {}

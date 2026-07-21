package com.gamma.config.spec;

import com.gamma.api.PublicApi;

/**
 * Severity of a validation {@link Finding}.
 *
 * <p>{@code ERROR} marks a configuration that cannot run as written (today's eager
 * {@code IllegalArgumentException}s in the various {@code load} methods); {@code WARNING}
 * marks a suspicious-but-legal pattern (today's {@code ConfigValidator} messages). Keeping the
 * two distinct lets a UI block on errors while surfacing warnings non-fatally.
 */
@PublicApi(since = "4.0.0")
public enum Severity {
    ERROR,
    WARNING
}

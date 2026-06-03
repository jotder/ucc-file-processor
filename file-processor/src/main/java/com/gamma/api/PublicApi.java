package com.gamma.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a type, method, or constructor as part of the <b>stable public API</b> —
 * the surface external code (plugin authors implementing {@code StreamingFileIngester},
 * and embedders driving the ETL from Java) is allowed to depend on.
 *
 * <h3>Stability contract</h3>
 * Within a major version, {@code @PublicApi} elements evolve under semantic
 * versioning: they are not removed or changed incompatibly; new members may be
 * added. Breaking changes to them happen only on a major version bump (and are
 * called out in the release notes).
 *
 * <p>Anything <em>not</em> annotated is internal: it may change or disappear in
 * any release, with no notice. Do not depend on unmarked types from outside the
 * framework. See {@code docs/api-stability.md} for the full policy and the
 * current marked surface.
 *
 * <p>Retention is {@link RetentionPolicy#CLASS} — visible in bytecode for tooling
 * and Javadoc, but not required at runtime.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR})
public @interface PublicApi {

    /** Version in which this element became public API (e.g. {@code "2.0.0"}). Informational. */
    String since() default "";
}

package com.gamma.job;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The declarative face of a {@link JobTypeProvider} (job-framework §6.1). {@code ServiceLoader} remains
 * authoritative for <em>loading</em> a provider; when present, this annotation is read after load for
 * <em>validation</em> — its {@link #id()} must equal the provider's {@code descriptor().id()} or the
 * provider is rejected fail-closed ({@link JobPackManager}, §12.3). It lets a Job Pack declare its type
 * by annotation as well as by interface, satisfying R8 without a classpath-scanning library.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface JobTypeMeta {
    String id();
    String title() default "";
    String[] emits() default {};
}

package com.gamma.event;

/**
 * Well-known {@link Event#type()} constants. {@code Event.type} is a free-form {@code String} (the
 * model is <em>extensible</em> per the platform requirement), so these are conventions rather than a
 * closed enum — a caller may emit a new type without touching this class.
 *
 * <h3>Two families</h3>
 * <ul>
 *   <li>{@link #LOG} — an automatically captured SLF4J log record (INFO and above). High volume,
 *       low structure (message + logger name).</li>
 *   <li>The rest — <em>domain</em> facts emitted explicitly at lifecycle points. Lower volume, high
 *       structure (they carry typed {@link Event#attributes()} and a {@link Event#correlationId()}).</li>
 * </ul>
 *
 * @since 4.2.0
 */
@com.gamma.api.PublicApi(since = "4.2.0")
public final class EventType {

    private EventType() {}

    /** A captured SLF4J/logback log record (level ≥ {@link EventLevel#CAPTURE_THRESHOLD}). */
    public static final String LOG = "LOG";

    // ── service / pipeline lifecycle ──────────────────────────────────────────────
    public static final String SERVICE_STARTED     = "SERVICE_STARTED";
    public static final String PIPELINE_REGISTERED = "PIPELINE_REGISTERED";
    public static final String PIPELINE_PAUSED     = "PIPELINE_PAUSED";
    public static final String PIPELINE_RESUMED    = "PIPELINE_RESUMED";

    // ── batch / ingest facts (the headline operational signal) ──────────────────────
    public static final String BATCH_COMMITTED  = "BATCH_COMMITTED";
    public static final String BATCH_FAILED     = "BATCH_FAILED";
    public static final String FILE_RECEIVED    = "FILE_RECEIVED";
    public static final String FILE_QUARANTINED = "FILE_QUARANTINED";
    /** A discovered file passed the readiness gate — quiescent / size-stable, safe to ingest (Phase B). */
    public static final String FILE_STABLE      = "FILE_STABLE";

    // ── job / enrichment ────────────────────────────────────────────────────────────
    public static final String JOB_STARTED   = "JOB_STARTED";
    public static final String JOB_SUCCEEDED = "JOB_SUCCEEDED";
    public static final String JOB_FAILED    = "JOB_FAILED";
    public static final String ENRICHMENT_RUN = "ENRICHMENT_RUN";

    // ── operational-object bridge (Phase 2 ties back to here) ───────────────────────
    public static final String ALERT_FIRED     = "ALERT_FIRED";
    public static final String CONFIG_VALIDATED = "CONFIG_VALIDATED";

    /** A managed object (ALERT/ISSUE/…) was created in its workflow's initial state (Phase 2). */
    public static final String OBJECT_OPENED   = "OBJECT_OPENED";
    /** A managed object changed state via a workflow transition (ack/resolve/…) (Phase 2). */
    public static final String OBJECT_ACTIVITY = "OBJECT_ACTIVITY";
    /** An object (an ISSUE) passed its {@code dueAt} while still unresolved — an SLA breach (Phase 3). */
    public static final String OBJECT_SLA_BREACH = "OBJECT_SLA_BREACH";
    /** Two objects were correlated by an {@code OBJECT_LINK} (e.g. {@code Case CONTAINS Issue}) (Phase 4). */
    public static final String OBJECT_LINKED = "OBJECT_LINKED";
    /** A note was added to an object — a comment or an attachment reference ({@code noteKind} attr) (Phase 4). */
    public static final String OBJECT_NOTE = "OBJECT_NOTE";
}

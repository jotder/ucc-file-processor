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

    // ── audit trail (who did what, when, where) ─────────────────────────────────────
    /** A user/system action recorded for the security audit trail; "who / what / where" detail rides
     *  in {@link AuditAttrs} keys. Emitted centrally for state-changing Control API requests. */
    public static final String AUDIT = "AUDIT";
    /** An attempt to reach a forbidden/unknown API route (the auth-free analogue of 401/403):
     *  a non-GET request that matched no route (404) or a disallowed method on a read-only route (405). */
    public static final String ACCESS_DENIED = "ACCESS_DENIED";

    // ── service / pipeline lifecycle ──────────────────────────────────────────────
    public static final String SERVICE_STARTED     = "SERVICE_STARTED";
    /** A space's on-disk tree departs from the storage-layout contract (config/data/audit/duckdb axes must
     *  not mix; canonical subdirs must exist). Emitted at boot as a {@link EventLevel#WARN} advisory — a
     *  violation is never fatal. The {@code kind} attribute names the rule; {@code path} the offending entry. */
    public static final String LAYOUT_CONTRACT_VIOLATION = "LAYOUT_CONTRACT_VIOLATION";
    public static final String PIPELINE_REGISTERED = "PIPELINE_REGISTERED";
    public static final String PIPELINE_PAUSED     = "PIPELINE_PAUSED";
    public static final String PIPELINE_RESUMED    = "PIPELINE_RESUMED";

    // ── batch / ingest facts (the headline operational signal) ──────────────────────
    public static final String BATCH_COMMITTED  = "BATCH_COMMITTED";
    public static final String BATCH_FAILED     = "BATCH_FAILED";
    public static final String FILE_RECEIVED    = "FILE_RECEIVED";
    public static final String FILE_QUARANTINED = "FILE_QUARANTINED";
    /** A source connector listed a candidate file during discovery (Phase E). */
    public static final String FILE_DISCOVERED  = "FILE_DISCOVERED";
    /** A remote file's bytes were retrieved (staged/streamed) from the source connector (Phase E). The
     *  {@code bytes} attribute carries the transferred size. */
    public static final String FILE_FETCHED     = "FILE_FETCHED";
    /** A fetched file passed its integrity check — size match and/or checksum verified (Phase E). */
    public static final String FILE_VALIDATED   = "FILE_VALIDATED";
    /** Retrieval (or integrity validation) of a remote file failed; it was skipped this cycle (Phase E). */
    public static final String FILE_FETCH_FAILED = "FILE_FETCH_FAILED";
    /** A processed source file was finalized on the source side via a post-action — moved/renamed/deleted (Phase F).
     *  The {@code action} attribute carries the kind (DELETE/MOVE/RENAME/TAG). */
    public static final String FILE_ARCHIVED    = "FILE_ARCHIVED";
    /** A source's circuit breaker tripped OPEN after repeated connectivity failures; the source is being skipped
     *  until its cooldown elapses (Phase F). */
    public static final String SOURCE_CIRCUIT_OPEN = "SOURCE_CIRCUIT_OPEN";
    /** A discovered file passed the readiness gate — quiescent / size-stable, safe to ingest (Phase B). */
    public static final String FILE_STABLE      = "FILE_STABLE";
    /** A file at a known path was re-seen with changed content (size/mtime/checksum differs) (Phase C). */
    public static final String FILE_CHANGED     = "FILE_CHANGED";
    /** An expected file in a configured sequence is missing — a hole in the series (Phase D). The
     *  {@code expected} attribute carries the missing key; {@code sequence}/{@code unit} describe the series. */
    public static final String SEQUENCE_GAP     = "SEQUENCE_GAP";

    // ── job / enrichment ────────────────────────────────────────────────────────────
    public static final String JOB_STARTED   = "JOB_STARTED";
    public static final String JOB_SUCCEEDED = "JOB_SUCCEEDED";
    public static final String JOB_FAILED    = "JOB_FAILED";
    public static final String ENRICHMENT_RUN = "ENRICHMENT_RUN";
    /** A delete/maintenance job targets a resting store with an active producer/consumer — the one
     *  cross-driver hazard the deletion fence guards (§3.8 rule 4, T25). The {@code store},
     *  {@code activeProducers} and {@code activeConsumers} attributes name the racing flows. */
    public static final String STORE_DELETE_CONFLICT = "STORE_DELETE_CONFLICT";
    /** A flow run's data-plane provenance failed the conservation invariant at a non-amplifying node — records
     *  entered that did not leave (silent data loss) or were unexpectedly amplified (§11.4, T22). The
     *  {@code node}, {@code recordsIn}, {@code recordsOut} and {@code kind} (LOSS/AMPLIFICATION) attributes
     *  describe the imbalance; {@code correlationId} is the run's {@code batchId}. */
    public static final String FLOW_CONSERVATION_IMBALANCE = "FLOW_CONSERVATION_IMBALANCE";

    // ── cross-space sharing (the Exchange — Share Grants) ───────────────────────────
    /** An owner Space listed a Dataset/Widget as shareable in the Exchange. {@code owner}/{@code kind}/
     *  {@code item} attributes name the offered item. */
    public static final String EXCHANGE_OFFERED   = "EXCHANGE_OFFERED";
    /** A consumer Space requested use of an offered item; {@code consumer}/{@code owner}/{@code kind}/
     *  {@code item}/{@code purpose} carry the request. */
    public static final String EXCHANGE_REQUESTED = "EXCHANGE_REQUESTED";
    /** An owner approved a request — the grant is now {@code active}. */
    public static final String EXCHANGE_GRANTED   = "EXCHANGE_GRANTED";
    /** An owner denied a pending request. */
    public static final String EXCHANGE_DENIED    = "EXCHANGE_DENIED";
    /** An owner revoked an active grant — the consumer's access is withdrawn (fail-closed). */
    public static final String EXCHANGE_REVOKED   = "EXCHANGE_REVOKED";

    // ── operational-object bridge (Phase 2 ties back to here) ───────────────────────
    public static final String ALERT_FIRED     = "ALERT_FIRED";
    /** A scheduled report/export artifact was produced (BI-4); {@code attributes.path} points at it. */
    public static final String REPORT_READY    = "REPORT_READY";
    /** A data-quality {@code Expectation} evaluated with violating records (ING-6) — raises an Incident + notifies. */
    public static final String EXPECTATION_FAILED = "EXPECTATION_FAILED";
    public static final String CONFIG_VALIDATED = "CONFIG_VALIDATED";

    /** A managed object (ALERT/INCIDENT/…) was created in its workflow's initial state (Phase 2). */
    public static final String OBJECT_OPENED   = "OBJECT_OPENED";
    /** A managed object changed state via a workflow transition (ack/resolve/…) (Phase 2). */
    public static final String OBJECT_ACTIVITY = "OBJECT_ACTIVITY";
    /** An object (an INCIDENT) passed its {@code dueAt} while still unresolved — an SLA breach (Phase 3). */
    public static final String OBJECT_SLA_BREACH = "OBJECT_SLA_BREACH";
    /** Two objects were correlated by an {@code OBJECT_LINK} (e.g. {@code Case CONTAINS Incident}) (Phase 4). */
    public static final String OBJECT_LINKED = "OBJECT_LINKED";
    /** A note was added to an object — a comment or an attachment reference ({@code noteKind} attr) (Phase 4). */
    public static final String OBJECT_NOTE = "OBJECT_NOTE";
    /** An object was (re)assigned to a person or routed through a queue (INC-4); {@code from}/{@code to}/
     *  {@code queue} attrs carry the change — the assignment history parallel to {@link #OBJECT_ACTIVITY}. */
    public static final String OBJECT_ASSIGNED = "OBJECT_ASSIGNED";
    /** An SLA-breached INCIDENT was escalated per an {@code *_escalation.toon} policy (INC-4) — severity
     *  bumped and/or re-routed to a queue; {@code severity}/{@code queue}/{@code assignee} carry the result. */
    public static final String OBJECT_ESCALATED = "OBJECT_ESCALATED";
}

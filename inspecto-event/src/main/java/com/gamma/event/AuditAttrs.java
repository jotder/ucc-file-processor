package com.gamma.event;

/**
 * Well-known {@link Event#attributes()} keys for audit-trail facts ({@code type = }{@link EventType#AUDIT}).
 *
 * <p>The audit trail is <em>not</em> a parallel store — an audit entry is an ordinary append-only
 * {@link Event} whose {@link Event#type()} is {@link EventType#AUDIT} and whose "who / what / where"
 * detail rides in these attribute keys. That keeps the immutable {@link EventStore} contract, the
 * {@code /events*} read API, partitioning and export all reused, with the audit anatomy carried in the
 * already-extensible attribute map (see {@link Event} and {@link Event.Builder}).
 *
 * <p>The shape mirrors the standard audit anatomy — <b>actor</b> (who), <b>action</b> (what),
 * <b>target</b> (on what), and <b>contextual environment</b> (from where). GeoIP {@code location} is
 * intentionally omitted in the auth-free core (only {@link #IP} is captured); editions resolve it.
 *
 * @since 4.4.0
 */
public final class AuditAttrs {

    private AuditAttrs() {}

    // ── actor (who) ───────────────────────────────────────────────────────────────
    /** The acting identity. Auth-free core defaults to {@code appUser}; an edition's security module
     *  injects the real principal via the {@code X-Actor} request header. */
    public static final String ACTOR = "actor";
    /** Actor kind: {@code user}, {@code system}, {@code api_key}, … (defaults to {@code user}). */
    public static final String ACTOR_TYPE = "actor_type";

    // ── action (what) ─────────────────────────────────────────────────────────────
    /** Dotted action name, e.g. {@code pipeline.deleted}, {@code config.written}, {@code access.denied}. */
    public static final String ACTION = "action";
    /** Coarse class: {@code data_mutation}, {@code destructive}, {@code export}, {@code configuration},
     *  {@code authorization}, {@code authentication}. */
    public static final String ACTION_CATEGORY = "action_category";

    // ── target (on what) ──────────────────────────────────────────────────────────
    /** Target resource kind, e.g. {@code pipeline}, {@code connection}, {@code space}. */
    public static final String TARGET_TYPE = "target_type";
    /** Target identifier, e.g. the pipeline name or object id. */
    public static final String TARGET_ID = "target_id";

    // ── contextual environment (from where) ───────────────────────────────────────
    /** Client IP (IPv4/IPv6) of the request. */
    public static final String IP = "ip";
    /** Request {@code User-Agent}. */
    public static final String USER_AGENT = "user_agent";
    /** HTTP method of the audited request. */
    public static final String HTTP_METHOD = "http_method";
    /** HTTP path of the audited request (space-prefix and {@code /api} stripped). */
    public static final String HTTP_PATH = "http_path";
    /** HTTP response status of the audited request. */
    public static final String HTTP_STATUS = "http_status";

    // ── access-policy decision (ABAC A5) ──────────────────────────────────────────
    /** The ABAC action verb the decision was made over: {@code read} / {@code write} / {@code operate}. */
    public static final String ABAC_ACTION = "abac_action";
    /** The name of the Access Policy that matched the decision ({@code access.denied}/{@code access.granted}),
     *  or a marker like {@code <policies-unreadable>} for a fail-closed deny. */
    public static final String POLICY = "policy";
}

package com.gamma.acquire;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;

/**
 * A pluggable source of files — the acquisition seam (Data Acquisition roadmap Phase A).
 *
 * <p>Inspecto historically had exactly one, hard-wired source: the local filesystem under
 * {@code dirs.poll}, scanned by {@link com.gamma.inspector.CollectorProcessor#collectCandidates}. This SPI
 * extracts that behaviour so additional protocols (SFTP/FTP/S3/…) become plugins discovered via
 * {@link CollectorConnectorFactory} + {@link java.util.ServiceLoader} <em>without modifying the core engine</em>
 * — the requirement's extensibility clause. The built-in {@link LocalFileSystemConnector} reproduces the
 * legacy behaviour byte-for-byte, so a pipeline with no {@code source:} block is unaffected.
 *
 * <p>One instance is created per configured {@code source:} block (see {@link CollectorConnectors#forConfig}),
 * so an implementation may hold a connection/session for its lifetime; the engine closes it when the scan
 * cycle ends. Implementations need not be thread-safe across instances — each pipeline gets its own.
 *
 * <h3>I/O discipline (read this before implementing a remote connector)</h3>
 * Acquisition must move bytes the minimum number of times. The engine decides <em>how</em> to retrieve each
 * file with {@link RetrievalPlanner}; a connector simply honours the two read primitives:
 * <ul>
 *   <li>{@link #open(RemoteFile)} — read straight from the source with <b>no local copy</b>. Used when nothing
 *       downstream needs to keep the bytes (no backup, no random-access requirement).</li>
 *   <li>{@link #fetchTo(RemoteFile, Path)} — materialise the bytes <b>once, at their final resting place</b>.
 *       When a backup/archive is configured the engine passes the backup destination here, so the file is
 *       written exactly once and read from there — never copied to a temp dir and then moved.</li>
 * </ul>
 * In particular: <em>do not</em> download to temp, read, then move to backup. Either stream
 * ({@code open}) when there is nothing to keep, or write directly to the destination ({@code fetchTo}).
 */
public interface CollectorConnector extends AutoCloseable {

    /** Stable scheme id used in config ({@code source.connector}) and {@code ServiceLoader} lookup. */
    String scheme();

    /** What this connector can do — lets {@link RetrievalPlanner} choose stream vs. materialise and lets the
     *  engine validate a configured post-action against the source's real abilities. */
    EnumSet<Capability> capabilities();

    /**
     * <b>Discover.</b> List the candidate files visible to this connector after applying the engine-supplied
     * include/exclude/depth filters in {@code ctx}. The result is <em>not</em> duplicate-filtered — markers /
     * the fingerprint ledger are an engine concern applied on top. Must perform no destructive action and,
     * for sources that don't exist yet (e.g. a missing local inbox), return an empty list and create nothing.
     */
    List<RemoteFile> discover(DiscoveryContext ctx) throws AcquisitionException;

    /**
     * <b>Determine readiness.</b> Has this file finished arriving? Connectors that know natively (S3 object
     * finalized, SFTP rename-on-complete) answer {@link Readiness#READY}/{@link Readiness#NOT_READY}; those
     * that don't return {@link Readiness#UNKNOWN}, and the engine falls back to size/mtime stabilization
     * (roadmap Phase B). Not consulted by the engine in Phase A.
     */
    Readiness readiness(RemoteFile file) throws AcquisitionException;

    /** <b>Retrieve (stream).</b> Open the file for reading directly from the source — no local copy. */
    InputStream open(RemoteFile file) throws AcquisitionException;

    /**
     * <b>Retrieve (materialise).</b> Copy the file's bytes to {@code dest} and return the local path written
     * (normally {@code dest}). The engine sets {@code dest} to the file's <em>final</em> location when one is
     * known (the backup/archive dir for a keep/MOVE post-action), so the bytes land once with no later move.
     * Parent directories are created as needed. Supports resume when {@link Capability#RESUMABLE} is present.
     */
    Path fetchTo(RemoteFile file, Path dest) throws AcquisitionException;

    /** <b>Finalize.</b> Apply a post-processing action to the source-side file (retain/delete/move/rename/tag). */
    void post(RemoteFile file, PostAction action) throws AcquisitionException;

    /** Release any held connection/session. The local connector holds nothing; default is a no-op. */
    @Override
    default void close() throws AcquisitionException {}

    /** Whether a discovered file is done arriving. */
    enum Readiness { READY, NOT_READY, UNKNOWN }

    /** Optional connector abilities, consulted by {@link RetrievalPlanner} and post-action validation. */
    enum Capability {
        /** Can serve bytes without a local copy ({@link #open}). */            STREAM,
        /** Supports seek / range reads (enables resumable + multipart). */      RANDOM_ACCESS,
        /** A partial {@link #fetchTo} can resume rather than restart. */        RESUMABLE,
        /** Can delete a source file. */                                         DELETE,
        /** Can move/relocate a source file (archive). */                        MOVE,
        /** Can rename a source file in place. */                                RENAME,
        /** Supports object-metadata tagging (object storage). */                TAG,
        /** Exposes a content hash/etag in listings (cheap change detection). */ ETAG,
        /** Exposes object versions (S3/GCS). */                                 VERSIONING
    }
}

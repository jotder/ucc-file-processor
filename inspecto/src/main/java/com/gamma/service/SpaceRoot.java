package com.gamma.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The set of filesystem locations one <em>space</em>'s persistent state lives under. A single Inspecto
 * server hosts many spaces concurrently, each fully isolated; {@code SpaceRoot} is the one object that
 * decides <b>where</b> a space's config, data, audit trail, DuckDB stores, and authored flows are
 * read/written.
 *
 * <p>Each accessor returns the <em>default</em> location. {@link ServiceStores} still lets the matching
 * {@code -D} flag override it, so a single-tenant deployment driven entirely by system properties behaves
 * exactly as before (see {@link #legacy()}).
 *
 * <ul>
 *   <li>{@link #legacy()} — the pre-spaces layout: flat files in the working directory under their
 *       historical names, honouring every existing {@code -D} flag. This is the default for the
 *       long-standing single-tenant {@link CollectorService} constructors, so their behaviour is unchanged.</li>
 *   <li>{@link #under(Path)} — a self-contained space directory
 *       {@code <base>/{config,data,audit,duckdb}} (authored flows under {@code config/flows/}).</li>
 * </ul>
 */
public interface SpaceRoot {

    /** The space's identifier (its {@code spaces/<id>} directory name); {@code "default"} for {@link #legacy()}. */
    String id();

    /** The space's root directory (its {@code spaces/<id>/}); the working directory for {@link #legacy()}. */
    Path base();

    /** Directory scanned for this space's {@code *.toon} config; {@code null} for {@link #legacy()} (configs come from CLI args). */
    Path config();

    /** Jobs audit directory (run journal, watermarks, branch-commit logs). */
    String auditDir();

    /** Data root where partition stores are written. */
    String dataDir();

    /** Authored-flow store directory, or {@code null} when flow authoring is disabled. Always the same
     *  {@code flows/} subtree of the write root the HTTP flow CRUD ({@code /pipelines/authored}) writes,
     *  so a UI-authored flow is visible to pipeline-type jobs and the deletion fence (T32):
     *  {@code config/flows/} for a space, {@code -Dassist.write.root/flows} for {@link #legacy()}. */
    Path flowsDir();

    /** Rolling-Parquet event-store directory. */
    Path eventsDir();

    /** Default JDBC URL for the job-run reporting store. */
    String jobRunDbUrl();

    /** Default JDBC URL for the data-plane provenance store. */
    String provenanceDbUrl();

    /** Default JDBC URL for the operational-object store. */
    String objectsDbUrl();

    /** Default JDBC URL for the correlation-link store. */
    String linksDbUrl();

    /** Default JDBC URL for the evidence/notes store. */
    String notesDbUrl();

    /** Default JDBC URL for the status projection store. */
    String statusDbUrl();

    /** Default JDBC URL for the acquisition (dedup) ledger, when {@code -Dacquire.ledger.backend=db}. */
    String acquisitionLedgerDbUrl();

    /** The pre-spaces flat layout: historical file names in the working directory. */
    static SpaceRoot legacy() {
        return new LegacySpaceRoot();
    }

    /** A self-contained space rooted at {@code base}: {@code base/{config,data,audit,duckdb}}. */
    static SpaceRoot under(Path base) {
        return new DirSpaceRoot(base.toAbsolutePath().normalize());
    }
}

/** Pre-spaces single-tenant layout — flat files in the working directory under their historical names. */
final class LegacySpaceRoot implements SpaceRoot {
    public String id() { return "default"; }

    public Path base() { return Path.of("").toAbsolutePath(); }

    public Path config() { return null; }

    public String auditDir() { return "jobs_audit"; }

    public String dataDir() { return "database"; }

    public Path flowsDir() {
        String wr = System.getProperty("assist.write.root");
        return (wr == null || wr.isBlank()) ? null : Path.of(wr.trim()).resolve("flows");
    }

    public Path eventsDir() { return Path.of("inspecto-events"); }

    public String jobRunDbUrl() { return "jdbc:duckdb:jobs_report.duckdb"; }

    public String provenanceDbUrl() { return "jdbc:duckdb:provenance.duckdb"; }

    public String objectsDbUrl() { return "jdbc:duckdb:inspecto-ops.db"; }

    public String linksDbUrl() { return "jdbc:duckdb:inspecto-ops-links.db"; }

    public String notesDbUrl() { return "jdbc:duckdb:inspecto-ops-notes.db"; }

    public String statusDbUrl() {
        // Pre-rebrand default file is honoured when present and the new-name file is absent.
        return (!Files.exists(Path.of("inspecto-status.db")) && Files.exists(Path.of("ucc-status.db")))
                ? "jdbc:duckdb:ucc-status.db"
                : "jdbc:duckdb:inspecto-status.db";
    }

    public String acquisitionLedgerDbUrl() { return "jdbc:duckdb:inspecto-acquisition.db"; }
}

/** A self-contained per-space directory: {@code base/{config,data,audit,duckdb}}. */
final class DirSpaceRoot implements SpaceRoot {
    private final Path base;

    DirSpaceRoot(Path base) { this.base = base; }

    public String id() { return base.getFileName().toString(); }

    public Path base() { return base; }

    public Path config() { return base.resolve("config"); }

    public String auditDir() { return base.resolve("audit").toString(); }

    public String dataDir() { return base.resolve("data").toString(); }

    public Path flowsDir() { return config().resolve("flows"); }

    public Path eventsDir() { return base.resolve("data").resolve("events"); }

    /** JDBC URL under {@code <base>/duckdb/}, minting the dir first: repo-checked-out spaces gitignore
     *  {@code duckdb/}, and DuckDB does not create parent dirs — without the mkdir every DB-backed store
     *  would silently degrade to in-memory on a fresh checkout (ServiceStores' fail-open contract). */
    private String duckdb(String file) {
        Path dir = base.resolve("duckdb");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            // fall through — ServiceStores logs the open failure and degrades to in-memory
        }
        return "jdbc:duckdb:" + dir.resolve(file);
    }

    public String jobRunDbUrl() { return duckdb("jobs_report.duckdb"); }

    public String provenanceDbUrl() { return duckdb("provenance.duckdb"); }

    public String objectsDbUrl() { return duckdb("inspecto-ops.db"); }

    public String linksDbUrl() { return duckdb("inspecto-ops-links.db"); }

    public String notesDbUrl() { return duckdb("inspecto-ops-notes.db"); }

    public String statusDbUrl() { return duckdb("inspecto-status.db"); }

    public String acquisitionLedgerDbUrl() { return duckdb("inspecto-acquisition.db"); }
}

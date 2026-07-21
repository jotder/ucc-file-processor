package com.gamma.job;

import com.gamma.signal.Severity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.security.CodeSigner;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;

/**
 * Hot-deployable Job Packs (R8, {@code docs/job-framework-design.md} §12). A Job Pack is a single jar
 * dropped into {@code -Djobs.packs.dir} bundling one or more {@link JobTypeProvider}s (SPI +
 * {@link JobTypeMeta}) plus their shaded deps. Each jar loads in its own parent-first
 * {@link URLClassLoader} — SPI/API types resolve from the engine, pack-private deps stay isolated — and
 * its providers register into the shared {@link JobTypeRegistry} keyed by the jar filename (the pack's
 * "owner"), so unload/reload touches only that pack's types and never a built-in.
 *
 * <p>{@link #rescan()} is the idempotent reconciler: new jar ⇒ load, removed jar ⇒ unload, changed jar
 * (content hash) ⇒ reload. It runs once at construction (before Jobs are built, so a Job authored against
 * a pack type resolves) and again on every {@link WatchService} event after a settle delay, and is also
 * reachable via {@code POST /jobs/packs/rescan}. A load failure rejects the <em>whole</em> pack (no
 * partial registration) and emits {@code job.pack.rejected}; success emits {@code job.pack.loaded}.
 *
 * <p><b>Fail-closed & scope (§12.3):</b> absent flag ⇒ feature entirely off (no dynamic code loading).
 * {@code -Djobs.packs.requireSignature} (default off) rejects jars with any unsigned class entry; matching
 * the signer against {@code -Djobs.packs.trustStore} is the SEC-7 sign-off gate and is not yet enforced.
 *
 * <p><b>In-flight-Run quiesce (§12.2, 2026-07-20 SHIPPED the classloader half):</b> {@link #acquireRun}/
 * {@link #releaseRun} let {@code JobService} pin a pack's active-run count for the duration of
 * {@code Job.run(ctx)}; {@link #unload} still deregisters the pack's types immediately (a reload's new
 * types register right away), but defers actually {@linkplain URLClassLoader#close() closing} the old
 * loader and deleting its staged jar copy until that count drops to zero, so a Run already executing pack
 * code never has its classloader's resources yanked out from under a lazy class-load/reflection/resource
 * read mid-run. {@code unload} also notifies an optional {@link UnloadListener} with the pack's owner key
 * right after deregistering its types, so {@code JobService} can flip any already-built authored {@code Job}
 * sourced from that pack to unavailable — a *later* Run on the same config then fails fast (REJECTED)
 * instead of running the stale cached {@code Job} instance.
 */
final class JobPackManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(JobPackManager.class);

    /** Signals a pack transition to the space ledger; a no-op sink before the event log is wired. */
    @FunctionalInterface
    interface SignalSink { void emit(String type, Severity sev, Map<String, Object> payload); }

    /** Notified with a pack's owner key (jar filename) the moment its types are deregistered by
     *  {@link #unload}, so the caller ({@code JobService}) can flip any already-built {@link Job} instance
     *  sourced from that pack to unavailable — a later Run on the same config then fails fast instead of
     *  running whatever {@code Job} was cached at registration time. */
    @FunctionalInterface
    interface UnloadListener { void onUnload(String owner); }

    /**
     * One loaded pack: manifest identity, content hash, contributed type ids, owning loader and the
     * private {@code staged} copy the loader actually reads. We load from a staged copy so the watched-dir
     * jar stays unlocked — an admin can delete/replace it (essential on Windows, where a
     * {@link URLClassLoader} would otherwise pin the file), which is exactly what {@link #rescan()} needs
     * to detect a removal/change.
     */
    record LoadedPack(String id, String version, Path jar, String hash, List<String> types,
                      URLClassLoader loader, Path staged) {}

    private final Path dir;                         // null ⇒ feature off
    private final JobTypeRegistry registry;
    private final SignalSink signals;
    private final UnloadListener unloadListener;      // nullable — no-op when not wired
    private final boolean requireSignature;
    private final long settleMillis;
    private final Map<String, LoadedPack> loaded = new ConcurrentHashMap<>();   // jar filename -> pack
    /** In-flight Run count per pack (jar filename/owner key), incremented for the duration of one
     *  {@code Job.run(ctx)} built from that pack's classes. Only packs with pack-owned Jobs appear here. */
    private final Map<String, AtomicInteger> activeRuns = new ConcurrentHashMap<>();
    /** Packs whose {@link #unload} was requested while {@link #activeRuns} was still positive — their
     *  loader/staged file close is deferred to {@link #releaseRun} once the count drops to zero. */
    private final Map<String, LoadedPack> draining = new ConcurrentHashMap<>();

    private volatile boolean running;
    private WatchService watcher;
    private Thread watchThread;
    private Path stagingDir;                         // lazily created; holds the locked copies we load from

    JobPackManager(String packsDir, JobTypeRegistry registry, SignalSink signals) {
        this(packsDir, registry, signals, null);
    }

    JobPackManager(String packsDir, JobTypeRegistry registry, SignalSink signals, UnloadListener unloadListener) {
        this.dir = (packsDir == null || packsDir.isBlank()) ? null : Path.of(packsDir).toAbsolutePath().normalize();
        this.registry = registry;
        this.signals = signals;
        this.unloadListener = unloadListener;
        this.requireSignature = Boolean.getBoolean("jobs.packs.requireSignature");
        this.settleMillis = Long.getLong("jobs.packs.settleMillis", 500L);
    }

    boolean enabled() { return dir != null; }

    /** Load the packs already present at startup (before Jobs are built). No-op when the feature is off. */
    void scanAtStartup() {
        if (!enabled()) return;
        if (!Files.isDirectory(dir)) {
            log.warn("[PACKS] jobs.packs.dir '{}' is not a directory — no Job Packs loaded", dir);
            return;
        }
        log.info("[PACKS] scanning Job Pack dir {} (requireSignature={})", dir, requireSignature);
        rescan();
    }

    /**
     * Reconcile the registry with the packs dir: load new jars, unload removed jars, reload changed jars
     * (by content hash). Idempotent and synchronized. Returns a summary for {@code POST /jobs/packs/rescan}.
     */
    synchronized Map<String, Object> rescan() {
        List<String> loadedNow = new ArrayList<>(), reloaded = new ArrayList<>(),
                unloaded = new ArrayList<>(), rejected = new ArrayList<>();
        if (!enabled() || !Files.isDirectory(dir))
            return summary(loadedNow, reloaded, unloaded, rejected);

        Map<String, Path> present = new LinkedHashMap<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.jar")) {
            for (Path p : ds) if (Files.isRegularFile(p)) present.put(p.getFileName().toString(), p);
        } catch (IOException e) {
            log.warn("[PACKS] cannot list {}: {}", dir, e.toString());
            return summary(loadedNow, reloaded, unloaded, rejected);
        }

        // Removed jars ⇒ unload.
        for (String name : List.copyOf(loaded.keySet()))
            if (!present.containsKey(name)) { unload(name); unloaded.add(name); }

        // New or changed jars ⇒ (re)load.
        for (var e : present.entrySet()) {
            String name = e.getKey();
            String hash = hash(e.getValue());
            LoadedPack cur = loaded.get(name);
            if (cur != null && cur.hash().equals(hash)) continue;      // unchanged
            boolean isReload = cur != null;
            if (isReload) unload(name);                                // reload = unload old + load new
            if (load(name, e.getValue(), hash)) (isReload ? reloaded : loadedNow).add(name);
            else rejected.add(name);
        }
        return summary(loadedNow, reloaded, unloaded, rejected);
    }

    /** Load one pack atomically: verify → discover → validate → register all, or reject the whole jar. */
    private boolean load(String name, Path jar, String hash) {
        URLClassLoader loader = null;
        Path staged = null;
        try {
            if (requireSignature) verifySignature(jar);
            staged = stage(name, hash, jar);          // load from a private copy; keep the watched jar unlocked
            loader = new URLClassLoader(new URL[]{staged.toUri().toURL()}, JobPackManager.class.getClassLoader());

            List<JobTypeProvider> providers = new ArrayList<>();
            for (JobTypeProvider p : ServiceLoader.load(JobTypeProvider.class, loader))
                if (p.getClass().getClassLoader() == loader) providers.add(p);   // only this pack's own types
            if (providers.isEmpty())
                throw new IllegalStateException("no JobTypeProvider in META-INF/services");

            List<String> ids = new ArrayList<>();
            for (JobTypeProvider p : providers) {
                String id = p.descriptor().id();
                if (id == null || id.isBlank())
                    throw new IllegalStateException(p.getClass().getName() + " has a blank descriptor id");
                JobTypeMeta meta = p.getClass().getAnnotation(JobTypeMeta.class);
                if (meta != null && !meta.id().equals(id))
                    throw new IllegalStateException("@JobTypeMeta id '" + meta.id()
                            + "' != descriptor id '" + id + "' in " + p.getClass().getName());
                if (registry.has(id))
                    throw new IllegalStateException("job type id '" + id + "' already registered");
                ids.add(id);
            }
            for (JobTypeProvider p : providers) registry.register(p, name);   // owner = jar filename

            String[] mf = manifest(jar);
            LoadedPack pack = new LoadedPack(mf[0] != null ? mf[0] : name, mf[1] != null ? mf[1] : "?",
                    jar, hash, List.copyOf(ids), loader, staged);
            loaded.put(name, pack);
            log.info("[PACKS] loaded {} v{} ({}): {}", pack.id(), pack.version(), name, ids);
            signals.emit("job.pack.loaded", Severity.INFO, packPayload(pack));
            return true;
        } catch (Exception | ServiceConfigurationError ex) {   // Error: a provider that fails to instantiate
            registry.deregister(name);                                  // roll back any partial registration
            if (loader != null) try { loader.close(); } catch (IOException ignore) { /* best effort */ }
            if (staged != null) try { Files.deleteIfExists(staged); } catch (IOException ignore) { /* best effort */ }
            log.warn("[PACKS] rejected {}: {}", name, ex.toString());
            signals.emit("job.pack.rejected", Severity.WARN,
                    Map.of("file", name, "hash", hash, "cause", String.valueOf(ex.getMessage())));
            return false;
        }
    }

    /** Deregister a pack's types immediately, but only close its loader (release the jar file handle)
     *  once no Run is still executing its code — see {@link #acquireRun}/{@link #releaseRun}. */
    private void unload(String name) {
        LoadedPack pack = loaded.remove(name);
        if (pack == null) return;
        List<String> removed = registry.deregister(name);
        log.info("[PACKS] unloaded {} ({}): {}", pack.id(), name, removed);
        signals.emit("job.pack.unloaded", Severity.INFO, packPayload(pack));
        if (unloadListener != null) unloadListener.onUnload(name);
        closeOrDefer(name, pack);
    }

    /** Close a pack's loader/staged copy now, or — if a Run is still in flight on it — mark it draining
     *  so {@link #releaseRun} finishes the close once that Run completes. */
    private void closeOrDefer(String name, LoadedPack pack) {
        AtomicInteger count = activeRuns.get(name);
        if (count != null && count.get() > 0) {
            draining.put(name, pack);
            log.info("[PACKS] deferring classloader close for {} ({} in-flight run(s))", name, count.get());
            return;
        }
        try { pack.loader().close(); } catch (IOException ignore) { /* best effort */ }
        try { Files.deleteIfExists(pack.staged()); } catch (IOException ignore) { /* best effort */ }
    }

    /** Pin {@code owner}'s (a pack's jar filename) active-run count for the duration of one Run's
     *  {@code Job.run(ctx)} — call {@link #releaseRun} in a {@code finally}. No-op for {@code null}
     *  (built-in/permanent job types have no owning pack). */
    void acquireRun(String owner) {
        if (owner == null) return;
        activeRuns.computeIfAbsent(owner, k -> new AtomicInteger()).incrementAndGet();
    }

    /** The counterpart to {@link #acquireRun}: when the count drops to zero, finish closing a pack whose
     *  unload was deferred while this Run (or a sibling) was still executing. No-op for {@code null}. */
    void releaseRun(String owner) {
        if (owner == null) return;
        AtomicInteger count = activeRuns.get(owner);
        if (count == null) return;
        if (count.decrementAndGet() <= 0) {
            LoadedPack pending = draining.remove(owner);
            if (pending != null) {
                try { pending.loader().close(); } catch (IOException ignore) { /* best effort */ }
                try { Files.deleteIfExists(pending.staged()); } catch (IOException ignore) { /* best effort */ }
                log.info("[PACKS] closed deferred classloader for {} (last in-flight run finished)", owner);
            }
        }
    }

    /** Start watching the dir; each settled batch of changes triggers a {@link #rescan()}. No-op when off. */
    void startWatching() {
        if (!enabled() || !Files.isDirectory(dir)) return;
        try {
            watcher = FileSystems.getDefault().newWatchService();
            dir.register(watcher, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);
        } catch (IOException e) {
            log.warn("[PACKS] cannot watch {} — packs are load-once at startup: {}", dir, e.toString());
            return;
        }
        running = true;
        watchThread = new Thread(this::watchLoop, "job-packs-watch");
        watchThread.setDaemon(true);
        watchThread.start();
    }

    private void watchLoop() {
        while (running) {
            WatchKey key;
            try {
                key = watcher.take();
            } catch (InterruptedException | ClosedWatchServiceException e) {
                return;
            }
            boolean any = !key.pollEvents().isEmpty();
            key.reset();
            if (!any) continue;
            try {
                Thread.sleep(settleMillis);                              // settle: let the jar finish copying
            } catch (InterruptedException e) {
                return;
            }
            WatchKey k;                                                 // drain events that arrived while settling
            while ((k = watcher.poll()) != null) { k.pollEvents(); k.reset(); }
            try {
                rescan();
            } catch (RuntimeException e) {
                log.warn("[PACKS] rescan failed: {}", e.toString());
            }
        }
    }

    /** Whether {@code name}'s pack is unloaded-but-not-yet-closed, pinned open by an in-flight Run
     *  (test/introspection only — not on any HTTP surface). */
    boolean isDraining(String name) { return draining.containsKey(name); }

    /** Pack inventory for {@code GET /jobs/packs} (id, version, file, hash, types, state). */
    List<Map<String, Object>> inventory() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (LoadedPack p : loaded.values()) out.add(packPayload(p));
        return out;
    }

    @Override
    public synchronized void close() {
        running = false;
        if (watchThread != null) watchThread.interrupt();
        if (watcher != null) try { watcher.close(); } catch (IOException ignore) { /* best effort */ }
        for (LoadedPack p : loaded.values()) {
            try { p.loader().close(); } catch (IOException ignore) { /* best effort */ }
            try { Files.deleteIfExists(p.staged()); } catch (IOException ignore) { /* best effort */ }
        }
        loaded.clear();
        // Process is going down regardless of any Run still in flight — close deferred packs too rather
        // than leaking their staged jar copies.
        for (LoadedPack p : draining.values()) {
            try { p.loader().close(); } catch (IOException ignore) { /* best effort */ }
            try { Files.deleteIfExists(p.staged()); } catch (IOException ignore) { /* best effort */ }
        }
        draining.clear();
        activeRuns.clear();
        if (stagingDir != null) try { Files.deleteIfExists(stagingDir); } catch (IOException ignore) { /* best effort */ }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────

    private static Map<String, Object> packPayload(LoadedPack p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.id());
        m.put("version", p.version());
        m.put("file", p.jar().getFileName().toString());
        m.put("hash", p.hash());
        m.put("types", p.types());
        m.put("state", "loaded");
        return m;
    }

    private static Map<String, Object> summary(List<String> loaded, List<String> reloaded,
                                                List<String> unloaded, List<String> rejected) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("loaded", loaded);
        m.put("reloaded", reloaded);
        m.put("unloaded", unloaded);
        m.put("rejected", rejected);
        return m;
    }

    /** Copy the watched jar into a private staging dir and return the copy the loader will lock. */
    private Path stage(String name, String hash, Path jar) throws IOException {
        if (stagingDir == null) stagingDir = Files.createTempDirectory("job-packs-");
        Path dest = stagingDir.resolve(hash.substring(0, Math.min(12, hash.length())) + "-" + name);
        Files.copy(jar, dest, StandardCopyOption.REPLACE_EXISTING);
        return dest;
    }

    /** SHA-256 hex of the jar's bytes — the reload trigger and the audit fingerprint. */
    private static String hash(Path jar) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(Files.readAllBytes(jar));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
            return sb.toString();
        } catch (Exception e) {
            return "?" + jar.getFileName();   // unreadable ⇒ treat as always-changed (will re-attempt/reject)
        }
    }

    private static String[] manifest(Path jar) {
        try (JarFile jf = new JarFile(jar.toFile())) {
            Manifest mf = jf.getManifest();
            if (mf == null) return new String[]{null, null};
            Attributes a = mf.getMainAttributes();
            return new String[]{a.getValue("Pack-Id"), a.getValue("Pack-Version")};
        } catch (IOException e) {
            return new String[]{null, null};
        }
    }

    /**
     * Reject a jar with any unsigned class entry (JDK jar-signature model). Reads every entry so the
     * verifier populates {@link JarEntry#getCodeSigners()}. Trust-anchor matching against
     * {@code -Djobs.packs.trustStore} is the SEC-7 gate and is intentionally not yet enforced (§12.3).
     */
    private static void verifySignature(Path jar) throws IOException, SecurityException {
        try (JarFile jf = new JarFile(jar.toFile(), true)) {   // verify=true
            byte[] buf = new byte[8192];
            boolean anySigned = false;
            var entries = jf.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                try (InputStream in = jf.getInputStream(e)) { while (in.read(buf) != -1) { /* force verify */ } }
                if (e.isDirectory() || e.getName().startsWith("META-INF/")) continue;
                CodeSigner[] signers = e.getCodeSigners();
                if (signers == null || signers.length == 0)
                    throw new SecurityException("unsigned entry: " + e.getName());
                anySigned = true;
            }
            if (!anySigned) throw new SecurityException("jar has no signed entries");
        } catch (ServiceConfigurationError e) {
            throw new SecurityException("signature verification failed: " + e.getMessage());
        }
    }
}

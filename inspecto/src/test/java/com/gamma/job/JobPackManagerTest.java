package com.gamma.job;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * P2c Job Packs (§12): a pack jar dropped in the watched dir is loaded through its own isolated
 * {@link ClassLoader}, its {@link JobTypeProvider} registers, {@link #rescan()} reconciles add/remove/
 * change, and a whole pack is rejected atomically on collision or {@code @JobTypeMeta} mismatch. The
 * provider classes are compiled at test time into a jar so they are genuinely OFF the test classpath —
 * proving real classloader isolation, not a classpath fake.
 */
class JobPackManagerTest {

    /** Captures the audit signals a manager emits, so tests can assert loaded/unloaded/rejected. */
    private static final class Sink implements JobPackManager.SignalSink {
        final List<String> types = new CopyOnWriteArrayList<>();
        @Override public void emit(String type, com.gamma.signal.Severity sev, Map<String, Object> payload) {
            types.add(type);
        }
    }

    @Test
    void loadsRegistersAndUnloadsAPackTypeThroughAnIsolatedClassLoader(@TempDir Path work) throws Exception {
        assumeTrue(ToolProvider.getSystemJavaCompiler() != null, "needs a JDK (javac) to build the pack jar");
        Path packsDir = Files.createDirectories(work.resolve("packs"));
        Path jar = buildPackJar(work, packsDir.resolve("greet-1.jar"), "acme.greet", "acme.greet",
                "GreetType", "acme-greet", "1.0.0");

        JobTypeRegistry registry = new JobTypeRegistry();
        Sink sink = new Sink();
        try (JobPackManager mgr = new JobPackManager(packsDir.toString(), registry, sink)) {
            mgr.scanAtStartup();

            assertTrue(registry.has("acme.greet"), "pack type registered");
            Job job = registry.create("acme.greet", jobConfig("g1", "acme.greet"));
            assertNotSame(getClass().getClassLoader(), job.getClass().getClassLoader(),
                    "pack Job is loaded by the pack's own classloader, not the test's");
            assertEquals("SUCCESS", job.run().status());
            assertTrue(sink.types.contains("job.pack.loaded"));

            List<Map<String, Object>> inv = mgr.inventory();
            assertEquals(1, inv.size());
            assertEquals("acme-greet", inv.get(0).get("id"));
            assertEquals(List.of("acme.greet"), inv.get(0).get("types"));

            // Remove the jar (unlocked because we load from a staged copy) → rescan unloads the type.
            Files.delete(jar);
            Map<String, Object> summary = mgr.rescan();
            assertEquals(List.of("greet-1.jar"), summary.get("unloaded"));
            assertFalse(registry.has("acme.greet"), "type deregisters when the jar is removed");
            assertTrue(mgr.inventory().isEmpty());
            assertTrue(sink.types.contains("job.pack.unloaded"));
        }
    }

    @Test
    void unloadDefersClassloaderCloseWhileARunIsInFlight(@TempDir Path work) throws Exception {
        assumeTrue(ToolProvider.getSystemJavaCompiler() != null, "needs a JDK (javac) to build the pack jar");
        Path packsDir = Files.createDirectories(work.resolve("packs"));
        Path jar = buildPackJar(work, packsDir.resolve("greet-1.jar"), "acme.greet", "acme.greet",
                "GreetType", "acme-greet", "1.0.0");

        JobTypeRegistry registry = new JobTypeRegistry();
        Sink sink = new Sink();
        try (JobPackManager mgr = new JobPackManager(packsDir.toString(), registry, sink)) {
            mgr.scanAtStartup();
            Job job = registry.create("acme.greet", jobConfig("g1", "acme.greet"));

            // Simulate JobService pinning the pack for the duration of a Run.
            mgr.acquireRun("greet-1.jar");
            assertFalse(mgr.isDraining("greet-1.jar"), "not draining until an unload is actually requested");

            Files.delete(jar);
            Map<String, Object> summary = mgr.rescan();
            assertEquals(List.of("greet-1.jar"), summary.get("unloaded"));
            assertFalse(registry.has("acme.greet"), "type deregisters immediately regardless of in-flight Runs");
            assertTrue(mgr.isDraining("greet-1.jar"), "classloader close is deferred while the Run is in flight");

            // The classloader is still open, so the Job instance built before the unload keeps working —
            // this is the actual bug being fixed: without quiescing, closing the loader here could break a
            // Run still executing pack code (a lazy class load / reflection / resource read failing).
            assertEquals("SUCCESS", job.run().status(), "in-flight Run's classes remain usable during quiesce");

            mgr.releaseRun("greet-1.jar");
            assertFalse(mgr.isDraining("greet-1.jar"), "close finishes once the last in-flight Run ends");
        }
    }

    @Test
    void collisionWithAnExistingTypeRejectsTheWholePack(@TempDir Path work) throws Exception {
        assumeTrue(ToolProvider.getSystemJavaCompiler() != null);
        Path packsDir = Files.createDirectories(work.resolve("packs"));
        // A pack that tries to claim an id already held by a "built-in".
        buildPackJar(work, packsDir.resolve("dupe-1.jar"), "report", "report", "DupeType", "dupe", "1.0.0");

        JobTypeRegistry registry = new JobTypeRegistry();
        JobTypeProvider builtin = JobTypeProvider.of(
                new JobTypeDescriptor("report", "Report", "built-in", List.of(), List.of(), List.of()),
                c -> { throw new UnsupportedOperationException(); });
        registry.register(builtin);   // permanent, owner=null

        Sink sink = new Sink();
        try (JobPackManager mgr = new JobPackManager(packsDir.toString(), registry, sink)) {
            mgr.scanAtStartup();
            assertTrue(registry.has("report"));
            // The built-in still owns 'report' — its factory throws; the pack's would have returned a Job.
            assertThrows(UnsupportedOperationException.class,
                    () -> registry.create("report", jobConfig("r", "report")),
                    "the built-in still owns 'report' (pack did not displace it)");
            assertTrue(mgr.inventory().isEmpty(), "rejected pack is not in the inventory");
            assertTrue(sink.types.contains("job.pack.rejected"));
        }
    }

    @Test
    void metaMismatchRejectsThePack(@TempDir Path work) throws Exception {
        assumeTrue(ToolProvider.getSystemJavaCompiler() != null);
        Path packsDir = Files.createDirectories(work.resolve("packs"));
        // @JobTypeMeta id ("meta.id") disagrees with descriptor id ("real.id") → fail-closed.
        buildPackJar(work, packsDir.resolve("mismatch-1.jar"), "real.id", "meta.id", "MismatchType", "mm", "1.0.0");

        JobTypeRegistry registry = new JobTypeRegistry();
        Sink sink = new Sink();
        try (JobPackManager mgr = new JobPackManager(packsDir.toString(), registry, sink)) {
            mgr.scanAtStartup();
            assertFalse(registry.has("real.id"));
            assertFalse(registry.has("meta.id"));
            assertTrue(sink.types.contains("job.pack.rejected"));
        }
    }

    // ── fixture: compile a provider + Job and package them into a real jar off the test classpath ──

    private static JobConfig jobConfig(String name, String type) {
        return JobConfig.fromMap(Map.of("job", Map.of("name", name, "type", type)));
    }

    /**
     * Compile a {@link JobTypeProvider} whose {@code descriptor().id()} is {@code descriptorId} and whose
     * {@code @JobTypeMeta.id} is {@code metaId} (equal ⇒ valid pack; different ⇒ rejected), plus its Job,
     * and package them with a {@code META-INF/services} entry + {@code Pack-Id}/{@code Pack-Version} manifest.
     */
    private static Path buildPackJar(Path work, Path jar, String descriptorId, String metaId,
                                     String cls, String packId, String version) throws Exception {
        String fqcn = "com.acme.pack." + cls;
        String src = """
                package com.acme.pack;
                import com.gamma.job.*;
                import java.util.List;
                @JobTypeMeta(id = "%s", title = "Test")
                public class %s implements JobTypeProvider {
                    public JobTypeDescriptor descriptor() {
                        return new JobTypeDescriptor("%s", "Test", "test pack type",
                                List.of(), List.of(), List.of());
                    }
                    public Job create(JobConfig config) {
                        return new Job() {
                            public String name() { return config.name(); }
                            public String type() { return "%s"; }
                            public JobResult run() { return JobResult.ok("hi", 0L); }
                        };
                    }
                }
                """.formatted(metaId, cls, descriptorId, descriptorId);

        Path stage = Files.createTempDirectory(work, "stage-");
        Path srcFile = stage.resolve("com/acme/pack/" + cls + ".java");
        Files.createDirectories(srcFile.getParent());
        Files.writeString(srcFile, src);
        Path classes = Files.createDirectories(stage.resolve("classes"));

        // The pack imports only com.gamma.job.* — compile against that API's real code-source location
        // (robust under Maven surefire, where java.class.path is just the booter jar).
        String apiCp = Path.of(JobTypeProvider.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .toString();
        JavaCompiler jc = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager fm = jc.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            List<String> opts = List.of("-classpath", apiCp, "-d", classes.toString());
            boolean ok = jc.getTask(null, fm, null, opts, null,
                    fm.getJavaFileObjects(srcFile.toFile())).call();
            assertTrue(ok, "pack source compiled");
        }

        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        mf.getMainAttributes().putValue("Pack-Id", packId);
        mf.getMainAttributes().putValue("Pack-Version", version);
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jar), mf);
             Stream<Path> files = Files.walk(classes)) {
            for (Path p : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                jos.putNextEntry(new JarEntry(classes.relativize(p).toString().replace('\\', '/')));
                Files.copy(p, jos);
                jos.closeEntry();
            }
            jos.putNextEntry(new JarEntry("META-INF/services/com.gamma.job.JobTypeProvider"));
            writeUtf8(jos, fqcn + "\n");
            jos.closeEntry();
        }
        return jar;
    }

    private static void writeUtf8(OutputStream os, String s) throws Exception {
        os.write(s.getBytes(StandardCharsets.UTF_8));
    }
}

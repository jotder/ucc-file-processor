package com.gamma.job;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** PIP-6 job templates: load, parameter substitution, required-param enforcement, instance overrides. */
class JobTemplateTest {

    private static JobTemplate retentionSweep(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("retention_job_template.toon");
        Files.writeString(f, """
                job_template:
                  name: retention-sweep
                  params:
                    dir:
                    retention_days: "30"
                  job:
                    type: maintenance
                    task: cleanup
                    cron: "0 3 * * *"
                    dir: ${dir}
                    retention_days: ${retention_days}
                """);
        return JobTemplate.load(f.toString());
    }

    private static Map<String, Object> instance(String name, Map<String, Object> params) {
        Map<String, Object> job = new LinkedHashMap<>();
        job.put("name", name);
        job.put("template", "retention-sweep");
        if (params != null) job.put("params", params);
        return job;
    }

    @Test
    void instantiatesWithDefaultsAndOverrides(@TempDir Path dir) throws Exception {
        JobTemplate t = retentionSweep(dir);
        assertEquals("retention-sweep", t.name());

        Map<String, Object> resolved = t.instantiate(instance("backup-retention", Map.of("dir", "data/backup")));
        JobConfig cfg = JobConfig.fromMap(Map.of("job", resolved));

        assertEquals("backup-retention", cfg.name());
        assertEquals("maintenance", cfg.type());   // P2b: type is the lowercased registry id
        assertEquals("0 3 * * *", cfg.cron());
        assertEquals("cleanup", cfg.params().get("task"));
        assertEquals("data/backup", cfg.params().get("dir"), "instance param fills the placeholder");
        assertEquals("30", cfg.params().get("retention_days"), "declared default applies when not overridden");
        assertNull(cfg.params().get("template"), "resolution machinery never leaks into job params");
        assertNull(cfg.params().get("params"), "resolution machinery never leaks into job params");
    }

    @Test
    void missingRequiredParamFailsAtLoad(@TempDir Path dir) throws Exception {
        JobTemplate t = retentionSweep(dir);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> t.instantiate(instance("broken", null)));   // 'dir' has no default
        assertTrue(ex.getMessage().contains("dir"), ex.getMessage());
    }

    @Test
    void undeclaredPlaceholderFails(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("bad_job_template.toon");
        Files.writeString(f, """
                job_template:
                  name: bad
                  job:
                    type: maintenance
                    task: cleanup
                    dir: ${never_declared}
                """);
        JobTemplate t = JobTemplate.load(f.toString());
        assertThrows(IllegalArgumentException.class,
                () -> t.instantiate(instance("x", Map.of())));
    }

    @Test
    void instanceKeysOverrideTheTemplateBlock(@TempDir Path dir) throws Exception {
        JobTemplate t = retentionSweep(dir);
        Map<String, Object> job = instance("weekly", Map.of("dir", "data/quarantine", "retention_days", "90"));
        job.put("cron", "0 4 * * 0");   // direct override of the template's schedule
        JobConfig cfg = JobConfig.fromMap(Map.of("job", t.instantiate(job)));

        assertEquals("0 4 * * 0", cfg.cron(), "instance cron wins over the template's");
        assertEquals("90", cfg.params().get("retention_days"));
        assertEquals("data/quarantine", cfg.params().get("dir"));
    }
}

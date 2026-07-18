package com.gamma.service;

import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.ops.ObjectType;
import com.gamma.ops.workflow.Workflow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ServiceBootstrap#loadWorkflows} scans {@code *_workflow.toon}, registering the valid ones and
 * warning + skipping any that fail to parse (the {@code loadRcaTemplates} robustness contract), and
 * {@link ServiceBootstrap#buildFrom} wires each override into {@link com.gamma.ops.ObjectService} so
 * {@code workflow(type)} (what {@code GET /workflows/{type}} serves) reflects it — the boot-scan seam that
 * was previously missing (the {@code workflows} map was frozen to {@link Workflow#defaultFor} at
 * construction).
 */
class ServiceBootstrapWorkflowTest {

    private static final String TASK_OVERRIDE = """
            workflow:
              object_type: TASK
              initial: TODO
              terminal[1]: "DONE"
              transitions[2]{from,to,action}:
                TODO,DOING,start
                DOING,DONE,finish
            """;

    @Test
    void loadsValidOverridesAndSkipsBad(@TempDir Path dir) throws Exception {
        Path good = dir.resolve("task_workflow.toon");
        Files.writeString(good, TASK_OVERRIDE);
        Path bad = dir.resolve("broken_workflow.toon");
        Files.writeString(bad, "workflow:\n  initial: OPEN\n");   // no object_type → invalid, must be skipped

        List<Workflow> loaded = ServiceBootstrap.loadWorkflows(List.of(good, bad));
        assertEquals(1, loaded.size(), "the malformed override is skipped");
        assertEquals(ObjectType.TASK, loaded.get(0).objectType());
        assertEquals("TODO", loaded.get(0).initialState());
    }

    @Test
    void buildFromWiresOverrideIntoObjectService(@TempDir Path dir) throws Exception {
        // a normal pipeline so the boot is realistic, plus the workflow override in the same config dir
        TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        Files.writeString(dir.resolve("task_workflow.toon"), TASK_OVERRIDE);

        try (var svc = ServiceBootstrap.buildFrom(SpaceRoot.legacy(), new String[]{dir.toString()}, false)) {
            Workflow task = svc.objects().workflow(ObjectType.TASK);
            assertEquals("TODO", task.initialState(), "the authored override replaced the OPEN→CLOSED default");
            assertEquals("DOING", task.apply("TODO", "start").orElseThrow());
            assertTrue(task.isTerminal("DONE"));

            // a type with no override still gets its built-in default
            assertEquals(Workflow.defaultFor(ObjectType.ALERT).initialState(),
                    svc.objects().workflow(ObjectType.ALERT).initialState());
        }
    }
}

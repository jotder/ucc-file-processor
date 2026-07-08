package com.gamma.service;

import com.gamma.etl.PipelineConfigBatchTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** ACQ-6 push discovery: a file landing in a {@code source.discovery: watch} inbox triggers a run. */
class SourceWatcherTest {

    /** Registry over one pipeline toon, with an optional appended top-level {@code source:} block. */
    private static ConfigRegistry registry(Path dir, String sourceBlock) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(dir, "");
        if (sourceBlock != null) Files.writeString(pipe, "\n" + sourceBlock, StandardOpenOption.APPEND);
        ConfigRegistry reg = new ConfigRegistry();
        reg.rebuild(List.of(pipe));
        assertEquals(1, reg.size(), "pipeline toon must parse");
        return reg;
    }

    @Test
    void noWatchSourcesMeansNoWatcher(@TempDir Path dir) throws Exception {
        ConfigRegistry reg = registry(dir, null);   // default discovery: poll
        assertNull(SourceWatcher.startFor(reg.all(), p -> fail("no trigger expected")),
                "poll-only registries start no watcher thread");
    }

    @Test
    void fileLandingInWatchedInboxTriggersThePipeline(@TempDir Path dir) throws Exception {
        ConfigRegistry reg = registry(dir, "source:\n  discovery: watch\n");
        Path inbox = Path.of(reg.all().get(0).config().dirs().poll());
        Files.createDirectories(inbox);

        BlockingQueue<String> triggered = new LinkedBlockingQueue<>();
        SourceWatcher watcher = SourceWatcher.startFor(reg.all(), triggered::add);
        assertNotNull(watcher, "a watch source must start the watcher");
        try {
            Files.writeString(inbox.resolve("feed.csv"), "ID,AMT,EVENT_DATE\n1,2,2020-04-03\n");
            String pipeline = triggered.poll(15, TimeUnit.SECONDS);
            assertEquals(reg.all().get(0).id(), pipeline, "the owning pipeline is triggered after the quiet window");
        } finally {
            watcher.close();
        }
    }

    @Test
    void burstsCoalesceIntoOneTrigger(@TempDir Path dir) throws Exception {
        ConfigRegistry reg = registry(dir, "source:\n  discovery: watch\n");
        Path inbox = Path.of(reg.all().get(0).config().dirs().poll());
        Files.createDirectories(inbox);

        BlockingQueue<String> triggered = new LinkedBlockingQueue<>();
        SourceWatcher watcher = SourceWatcher.startFor(reg.all(), triggered::add);
        assertNotNull(watcher);
        try {
            for (int i = 0; i < 5; i++) Files.writeString(inbox.resolve("f" + i + ".csv"), "ID\n" + i + "\n");
            assertNotNull(triggered.poll(15, TimeUnit.SECONDS), "burst fires at least once");
            // After the burst quiesces and the first trigger fires, no steady drip of extra triggers follows.
            Thread.sleep(1500);
            int extras = 0;
            while (triggered.poll() != null) extras++;
            assertTrue(extras <= 1, "a 5-file burst coalesces (saw " + extras + " extra trigger(s))");
        } finally {
            watcher.close();
        }
    }
}

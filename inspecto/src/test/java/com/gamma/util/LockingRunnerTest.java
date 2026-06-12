package com.gamma.util;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LockingRunnerTest {

    @Test
    void runExclusiveSerialisesTheSameName() throws Exception {
        LockingRunner runner = new LockingRunner();
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maxSeen = new AtomicInteger();
        int workers = 8;
        CountDownLatch done = new CountDownLatch(workers);
        for (int i = 0; i < workers; i++) {
            Thread.ofVirtual().start(() -> {
                runner.runExclusive("same", () -> {
                    int now = concurrent.incrementAndGet();
                    maxSeen.accumulateAndGet(now, Math::max);
                    try { Thread.sleep(5); } catch (InterruptedException ignored) { }
                    concurrent.decrementAndGet();
                });
                done.countDown();
            });
        }
        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertEquals(1, maxSeen.get());   // never overlapped
    }

    @Test
    void differentNamesRunInParallel() throws Exception {
        LockingRunner runner = new LockingRunner();
        CountDownLatch bothInside = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        for (String name : new String[]{"a", "b"}) {
            Thread.ofVirtual().start(() -> runner.runExclusive(name, () -> {
                bothInside.countDown();
                try { release.await(); } catch (InterruptedException ignored) { }
            }));
        }
        // Both bodies must be inside their locks at once — would deadlock here if serialized.
        assertTrue(bothInside.await(5, TimeUnit.SECONDS));
        release.countDown();
    }

    @Test
    void runExclusiveOrSkipRunsOnBusyInsteadOfWaiting() throws Exception {
        LockingRunner runner = new LockingRunner();
        CountDownLatch holderInside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger ran = new AtomicInteger(), skipped = new AtomicInteger();

        Thread holder = Thread.ofVirtual().start(() -> runner.runExclusiveOrSkip("n", () -> {
            ran.incrementAndGet();
            holderInside.countDown();
            try { release.await(); } catch (InterruptedException ignored) { }
        }, skipped::incrementAndGet));

        assertTrue(holderInside.await(5, TimeUnit.SECONDS));
        runner.runExclusiveOrSkip("n", ran::incrementAndGet, skipped::incrementAndGet);
        release.countDown();
        holder.join(5000);

        assertEquals(1, ran.get());       // only the holder's task ran
        assertEquals(1, skipped.get());   // the second fire was diverted to onBusy
    }
}
